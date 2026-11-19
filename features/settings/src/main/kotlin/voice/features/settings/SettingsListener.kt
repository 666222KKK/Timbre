package voice.features.settings

import voice.core.data.ThemeMode
import java.time.LocalTime

interface SettingsListener {
  fun close()
  fun onThemeModeRowClick()
  fun setThemeMode(themeMode: ThemeMode)
  fun toggleDiscoverPage()
  fun toggleGrid()
  fun dismissDialog()
  fun openProjectRepository()
  fun openSupportVoice()
  fun setAutoSleepTimer(checked: Boolean)
  fun setAutoSleepTimerStart(time: LocalTime)
  fun setAutoSleepTimerEnd(time: LocalTime)
  fun toggleAnalytics()
  fun openWebDav()
  fun openBackup()
  fun openExtensions()
  fun setOnlineSourceEnabled(checked: Boolean)
  fun onOnlineSourceBaseUrlRowClick()
  fun onOnlineSourceCredentialRowClick()
  fun onlineSourceBaseUrlChanged(value: String)
  fun onlineSourceCredentialChanged(value: String)
  fun openPlayerSettings()
  fun onAppVersionClick()
  fun onUpdateDialogConfirm()
  fun onUpdateDialogDismiss()

  fun openDeveloperMenu()

  companion object {
    fun noop() = object : SettingsListener {
      override fun close() {}
      override fun onThemeModeRowClick() {}
      override fun setThemeMode(themeMode: ThemeMode) {}
      override fun toggleDiscoverPage() {}
      override fun toggleGrid() {}
      override fun setOnlineSourceEnabled(checked: Boolean) {}
      override fun onOnlineSourceBaseUrlRowClick() {}
      override fun onOnlineSourceCredentialRowClick() {}
      override fun onlineSourceBaseUrlChanged(value: String) {}
      override fun onlineSourceCredentialChanged(value: String) {}
      override fun openPlayerSettings() {}
      override fun dismissDialog() {}
      override fun openProjectRepository() {}
      override fun openSupportVoice() {}
      override fun setAutoSleepTimer(checked: Boolean) {}
      override fun setAutoSleepTimerStart(time: LocalTime) {}
      override fun setAutoSleepTimerEnd(time: LocalTime) {}
      override fun toggleAnalytics() {}
      override fun openWebDav() {}
      override fun openBackup() {}
      override fun openExtensions() {}
      override fun onAppVersionClick() {}
      override fun onUpdateDialogConfirm() {}
      override fun onUpdateDialogDismiss() {}
      override fun openDeveloperMenu() {}
    }
  }
}
