package voice.core.extension

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.runner.RunWith
import voice.core.webdav.WebDavSecrets
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ExtensionWebSessionsTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()
  private val secrets = object : WebDavSecrets {
    override fun encrypt(plain: String): String = Base64.getEncoder().encodeToString(plain.toByteArray())
    override fun decrypt(encrypted: String): String = String(Base64.getDecoder().decode(encrypted))
  }
  private val store = ExtensionWebSessions(app, secrets)

  @Test
  fun sessionSurvivesRestartWithoutWritingPlaintextCookies() {
    store.save("moon", 1, "https://asmrmoon.com/", "session=private-token", "Browser/1")
    val reopened = ExtensionWebSessions(app, secrets)
    assertEquals("session=private-token", reopened.headers("moon", 1, "https://asmrmoon.com/api/fs/list")["Cookie"])
    assertEquals("Browser/1", reopened.headers("moon", 1, "https://asmrmoon.com/p/file")["User-Agent"])
    assertFalse(File(app.noBackupFilesDir, "extension-web-sessions.json").readText().contains("private-token"))
  }

  @Test
  fun sessionsAreScopedToThePackageInstallationAndExactSecureOrigin() {
    store.save("moon", 1, "https://asmrmoon.com/", "session=secret", "Browser/1")
    listOf(
      "https://cdn.asmrmoon.com/a",
      "https://asmrmoon.com.evil.test/a",
      "http://asmrmoon.com/a",
      "https://asmrmoon.com:8443/a",
    ).forEach {
      assertTrue(store.headers("moon", 1, it).isEmpty())
    }
    assertTrue(store.headers("another-package", 1, "https://asmrmoon.com/").isEmpty())
    assertTrue(store.headers("moon", 2, "https://asmrmoon.com/").isEmpty())
  }

  @Test
  fun invalidOrEmptySessionsAreNeverSaved() {
    assertNull(ExtensionWebSessions.origin("https://user:password@asmrmoon.com/"))
    assertFailsWith<IllegalArgumentException> { store.save("moon", 1, "http://asmrmoon.com/", "secret", "ua") }
    assertFailsWith<IllegalArgumentException> { store.save("moon", 1, "https://asmrmoon.com/", "", "ua") }
    assertFailsWith<IllegalArgumentException> { store.save("moon", 1, "https://asmrmoon.com/", "secret\r\nInjected: yes", "ua") }
    assertTrue(store.headers("moon", 1, "https://asmrmoon.com/").isEmpty())
  }

  @Test
  fun clearingSessionRevokesItForSubsequentRequests() {
    store.save("moon", 1, "https://asmrmoon.com/", "session=secret", "Browser/1")
    store.clear("moon")
    assertTrue(ExtensionWebSessions(app, secrets).headers("moon", 1, "https://asmrmoon.com/").isEmpty())
  }

  @Test
  fun networkInterceptorAddsSessionOnlyOnTheGrantedOriginAndRemovesItOnRedirects() {
    store.save("moon", 1, "https://asmrmoon.com/", "session=secret", "Browser/1")
    fun intercepted(request: Request): Request {
      var sent: Request? = null
      val chain = mockk<Interceptor.Chain>()
      every { chain.request() } returns request
      every { chain.proceed(any()) } answers {
        sent = firstArg()
        Response.Builder().request(
          requireNotNull(sent),
        ).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()
      }
      ExtensionSessionInterceptor(store, "moon", 1).intercept(chain).close()
      return requireNotNull(sent)
    }
    val first = intercepted(Request.Builder().url("https://asmrmoon.com/api/fs/list").build())
    assertEquals("session=secret", first.header("Cookie"))
    assertEquals("Browser/1", first.header("User-Agent"))
    val redirected = intercepted(first.newBuilder().url("https://cdn.example.com/file.mp3").build())
    assertNull(redirected.header("Cookie"))
  }
}
