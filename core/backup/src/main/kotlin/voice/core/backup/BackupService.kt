package voice.core.backup

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.room.RoomDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import voice.core.data.repo.internals.transaction
import voice.core.webdav.WebDavSecrets
import voice.core.webdav.WebDavServer
import voice.core.webdav.WebDavServersStore
import java.io.File

@Inject
@SingleIn(AppScope::class)
class BackupService(
  private val application: Application,
  private val database: RoomDatabase,
  @WebDavServersStore private val servers: DataStore<List<WebDavServer>>,
  private val secrets: WebDavSecrets,
  private val connectionStore: BackupConnectionStore,
) {
  private val mutex = Mutex()

  suspend fun create(): ByteArray = mutex.withLock {
    withContext(Dispatchers.IO) {
      val files = linkedMapOf<String, ByteArray>()
      var total = 0L
      BackupArchive.directories.forEach { name ->
        val directory = File(application.filesDir, name)
        directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
          require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()))
          total += file.length()
          require(total <= BackupArchive.MAX_BYTES) { "数据过大，无法生成备份" }
          files["files/$name/${file.relativeTo(directory).invariantSeparatorsPath}"] = file.readBytes()
        }
      }
      val passwords = servers.data.first().associate { server ->
        server.id to requireNotNull(secrets.decrypt(server.encryptedPassword)) { "请先重新填写 WebDAV 密码" }
      }
      files["webdav-secrets.json"] = Json.encodeToString(
        MapSerializer(String.serializer(), String.serializer()),
        passwords,
      ).toByteArray()
      connectionStore.read()?.let { connection ->
        files["backup-webdav-secret.json"] = Json.encodeToString(connectionStore.password(connection)).toByteArray()
      }
      var version = 0
      database.transaction {
        val db = database.openHelper.writableDatabase
        version = db.version
        val source = File(requireNotNull(db.path))
        listOf(source, File(source.path + "-wal")).filter { it.isFile }.forEach { file ->
          total += file.length()
          require(total <= BackupArchive.MAX_BYTES) { "数据库过大，无法生成备份" }
          files["databases/${file.name}"] = file.readBytes()
        }
      }
      BackupArchive.encode(
        BackupContents(
          BackupManifest(createdAt = System.currentTimeMillis(), filesPath = application.filesDir.path, databaseVersion = version),
          files,
        ),
      )
    }
  }

  suspend fun inspect(
    bytes: ByteArray,
    password: CharArray = charArrayOf(),
  ): BackupContents = withContext(Dispatchers.IO) {
    BackupArchive.decode(bytes, password).also {
      require(it.manifest.databaseVersion == database.openHelper.writableDatabase.version) { "备份数据库版本与当前应用不兼容" }
    }
  }

  suspend fun stage(contents: BackupContents) = mutex.withLock {
    withContext(Dispatchers.IO) {
      PendingBackupRestore.stage(application, contents)
    }
  }
}
