package voice.features.backup

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import voice.core.backup.BackupArchive
import voice.core.backup.BackupConnection
import voice.core.backup.BackupConnectionStore
import voice.core.backup.BackupContents
import voice.core.backup.BackupService
import voice.core.backup.BackupWebDav
import voice.core.playback.PlayerController
import voice.core.webdav.WebDavResource
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Inject
class BackupViewModel(
  private val application: Application,
  private val service: BackupService,
  private val connectionStore: BackupConnectionStore,
  private val remote: BackupWebDav,
  private val player: PlayerController,
) {
  private val scope = MainScope()
  private val saved = runCatching { connectionStore.read() }.getOrNull()
  var password by mutableStateOf("")
  var confirmation by mutableStateOf("")
  var serverUrl by mutableStateOf(saved?.server?.baseUrl ?: "https://dav.jianguoyun.com/dav/")
  var username by mutableStateOf(saved?.server?.username.orEmpty())
  var applicationPassword by mutableStateOf(saved?.let { runCatching { connectionStore.password(it) }.getOrNull() }.orEmpty())
  var directory by mutableStateOf(saved?.directory ?: "Timbre/backup")
  var remoteStatus by mutableStateOf<String?>(null)
    private set
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
    val connection = saveConnection()
    player.pauseForBackup()
    val bytes = service.create(password.toCharArray())
    val filename = name()
    remote.upload(connection.server, applicationPassword, connection.directory, filename, bytes)
    remoteStatus = "备份成功并已核对：${connection.directory}/$filename"
    message = "WebDAV 备份成功，已核对云端文件"
  }

  fun listRemote() = run {
    val connection = saveConnection()
    remoteFiles = remote.list(connection.server, applicationPassword, connection.directory)
  }

  fun inspectRemote(file: WebDavResource) = run {
    require(password.isNotEmpty()) { "请先输入该备份文件的密码，再选择要恢复的文件" }
    val connection = saveConnection()
    val bytes = remote.download(connection.server, applicationPassword, connection.directory, file.url)
    preview = service.inspect(bytes, password.toCharArray())
    remoteFiles = null
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

  fun validateCreate(): Boolean = try {
    checkPassword()
    true
  } catch (e: IllegalArgumentException) {
    message = e.message
    false
  }

  fun validateRestore(): Boolean {
    if (password.isNotEmpty()) return true
    message = "请先输入备份文件的密码"
    return false
  }

  fun testConnection() = run {
    remoteStatus = null
    val connection = saveConnection()
    remote.testConnection(connection.server, applicationPassword, connection.directory)
    remoteStatus = "备份连接正常：写入、读取、删除均通过。点击“备份到 WebDAV”创建备份。"
    message = "备份目录可写，测试文件已清理"
  }

  private suspend fun saveConnection(): BackupConnection = withContext(Dispatchers.IO) {
    val url = requireNotNull(serverUrl.trim().toHttpUrlOrNull()) { "请输入有效的备份服务器地址" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "服务器地址不能包含账号、查询参数或片段" }
    require(username.isNotBlank()) { "请填写备份 WebDAV 账号" }
    require(applicationPassword.isNotEmpty()) { "请填写备份 WebDAV 应用密码（坚果云需使用第三方应用密码）" }
    require(directory.isNotBlank()) { "请填写备份目录" }
    connectionStore.save(url.toString(), username.trim(), applicationPassword, directory.trim())
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
