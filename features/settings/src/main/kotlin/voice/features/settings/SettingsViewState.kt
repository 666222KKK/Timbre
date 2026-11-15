package voice.features.settings

import voice.core.data.ThemeMode
import voice.core.update.UpdateAvailable
import java.time.LocalTime

data class SettingsViewState(
  val themeMode: ThemeMode,
  val appVersion: String,
  val dialog: Dialog?,
  val useGrid: Boolean,
  val discoverPageEnabled: Boolean,
  val autoSleepTimer: AutoSleepTimerViewState,
  val showAnalyticSetting: Boolean,
  val analyticsEnabled: Boolean,
  val showDeveloperMenu: Boolean,
  val onlineSourceEnabled: Boolean,
  val onlineSourceBaseUrl: String,
  val onlineSourceCredential: String,
  val onlineSourceVerifying: Boolean = false,
  val onlineSourceVerifyError: Int? = null,
  val showSupportDevelopment: Boolean,
  val kioskMode: Boolean,
  /** Hidden for now: the row stays out of the list until this flips to true. */
  val showOnlineSource: Boolean = false,
  val availableUpdate: UpdateAvailable? = null,
  val showUpdateDialog: Boolean = false,
) {

  enum class Dialog {
    OnlineSourceBaseUrl,
    OnlineSourceCredential,
    Theme,
  }

  companion object {
    fun preview(): SettingsViewState {
      return SettingsViewState(
        themeMode = ThemeMode.FollowSystem,
        onlineSourceEnabled = false,
        onlineSourceBaseUrl = "",
        onlineSourceCredential = "",
        onlineSourceVerifying = false,
        onlineSourceVerifyError = null,
        dialog = null,
        appVersion = "1.2.3",
        useGrid = true,
        discoverPageEnabled = true,
        autoSleepTimer = AutoSleepTimerViewState.preview(),
        analyticsEnabled = false,
        showAnalyticSetting = true,
        showDeveloperMenu = true,
        showSupportDevelopment = true,
        kioskMode = false,
      )
    }
  }

  data class AutoSleepTimerViewState(
    val enabled: Boolean,
    val startTime: LocalTime,
    val endTime: LocalTime,
  ) {
    companion object {
      fun preview(): AutoSleepTimerViewState {
        return AutoSleepTimerViewState(
          enabled = false,
          startTime = LocalTime.of(22, 0),
          endTime = LocalTime.of(6, 0),
        )
      }
    }
  }
}
