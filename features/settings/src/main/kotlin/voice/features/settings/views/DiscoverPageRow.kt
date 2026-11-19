package voice.features.settings.views

import androidx.compose.foundation.clickable
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
 * Row directly below "use grid layout": turning it off removes the discover
 * page from the shelf swipe, leaving only the library page.
 */
@Composable
internal fun DiscoverPageRow(
  enabled: Boolean,
  onToggle: () -> Unit,
) {
  ListItem(
    modifier = Modifier.clickable(onClick = onToggle),
    leadingContent = {
      Icon(
        imageVector = VoiceIcons.Search,
        contentDescription = stringResource(StringsR.string.settings_library_discover_page_title),
      )
    },
    supportingContent = {
      Text(stringResource(StringsR.string.settings_library_discover_page_summary))
    },
    trailingContent = {
      Switch(
        checked = enabled,
        onCheckedChange = { onToggle() },
      )
    },
  ) {
    Text(stringResource(StringsR.string.settings_library_discover_page_title))
  }
}
