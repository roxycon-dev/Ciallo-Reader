package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.CredentialVault
import com.example.data.VaultCredential
import com.example.i18n.tr
import com.example.source.BookSource
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppToast
import com.example.ui.components.GlassCard
import com.example.ui.source.SourceCredentialFields
import com.example.ui.wenku.WenkuActionSheet
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * 密码箱：集中管理需要登录的书源账号（Z-Library、哔咔、vomic 等）。
 *
 * 数据由 [CredentialVault] 用 Android Keystore 主密钥加密后存在应用私有目录，
 * 同签名覆盖安装/升级自动保留；按设计不进入 `.ciallo` 备份与设备互传。
 */
@Composable
fun CredentialVaultScreen(
    sources: List<BookSource>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(CredentialVault.list(context)) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VaultCredential?>(null) }
    var deleteTarget by remember { mutableStateOf<VaultCredential?>(null) }
    val available = remember(sources) {
        sources.filter { it.requiresLogin }.ifEmpty { sources }.sortedBy { it.name }
    }

    fun reload() {
        entries = CredentialVault.list(context)
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconButton(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    tr("返回"),
                    onClick = onBack,
                    wenkuSymbol = WenkuSymbol.Back,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("密码箱"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("加密保存在本机，升级 App 不会丢；不随备份迁移到新设备"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!CredentialVault.isAvailable(context)) {
                    item("vault_unavailable") {
                        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Text(
                                tr("系统安全存储不可用，密码箱暂时无法读写。请在设备解锁后重试，或检查系统安全设置。"),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (entries.isEmpty()) {
                    item("vault_empty") {
                        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("还没有保存的账号"), fontWeight = FontWeight.SemiBold)
                                Text(
                                    tr("登录书源时勾选「保存到密码箱」，或点下面的按钮手动添加。"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                items(entries, key = { it.sourceId }) { entry ->
                    VaultRow(
                        entry = entry,
                        onEdit = { editing = entry },
                        onDelete = { deleteTarget = entry },
                    )
                }
                item("vault_add") {
                    WenkuTextButton(
                        tr("添加账号"),
                        { adding = true },
                        Modifier.fillMaxWidth(),
                        selected = true,
                        enabled = available.isNotEmpty() && CredentialVault.isAvailable(context),
                        wenkuSymbol = WenkuSymbol.Add,
                    )
                }
                item("vault_bottom_space") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (adding || editing != null) {
        VaultEditorSheet(
            sources = available,
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onDelete = editing?.let { target -> { deleteTarget = target } },
            onSave = { credential ->
                if (CredentialVault.save(context, credential)) {
                    reload()
                    adding = false
                    editing = null
                    AppToast.makeText(context, tr("已保存到密码箱"), Toast.LENGTH_SHORT).show()
                } else {
                    AppToast.makeText(context, tr("保存失败：系统安全存储不可用或字段为空"), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tr("删除密码箱账号")) },
            text = { Text(tr("将删除「%s」保存的账号密码，书源本身的登录状态不受影响。").replace("%s", target.sourceName)) },
            confirmButton = {
                TextButton(onClick = {
                    CredentialVault.delete(context, target.sourceId)
                    reload()
                    deleteTarget = null
                }) { Text(tr("删除")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(tr("取消")) }
            },
        )
    }
}

@Composable
private fun VaultRow(
    entry: VaultCredential,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var revealed by remember(entry.sourceId) { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.sourceName.ifBlank { entry.sourceId }, fontWeight = FontWeight.SemiBold)
                    Text(entry.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIconButton(
                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    if (revealed) tr("隐藏密码") else tr("显示密码"),
                    onClick = { revealed = !revealed },
                    wenkuSymbol = if (revealed) WenkuSymbol.EyeOff else WenkuSymbol.Eye,
                )
            }
            Text(
                if (revealed) entry.password else "••••••••",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppIconButton(Icons.Filled.ContentCopy, tr("复制账号"), onClick = {
                    copyToClipboard(context, entry.username)
                    AppToast.makeText(context, tr("已复制账号"), Toast.LENGTH_SHORT).show()
                }, wenkuSymbol = WenkuSymbol.Copy)
                AppIconButton(Icons.Filled.ContentCopy, tr("复制密码"), onClick = {
                    copyToClipboard(context, entry.password)
                    AppToast.makeText(context, tr("已复制密码"), Toast.LENGTH_SHORT).show()
                }, wenkuSymbol = WenkuSymbol.Copy)
                AppIconButton(Icons.Filled.Edit, tr("编辑"), onClick = onEdit, wenkuSymbol = WenkuSymbol.Edit)
                AppIconButton(Icons.Filled.Delete, tr("删除"), onClick = onDelete, wenkuSymbol = WenkuSymbol.Delete)
            }
        }
    }
}

@Composable
private fun VaultEditorSheet(
    sources: List<BookSource>,
    initial: VaultCredential?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (VaultCredential) -> Unit,
) {
    var selectedId by remember { mutableStateOf(initial?.sourceId ?: sources.firstOrNull()?.id.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    val scope = rememberCoroutineScope()
    val selectedSource = sources.firstOrNull { it.id == selectedId }
    val canSave = selectedId.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    WenkuActionSheet(
        if (initial == null) tr("添加账号") else tr("编辑账号"),
        onDismiss,
        fitKeyboard = true,
        centerVertically = true,
    ) {
        if (initial == null) {
            Text(tr("选择书源"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            sources.forEach { src ->
                Row(
                    Modifier.fillMaxWidth().clickable { selectedId = src.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedId == src.id, onClick = { selectedId = src.id })
                    Text(src.name)
                }
            }
        } else {
            Text(
                tr("书源：%s").replace("%s", initial.sourceName.ifBlank { initial.sourceId }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SourceCredentialFields(username, { username = it }, password, { password = it }, true) {}
        Text(
            tr("只保存在本机（加密），不会上传；升级 App 后仍然可用。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WenkuTextButton(
            tr("保存"),
            {
                if (canSave) {
                    scope.launch {
                        onSave(
                            VaultCredential(
                                sourceId = selectedId,
                                sourceName = selectedSource?.name ?: initial?.sourceName ?: selectedId,
                                username = username.trim(),
                                password = password,
                            )
                        )
                    }
                }
            },
            Modifier.fillMaxWidth(),
            selected = true,
            enabled = canSave,
            wenkuSymbol = WenkuSymbol.Check,
        )
        if (onDelete != null) {
            WenkuTextButton(
                tr("删除这条账号"),
                { onDelete() },
                Modifier.fillMaxWidth(),
                compact = true,
                wenkuSymbol = WenkuSymbol.Delete,
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("ciallo-vault", text))
    }
}
