package voice.features.cover.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
internal class CoverApiTest {
  @Test
  fun searchUsesTheTokenAndDecodesImageResults() = runTest {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse.Builder().body("vqd=123-456&source=images").build())
      server.enqueue(
        MockResponse.Builder().body(
          """{"next":null,"results":[{"width":600,"height":900,"image":"https://example.com/cover.jpg","thumbnail":"https://example.com/thumb.jpg","extra":"ignored"}]}""",
        ).build(),
      )
      val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val localUrl = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
        chain.proceed(request.newBuilder().url(localUrl).build())
      }.build()
      val api = CoverApi(object : CoverModule {}.internalApi(client))
      val query = "三体 & books"
      val token = assertNotNull(api.token(query))
      assertEquals("123-456", token)
      val result = api.search(query, token)
      assertEquals("https://example.com/cover.jpg", result.results.single().image)
      assertEquals(900, result.results.single().height)
      assertEquals(query, server.takeRequest().url.queryParameter("q"))
      val search = server.takeRequest()
      assertEquals("/i.js", search.url.encodedPath)
      assertEquals(query, search.url.queryParameter("q"))
      assertEquals(token, search.url.queryParameter("vqd"))
    }
  }
}
