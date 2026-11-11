package com.example.transfer

import com.example.i18n.tr


import com.example.ui.components.PullOutTextButton
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.data.BackupManager
import com.example.data.PrivacyManager
import com.example.ui.components.AppSwitch
import com.example.ui.glasskit.GlassKitHost
import com.example.ui.components.GlassCard
import com.example.ui.components.paperDismiss
import com.example.ui.wenku.LocalWenkuDecorativeMotion
import com.example.ui.wenku.LocalWenkuMainSurface
import com.example.ui.wenku.WenkuIcon
import com.example.ui.wenku.WenkuLoadedContentSequence
import com.example.ui.wenku.WenkuMorphText
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuSheetDialog
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import com.example.ui.wenku.rememberWenkuAccessibility
import com.example.ui.wenku.wenkuPagePart
import com.example.ui.wenku.wenkuReveal
import com.example.ui.components.AppIconButton
import com.example.ui.glasskit.GlassTopBarStrip
import com.example.ui.glasskit.GlassTokens
import com.example.ui.glasskit.GlassManagementTopBar
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
    if (!open && state.phase == TransferPhase.AWAITING_CONFIRMATION && state.incoming) {
        val prompt = state
        var opening by remember(prompt.peer?.id, prompt.verificationCode) { mutableStateOf(false) }
        TransferSheet(
            title = "${prompt.peer?.name ?: "附近设备"} 想发送数据",
            onDismiss = {
                if (!opening && manager.state.value.matchesPrompt(prompt)) manager.confirm(false)
            }
        ) {
            Text(tr("请核对两台设备的校验码一致，再接收。"))
            Text(prompt.verificationCode.orEmpty().chunked(3).joinToString(" "),
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(tr("默认合并，保留本机设置和已有内容。"))
            val openAfterExit = paperDismiss { if (manager.state.value.matchesPrompt(prompt)) manager.show() }
            WenkuTextButton(tr("查看并接收"), onClick = {
                opening = true
                openAfterExit()
            }, wenkuSymbol = WenkuSymbol.Forward, modifier = Modifier.fillMaxWidth())
            WenkuTextButton(tr("拒绝"), onClick = paperDismiss {}, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(manager:TransferManager,backup:BackupManager,onEnablePrivacy:suspend(String)->Boolean={false},onBack:()->Unit=manager::hide) {
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
    var pinError by remember(pinPurpose) { mutableStateOf<String?>(null) }
    var showUndo by remember { mutableStateOf(false) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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
            Toast.makeText(context,tr("备份已保存"),Toast.LENGTH_SHORT).show()
        }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;localError=e.message}finally{localProgress=null}
    } }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)manager.openShared(uri) }
    val activeState=localProgress?.let { TransferSessionState(it.phase,progress=it,message=it.message) }?:state
    val colors = MaterialTheme.colorScheme
    val motion = rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
    val listState = rememberLazyListState()
    LaunchedEffect(state.phase) {
        if (state.phase == TransferPhase.AWAITING_CONFIRMATION || state.phase == TransferPhase.RECEIVED ||
            state.phase == TransferPhase.COMPLETED) listState.scrollToItem(0)
    }
    val closePage: () -> Unit = { if (localProgress == null) onBack() }
    BackHandler { closePage() }
    val scrollBehavior=TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val statusTop=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerTravel = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    CompositionLocalProvider(LocalWenkuMainSurface provides true) {
    GlassKitHost(topGradientHeight=statusTop+GlassTokens.TopBarHeight+D.SpaceXxl) {
        Scaffold(containerColor=Color.Transparent,modifier=Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),topBar={
            Box {
                GlassTopBarStrip((-scrollBehavior.state.contentOffset / headerTravel).coerceIn(0f, 1f),Modifier.fillMaxWidth().height(statusTop+GlassTokens.TopBarHeight))
                GlassManagementTopBar("设备互传", "SYSTEM / DATA TRANSFER", scrollBehavior, closePage,
                    actions={AppIconButton(icon=Icons.Default.History,contentDescription=tr("传输记录"),wenkuSymbol=WenkuSymbol.Timer,onClick={showHistory=true})})
            }
        }) { padding ->
        LazyColumn(state = listState, modifier=Modifier.fillMaxSize().padding(padding).navigationBarsPadding().testTag("device_transfer_screen"),
            contentPadding=PaddingValues(horizontal=D.SpacePage,vertical=D.SpaceSm),verticalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
            item(key="hero",contentType="hero") {
                Column(Modifier.fillMaxWidth().wenkuPagePart(1),horizontalAlignment=Alignment.CenterHorizontally) {
                    TransferArtwork(activeState)
                    WenkuMorphText(
                        activeState.phase.label,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        morphOnChange = motion
                    )
                    activeState.peer?.let { Text(it.name,style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant) }
                    if(activeState.phase in setOf(TransferPhase.CONNECTING,TransferPhase.FAILED,TransferPhase.WAITING_TO_RESUME,TransferPhase.RECEIVED) && activeState.message.isNotBlank()) {
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
                        Text(tr("两台设备显示相同的校验码"),style=MaterialTheme.typography.labelLarge)
                        Text(state.verificationCode.orEmpty().chunked(3).joinToString("  "),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold,color=colors.primary,modifier=Modifier.testTag("transfer_pairing_code"))
                        Text("${manager.deviceName}  ↔  ${state.peer?.name.orEmpty()}",style=MaterialTheme.typography.bodyMedium)
                    }
                    state.preview?.warnings?.forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=colors.error) }
                    WenkuTextButton(text=if(state.incoming)tr("校验码一致，合并接收") else if(state.verificationCode==null)tr("确认提示，继续发送") else tr("校验码一致，发送"),onClick={manager.confirm(true)})
                    if(state.incoming)PullOutTextButton(onClick={replaceConfirm=true}) {Text(tr("整机迁移，替换所选数据"))}
                    PullOutTextButton(onClick={manager.confirm(false)}) {Text(tr("取消"))}
                } }
            }
            if (state.phase == TransferPhase.RECEIVED && state.incoming) item(key = "received", contentType = "received") {
                WenkuLoadedContentSequence(contentKey = state.preview?.packageId) {
                    GlassCard(Modifier.fillMaxWidth().then(if (motion) Modifier.wenkuReveal(0) else Modifier)) {
                        Column(Modifier.padding(D.SpaceXl), verticalArrangement = Arrangement.spacedBy(D.SpaceMd)) {
                            Text(tr("已识别的内容"), style = MaterialTheme.typography.titleMedium)
                            Text(summary(state.preview), style = MaterialTheme.typography.bodyMedium)
                            Text(tr("合并会保留本机已有内容。替换所选数据需要再次确认。"), color = colors.onSurfaceVariant)
                            state.preview?.warnings?.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            val needsPin = state.preview?.containsProtected == true && !PrivacyManager(context).isEnabled()
                            WenkuTextButton(
                                if (needsPin) tr("先设置本机隐私保护") else tr("合并到本机"),
                                onClick = {
                                    if (needsPin) {
                                        pin = ""
                                        pinAgain = ""
                                        pinPurpose = "receive"
                                    } else {
                                        manager.importShared(ImportMode.MERGE)
                                        manager.continueProtectedImport(ImportMode.MERGE)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                selected = true,
                                wenkuSymbol = WenkuSymbol.Check
                            )
                            WenkuTextButton(tr("整机迁移，替换所选数据"), onClick = { replaceConfirm = true },
                                modifier = Modifier.fillMaxWidth(), wenkuSymbol = WenkuSymbol.Warning)
                            WenkuTextButton(tr("取消恢复"), onClick = manager::cancel, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if(state.phase==TransferPhase.COMPLETED) item(key="result",contentType="result") {
                GlassCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(D.SpaceXl),verticalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
                    state.result?.let { Text("新增 ${it.added} 项 · 合并 ${it.merged} 项 · 跳过 ${it.skipped} 项",style=MaterialTheme.typography.bodyMedium) }
                    if(!state.incoming)Text(tr("对方已收到 ✓   对方已导入 ✓"),color=colors.primary)
                    state.result?.warnings?.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                    WenkuTextButton(text=if(manager.needsRestart())"完成并重新打开" else tr("完成"),onClick={if(manager.needsRestart())restart()else {manager.reset();sending=false}})
                } }
            }
            if(state.phase==TransferPhase.WAITING_TO_RESUME || state.phase==TransferPhase.FAILED) item(key="retry",contentType="retry") {
                Column(verticalArrangement=Arrangement.spacedBy(D.SpaceSm)) {
                    WenkuTextButton(text=tr("继续传输"),onClick={manager.retry()})
                    if(state.peer?.p2pAddress!=null)PullOutTextButton(onClick={manager.retry(true)}) {Text(tr("改用附近直连"))}
                    PullOutTextButton(onClick={manager.cancel()}) {Text(tr("取消本次任务"))}
                }
            }
            if(!state.busy && state.phase !in setOf(TransferPhase.RECEIVED,TransferPhase.COMPLETED,TransferPhase.WAITING_TO_RESUME) && localProgress==null) {
                item(key="actions",contentType="actions") {
                    Row(Modifier.fillMaxWidth().wenkuPagePart(2),horizontalArrangement=Arrangement.spacedBy(D.SpaceMd)) {
                        WenkuTextButton(tr("发送数据"), onClick={discover()}, modifier=Modifier.weight(1f), selected=true, wenkuSymbol=WenkuSymbol.Upload, compact=true)
                        WenkuTextButton(tr("接收数据"), onClick={sending=false;if(!TransferDiscovery.allowed(context))permissions.launch(permissionRequests())else {manager.enable(true);manager.scan()}}, modifier=Modifier.weight(1f), wenkuSymbol=WenkuSymbol.Download, compact=true)
                    }
                }
                item(key="selection",contentType="selection") {
                    GlassCard(Modifier.fillMaxWidth().wenkuPagePart(3)) {Column(Modifier.padding(D.SpaceLg)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(tr("传输内容"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                            WenkuTextButton(
                                text = if (showDetails) tr("收起") else if (selected.categories.size == TransferCategory.entries.size) tr("全部 · 调整") else tr("调整"),
                                onClick = { showDetails = !showDetails },
                                compact = true,
                                wenkuSymbol = if (showDetails) WenkuSymbol.Up else WenkuSymbol.Sliders
                            )
                        }
                        AnimatedVisibility(
                            visible = showDetails,
                            enter = if (motion) expandVertically(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.ease)) +
                                fadeIn(tween(160)) else EnterTransition.None,
                            exit = if (motion) shrinkVertically(tween(WenkuMotion.EXIT_MS, easing = WenkuMotion.ease)) +
                                fadeOut(tween(180)) else ExitTransition.None
                        ) {
                            WenkuLoadedContentSequence {
                                Column {
                                    TransferCategory.entries.forEachIndexed { order, category ->
                                        val checked = category in selected.categories
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .then(if (motion) Modifier.wenkuReveal(order) else Modifier)
                                                .toggleable(checked, enabled = showDetails, role = Role.Checkbox) { include ->
                                                    selected = selected.copy(categories = if (include) selected.categories + category
                                                        else selected.categories - category)
                                                }
                                                .heightIn(min = 48.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(checked = checked, onCheckedChange = null, enabled = showDetails)
                                            Spacer(Modifier.width(D.SpaceMd))
                                            Text(category.label, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                        Text("已选 ${selected.categories.size} 类 · "+(estimate?.let {"${it.books} 本书 · ${it.favorites} 项收藏 · 内容约 ${bytes(it.approximateBytes)}"}?:"正在计算数量与体积…"),style=MaterialTheme.typography.labelMedium,color=colors.primary)
                        estimate?.warnings?.forEach {Text(it,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)}
                        if(PrivacyManager(context).hasPin())PullOutTextButton(onClick={if(selected.protectedAuthorized)selected=selected.copy(protectedAuthorized=false)else {pin="";pinPurpose="send"}}) {Text(if(selected.protectedAuthorized)tr("已解锁受保护内容 · 点击排除") else tr("解锁并加入受保护内容"))}
                    } }
                }
                if(sending) {
                    item(key="nearby_title",contentType="label") {Text(tr("可发送到"),style=MaterialTheme.typography.titleMedium)}
                    itemsIndexed(peers, key = { _, peer -> peer.id }, contentType = { _, _ -> "peer" }) { order, peer ->
                        GlassCard(
                            Modifier.fillMaxWidth().wenkuPagePart(3 + order.coerceAtMost(5)),
                            onClick = if (selected.categories.isNotEmpty()) {
                                { manager.startSend(peer, selected) }
                            } else null
                        ) {
                            Row(Modifier.fillMaxWidth().padding(D.SpaceXl), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(D.SpaceLg)) {
                                WenkuIcon(WenkuSymbol.Phone, size = D.SpaceXl, tint = colors.primary, entrance = false)
                                Column(Modifier.weight(1f)) {
                                    Text(peer.name, fontWeight = FontWeight.SemiBold)
                                    Text(peer.routeLabel, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                }
                                WenkuIcon(WenkuSymbol.Forward, size = D.SpaceLg, entrance = false)
                            }
                        }
                    }
                    if(peers.isEmpty()) item(key="empty",contentType="empty") {Text(tr("未发现设备。请打开对方的设备互传，或连接同一 Wi-Fi / 热点。"),style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)}
                    item(key="scan",contentType="scan") {PullOutTextButton(onClick={manager.scan()}) {Text(tr("重新发现设备"))}}
                }
                item(key="availability",contentType="availability") {GlassCard(Modifier.fillMaxWidth().wenkuPagePart(4)) {
                    Row(Modifier.padding(D.SpaceLg),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {Text(tr("允许接收"),style=MaterialTheme.typography.bodyMedium)
                            Text("${manager.deviceName} · ${if(enabled)tr("正在等待接收") else tr("尚未开启")}",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)}
                        AppSwitch(checked=enabled,onCheckedChange={if(it && !TransferDiscovery.allowed(context))permissions.launch(permissionRequests())else manager.enable(it)})
                    }
                } }
                if(discoveryMessage.isNotBlank())item(key="discovery_message",contentType="message") {Text(discoveryMessage,color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
                item(key="backup",contentType="backup") {GlassCard(Modifier.fillMaxWidth().wenkuPagePart(5)) {Column(Modifier.padding(D.SpaceLg),verticalArrangement=Arrangement.spacedBy(D.SpaceSm)) {
                    Text(tr("备份与分享文件"), style = MaterialTheme.typography.titleMedium)
                    Text(tr("将所选书籍、阅读记录和偏好保存为 Ciallo 备份。打开已有文件后可先查看内容。"),
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    WenkuTextButton(tr("分享数据包"), modifier=Modifier.fillMaxWidth(), wenkuSymbol=WenkuSymbol.Share, enabled=selected.categories.isNotEmpty(), onClick={scope.launch {
                        try {localProgress=TransferProgress(TransferPhase.PREPARING);val file=prepare()
                            val preview=backup.inspectArchive(file)
                            if(preview.warnings.isNotEmpty()) { localError=preview.warnings.joinToString("\n")+"\n请修复资源后重试，或使用本地备份保存当前可用内容。";file.delete();return@launch }
                            val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type=TRANSFER_MIME;putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("Ciallo 数据包",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"分享 Ciallo 数据"))
                        }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;localError=e.message}finally{localProgress=null}
                    }})
                    WenkuTextButton(tr("保存本地备份"), modifier=Modifier.fillMaxWidth(), wenkuSymbol=WenkuSymbol.Download, enabled=selected.categories.isNotEmpty(), onClick={save.launch("Ciallo-backup.ciallo")})
                    WenkuTextButton(tr("打开已有备份或分享文件"), modifier=Modifier.fillMaxWidth(), wenkuSymbol=WenkuSymbol.FolderOpen, onClick={open.launch(arrayOf(TRANSFER_MIME,"application/zip","application/octet-stream"))})
                    if (backup.hasMigrationUndo()) WenkuTextButton(tr("撤销最近一次整机迁移"), onClick = { showUndo = true }, modifier = Modifier.fillMaxWidth(), wenkuSymbol = WenkuSymbol.Refresh)
                } } }
            }
            if(state.busy && state.phase!=TransferPhase.AWAITING_CONFIRMATION)item(key="cancel",contentType="cancel") {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
                    PullOutTextButton(onClick=onBack) {Text(tr("收起，后台继续"))}
                    if(state.phase!=TransferPhase.IMPORTING)PullOutTextButton(onClick={manager.cancel()}) {Text(tr("取消传输"))}
                }
            }
        }
    }
    }
    }
    localError?.let { error ->
        TransferSheet("操作提示", onDismiss = { localError = null }) {
            Text(error, color = colors.onSurfaceVariant)
            WenkuTextButton(tr("知道了"), onClick = paperDismiss {}, modifier = Modifier.fillMaxWidth())
        }
    }
    if (replaceConfirm) {
        val target = remember { state }
        TransferSheet("确认整机迁移", onDismiss = { replaceConfirm = false }) {
            Text(tr("将替换所选类别的本机内容，并暂停下载。替换书籍时也会替换其关联的标注与阅读记录。迁移前会保存回退快照。"))
            WenkuTextButton(
                "确认迁移",
                onClick = paperDismiss {
                    val current = manager.state.value
                    if (!current.matchesPrompt(target)) return@paperDismiss
                    if (current.phase == TransferPhase.AWAITING_CONFIRMATION) {
                        manager.confirm(true, ImportMode.REPLACE)
                    } else if (current.preview?.containsProtected == true && !PrivacyManager(context).isEnabled()) {
                        pin = ""
                        pinAgain = ""
                        pinPurpose = "receive"
                    } else {
                        manager.importShared(ImportMode.REPLACE)
                        manager.continueProtectedImport(ImportMode.REPLACE)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                color = colors.error,
                wenkuSymbol = WenkuSymbol.Warning
            )
            WenkuTextButton(tr("取消"), onClick = paperDismiss {}, modifier = Modifier.fillMaxWidth())
        }
    }
    if (showUndo) {
        TransferSheet("撤销整机迁移", onDismiss = { showUndo = false }) {
            Text(tr("将恢复最近一次整机迁移前保存的快照，并重新打开应用。"))
            WenkuTextButton(
                "确认撤销",
                modifier = Modifier.fillMaxWidth(),
                color = colors.error,
                wenkuSymbol = WenkuSymbol.Refresh,
                onClick = paperDismiss {
                    if (backup.hasMigrationUndo() && !manager.state.value.busy) {
                        scope.launch {
                            try {
                                localProgress = TransferProgress(TransferPhase.IMPORTING)
                                backup.undoMigration()
                                restart()
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                localError = error.message ?: "撤销失败"
                            } finally {
                                localProgress = null
                            }
                        }
                    }
                }
            )
            WenkuTextButton(tr("取消"), onClick = paperDismiss {}, modifier = Modifier.fillMaxWidth())
        }
    }
    pinPurpose?.let { purpose ->
        val unlock = purpose == "send"
        TransferSheet(
            title = if (unlock) tr("解锁受保护内容") else tr("设置本机隐私 PIN"),
            onDismiss = { pinPurpose = null; pin = ""; pinAgain = "" },
            dismissGesturesEnabled = !pinBusy
        ) {
            Text(if (unlock) tr("验证本机 6 位 PIN 后，受保护内容才会加入本次发送。")
                else tr("受保护内容需要本机独立的 6 位 PIN。发送机的 PIN 不会迁移。"))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(6); pinError = null },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pinBusy,
                label = { Text(tr("6 位 PIN")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            if (!unlock) OutlinedTextField(
                value = pinAgain,
                onValueChange = { pinAgain = it.filter(Char::isDigit).take(6); pinError = null },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pinBusy,
                label = { Text(tr("再次输入 PIN")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            pinError?.let { Text(it, color = colors.error) }
            val closeAfterSuccess = paperDismiss {}
            WenkuTextButton(
                text = if (pinBusy) tr("正在验证…") else tr("确认"),
                enabled = !pinBusy && pin.length == 6 && (unlock || pinAgain.length == 6),
                modifier = Modifier.fillMaxWidth(),
                wenkuSymbol = WenkuSymbol.Check,
                onClick = {
                    if (!pinBusy) {
                        pinBusy = true
                        scope.launch {
                            try {
                                if (unlock) selected = backup.authorizeProtectedExport(pin, selected)
                                else {
                                    require(pin == pinAgain) { tr("两次 PIN 不一致") }
                                    check(onEnablePrivacy(pin)) { "PIN 设置失败" }
                                }
                                closeAfterSuccess()
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                pinError = error.message ?: "验证失败"
                            } finally {
                                pinBusy = false
                            }
                        }
                    }
                }
            )
            WenkuTextButton(tr("取消"), onClick = paperDismiss {}, enabled = !pinBusy, modifier = Modifier.fillMaxWidth())
        }
    }
    if (showHistory) {
        val records = remember { manager.history() }
        TransferSheet("最近传输", onDismiss = { showHistory = false }) {
            if (records.isEmpty()) Text(tr("还没有传输记录"), color = colors.onSurfaceVariant)
            else records.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            WenkuTextButton(tr("关闭"), onClick = paperDismiss {}, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun TransferSessionState.matchesPrompt(other: TransferSessionState): Boolean =
    phase == other.phase && peer?.id == other.peer?.id &&
        verificationCode == other.verificationCode && preview?.packageId == other.preview?.packageId

@Composable
private fun TransferSheet(
    title: String,
    onDismiss: () -> Unit,
    dismissGesturesEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    WenkuSheetDialog(onDismissRequest = onDismiss, dismissGesturesEnabled = dismissGesturesEnabled) {
        val motion = rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
        WenkuLoadedContentSequence {
            Column(
                Modifier.fillMaxWidth().semantics { paneTitle = title }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = D.SpaceXl, vertical = D.SpaceMd),
                verticalArrangement = Arrangement.spacedBy(D.SpaceSm)
            ) {
                Row(Modifier.fillMaxWidth().then(if (motion) Modifier.wenkuReveal(0) else Modifier),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    AppIconButton(
                        Icons.Filled.Close, "关闭面板", onClick = paperDismiss {},
                        enabled = dismissGesturesEnabled
                    )
                }
                Column(
                    Modifier.fillMaxWidth().then(if (motion) Modifier.wenkuReveal(1) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(D.SpaceSm),
                    content = content
                )
            }
        }
    }
}
@Composable
internal fun TransferArtwork(state:TransferSessionState) {
    val colors=MaterialTheme.colorScheme
    val reduced = !rememberWenkuAccessibility().decorativeMotion || !LocalWenkuDecorativeMotion.current
    val owner=LocalLifecycleOwner.current
    var time by remember { mutableFloatStateOf(0f) }
    val transferring=state.phase in setOf(TransferPhase.TRANSFERRING,TransferPhase.PREPARING,TransferPhase.VERIFYING,TransferPhase.IMPORTING)
    val discovery = state.phase == TransferPhase.DISCOVERING
    LaunchedEffect(reduced,transferring,discovery,owner) {
        time = 0f
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
        if (state.progress.totalBytes > 0 && state.phase != TransferPhase.COMPLETED) {
            val percentage by remember { derivedStateOf { (fraction * 100).toInt() } }
            Text("$percentage%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                color = colors.onSurface, modifier = Modifier.padding(bottom = D.SpaceSection))
        }
    }
}
private fun bytes(value:Long):String = when {value>=1024L*1024*1024 -> "%.2f GiB".format(value/(1024.0*1024*1024));value>=1024L*1024 -> "%.1f MiB".format(value/(1024.0*1024));value>=1024 -> "%.0f KiB".format(value/1024.0);else -> "$value B"}
private fun summary(preview:ArchivePreview?):String=preview?.let {"${it.counts["books"]?:0} 本书 · ${it.counts["favorites"]?:0} 项收藏\n${it.counts.values.sum()} 条数据 · 解包约 ${bytes(it.expandedBytes)}"}?:"正在检查内容"
private fun permissionRequests():Array<String> = TransferDiscovery.permissions() + if(android.os.Build.VERSION.SDK_INT>=33)arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)else emptyArray()
