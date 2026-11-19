package voice.features.folderPicker.addcontent

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.extension.ExtensionInstallException
import voice.core.extension.ExtensionManager
import voice.core.logging.api.Logger
import voice.navigation.Destination
import voice.navigation.Destination.OnboardingCompletion
import voice.navigation.Navigator
import voice.navigation.Origin

@AssistedInject
class AddContentViewModel(
  private val extensionManager: ExtensionManager,
  dispatcherProvider: DispatcherProvider,
  private val navigator: Navigator,
  @Assisted
  private val origin: Origin,
) {

  private val scope = MainScope(dispatcherProvider)

  private val importError = MutableStateFlow<String?>(null)
  val sourceImportError: StateFlow<String?> = importError.asStateFlow()

  internal fun importSource(source: SourceImport) {
    scope.launch {
      try {
        val manifest = when (source) {
          is SourceImport.Local -> extensionManager.installFromUri(source.uri)
          is SourceImport.Url -> extensionManager.installFromUrl(source.url)
        }
        Logger.i("导入书源成功: " + manifest.name + " v" + manifest.version)
        navigateAfterAdd()
      } catch (e: Exception) {
        importError.value = (e as? ExtensionInstallException)?.message
          ?: "书源导入失败：" + (e.message ?: "未知错误")
      }
    }
  }

  /** 跳过引导：不添加任何内容，直接进入书架（或完成引导流程）。 */
  internal fun enter() {
    navigateAfterAdd()
  }

  internal fun consumeImportError() {
    importError.value = null
  }

  private fun navigateAfterAdd() {
    when (origin) {
      Origin.Default -> {
        navigator.setRoot(Destination.BookOverview)
      }
      Origin.Onboarding -> {
        navigator.goTo(OnboardingCompletion)
      }
    }
  }

  internal fun openWebDav() {
    navigator.goTo(Destination.WebDavServers(origin))
  }

  internal fun back() {
    navigator.goBack()
  }

  @AssistedFactory
  interface Factory {
    fun create(origin: Origin): AddContentViewModel
  }
}
