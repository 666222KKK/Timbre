package voice.features.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import voice.core.backup.BackupConnectionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
  private fun model(): BackupViewModel {
    val store = mockk<BackupConnectionStore> { every { read() } returns null }
    return BackupViewModel(ApplicationProvider.getApplicationContext<Application>(), mockk(), store, mockk(), mockk())
  }

  @Test
  fun emptyOrMismatchedArchivePasswordExplainsWhyBackupCannotStart() {
    val model = model()
    assertFalse(model.validateCreate())
    assertEquals("请设置至少 8 位的备份密码", model.message)
    model.password = "password123"
    model.confirmation = "different"
    assertFalse(model.validateCreate())
    assertEquals("两次输入的备份密码不一致", model.message)
    model.confirmation = model.password
    assertTrue(model.validateCreate())
    model.password = ""
    assertFalse(model.validateRestore())
    assertEquals("请先输入备份文件的密码", model.message)
  }

  @Test
  fun uploadWithEmptyPasswordReportsValidationErrorAndReenablesActions() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val model = model()
      model.upload()
      assertTrue(model.busy)
      runCurrent()
      assertFalse(model.busy)
      assertEquals("请设置至少 8 位的备份密码", model.message)
      assertEquals("https://dav.jianguoyun.com/dav/", model.serverUrl)
      assertEquals("", model.username)
    } finally {
      Dispatchers.resetMain()
    }
  }
}
