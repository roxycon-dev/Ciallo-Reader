package com.example.transfer

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.data.BackupManager
import com.example.data.PrivacyManager
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppButtonVariant
import com.example.ui.components.GlassCard
import com.example.ui.design.DesignTokens as D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TransferHost(backup:BackupManager,onEnablePrivacy:suspend(String)->Boolean={false},onImported:suspend()->Unit={}) {
    val context=LocalContext.current
    val manager=remember { TransferManager.get(context) }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,manager) {
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_START -> manager.setForeground(true)
            Lifecycle.Event.ON_STOP -> manager.setForeground(false)
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        manager.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer); manager.setForeground(false) }
    }
    val open by manager.openPage.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.phase,state.incoming) { if(state.phase==TransferPhase.COMPLETED && state.incoming)onImported() }
    if(open) Dialog(onDismissRequest={manager.hide()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        TransferScreen(manager,backup,onEnablePrivacy)
    }
    else if(state.phase==TransferPhase.AWAITING_CONFIRMATION && state.incoming) AlertDialog(
        onDismissRequest={manager.confirm(false)},title={Text("${state.peer?.name?:"附近设备"} 想发送数据")},
        text={Column(verticalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
            Text("请核对两台设备的校验码一致，再接收。")
            Text(state.verificationCode.orEmpty().chunked(3).joinToString(" "),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Text("默认合并，保留本机设置和已有内容。")
        }},confirmButton={TextButton(onClick={manager.show()}) {Text("查看并接收")}},
        dismissButton={TextButton(onClick={manager.confirm(false)}) {Text("拒绝")}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(manager:TransferManager,backup:BackupManager,onEnablePrivacy:suspend(String)->Boolean={false}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val state by manager.state.collectAsStateWithLifecycle()
    val peers by manager.peers.collectAsStateWithLifecycle()
    val discoveryMessage by manager.discoveryMessage.collectAsStateWithLifecycle()
    val enabled by manager.enabled.collectAsStateWithLifecycle()
    var sending by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(ExportSelection()) }
    var estimate by remember { mutableStateOf<TransferEstimate?>(null) }
    LaunchedEffect(selected) { estimate=null;runCatching {backup.estimateArchive(selected)}.onSuccess {estimate=it} }
    var localProgress by remember { mutableStateOf<TransferProgress?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var pinPurpose by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }; var pinAgain by remember { mutableStateOf("") }
    var pinBusy by remember { mutableStateOf(false) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grant ->
        manager.enable(true); manager.scan()
        if(!TransferDiscovery.allowed(context))localError="附近直连未获授权，仍可使用同 Wi-Fi 互传或分享文件。"
    }
    fun discover() { sending=true
        if(!TransferDiscovery.allowed(context))permissions.launch(permissionRequests())else {manager.enable(true);manager.scan()} }
    fun restart() { context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK); context.startActivity(intent)
    } }
    suspend fun prepare():File {
        val file=File(context.cacheDir,"share_temp/Ciallo-${UUID.randomUUID()}.ciallo")
        backup.exportArchive(file,selected) { localProgress=it }
        return file
    }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TRANSFER_MIME)) { uri -> if(uri!=null)scope.launch {
        try { localProgress=TransferProgress(TransferPhase.PREPARING); val file=prepare()
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }?:error("无法保存到所选位置") } }
            finally { file.delete() }
            Toast.makeText(context,"备份已保存",Toast.LENGTH_SHORT).show()
        }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;localError=e.message}finally{localProgress=null}
    } }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)manager.openShared(uri) }
    val activeState=localProgress?.let { TransferSessionState(it.phase,progress=it,message=it.message) }?:state
    val colors=MaterialTheme.colorScheme
    Surface(color=colors.background,modifier=Modifier.fillMaxSize()) {
        LazyColumn(modifier=Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("device_transfer_screen"),
            contentPadding=PaddingValues(horizontal=D.SpacePage,vertical=D.SpaceSm),verticalArrangement=Arrangement.spacedBy(D.SpaceLg)) {
            item(key="header",contentType="header") {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={manager.hide()}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")}
                    Text("设备互传",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    IconButton(onClick={showHistory=true}) {Icon(Icons.Default.History,"传输记录")}
                }
            }
            item(key="hero",contentType="hero") {
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                    TransferArtwork(activeState)
                    if(activeState.phase!=TransferPhase.IDLE)AnimatedContent(targetState=activeState.phase,transitionSpec={fadeIn(tween(240)) togetherWith fadeOut(tween(180))},label="transfer_title") { phase ->
                        Text(phase.label,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    }
                    activeState.peer?.let { Text(it.name,style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant) }
                    if(activeState.phase in setOf(TransferPhase.FAILED,TransferPhase.WAITING_TO_RESUME,TransferPhase.RECEIVED) && activeState.message.isNotBlank()) {
                        Spacer(Modifier.height(D.SpaceSm));Text(activeState.message,color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                    }
                    if(activeState.progress.totalBytes>0) {
                        Spacer(Modifier.height(D.SpaceMd))
                        Text(if(activeState.phase==TransferPhase.IMPORTING)"${activeState.progress.completedBytes} / ${activeState.progress.totalBytes} 项数据" else "${bytes(activeState.progress.completedBytes)} / ${bytes(activeState.progress.totalBytes)}",style=MaterialTheme.typography.labelLarge)
                        if(activeState.progress.bytesPerSecond>0)Text("${bytes(activeState.progress.bytesPerSecond)}/s  ·  约剩 ${activeState.progress.remainingSeconds?:0} 秒",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                    }
                }
            }
            if(activeState.phase==TransferPhase.AWAITING_CONFIRMATION) item(key="pair",contentType="pair") {
                GlassCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(D.SpaceXl),verticalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
                    if(state.verificationCode!=null) {
                        Text("两台设备显示相同的校验码",style=MaterialTheme.typography.labelLarge)
                        Text(state.verificationCode.orEmpty().chunked(3).joinToString("  "),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold,color=colors.primary,modifier=Modifier.testTag("transfer_pairing_code"))
                        Text("${manager.deviceName}  ↔  ${state.peer?.name.orEmpty()}",style=MaterialTheme.typography.bodyMedium)
                    }
                    state.preview?.warnings?.forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=colors.error) }
                    AppActionButton(text=if(state.incoming)"校验码一致，合并接收" else if(state.verificationCode==null)"确认提示，继续发送" else "校验码一致，发送",onClick={manager.confirm(true)})
                    if(state.incoming)TextButton(onClick={replaceConfirm=true}) {Text("整机迁移，替换所选数据")}
                    TextButton(onClick={manager.confirm(false)}) {Text("取消")}
                } }
            }
            if(state.phase==TransferPhase.RECEIVED && state.incoming) item(key="received",contentType="received") {
                GlassCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(D.SpaceXl),verticalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
                    Text("已识别的内容",style=MaterialTheme.typography.titleMedium)
                    Text(summary(state.preview),style=MaterialTheme.typography.bodyMedium)
                    state.preview?.warnings?.forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant) }
                    AppActionButton(text=if(state.preview?.containsProtected==true && !PrivacyManager(context).isEnabled())"先设置本机隐私保护" else "合并到本机",onClick={
                        if(state.preview?.containsProtected==true && !PrivacyManager(context).isEnabled()) {pin="";pinAgain="";pinPurpose="receive"}
                        else {manager.importShared(ImportMode.MERGE);manager.continueProtectedImport(ImportMode.MERGE)}
                    })
                    TextButton(onClick={replaceConfirm=true}) {Text("整机迁移，替换所选数据")}
                } }
            }
            if(state.phase==TransferPhase.COMPLETED) item(key="result",contentType="result") {
                GlassCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(D.SpaceXl),verticalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
                    state.result?.let { Text("新增 ${it.added} 项 · 合并 ${it.merged} 项 · 跳过 ${it.skipped} 项",style=MaterialTheme.typography.bodyMedium) }
                    if(!state.incoming)Text("对方已收到 ✓   对方已导入 ✓",color=colors.primary)
                    state.result?.warnings?.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                    AppActionButton(text=if(manager.needsRestart())"完成并重新打开" else "完成",onClick={if(manager.needsRestart())restart()else {manager.reset();sending=false}})
                } }
            }
            if(state.phase==TransferPhase.WAITING_TO_RESUME || state.phase==TransferPhase.FAILED) item(key="retry",contentType="retry") {
                Column(verticalArrangement=Arrangement.spacedBy(D.SpaceSm)) {
                    AppActionButton(text="继续传输",onClick={manager.retry()})
                    if(state.peer?.p2pAddress!=null)TextButton(onClick={manager.retry(true)}) {Text("改用附近直连")}
                    TextButton(onClick={manager.cancel()}) {Text("取消本次任务")}
                }
            }
            if(!state.busy && state.phase !in setOf(TransferPhase.RECEIVED,TransferPhase.COMPLETED,TransferPhase.WAITING_TO_RESUME) && localProgress==null) {
                item(key="actions",contentType="actions") {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
                        AppActionButton(text="发送数据",onClick={discover()},modifier=Modifier.weight(1f))
                        AppActionButton(text="接收数据",onClick={sending=false;if(!TransferDiscovery.allowed(context))permissions.launch(permissionRequests())else {manager.enable(true);manager.scan()}},variant=AppButtonVariant.Secondary,modifier=Modifier.weight(1f))
                    }
                }
                item(key="selection",contentType="selection") {
                    GlassCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(D.SpaceLg)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("传输内容",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                            TextButton(onClick={showDetails=!showDetails}) {Text(if(showDetails)"收起" else if(selected.categories.size==TransferCategory.entries.size)"全部 · 调整" else "调整")}
                        }
                        AnimatedVisibility(showDetails) {Column {TransferCategory.entries.forEach { category ->
                            Row(Modifier.fillMaxWidth().clickable {selected=selected.copy(categories=if(category in selected.categories)selected.categories-category else selected.categories+category)},verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(checked=category in selected.categories,onCheckedChange={ checked -> selected=selected.copy(categories=if(checked)selected.categories+category else selected.categories-category) })
                                Text(category.label,style=MaterialTheme.typography.bodyMedium)
                            }
                        } } }
                        Text("已选 ${selected.categories.size} 类 · "+(estimate?.let {"${it.books} 本书 · ${it.favorites} 项收藏 · 内容约 ${bytes(it.approximateBytes)}"}?:"正在计算数量与体积…"),style=MaterialTheme.typography.labelMedium,color=colors.primary)
                        estimate?.warnings?.forEach {Text(it,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)}
                        if(PrivacyManager(context).hasPin())TextButton(onClick={if(selected.protectedAuthorized)selected=selected.copy(protectedAuthorized=false)else {pin="";pinPurpose="send"}}) {Text(if(selected.protectedAuthorized)"已解锁受保护内容 · 点击排除" else "解锁并加入受保护内容")}
                    } }
                }
                if(sending) {
                    item(key="nearby_title",contentType="label") {Text("可发送到",style=MaterialTheme.typography.titleMedium)}
                    items(peers,key={it.id},contentType={"peer"}) { peer ->
                        AnimatedVisibility(true,enter=fadeIn(tween(280))) {
                            GlassCard(Modifier.fillMaxWidth().clickable(enabled=selected.categories.isNotEmpty()) {manager.startSend(peer,selected)}) {
                                Row(Modifier.fillMaxWidth().padding(D.SpaceXl),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(D.SpaceLg)) {
                                    Box(Modifier.size(D.SpaceSection+D.SpaceLg).background(colors.primary.copy(alpha=.12f),CircleShape),contentAlignment=Alignment.Center) {Icon(Icons.Default.PhoneAndroid,"接收设备",tint=colors.primary)}
                                    Column(Modifier.weight(1f)) {Text(peer.name,fontWeight=FontWeight.SemiBold);Text(peer.routeLabel,style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)}
                                    Icon(Icons.Default.ChevronRight,"发送到此设备")
                                }
                            }
                        }
                    }
                    if(peers.isEmpty()) item(key="empty",contentType="empty") {Text("未发现设备。请打开对方的设备互传，或连接同一 Wi-Fi / 热点。",style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)}
                    item(key="scan",contentType="scan") {TextButton(onClick={manager.scan()}) {Text("重新发现设备")}}
                }
                item(key="availability",contentType="availability") {GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(D.SpaceLg),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {Text("允许接收",style=MaterialTheme.typography.bodyMedium)
                            Text("${manager.deviceName} · ${if(enabled)"正在等待接收" else "尚未开启"}",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)}
                        Switch(checked=enabled,onCheckedChange={if(it && !TransferDiscovery.allowed(context))permissions.launch(permissionRequests())else manager.enable(it)})
                    }
                } }
                if(discoveryMessage.isNotBlank())item(key="discovery_message",contentType="message") {Text(discoveryMessage,color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
                item(key="backup",contentType="backup") {GlassCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(D.SpaceLg),verticalArrangement=Arrangement.spacedBy(D.SpaceSm)) {
                    Text("备份与分享文件",style=MaterialTheme.typography.titleMedium)
                    TextButton(enabled=selected.categories.isNotEmpty(),onClick={scope.launch {
                        try {localProgress=TransferProgress(TransferPhase.PREPARING);val file=prepare()
                            val preview=backup.inspectArchive(file)
                            if(preview.warnings.isNotEmpty()) { localError=preview.warnings.joinToString("\n")+"\n请修复资源后重试，或使用本地备份保存当前可用内容。";file.delete();return@launch }
                            val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type=TRANSFER_MIME;putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("Ciallo 数据包",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"分享 Ciallo 数据"))
                        }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;localError=e.message}finally{localProgress=null}
                    }}) {Icon(Icons.Default.Share,"分享文件");Spacer(Modifier.width(D.SpaceSm));Text("分享数据包")}
                    TextButton(enabled=selected.categories.isNotEmpty(),onClick={save.launch("Ciallo-backup.ciallo")}) {Text("保存本地备份")}
                    TextButton(onClick={open.launch(arrayOf(TRANSFER_MIME,"application/zip","application/octet-stream"))}) {Text("打开已有备份或分享文件")}
                    if(backup.hasMigrationUndo())TextButton(onClick={scope.launch {try {localProgress=TransferProgress(TransferPhase.IMPORTING);backup.undoMigration();restart()}catch(e:Exception){localError=e.message}finally{localProgress=null}}}) {Text("撤销最近一次整机迁移")}
                } } }
            }
            if(state.busy && state.phase!=TransferPhase.AWAITING_CONFIRMATION)item(key="cancel",contentType="cancel") {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
                    TextButton(onClick={manager.hide()}) {Text("收起，后台继续")}
                    if(state.phase!=TransferPhase.IMPORTING)TextButton(onClick={manager.cancel()}) {Text("取消传输")}
                }
            }
        }
    }
    if(localError!=null)AlertDialog(onDismissRequest={localError=null},title={Text("操作提示")},text={Text(localError.orEmpty())},confirmButton={TextButton(onClick={localError=null}){Text("知道了")}})
    if(replaceConfirm)AlertDialog(onDismissRequest={replaceConfirm=false},title={Text("确认整机迁移")},text={Text("将替换所选类别的本机内容，并暂停下载。替换书籍时也会替换其关联的标注与阅读记录。迁移前会保存回退快照。")},
        confirmButton={TextButton(onClick={replaceConfirm=false
            if(state.phase==TransferPhase.AWAITING_CONFIRMATION)manager.confirm(true,ImportMode.REPLACE)
            else if(state.preview?.containsProtected==true && !PrivacyManager(context).isEnabled()){pinPurpose="receive";pin="";pinAgain=""}
            else {manager.importShared(ImportMode.REPLACE);manager.continueProtectedImport(ImportMode.REPLACE)}
        }){Text("确认迁移")}},dismissButton={TextButton(onClick={replaceConfirm=false}){Text("取消")}})
    if(pinPurpose!=null)AlertDialog(onDismissRequest={if(!pinBusy){pinPurpose=null;pin="";pinAgain=""}},title={Text(if(pinPurpose=="send")"解锁受保护内容" else "设置本机隐私 PIN")},text={Column(verticalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
        Text(if(pinPurpose=="send")"验证本机 6 位 PIN 后，受保护内容才会加入本次发送。" else "受保护内容需要本机独立的 6 位 PIN。发送机的 PIN 不会迁移。")
        OutlinedTextField(value=pin,onValueChange={pin=it.filter(Char::isDigit).take(6)},label={Text("6 位 PIN")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        if(pinPurpose=="receive")OutlinedTextField(value=pinAgain,onValueChange={pinAgain=it.filter(Char::isDigit).take(6)},label={Text("再次输入 PIN")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
    }},confirmButton={TextButton(enabled=!pinBusy && pin.length==6,onClick={scope.launch {pinBusy=true
        try {if(pinPurpose=="send")selected=backup.authorizeProtectedExport(pin,selected)
            else {require(pin==pinAgain){"两次 PIN 不一致"};check(onEnablePrivacy(pin)){"PIN 设置失败"}}
            pinPurpose=null;pin="";pinAgain=""
        }catch(e:Exception){localError=e.message}finally{pinBusy=false}
    }}){Text(if(pinBusy)"正在验证…" else "确认")}},dismissButton={TextButton(enabled=!pinBusy,onClick={pinPurpose=null;pin="";pinAgain=""}){Text("取消")}})
    if(showHistory)AlertDialog(onDismissRequest={showHistory=false},title={Text("最近传输")},text={Text(manager.history().joinToString("\n\n").ifBlank {"还没有传输记录"})},confirmButton={TextButton(onClick={showHistory=false}){Text("关闭")}})
}

@Composable
internal fun TransferArtwork(state:TransferSessionState) {
    val colors=MaterialTheme.colorScheme
    val reduced=com.example.ui.feedback.systemReduceMotion()
    val owner=LocalLifecycleOwner.current
    var time by remember { mutableFloatStateOf(0f) }
    val transferring=state.phase in setOf(TransferPhase.TRANSFERRING,TransferPhase.PREPARING,TransferPhase.VERIFYING,TransferPhase.IMPORTING)
    val discovery=state.phase in setOf(TransferPhase.IDLE,TransferPhase.DISCOVERING)
    LaunchedEffect(reduced,transferring,discovery,owner) {
        if(!reduced && (transferring || discovery))owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var start=0L
            while(true)withFrameNanos { now ->if(start==0L)start=now;time=((now-start)%2_600_000_000L)/2_600_000_000f }
        }
    }
    val fraction by animateFloatAsState(state.progress.fraction,tween(if(reduced)0 else 280),label="real_transfer_progress")
    val completed by animateFloatAsState(if(state.phase==TransferPhase.COMPLETED)1f else 0f,tween(if(reduced)0 else 450),label="cards_arrive")
    val connected=state.phase !in setOf(TransferPhase.IDLE,TransferPhase.DISCOVERING,TransferPhase.PREPARING)
    val separation by animateFloatAsState(if(connected)1f else 0f,tween(if(reduced)0 else 380),label="cards_separate")
    Box(Modifier.fillMaxWidth().height(D.SpaceSection*6.25f).testTag("transfer_animation"),contentAlignment=Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val w=size.width;val h=size.height;val unit=minOf(w*.18f,h*.34f)
            val center=Offset(w*.5f,h*.40f)
            val distance=w*.30f*separation*(1-completed)
            val left=center-Offset(distance,0f);val right=center+Offset(distance,0f)
            val breath=if(reduced)0f else sin(time*PI*2).toFloat()*unit*.025f
            // Soft light under real content shapes; no radar, phone outlines or decorative labels.
            drawOval(Brush.radialGradient(listOf(colors.primary.copy(alpha=.09f),colors.primary.copy(alpha=0f)),center,unit*2.2f),Offset(center.x-unit*2.2f,center.y-unit*1.35f),Size(unit*4.4f,unit*2.7f))
            fun card(position:Offset,scale:Float,rotation:Float,alpha:Float,front:Boolean=false) {
                val cw=unit*.86f*scale;val ch=unit*1.18f*scale
                val origin=position-Offset(cw/2,ch/2)
                withTransform({rotate(rotation,position)}) {
                    for(i in 3 downTo 1)drawRoundRect(colors.onSurface.copy(alpha=.012f*alpha),origin+Offset(0f,unit*.035f*i),Size(cw,ch),CornerRadius(unit*.10f))
                    val face=if(front)colors.primary else colors.surface
                    drawRoundRect(Brush.linearGradient(listOf(face.copy(alpha=alpha),if(front)colors.primary.copy(alpha=.82f*alpha)else colors.surfaceVariant.copy(alpha=.75f*alpha)),origin,origin+Offset(cw,ch)),origin,Size(cw,ch),CornerRadius(unit*.10f))
                    drawRoundRect(colors.onSurface.copy(alpha=.08f*alpha),origin,Size(cw,ch),CornerRadius(unit*.10f),style=Stroke(unit*.008f))
                    val ink=if(front)colors.onPrimary else colors.primary
                    drawLine(ink.copy(alpha=.28f*alpha),origin+Offset(cw*.14f,ch*.12f),origin+Offset(cw*.14f,ch*.88f),unit*.012f,StrokeCap.Round)
                    if(completed>.7f && front) {
                        val c=position
                        val tick=Path().apply{moveTo(c.x-cw*.18f,c.y);lineTo(c.x-cw*.035f,c.y+ch*.10f);lineTo(c.x+cw*.22f,c.y-ch*.12f)}
                        drawPath(tick,ink.copy(alpha=((completed-.7f)/.3f).coerceIn(0f,1f)),style=Stroke(unit*.045f,cap=StrokeCap.Round))
                    }else {
                        drawRoundRect(ink.copy(alpha=.62f*alpha),origin+Offset(cw*.30f,ch*.24f),Size(cw*.43f,ch*.06f),CornerRadius(unit*.014f))
                        for(line in 0..2)drawRoundRect(ink.copy(alpha=(.22f-line*.035f)*alpha),origin+Offset(cw*.30f,ch*(.43f+line*.105f)),Size(cw*(if(line==2).27f else .43f),ch*.025f),CornerRadius(unit*.01f))
                    }
                }
            }
            if(separation>.05f && completed<.95f) {
                val start=left+Offset(unit*.52f,0f);val end=right-Offset(unit*.52f,0f)
                drawLine(Brush.horizontalGradient(listOf(colors.primary.copy(alpha=.03f),colors.primary.copy(alpha=.13f),colors.primary.copy(alpha=.03f))),start,end,unit*.012f,StrokeCap.Round)
                if(transferring && !reduced)for(i in 0..3) {
                    val raw=(time+i/4f)%1f
                    val p=androidx.compose.animation.core.FastOutSlowInEasing.transform(if(state.incoming)1-raw else raw)
                    val opacity=sin(p*PI).toFloat().coerceIn(0f,1f)*(1-completed)
                    val position=Offset(start.x+(end.x-start.x)*p,center.y+sin(p*PI*2).toFloat()*unit*.10f)
                    card(position,.24f,-8f+16f*p,opacity)
                }
            }
            val floating=Offset(0f,breath)
            card(left+floating+Offset(-unit*.07f,-unit*.04f),1f,-9f*(1-completed),.8f)
            card(left+floating+Offset(unit*.04f,-unit*.02f),1f,5f*(1-completed),.95f)
            card(left+floating,1f,0f,1f,true)
            if(separation>.05f && completed<.95f) {
                card(right+Offset(unit*.06f,-unit*.025f-breath),1f,7f,.85f*(1-completed))
                card(right-Offset(0f,breath),1f,0f,1-completed)
            }
            val railY=h*.90f;val railLeft=w*.18f;val railRight=w*.82f
            if(state.progress.totalBytes>0 || completed>0) {
                drawLine(colors.onSurface.copy(alpha=.07f),Offset(railLeft,railY),Offset(railRight,railY),unit*.035f,StrokeCap.Round)
                drawLine(Brush.horizontalGradient(listOf(colors.primary.copy(alpha=.60f),colors.primary)),Offset(railLeft,railY),Offset(railLeft+(railRight-railLeft)*maxOf(fraction,completed),railY),unit*.035f,StrokeCap.Round)
            }else if(discovery && state.phase==TransferPhase.DISCOVERING)for(i in 0..2) {
                val alpha=if(reduced).35f else .2f+.5f*((sin((time-i*.16f)*PI*2)+1)/2).toFloat()
                drawCircle(colors.primary.copy(alpha=alpha),unit*.025f,Offset(w*.5f+(i-1)*unit*.18f,h*.83f))
            }
        }
        if(state.progress.totalBytes>0 && completed<.5f)Text("${(fraction*100).toInt()}%",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Medium,color=colors.onSurface,modifier=Modifier.padding(bottom=D.SpaceSection))
    }
}
private fun bytes(value:Long):String = when {value>=1024L*1024*1024 -> "%.2f GiB".format(value/(1024.0*1024*1024));value>=1024L*1024 -> "%.1f MiB".format(value/(1024.0*1024));value>=1024 -> "%.0f KiB".format(value/1024.0);else -> "$value B"}
private fun summary(preview:ArchivePreview?):String=preview?.let {"${it.counts["books"]?:0} 本书 · ${it.counts["favorites"]?:0} 项收藏\n${it.counts.values.sum()} 条数据 · 解包约 ${bytes(it.expandedBytes)}"}?:"正在检查内容"
private fun permissionRequests():Array<String> = TransferDiscovery.permissions() + if(android.os.Build.VERSION.SDK_INT>=33)arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)else emptyArray()
