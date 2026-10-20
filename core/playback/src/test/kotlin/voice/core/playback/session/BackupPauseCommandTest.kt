package voice.core.playback.session

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import voice.core.playback.player.VoicePlayer
import voice.core.playback.playstate.PositionUpdater
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class BackupPauseCommandTest {
  @Test
  fun backupAcknowledgementWaitsForPositionPersistence() = runTest {
    val player = mockk<VoicePlayer> { every { pause() } just Runs }
    val updater = mockk<PositionUpdater> { coEvery { flushPositionNow(any()) } just Runs }
    val callback = LibrarySessionCallback(
      mediaItemProvider = mockk(),
      scope = this,
      player = player,
      bookSearchParser = mockk(),
      bookSearchHandler = mockk(),
      currentBookStoreId = mockk(),
      contentRepo = mockk(),
      onlinePlaybackCatalog = mockk(),
      positionUpdater = updater,
    )
    val args = Bundle().apply {
      putString(CustomCommand.CUSTOM_COMMAND_EXTRA, Json.encodeToString(CustomCommand.serializer(), CustomCommand.PauseAndFlush))
    }
    val result = callback.onCustomCommand(
      mockk(),
      mockk(),
      SessionCommand(CustomCommand.CUSTOM_COMMAND_ACTION, Bundle.EMPTY),
      args,
    )
    advanceUntilIdle()
    assertEquals(SessionResult.RESULT_SUCCESS, result.get().resultCode)
    coVerifyOrder {
      player.pause()
      updater.flushPositionNow(true)
    }
  }
}
