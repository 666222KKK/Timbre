package voice.features.settings.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import voice.core.ui.icons.VoiceIcons
import voice.core.strings.R as StringsR

/**
 * Keeps WebDav shelf refreshes off mobile data. It lives in the player
 * settings sheet, since it only affects playback/shelf sync behaviour.
 */
@Composable
internal fun AutoRefreshWifiRow(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  ListItem(
    modifier = Modifier
      .clickable { onCheckedChange(!checked) }
      .fillMaxWidth(),
    leadingContent = {
      Icon(
        imageVector = VoiceIcons.Language,
        contentDescription = stringResource(StringsR.string.settings_library_auto_refresh_wifi_title),
      )
    },
    supportingContent = {
      Text(stringResource(StringsR.string.settings_library_auto_refresh_wifi_summary))
    },
    trailingContent = {
      Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
      )
    },
  ) {
    Text(stringResource(StringsR.string.settings_library_auto_refresh_wifi_title))
  }
}
