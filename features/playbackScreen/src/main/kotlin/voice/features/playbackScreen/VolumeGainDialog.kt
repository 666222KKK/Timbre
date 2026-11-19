package voice.features.playbackScreen

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import voice.core.playback.misc.Decibel
import voice.core.strings.R as StringsR

@Composable
internal fun VolumeGainDialog(
  dialogState: BookPlayDialogViewState.VolumeGainDialog,
  viewModel: BookPlayViewModel,
) {
  AlertDialog(
    onDismissRequest = { viewModel.dismissDialog() },
    confirmButton = {},
    text = {
      Column {
        Text(stringResource(id = StringsR.string.playback_option_volume_boost) + ": " + dialogState.valueFormatted)
        val gainLabel = stringResource(id = StringsR.string.a11y_slider_volume_gain)
        Slider(
          modifier = Modifier.semantics {
            contentDescription = gainLabel
            stateDescription = dialogState.valueFormatted
          },
          valueRange = 0F..dialogState.maxGain.value,
          value = dialogState.gain.value,
          onValueChange = {
            viewModel.onVolumeGainChanged(Decibel(it))
          },
        )
      }
    },
  )
}
