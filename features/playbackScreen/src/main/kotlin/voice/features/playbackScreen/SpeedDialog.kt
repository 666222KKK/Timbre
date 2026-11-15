package voice.features.playbackScreen

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import java.text.DecimalFormat
import voice.core.strings.R as StringsR

@Composable
internal fun SpeedDialog(
  dialogState: BookPlayDialogViewState.SpeedDialog,
  viewModel: BookPlayViewModel,
) {
  val speedFormatter = remember { DecimalFormat("0.00 x") }

  AlertDialog(
    onDismissRequest = { viewModel.dismissDialog() },
    confirmButton = {},
    title = {
      Text(stringResource(id = StringsR.string.playback_speed_title))
    },
    text = {
      Column {
        Text(stringResource(id = StringsR.string.playback_speed_title) + ": " + speedFormatter.format(dialogState.speed))
        val valueRange = 0.5F..dialogState.maxSpeed
        val rangeSize = valueRange.endInclusive - valueRange.start
        val stepSize = 0.05
        val steps = (rangeSize / stepSize).toInt() - 1
        val speedLabel = stringResource(id = StringsR.string.a11y_slider_speed)
        Slider(
          modifier = Modifier.semantics {
            contentDescription = speedLabel
            stateDescription = speedFormatter.format(dialogState.speed)
          },
          steps = steps,
          valueRange = valueRange,
          value = dialogState.speed,
          onValueChange = {
            viewModel.onPlaybackSpeedChanged(it)
          },
        )
      }
    },
  )
}
