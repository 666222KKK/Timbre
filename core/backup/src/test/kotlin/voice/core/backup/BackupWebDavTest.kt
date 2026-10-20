package voice.core.backup

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import voice.core.webdav.WebDavClient
import voice.core.webdav.WebDavServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class BackupWebDavTest {
  @Test
  fun uploadCreatesDirectoriesAndUsesAuthenticatedPut() = runTest {
    MockWebServer().use { server ->
      server.start()
      repeat(2) { server.enqueue(MockResponse.Builder().code(201).build()) }
      server.enqueue(MockResponse.Builder().code(201).build())
      val config = WebDavServer("backup", "Backup", server.url("/dav/").toString(), "u", "encrypted")
      BackupWebDav(WebDavClient()).upload(config, "p", "Timbre/backup", "test.tbk", byteArrayOf(1, 2))
      assertEquals("MKCOL", server.takeRequest().method)
      assertEquals("MKCOL", server.takeRequest().method)
      val request = server.takeRequest()
      assertEquals("PUT", request.method)
      assertEquals("/dav/Timbre/backup/test.tbk", request.url.encodedPath)
      assertEquals("Basic dTpw", request.headers["Authorization"])
    }
  }

  @Test
  fun authenticatedDownloadReturnsTheExactBackupBytes() = runTest {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse.Builder().body("encrypted-backup").build())
      val config = WebDavServer("backup", "Backup", server.url("/dav").toString(), "u", "encrypted")
      val bytes = BackupWebDav(WebDavClient()).download(config, "p", "Timbre/backup", server.url("/dav/Timbre/backup/test.tbk").toString())
      assertEquals("encrypted-backup", bytes.decodeToString())
      val request = server.takeRequest()
      assertEquals("GET", request.method)
      assertEquals("Basic dTpw", request.headers["Authorization"])
    }
  }

  @Test
  fun downloadCannotSendCredentialsToAnotherOriginOrFolder() = runTest {
    val config = WebDavServer("backup", "Backup", "https://example.com/dav", "u", "encrypted")
    val client = BackupWebDav(WebDavClient())
    assertFailsWith<IllegalArgumentException> { client.download(config, "p", "Timbre/backup", "https://evil.example/test.tbk") }
    assertFailsWith<IllegalArgumentException> { client.download(config, "p", "Timbre/backup", "https://example.com/other/test.tbk") }
  }
}
