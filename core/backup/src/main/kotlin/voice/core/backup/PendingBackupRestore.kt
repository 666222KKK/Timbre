package voice.core.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import voice.core.webdav.KeystoreWebDavSecrets
import voice.core.webdav.WebDavSecrets
import java.io.File

object PendingBackupRestore {
  private const val PENDING = "timbre-restore-pending"
  private const val ROLLBACK = "timbre-restore-rollback"

  @Serializable
  private data class Move(
    val path: String,
    val existed: Boolean,
  )

  fun stage(
    application: Application,
    contents: BackupContents,
    secrets: WebDavSecrets = KeystoreWebDavSecrets(),
  ) {
    val pending = File(application.noBackupFilesDir, PENDING)
    val temp = File(application.noBackupFilesDir, "$PENDING-new")
    temp.deleteRecursively()
    check(temp.mkdirs())
    try {
      contents.files.forEach { (name, bytes) ->
        require(BackupArchive.allowed(name))
        File(temp, name).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
      }
      BackupArchive.directories.forEach { File(temp, "files/$it").mkdirs() }
      val passwords = contents.files["webdav-secrets.json"]?.let {
        Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it.decodeToString())
      }.orEmpty()
      val serverFile = File(temp, "files/datastore/webDavServers")
      if (serverFile.exists()) {
        val servers = Json.parseToJsonElement(serverFile.readText()).jsonArray.map { element ->
          val server = element.jsonObject
          val id = server.getValue("id").jsonPrimitive.content
          val password = requireNotNull(passwords[id]) { "备份缺少 WebDAV 凭据" }
          JsonObject(server + ("encryptedPassword" to JsonPrimitive(secrets.encrypt(password))))
        }
        serverFile.writeText(JsonArray(servers).toString())
      }
      File(temp, "files/datastore").listFiles().orEmpty().filter { it.isFile }.forEach { file ->
        val data = Json.parseToJsonElement(file.readText())
        file.writeText(remap(data, contents.manifest.filesPath, application.filesDir.path).toString())
      }
      File(temp, "webdav-secrets.json").delete()
      val dbFile = File(temp, "databases/autoBookDB")
      SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
          check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "备份数据库校验失败" }
        }
        require(db.version == contents.manifest.databaseVersion)
        db.execSQL(
          "UPDATE content2 SET cover = replace(cover, ?, ?) WHERE cover IS NOT NULL",
          arrayOf(contents.manifest.filesPath, application.filesDir.path),
        )
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
          check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "备份数据库日志合并失败" }
        }
      }
      File(temp, "databases/autoBookDB-shm").delete()
      File(temp, "ready").writeText("1")
      pending.deleteRecursively()
      check(temp.renameTo(pending)) { "无法准备恢复文件" }
    } catch (e: Exception) {
      temp.deleteRecursively()
      throw e
    }
  }

  fun applyOnStartup(application: Application) {
    val pending = File(application.noBackupFilesDir, PENDING)
    val rollback = File(application.noBackupFilesDir, ROLLBACK)
    if (rollback.exists()) {
      if (!File(rollback, "completed").exists()) recover(application, rollback)
      rollback.deleteRecursively()
      pending.deleteRecursively()
      return
    }
    if (!File(pending, "ready").isFile) return
    val roots = BackupArchive.directories.map { "files/$it" } +
      listOf("databases/autoBookDB", "databases/autoBookDB-wal", "databases/autoBookDB-shm")
    val moves = roots.map { Move(it, target(application, it).exists()) }
    try {
      check(rollback.mkdirs())
      val journal = File(rollback, "journal-new.json")
      journal.writeText(Json.encodeToString(moves))
      check(journal.renameTo(File(rollback, "journal.json")))
      moves.forEach { move ->
        val destination = target(application, move.path)
        val old = File(rollback, move.path)
        if (destination.exists()) {
          old.parentFile?.mkdirs()
          check(destination.renameTo(old)) { "无法保存恢复前的数据" }
        }
        val replacement = File(pending, move.path)
        if (replacement.exists()) {
          destination.parentFile?.mkdirs()
          check(replacement.renameTo(destination)) { "无法应用恢复数据" }
        }
      }
      File(rollback, "completed").writeText("1")
      pending.deleteRecursively()
      rollback.deleteRecursively()
    } catch (e: Exception) {
      recover(application, rollback)
      pending.deleteRecursively()
      rollback.deleteRecursively()
      File(application.noBackupFilesDir, "restore-error.txt").writeText(e.message.orEmpty())
    }
  }

  private fun recover(
    application: Application,
    rollback: File,
  ) {
    val journal = File(rollback, "journal.json")
    if (!journal.exists()) return
    Json.decodeFromString<List<Move>>(journal.readText()).reversed().forEach { move ->
      val old = File(rollback, move.path)
      val destination = target(application, move.path)
      if (old.exists()) {
        destination.deleteRecursively()
        destination.parentFile?.mkdirs()
        check(old.renameTo(destination)) { "恢复回滚失败，请保留应用数据" }
      } else if (!move.existed) {
        destination.deleteRecursively()
      }
    }
  }

  private fun target(
    application: Application,
    relative: String,
  ): File {
    require(
      relative in BackupArchive.directories.map { "files/$it" } ||
        relative in listOf("databases/autoBookDB", "databases/autoBookDB-wal", "databases/autoBookDB-shm"),
    )
    return if (relative.startsWith("files/")) {
      File(application.filesDir, relative.removePrefix("files/"))
    } else {
      application.getDatabasePath(relative.removePrefix("databases/"))
    }
  }

  private fun remap(
    element: JsonElement,
    old: String,
    new: String,
  ): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { remap(it.value, old, new) })
    is JsonArray -> JsonArray(element.map { remap(it, old, new) })
    is JsonPrimitive -> if (element.isString) JsonPrimitive(element.content.replace(old, new)) else element
  }
}
