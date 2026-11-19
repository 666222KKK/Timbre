package voice.features.sleepTimer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue.Expanded
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import voice.core.strings.R as StringsR

@Composable
fun SleepTimerDialog(
  viewState: SleepTimerViewState,
  onDismiss: () -> Unit,
  onAcceptSleepTime: (Int) -> Unit,
  onAcceptSleepAtEndOfChapter: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showCustomInput by remember { mutableStateOf(false) }
  ModalBottomSheet(
    modifier = modifier,
    onDismissRequest = onDismiss,
    sheetState = rememberBottomSheetState(
      initialValue = Hidden,
      enabledValues = setOf(Hidden, Expanded),
    ),
  ) {
    Column {
      Text(
        modifier = Modifier
          .padding(horizontal = 16.dp)
          .fillMaxWidth(),
        text = stringResource(id = StringsR.string.sleep_timer_dialog_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.size(16.dp))
      listOf(5, 15, 30, 60).forEach { time ->
        ListItem(
          modifier = Modifier.clickable {
            onAcceptSleepTime(time)
          },
          colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
          Text(text = minutes(minutes = time))
        }
      }
      // Custom duration is typed in, not stepped: tapping opens a number input
      // and the value is remembered for the next session.
      ListItem(
        modifier = Modifier.clickable { showCustomInput = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        trailingContent = {
          Text(
            text = minutes(minutes = viewState.customSleepTime),
            color = MaterialTheme.colorScheme.primary,
          )
        },
      ) {
        Text(text = stringResource(id = StringsR.string.sleep_timer_custom_title))
      }
      ListItem(
        modifier = Modifier.clickable(onClick = onAcceptSleepAtEndOfChapter),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      ) {
        Text(text = stringResource(id = StringsR.string.sleep_timer_end_of_chapter))
      }
      Spacer(modifier = Modifier.size(32.dp))
    }
  }

  if (showCustomInput) {
    CustomMinutesDialog(
      initialMinutes = viewState.customSleepTime,
      onDismiss = { showCustomInput = false },
      onConfirm = { value ->
        showCustomInput = false
        onAcceptSleepTime(value)
      },
    )
  }
}

@Composable
private fun CustomMinutesDialog(
  initialMinutes: Int,
  onDismiss: () -> Unit,
  onConfirm: (Int) -> Unit,
) {
  var text by remember(initialMinutes) { mutableStateOf(initialMinutes.toString()) }
  val parsed = text.toIntOrNull()
  val valid = parsed != null && parsed in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES
  // Focus lands on the field automatically: a blind user must be able to type
  // right away, and sighted users get the keyboard without an extra tap.
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(id = StringsR.string.sleep_timer_custom_title)) },
    text = {
      OutlinedTextField(
        modifier = Modifier.focusRequester(focusRequester),
        value = text,
        onValueChange = { input -> text = input.filter { it.isDigit() }.take(4) },
        label = { Text(stringResource(id = StringsR.string.sleep_timer_custom_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
    },
    confirmButton = {
      TextButton(
        enabled = valid,
        onClick = {
          parsed?.let { onConfirm(it.coerceIn(MIN_CUSTOM_MINUTES, MAX_CUSTOM_MINUTES)) }
        },
      ) {
        Text(stringResource(id = StringsR.string.common_dialog_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(id = StringsR.string.common_dialog_cancel))
      }
    },
  )
}

@Composable
@ReadOnlyComposable
private fun minutes(minutes: Int): String {
  return pluralStringResource(StringsR.plurals.duration_minutes, minutes, minutes)
}

private const val MIN_CUSTOM_MINUTES = 1
private const val MAX_CUSTOM_MINUTES = 24 * 60
