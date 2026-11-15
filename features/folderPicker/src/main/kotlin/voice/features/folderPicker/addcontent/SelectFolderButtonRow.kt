package voice.features.folderPicker.addcontent

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.logging.api.Logger
import voice.core.strings.R
import voice.core.ui.icons.VoiceIcons

/**
 * 官方接口源包的默认地址，导入对话框首次打开时预填。
 *
 * 走 jsDelivr 而不是 raw.githubusercontent.com：后者在国内经常连不上，
 * 新用户第一次导入就失败，还以为接口源坏了。
 */
private const val DEFAULT_SOURCE_URL =
  "https://cdn.jsdelivr.net/gh/kxdebug/Timbre@main/samples/Timbre.jdr"

/** 导入书源的两种来源：本地 .jdr 文件或在线链接。 */
internal sealed interface SourceImport {
  data class Local(val uri: Uri) : SourceImport

  data class Url(val url: String) : SourceImport
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectFolderButtonRow(
  onEnter: () -> Unit,
  onImportSource: (SourceImport) -> Unit,
  onWebDav: (() -> Unit)?,
) {
  // FlowRow wraps instead of scrolling, so nothing gets cut off on narrow screens.
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    val sourceFileLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument(),
    ) { uri ->
      if (uri != null) {
        onImportSource(SourceImport.Local(uri))
      }
    }
    var showSourceDialog by remember { mutableStateOf(false) }
    var sourceUrl by remember { mutableStateOf(DEFAULT_SOURCE_URL) }

    SelectFolderButton(
      icon = VoiceIcons.ArrowForward,
      text = stringResource(id = R.string.folder_add_type_enter),
      onClick = onEnter,
    )
    SelectFolderButton(
      icon = VoiceIcons.Download,
      text = stringResource(id = R.string.folder_add_type_source),
      onClick = { showSourceDialog = true },
    )
    if (onWebDav != null) {
      SelectFolderButton(
        icon = VoiceIcons.Language,
        text = stringResource(id = R.string.folder_add_type_webdav),
        onClick = onWebDav,
      )
    }

    if (showSourceDialog) {
      AlertDialog(
        onDismissRequest = { showSourceDialog = false },
        title = { Text(stringResource(id = R.string.folder_add_source_title)) },
        text = {
          Column {
            OutlinedTextField(
              modifier = Modifier.fillMaxWidth(),
              value = sourceUrl,
              onValueChange = { sourceUrl = it },
              label = { Text(stringResource(id = R.string.folder_add_source_url_hint)) },
              singleLine = true,
            )
            TextButton(
              onClick = {
                showSourceDialog = false
                try {
                  sourceFileLauncher.launch(arrayOf("*/*"))
                } catch (e: ActivityNotFoundException) {
                  Logger.w(e, "Could not pick a source file")
                }
              },
            ) {
              Text(stringResource(id = R.string.folder_add_source_pick_file))
            }
          }
        },
        confirmButton = {
          TextButton(
            enabled = sourceUrl.isNotBlank(),
            onClick = {
              showSourceDialog = false
              onImportSource(SourceImport.Url(sourceUrl.trim()))
            },
          ) {
            Text(stringResource(id = R.string.folder_add_source_confirm))
          }
        },
        dismissButton = {
          TextButton(onClick = { showSourceDialog = false }) {
            Text(stringResource(id = R.string.common_dialog_cancel))
          }
        },
      )
    }
  }
}
