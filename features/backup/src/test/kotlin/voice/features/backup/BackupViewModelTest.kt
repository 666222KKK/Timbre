package voice.features.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import voice.core.backup.BackupConnectionStore
import voice.core.backup.BackupService
import voice.core.playback.PlayerController
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
  @Test
  fun localBackupSavesImmediatelyWithoutAskingForAFilePassword() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val store = mockk<BackupConnectionStore> { every { read() } returns null }
      val service = mockk<BackupService> { coEvery { create() } returns "backup-data".toByteArray() }
      val player = mockk<PlayerController> { coEvery { pauseForBackup() } returns Unit }
      val model = BackupViewModel(app, service, store, mockk(), player)
      val target = File(app.cacheDir, "passwordless-backup.tbk")
      model.createLocal(Uri.fromFile(target))
      withContext(Dispatchers.Default) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (model.busy && System.nanoTime() < deadline) Thread.sleep(5)
      }
      assertFalse(model.busy)
      assertEquals("本地备份已保存", model.message)
      assertEquals("backup-data", target.readText())
      coVerify(exactly = 1) { player.pauseForBackup() }
    } finally {
      Dispatchers.resetMain()
    }
  }
}
