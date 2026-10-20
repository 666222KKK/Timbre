package voice.core.backup

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import voice.core.webdav.WebDavClient
import voice.core.webdav.WebDavResource
import voice.core.webdav.WebDavServer
import java.io.ByteArrayOutputStream

@Inject
class BackupWebDav(private val client: WebDavClient) {
  suspend fun list(
    server: WebDavServer,
    password: String,
    directory: String,
  ): List<WebDavResource> {
    return client.list(server, password, directoryUrl(server, directory).toString())
      .filter { !it.isDirectory && it.url.toHttpUrl().pathSegments.last().endsWith(".tbk") }
      .sortedByDescending { it.url }
  }

  suspend fun upload(
    server: WebDavServer,
    password: String,
    directory: String,
    name: String,
    bytes: ByteArray,
  ) {
    require(bytes.size <= BackupArchive.MAX_BYTES && !name.contains('/') && name.endsWith(".tbk"))
    withContext(Dispatchers.IO) {
      val parts = segments(directory)
      val url = server.baseUrl.toHttpUrl().newBuilder()
      parts.forEach { part ->
        url.addPathSegment(part)
        execute(
          server,
          password,
          Request.Builder().url(url.build().newBuilder().addPathSegment("").build()).method("MKCOL", null),
        ) { response ->
          require(response.isSuccessful || response.code == 405) { "创建备份目录失败：HTTP ${response.code}" }
        }
      }
      execute(
        server,
        password,
        Request.Builder().url(directoryUrl(server, directory).newBuilder().addPathSegment(name).build()).put(bytes.toRequestBody()),
      ) { response ->
        require(response.isSuccessful) { "上传备份失败：HTTP ${response.code}" }
      }
      client.invalidateListingsForServer(server.id)
    }
  }

  suspend fun download(
    server: WebDavServer,
    password: String,
    directory: String,
    url: String,
  ): ByteArray = withContext(Dispatchers.IO) {
    val root = directoryUrl(server, directory)
    val target = url.toHttpUrl()
    require(
      target.scheme == root.scheme && target.host == root.host && target.port == root.port &&
        target.encodedPath.startsWith(root.encodedPath) && target.pathSegments.last().endsWith(".tbk"),
    ) { "无效的远程备份地址" }
    execute(server, password, Request.Builder().url(target).get()) { response ->
      require(response.isSuccessful) { "下载备份失败：HTTP ${response.code}" }
      require(response.body.contentLength() <= BackupArchive.MAX_BYTES) { "备份文件过大" }
      val out = ByteArrayOutputStream()
      response.body.byteStream().use { input ->
        val chunk = ByteArray(8192)
        while (true) {
          val count = input.read(chunk)
          if (count < 0) break
          require(out.size() + count <= BackupArchive.MAX_BYTES) { "备份文件过大" }
          out.write(chunk, 0, count)
        }
      }
      out.toByteArray()
    }
  }

  private fun directoryUrl(
    server: WebDavServer,
    directory: String,
  ): HttpUrl {
    val builder = server.baseUrl.toHttpUrl().newBuilder()
    segments(directory).forEach(builder::addPathSegment)
    return builder.addPathSegment("").build()
  }

  private fun segments(directory: String): List<String> {
    val parts = directory.trim('/').split('/').filter { it.isNotEmpty() }
    require(parts.isNotEmpty() && parts.none { it == ".." || it == "." || it.contains(':') || it.contains('\\') }) { "备份目录无效" }
    return parts
  }

  private fun <T> execute(
    server: WebDavServer,
    password: String,
    request: Request.Builder,
    action: (okhttp3.Response) -> T,
  ): T {
    client.preemptiveAuthHeader(server, password)?.let { request.header("Authorization", it) }
    return client.clientFor(server, password).newBuilder().followRedirects(false).followSslRedirects(false).build()
      .newCall(request.build()).execute().use(action)
  }
}
