package voice.core.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

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
