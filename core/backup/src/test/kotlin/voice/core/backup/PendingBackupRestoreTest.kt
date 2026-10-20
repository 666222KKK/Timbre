package voice.core.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import voice.core.webdav.WebDavSecrets
import voice.core.webdav.WebDavServer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PendingBackupRestoreTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  private fun database(): ByteArray {
    val file = File(app.cacheDir, "backup-test.db")
    file.delete()
    SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
      db.version = 61
      db.execSQL("CREATE TABLE content2 (id TEXT PRIMARY KEY, cover TEXT)")
      db.execSQL("INSERT INTO content2 VALUES ('book', '/old/files/bookCovers/a.png')")
    }
    return file.readBytes()
  }

  @Test
  fun restoreReplacesDataOnlyAtStartupAndRemapsInternalPaths() {
    val setting = File(app.filesDir, "datastore/themeMode").also {
      it.parentFile!!.mkdirs()
      it.writeText("\"Light\"")
    }
    val contents = BackupContents(
      BackupManifest(createdAt = 1, filesPath = "/old/files", databaseVersion = 61),
      mapOf("files/datastore/themeMode" to "\"Dark\"".toByteArray(), "databases/autoBookDB" to database()),
    )
    PendingBackupRestore.stage(app, contents)
    assertEquals("\"Light\"", setting.readText())
    PendingBackupRestore.applyOnStartup(app)
    assertEquals("\"Dark\"", setting.readText())
    SQLiteDatabase.openDatabase(app.getDatabasePath("autoBookDB").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.rawQuery("SELECT cover FROM content2", null).use { cursor ->
        assertEquals(true, cursor.moveToFirst())
        assertEquals("${app.filesDir.path}/bookCovers/a.png", cursor.getString(0))
      }
    }
  }

  @Test
  fun restoredWebDavCredentialsUseTheNewDeviceKey() {
    val contents = BackupContents(
      BackupManifest(createdAt = 1, filesPath = "/old/files", databaseVersion = 61),
      mapOf(
        "databases/autoBookDB" to database(),
        "files/datastore/webDavServers" to """[{"id":"server","encryptedPassword":"old-cipher"}]""".toByteArray(),
        "webdav-secrets.json" to """{"server":"secret"}""".toByteArray(),
      ),
    )
    val secrets = object : WebDavSecrets {
      override fun encrypt(plain: String): String = "new-device:$plain"
      override fun decrypt(encrypted: String): String? = null
    }
    PendingBackupRestore.stage(app, contents, secrets)
    assertFalse(File(app.noBackupFilesDir, "timbre-restore-pending/webdav-secrets.json").exists())
    PendingBackupRestore.applyOnStartup(app)
    val restored = File(app.filesDir, "datastore/webDavServers").readText()
    assertTrue(restored.contains("new-device:secret"))
    assertFalse(restored.contains("old-cipher"))
  }

  @Test
  fun backupAccountRemainsSeparateAndPortableAlongsideSourcesListsProgressAndSettings() {
    val connection =
      BackupConnection(
        WebDavServer("timbre-backup", "备份", "https://dav.jianguoyun.com/dav/", "backup@example.com", "old-backup-key"),
        "Timbre/backup",
      )
    val data = mapOf(
      "databases/autoBookDB" to database(),
      "files/datastore/backupWebDavConnection" to Json.encodeToString(connection).toByteArray(),
      "backup-webdav-secret.json" to Json.encodeToString("backup-password").toByteArray(),
      "files/datastore/webDavServers" to """[{"id":"audio","encryptedPassword":"old-audio-key"}]""".toByteArray(),
      "webdav-secrets.json" to """{"audio":"audio-password"}""".toByteArray(),
      "files/datastore/currentBook" to "123".toByteArray(),
      "files/datastore/themeMode" to "\"Dark\"".toByteArray(),
      "files/online_chapter_lists/book.json" to "[{\"id\":\"chapter-2\"}]".toByteArray(),
      "files/extensions/moon/script.js" to "export default {}".toByteArray(),
    )
    val contents = BackupArchive.decode(
      BackupArchive.encode(
        BackupContents(
          BackupManifest(createdAt = 1, filesPath = "/old/files", databaseVersion = 61),
          data,
        ),
        "archive-password".toCharArray(),
      ),
      "archive-password".toCharArray(),
    )
    val secrets = object : WebDavSecrets {
      override fun encrypt(plain: String): String = "new-device:$plain"
      override fun decrypt(encrypted: String): String? = encrypted.removePrefix("new-device:")
    }
    PendingBackupRestore.stage(app, contents, secrets)
    PendingBackupRestore.applyOnStartup(app)
    val restored = BackupConnectionStore(app, secrets).read()!!
    assertEquals("backup@example.com", restored.server.username)
    assertEquals("backup-password", BackupConnectionStore(app, secrets).password(restored))
    assertTrue(File(app.filesDir, "datastore/webDavServers").readText().contains("new-device:audio-password"))
    listOf("datastore/currentBook", "datastore/themeMode", "online_chapter_lists/book.json", "extensions/moon/script.js").forEach {
      assertEquals(data.getValue("files/$it").decodeToString(), File(app.filesDir, it).readText())
    }
    assertFalse(File(app.noBackupFilesDir, "timbre-restore-pending/backup-webdav-secret.json").exists())
  }

  @Test
  fun committedWalEntriesAreRestoredWithTheDatabase() {
    val file = File(app.cacheDir, "wal-backup.db")
    val contents = SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
      assertTrue(db.enableWriteAheadLogging())
      db.version = 61
      db.execSQL("CREATE TABLE content2 (id TEXT PRIMARY KEY, cover TEXT)")
      db.execSQL("INSERT INTO content2 VALUES ('wal-book', '/old/files/bookCovers/wal.png')")
      BackupContents(
        BackupManifest(createdAt = 1, filesPath = "/old/files", databaseVersion = 61),
        mapOf("databases/autoBookDB" to file.readBytes(), "databases/autoBookDB-wal" to File(file.path + "-wal").readBytes()),
      )
    }
    PendingBackupRestore.stage(app, contents)
    PendingBackupRestore.applyOnStartup(app)
    SQLiteDatabase.openDatabase(app.getDatabasePath("autoBookDB").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.rawQuery("SELECT cover FROM content2 WHERE id = 'wal-book'", null).use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("${app.filesDir.path}/bookCovers/wal.png", cursor.getString(0))
      }
    }
  }

  @Test
  fun invalidDatabaseCannotTouchExistingSettings() {
    val setting = File(app.filesDir, "datastore/themeMode").also {
      it.parentFile!!.mkdirs()
      it.writeText("\"Light\"")
    }
    val contents = BackupContents(
      BackupManifest(createdAt = 1, filesPath = "/old/files", databaseVersion = 61),
      mapOf("files/datastore/themeMode" to "\"Dark\"".toByteArray(), "databases/autoBookDB" to byteArrayOf(1, 2, 3)),
    )
    assertFails { PendingBackupRestore.stage(app, contents) }
    assertEquals("\"Light\"", setting.readText())
  }

  @Test
  fun interruptedJournalPreparationLeavesCurrentDataUntouched() {
    val setting = File(app.filesDir, "datastore/themeMode").also {
      it.parentFile!!.mkdirs()
      it.writeText("original")
    }
    val rollback = File(app.noBackupFilesDir, "timbre-restore-rollback").also { it.mkdirs() }
    File(rollback, "journal-new.json").writeText("[{broken")
    PendingBackupRestore.applyOnStartup(app)
    assertEquals("original", setting.readText())
  }

  @Test
  fun interruptedRestoreRecoversOriginalDataBeforeOpeningStores() {
    val target = File(app.filesDir, "datastore/themeMode").also {
      it.parentFile!!.mkdirs()
      it.writeText("new")
    }
    val rollback = File(app.noBackupFilesDir, "timbre-restore-rollback")
    File(rollback, "files/datastore/themeMode").also {
      it.parentFile!!.mkdirs()
      it.writeText("original")
    }
    File(rollback, "journal.json").writeText("""[{"path":"files/datastore","existed":true}]""")
    PendingBackupRestore.applyOnStartup(app)
    assertEquals("original", target.readText())
  }
}
