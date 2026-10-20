package voice.features.backup

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import voice.core.backup.BackupArchive
import voice.core.backup.BackupContents
import voice.core.backup.BackupService
import voice.core.backup.BackupWebDav
import voice.core.playback.PlayerController
import voice.core.webdav.WebDavCredentialResolver
import voice.core.webdav.WebDavLibrary
import voice.core.webdav.WebDavResource
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Inject
class BackupViewModel(
  private val application: Application,
  private val service: BackupService,
  val library: WebDavLibrary,
  private val credentials: WebDavCredentialResolver,
  private val remote: BackupWebDav,
  private val player: PlayerController,
) {
  private val scope = MainScope()
  private val options = application.getSharedPreferences("backupOptions", 0)
  var password by mutableStateOf("")
  var confirmation by mutableStateOf("")
  var serverId by mutableStateOf(options.getString("server", "").orEmpty())
  var directory by mutableStateOf(options.getString("directory", "Timbre/backup").orEmpty())
  var busy by mutableStateOf(false)
    private set
  var message by mutableStateOf<String?>(null)
    private set
  var preview by mutableStateOf<BackupContents?>(null)
    private set
  var remoteFiles by mutableStateOf<List<WebDavResource>?>(null)
    private set
  var restartRequired by mutableStateOf(false)
    private set

  fun name(): String =
    "timbre-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-${UUID.randomUUID().toString().take(6)}.tbk"

  fun createLocal(uri: Uri) = run {
    checkPassword()
    player.pauseForBackup()
    val data = withContext(Dispatchers.IO) { service.create(password.toCharArray()) }
    withContext(Dispatchers.IO) {
      requireNotNull(application.contentResolver.openOutputStream(uri, "wt")).use { it.write(data) }
    }
    message = "本地备份已保存"
  }

  fun inspectLocal(uri: Uri) = run {
    val bytes = withContext(Dispatchers.IO) {
      requireNotNull(application.contentResolver.openInputStream(uri)).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          require(output.size() + count <= BackupArchive.MAX_BYTES) { "备份文件过大" }
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
    }
    preview = service.inspect(bytes, password.toCharArray())
  }

  fun upload() = run {
    checkPassword()
    val resolved = withContext(Dispatchers.IO) { requireNotNull(credentials.byId(serverId)) { "请选择可用的 WebDAV 服务器" } }
    saveOptions()
    player.pauseForBackup()
    val bytes = service.create(password.toCharArray())
    remote.upload(resolved.server, resolved.password, directory, name(), bytes)
    message = "WebDAV 备份已上传"
  }

  fun listRemote() = run {
    val resolved = withContext(Dispatchers.IO) { requireNotNull(credentials.byId(serverId)) { "请选择可用的 WebDAV 服务器" } }
    saveOptions()
    remoteFiles = remote.list(resolved.server, resolved.password, directory)
  }

  fun inspectRemote(file: WebDavResource) = run {
    remoteFiles = null
    val resolved = withContext(Dispatchers.IO) { requireNotNull(credentials.byId(serverId)) { "服务器不可用" } }
    val bytes = remote.download(resolved.server, resolved.password, directory, file.url)
    preview = service.inspect(bytes, password.toCharArray())
  }

  fun confirmRestore() = run {
    val contents = requireNotNull(preview)
    player.pauseForBackup()
    service.stage(contents)
    preview = null
    password = ""
    confirmation = ""
    restartRequired = true
    message = "恢复数据已校验并准备完成，请重启应用"
  }

  fun dismissPreview() {
    preview = null
  }
  fun dismissRemote() {
    remoteFiles = null
  }
  fun clearMessage() {
    message = null
  }

  private fun checkPassword() {
    require(password.length >= 8) { "请设置至少 8 位的备份密码" }
    require(password == confirmation) { "两次输入的备份密码不一致" }
  }

  private fun saveOptions() {
    options.edit {
      putString("server", serverId)
      putString("directory", directory)
    }
  }

  private fun run(action: suspend () -> Unit) {
    if (busy || restartRequired) return
    busy = true
    scope.launch {
      try {
        action()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message = e.message ?: "操作失败，请重试"
      } finally {
        busy = false
      }
    }
  }
}
