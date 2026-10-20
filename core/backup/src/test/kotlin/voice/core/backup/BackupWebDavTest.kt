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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BackupWebDavTest {
  @Test
  fun uploadCreatesDirectoriesAndUsesAuthenticatedPut() = runTest {
    MockWebServer().use { server ->
      server.start()
      repeat(2) { server.enqueue(MockResponse.Builder().code(201).build()) }
      server.enqueue(MockResponse.Builder().code(201).build())
      server.enqueue(MockResponse.Builder().body(okio.Buffer().write(byteArrayOf(1, 2))).build())
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
  fun backupConnectionTestsWriteReadAndDeleteWithoutLeavingAnArchive() = runTest {
    MockWebServer().use { server ->
      server.start()
      server.dispatcher = object : mockwebserver3.Dispatcher() {
        private var content = ""
        override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse = when (request.method) {
          "MKCOL" -> MockResponse.Builder().code(405).build()
          "PUT" -> {
            content = request.body!!.utf8()
            MockResponse.Builder().code(201).build()
          }
          "GET" -> MockResponse.Builder().body(content).build()
          "DELETE" -> MockResponse.Builder().code(204).build()
          else -> MockResponse.Builder().code(500).build()
        }
      }
      val config = WebDavServer("backup", "Backup", server.url("/dav/").toString(), "u", "encrypted")
      BackupWebDav(WebDavClient()).testConnection(config, "p", "Timbre/backup")
      assertEquals("MKCOL", server.takeRequest().method)
      assertEquals("MKCOL", server.takeRequest().method)
      val put = server.takeRequest()
      assertTrue(put.url.encodedPath.startsWith("/dav/Timbre/backup/timbre-connection-test-"))
      val get = server.takeRequest()
      val delete = server.takeRequest()
      assertEquals("GET", get.method)
      assertEquals("DELETE", delete.method)
      assertEquals(put.url, get.url)
      assertEquals(put.url, delete.url)
      assertEquals("Basic dTpw", delete.headers["Authorization"])
    }
  }

  @Test
  fun successfulPutWithWrongCloudContentsDoesNotReportSuccess() = runTest {
    MockWebServer().use { server ->
      server.start()
      repeat(3) { server.enqueue(MockResponse.Builder().code(201).build()) }
      server.enqueue(MockResponse.Builder().body("truncated").build())
      val config = WebDavServer("backup", "Backup", server.url("/dav/").toString(), "u", "encrypted")
      val error = assertFailsWith<IllegalArgumentException> {
        BackupWebDav(WebDavClient()).upload(config, "p", "Timbre/backup", "test.tbk", "complete".toByteArray())
      }
      assertTrue(error.message!!.contains("校验失败"))
    }
  }

  @Test
  fun readOnlyAccountCannotPassBackupConnectionTest() = runTest {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse.Builder().code(405).build())
      server.enqueue(MockResponse.Builder().code(405).build())
      server.enqueue(MockResponse.Builder().code(403).build())
      server.enqueue(MockResponse.Builder().code(403).build())
      val config = WebDavServer("backup", "Backup", server.url("/dav/").toString(), "u", "encrypted")
      val error = assertFailsWith<IllegalArgumentException> {
        BackupWebDav(WebDavClient()).testConnection(config, "p", "Timbre/backup")
      }
      assertTrue(error.message!!.contains("上传备份失败：HTTP 403"))
      assertEquals(1, error.suppressed.size)
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
