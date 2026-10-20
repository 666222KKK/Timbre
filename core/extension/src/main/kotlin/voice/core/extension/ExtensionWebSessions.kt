package voice.core.extension

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import voice.core.online.ResolvedStream
import voice.core.webdav.WebDavSecrets
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
internal data class ExtensionWebSession(
  val origin: String,
  val installedAtMillis: Long,
  val cookie: String,
  val userAgent: String,
)

/** Explicitly saved sessions are scoped to one package installation and HTTPS origin. */
@Inject
@SingleIn(AppScope::class)
public class ExtensionWebSessions(
  application: Application,
  private val secrets: WebDavSecrets,
) {
  private val file = File(application.noBackupFilesDir, "extension-web-sessions.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val mediaClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).followSslRedirects(false).build()
  private val encrypted: MutableMap<String, String> by lazy {
    runCatching { json.decodeFromString<Map<String, String>>(file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
  }

  @Synchronized
  public fun save(
    packageId: String,
    installedAtMillis: Long,
    homepage: String,
    cookie: String,
    userAgent: String,
  ) {
    val origin = requireNotNull(origin(homepage)) { "网站验证仅支持有效 HTTPS 地址" }
    require(cookie.isNotBlank() && userAgent.isNotBlank()) { "尚未获取网站会话，请完成验证后再保存" }
    require(cookie.length <= 65536 && userAgent.length <= 4096 && !(cookie + userAgent).any { it == '\r' || it == '\n' })
    val session = ExtensionWebSession(origin, installedAtMillis, cookie, userAgent)
    val value = secrets.encrypt(json.encodeToString(session))
    persist(encrypted + (packageId to value))
    encrypted[packageId] = value
  }

  @Synchronized
  public fun clear(packageId: String) {
    persist(encrypted - packageId)
    encrypted.remove(packageId)
  }

  @Synchronized
  internal fun session(
    packageId: String,
    installedAtMillis: Long,
  ): ExtensionWebSession? {
    val value = encrypted[packageId] ?: return null
    return runCatching {
      json.decodeFromString<ExtensionWebSession>(requireNotNull(secrets.decrypt(value)))
        .takeIf { it.installedAtMillis == installedAtMillis }
    }.getOrNull()
  }

  public fun headers(
    packageId: String,
    installedAtMillis: Long,
    url: String,
  ): Map<String, String> {
    val saved = session(packageId, installedAtMillis) ?: return emptyMap()
    if (origin(url) != saved.origin) return emptyMap()
    return mapOf("Cookie" to saved.cookie, "User-Agent" to saved.userAgent)
  }

  public suspend fun media(
    packageId: String,
    installedAtMillis: Long,
    url: String,
    originalHeaders: Map<String, String>,
  ): ResolvedStream = withContext(Dispatchers.IO) {
    if (headers(packageId, installedAtMillis, url).isEmpty()) return@withContext ResolvedStream(url, originalHeaders)
    val client = mediaClient.newBuilder()
      .addNetworkInterceptor(ExtensionSessionInterceptor(this@ExtensionWebSessions, packageId, installedAtMillis))
      .build()
    val request = Request.Builder().url(url).get()
    originalHeaders.forEach { (name, value) -> request.header(name, value) }
    request.header("Range", "bytes=0-0")
    client.newCall(request.build()).execute().use { response ->
      require(response.code == 200 || response.code == 206) { "音频请求失败（${response.code}），请更新网站验证会话" }
      require(!response.body.contentType().toString().contains("text/html", ignoreCase = true)) { "音频需要网站验证，请更新会话" }
      val finalUrl = response.request.url.toString()
      val outgoing = streamHeaders(packageId, installedAtMillis, finalUrl, originalHeaders, response.request.header("User-Agent"))
      ResolvedStream(finalUrl, outgoing)
    }
  }

  internal fun streamHeaders(
    packageId: String,
    installedAtMillis: Long,
    finalUrl: String,
    originalHeaders: Map<String, String>,
    userAgent: String?,
  ): Map<String, String> {
    val outgoing = originalHeaders.filterKeys { !it.equals("Cookie", ignoreCase = true) && !it.equals("User-Agent", ignoreCase = true) }
    val agent = userAgent?.let { mapOf("User-Agent" to it) }.orEmpty()
    return outgoing + agent + headers(packageId, installedAtMillis, finalUrl)
  }

  private fun persist(values: Map<String, String>) {
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.writeText(json.encodeToString(values))
    check(temp.renameTo(file)) { "无法保存网站会话" }
  }

  public companion object {
    public fun origin(value: String): String? {
      val url = value.toHttpUrlOrNull() ?: return null
      if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
      return "${url.scheme}://${url.host}:${url.port}"
    }
  }
}

internal class ExtensionSessionInterceptor(
  private val sessions: ExtensionWebSessions,
  private val packageId: String,
  private val installedAtMillis: Long,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val original = chain.request()
    val saved = sessions.session(packageId, installedAtMillis) ?: return chain.proceed(original)
    val builder = original.newBuilder()
    if (ExtensionWebSessions.origin(original.url.toString()) == saved.origin) {
      builder.header("Cookie", saved.cookie).header("User-Agent", saved.userAgent)
    } else if (original.header("Cookie") == saved.cookie) {
      // A redirect can inherit a manually added Cookie header. Never forward
      // the saved website session to a CDN or a different origin.
      builder.removeHeader("Cookie")
    }
    return chain.proceed(builder.build())
  }
}
