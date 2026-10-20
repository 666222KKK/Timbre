package voice.core.backup

import android.app.Application
import android.util.AtomicFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import voice.core.webdav.WebDavSecrets
import voice.core.webdav.WebDavServer
import java.io.File

@Serializable
data class BackupConnection(
  val server: WebDavServer,
  val directory: String,
)

@Inject
@SingleIn(AppScope::class)
class BackupConnectionStore(
  private val application: Application,
  private val secrets: WebDavSecrets,
) {
  fun read(): BackupConnection? {
    val file = AtomicFile(File(application.filesDir, "datastore/backupWebDavConnection"))
    return try {
      Json.decodeFromString<BackupConnection>(file.openRead().bufferedReader().use { it.readText() })
    } catch (_: java.io.FileNotFoundException) {
      null
    }
  }

  fun password(connection: BackupConnection): String =
    requireNotNull(secrets.decrypt(connection.server.encryptedPassword)) { "请重新填写备份 WebDAV 的应用密码" }

  fun save(
    url: String,
    username: String,
    password: String,
    directory: String,
  ): BackupConnection {
    val connection = BackupConnection(
      WebDavServer("timbre-backup", "备份专用 WebDAV", url, username, secrets.encrypt(password)),
      directory,
    )
    val file = AtomicFile(File(application.filesDir, "datastore/backupWebDavConnection"))
    val stream = file.startWrite()
    try {
      stream.write(Json.encodeToString(connection).toByteArray())
      file.finishWrite(stream)
    } catch (e: Exception) {
      file.failWrite(stream)
      throw e
    }
    return connection
  }
}
