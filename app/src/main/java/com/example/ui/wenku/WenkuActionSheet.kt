package com.example.ui.wenku

import com.example.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIconButton
import com.example.ui.components.paperDismiss

@Composable
internal fun WenkuActionSheet(
    title: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    fitKeyboard: Boolean = false,
    requestDismiss: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    WenkuSheetDialog(
        onDismissRequest = onDismiss, dismissGesturesEnabled = dismissEnabled,
        fitKeyboard = fitKeyboard, requestDismiss = requestDismiss
    ) {
        WenkuLoadedContentSequence {
            Column(
                Modifier.fillMaxWidth().semantics { paneTitle = title }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth().wenkuPagePart(0), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    AppIconButton(Icons.Filled.Close, tr("关闭面板"), onClick = paperDismiss {}, enabled = dismissEnabled)
                }
                Column(Modifier.fillMaxWidth().wenkuPagePart(1), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}
