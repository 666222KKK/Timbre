package voice.app.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.core.strings.R as StringsR

internal val readerTabs = listOf(Destination.BookOverview, Destination.Discovery, Destination.Settings)

@Composable
internal fun ReaderTabs(
  selected: Destination.Compose,
  onSelect: (Destination.Compose) -> Unit,
) {
  NavigationBar {
    readerTabs.forEach { tab ->
      val label = stringResource(
        when (tab) {
          Destination.BookOverview -> StringsR.string.reader_shelf
          Destination.Discovery -> StringsR.string.discovery_title
          else -> StringsR.string.settings_action_open
        },
      )
      NavigationBarItem(
        selected = selected == tab,
        onClick = { if (selected != tab) onSelect(tab) },
        icon = {
          Icon(
            when (tab) {
              Destination.BookOverview -> VoiceIcons.LibraryBooks
              Destination.Discovery -> VoiceIcons.Search
              else -> VoiceIcons.Settings
            },
            contentDescription = null,
          )
        },
        label = { Text(label) },
      )
    }
  }
}
