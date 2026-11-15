package voice.core.extension

import android.annotation.SuppressLint
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import voice.core.extension.engine.ExtensionSourceMeta
import voice.core.extension.engine.JsSourceEngine
import voice.core.extension.engine.OkHttpSandboxHttp
import voice.core.logging.api.Logger
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Creates and caches one [JsSourceEngine] per enabled extension source.
 * Cache entries are validated against the package's installed timestamp, so
 * re-importing, disabling or uninstalling a package invalidates its engines
 * automatically.
 */
@Inject
@SingleIn(AppScope::class)
public class ExtensionEngineProvider(private val manager: ExtensionManager) {

  private val mutex = Mutex()
  private val engines = HashMap<String, CachedEngine>()

  public suspend fun <T> useEngine(
    sourceId: String,
    block: suspend (JsSourceEngine) -> T,
  ): T {
    val installed = manager.installed()
    val pkg = installed.firstOrNull { candidate ->
      candidate.manifest.sources.any { it.id == sourceId } && candidate.isSourceEnabled(sourceId)
    } ?: throw ExtensionInstallException("扩展源 $sourceId 不存在或已停用")

    // Drop engines whose package was uninstalled. Each cached engine owns a
    // QuickJS runtime plus a dedicated thread, and nothing else evicts them,
    // so repeated uninstall/install cycles used to leak both.
    val live = installed.flatMap { it.manifest.sources }.mapTo(HashSet()) { it.id }
    mutex.withLock {
      val stale = engines.filterKeys { it !in live }
      if (stale.isNotEmpty()) {
        stale.values.forEach { cached ->
          try {
            cached.engine.close()
          } catch (_: Exception) {
            // the engine is already unusable; closing is best effort
          }
        }
        engines.keys.removeAll(stale.keys)
      }
    }

    val cached = mutex.withLock { engines[sourceId] }
    val entry = if (cached != null && cached.installedAtMillis == pkg.installedAtMillis) {
      cached
    } else {
      mutex.withLock {
        val current = engines[sourceId]
        if (current != null && current.installedAtMillis == pkg.installedAtMillis) {
          current
        } else {
          current?.engine?.close()
          val created = create(sourceId, pkg)
          CachedEngine(created, pkg.installedAtMillis).also { engines[sourceId] = it }
        }
      }
    }
    // A QuickJs runtime is single-threaded and not safe for overlapping
    // evaluations. Cancelling a LaunchedEffect does not interrupt the blocking
    // JNI call, so rapid slot switches used to pile up concurrent evaluates on
    // one runtime (=> "result promise is still pending"). Serialize per source.
    return entry.callMutex.withLock { block(entry.engine) }
  }

  private suspend fun create(
    sourceId: String,
    pkg: InstalledExtensionPackage,
  ): JsSourceEngine {
    val meta: ExtensionSourceMeta = pkg.manifest.sources.first { it.id == sourceId }
    val dir = manager.packageDir(pkg.manifest.id)
    val script = withContext(Dispatchers.IO) { File(dir, meta.script).readText(Charsets.UTF_8) }
    // Several recommendation slots may share one script; load each file once.
    val recommenderScripts = withContext(Dispatchers.IO) {
      meta.recommenders.map { it.script }.distinct().mapNotNull { name ->
        runCatching { name to File(dir, name).readText(Charsets.UTF_8) }
          .onFailure { Logger.i("[$SOURCE_PREFIX$sourceId] 读取推荐脚本失败: $name (${it.message})") }
          .getOrNull()
      }
    }
    return JsSourceEngine.create(
      sourceId = sourceId,
      script = script,
      scriptName = meta.script,
      http = OkHttpSandboxHttp(if (pkg.manifest.allowInsecure) insecureClient else secureClient),
      log = { line -> Logger.i("[$SOURCE_PREFIX$sourceId] $line") },
      additionalScripts = recommenderScripts,
    )
  }

  private val secureClient by lazy { buildHttpClient(allowInsecure = false) }
  private val insecureClient by lazy { buildHttpClient(allowInsecure = true) }

  private fun buildHttpClient(allowInsecure: Boolean): OkHttpClient {
    val builder = OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
    if (allowInsecure) {
      val context = SSLContext.getInstance("TLS")
      context.init(null, arrayOf<TrustManager>(TrustAllX509TrustManager), SecureRandom())
      builder.sslSocketFactory(context.socketFactory, TrustAllX509TrustManager)
        .hostnameVerifier { _, _ -> true }
    }
    return builder.build()
  }

  /**
   * Opt-in per package for broken TLS chains: the manifest's `allowInsecure`
   * flag explicitly enables trusting arbitrary certificates, mirroring the
   * WebDAV trust-all option. Lint-suppressed at the declaration level.
   */
  @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
  private object TrustAllX509TrustManager : X509TrustManager {
    override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
    ) = Unit

    override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
  }

  private data class CachedEngine(
    val engine: JsSourceEngine,
    val installedAtMillis: Long,
    val callMutex: Mutex = Mutex(),
  )

  public companion object {
    public const val SOURCE_PREFIX: String = "jdr:"
  }
}
