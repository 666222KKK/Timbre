package voice.core.extension.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** One recommendation slot declared by a source in its manifest. */
@Serializable
public data class ExtensionRecommenderMeta(
  /** Unique within the source; also passed to the script as `groupId`. */
  public val id: String,
  public val name: String,
  /** Global function name exposed by that script. */
  public val entry: String,
  /** Script file path inside the package; inherited from the config file's
   *  top-level `script` when omitted. Validation still requires it non-blank. */
  public val script: String = "",
  /** Short blurb rendered on the discover page. */
  public val description: String = "",
  /**
   * How long a fetched page stays usable, in minutes. `0` disables caching for
   * this slot, so a source can cache slow ranking APIs while leaving fast or
   * volatile slots untouched.
   */
  public val cacheMinutes: Int = 0,
)

/**
 * Shape of a standalone recommender config file, e.g. `f_rank.json`:
 *
 * ```json
 * { "script": "f_rank.js",
 *   "recommenders": [{ "id": "hot", "name": "热门榜单", "entry": "__rec_rank" }] }
 * ```
 *
 * Slots inherit [script] so they don't repeat it; a slot may still override it
 * when one source bundles several recommender scripts.
 */
@Serializable
public data class RecommenderConfig(
  public val script: String = "",
  public val recommenders: List<ExtensionRecommenderMeta> = emptyList(),
)

/** One source (interface) declared by a .jdr package manifest. */
@Serializable
public data class ExtensionSourceMeta(
  /** Globally unique source id; the app routes it as `jdr:<id>`. */
  public val id: String,
  public val name: String,
  /** Script file path inside the package, e.g. `itingshu.js`. */
  public val script: String,
  /** Subset of `search`, `chapters`, `audio`. */
  public val capabilities: List<String> = listOf(CAP_SEARCH, CAP_CHAPTERS, CAP_AUDIO),
  /** Short blurb rendered on the discover page. */
  public val description: String = "",
  /** Optional recommendation slots; empty means no discover entries. */
  public val recommenders: List<ExtensionRecommenderMeta> = emptyList(),
  /**
   * Package-relative path to a standalone recommender config (JSON). When set
   * it replaces [recommenders], so a source with many slots keeps the main
   * manifest to one line and each script owns its own config file.
   */
  public val recommenderConfig: String = "",
) {
  public companion object {
    public const val CAP_SEARCH: String = "search"
    public const val CAP_CHAPTERS: String = "chapters"
    public const val CAP_AUDIO: String = "audio"
  }
}

/** manifest.json of a .jdr package. */
@Serializable
public data class ExtensionManifest(
  public val id: String,
  public val name: String,
  public val version: String,
  public val author: String = "",
  public val description: String = "",
  public val homepage: String = "",
  /** Trust broken TLS certificates for this package's http bridge. */
  public val allowInsecure: Boolean = false,
  public val sources: List<ExtensionSourceMeta> = emptyList(),
)

/** Thrown when a .jdr package is malformed. */
public class JdrFormatException public constructor(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A parsed .jdr package: a zip containing `manifest.json` plus the source
 * scripts it references. Parsing validates structure, sizes and ids.
 */
public class JdrArchive private constructor(
  public val manifest: ExtensionManifest,
  public val scripts: Map<String, String>,
) {

  public fun scriptFor(source: ExtensionSourceMeta): String =
    scripts[source.script] ?: throw JdrFormatException("包内缺少脚本文件 ${source.script}")

  public companion object {

    public const val MANIFEST_ENTRY: String = "manifest.json"
    public const val FILE_EXTENSION: String = ".jdr"
    public const val MAX_TOTAL_BYTES: Int = 20 * 1024 * 1024
    public const val MAX_SCRIPT_BYTES: Int = 5 * 1024 * 1024
    public const val MAX_ENTRIES: Int = 64

    /**
     * Cap for everything a package inflates to, not just for the zip itself.
     * Scripts are text and deflate roughly 4-8x, so a few hundred KB of zip
     * used to expand to the per-entry maximum times [MAX_ENTRIES]: 64 * 5MB =
     * 320MB held as bytes and again as strings. That is an out-of-memory on a
     * low end device from a package that looks perfectly small on disk. Real
     * sources are far below this: the limit only exists to kill such bombs.
     */
    // Must stay above MAX_TOTAL_BYTES: a legit 20MB zip of already-compressed
    // images can inflate past 16MB and used to be rejected for no good reason.
    public const val MAX_UNCOMPRESSED_BYTES: Int = 32 * 1024 * 1024

    private val MANIFEST_ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val SOURCE_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val KNOWN_CAPABILITIES =
      setOf(ExtensionSourceMeta.CAP_SEARCH, ExtensionSourceMeta.CAP_CHAPTERS, ExtensionSourceMeta.CAP_AUDIO)
    private val ENTRY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private const val MAX_DESCRIPTION_LENGTH = 200
    private const val MAX_CACHE_MINUTES = 7 * 24 * 60

    private val json = Json { ignoreUnknownKeys = true }

    public fun parse(bytes: ByteArray): JdrArchive {
      if (bytes.isEmpty()) throw JdrFormatException("空文件")
      if (bytes.size > MAX_TOTAL_BYTES) throw JdrFormatException("包超过 ${MAX_TOTAL_BYTES / 1024 / 1024}MB 上限")

      var manifestJson: String? = null
      val files = HashMap<String, String>()
      var uncompressedBytes = 0
      ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        var count = 0
        while (entry != null) {
          count++
          if (count > MAX_ENTRIES) throw JdrFormatException("包内文件数超过 $MAX_ENTRIES 上限")
          val name = entry.name
          if (name.startsWith("/") || name.contains("\\") || name.contains("..")) {
            throw JdrFormatException("非法的包内路径: $name")
          }
          if (entry.isDirectory) {
            entry = zip.nextEntry
            continue
          }
          // bounded read: never allocate more than the cap for one entry
          val content = readBounded(zip, MAX_SCRIPT_BYTES, name)
          uncompressedBytes += content.size
          if (uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
            throw JdrFormatException("包解压后超过 ${MAX_UNCOMPRESSED_BYTES / 1024 / 1024}MB 上限")
          }
          if (name == MANIFEST_ENTRY) {
            manifestJson = content.toString(Charsets.UTF_8)
          } else {
            files[name] = content.toString(Charsets.UTF_8)
          }
          entry = zip.nextEntry
        }
      }

      val rawManifest = manifestJson ?: throw JdrFormatException("包内缺少 manifest.json")
      val parsed = try {
        json.decodeFromString<ExtensionManifest>(rawManifest)
      } catch (e: Exception) {
        throw JdrFormatException("manifest.json 解析失败: ${e.message}", e)
      }
      // Recommendation slots may live in per-script JSON files. Inline them
      // before validation so every downstream check (script presence, ids,
      // cacheMinutes) works on one normalized manifest.
      val manifest = expandRecommenderConfigs(parsed, files)
      validateManifest(manifest)
      manifest.sources.forEach { source ->
        if (!files.containsKey(source.script)) {
          throw JdrFormatException("manifest 引用的脚本不存在: ${source.script}")
        }
        if (files.getValue(source.script).isBlank()) {
          throw JdrFormatException("脚本文件为空: ${source.script}")
        }
      }
      manifest.sources.flatMap { it.recommenders }.distinctBy { it.script }.forEach { recommender ->
        if (!files.containsKey(recommender.script)) {
          throw JdrFormatException("manifest 引用的推荐脚本不存在: ${recommender.script}")
        }
        if (files.getValue(recommender.script).isBlank()) {
          throw JdrFormatException("推荐脚本文件为空: ${recommender.script}")
        }
      }
      return JdrArchive(manifest, files)
    }

    private fun readBounded(
      zip: ZipInputStream,
      maxBytes: Int,
      name: String,
    ): ByteArray {
      val buffer = java.io.ByteArrayOutputStream(64 * 1024)
      val chunk = ByteArray(64 * 1024)
      var total = 0
      while (true) {
        val read = zip.read(chunk)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw JdrFormatException("文件超过大小上限: $name")
        buffer.write(chunk, 0, read)
      }
      return buffer.toByteArray()
    }

    /**
     * Merges `sources[].recommenderConfig` files into the in-memory manifest.
     * Slots inherit the file's top-level `script`, so they don't repeat it.
     */
    private fun expandRecommenderConfigs(
      manifest: ExtensionManifest,
      files: Map<String, String>,
    ): ExtensionManifest {
      if (manifest.sources.none { it.recommenderConfig.isNotBlank() }) return manifest
      return manifest.copy(
        sources = manifest.sources.map { source ->
          if (source.recommenderConfig.isBlank()) return@map source
          val raw = files[source.recommenderConfig]
            ?: throw JdrFormatException(
              "源 ${source.id} 引用的推荐配置文件不存在: ${source.recommenderConfig}",
            )
          val config = try {
            json.decodeFromString<RecommenderConfig>(raw)
          } catch (e: Exception) {
            throw JdrFormatException(
              "推荐配置解析失败(${source.recommenderConfig}): ${e.message}",
              e,
            )
          }
          val slots = config.recommenders.map { slot ->
            when {
              slot.script.isNotBlank() -> slot
              config.script.isNotBlank() -> slot.copy(script = config.script)
              else -> throw JdrFormatException(
                "推荐位 ${slot.id} 缺少 script，且 ${source.recommenderConfig} 未提供默认 script",
              )
            }
          }
          if (slots.isEmpty()) {
            throw JdrFormatException("推荐配置文件为空: ${source.recommenderConfig}")
          }
          source.copy(recommenders = slots)
        },
      )
    }

    private fun validateManifest(manifest: ExtensionManifest) {
      if (!MANIFEST_ID_REGEX.matches(manifest.id)) {
        throw JdrFormatException("manifest.id 非法（需小写字母/数字/._-，3-64 位）: ${manifest.id}")
      }
      if (manifest.name.isBlank()) throw JdrFormatException("manifest.name 不能为空")
      if (manifest.version.isBlank()) throw JdrFormatException("manifest.version 不能为空")
      if (manifest.sources.isEmpty()) throw JdrFormatException("manifest.sources 不能为空")
      val ids = HashSet<String>()
      manifest.sources.forEach { source ->
        if (!SOURCE_ID_REGEX.matches(source.id)) {
          throw JdrFormatException("源 id 非法: ${source.id}")
        }
        if (!ids.add(source.id)) {
          throw JdrFormatException("源 id 重复: ${source.id}")
        }
        if (source.name.isBlank()) throw JdrFormatException("源 ${source.id} 的 name 不能为空")
        if (source.script.isBlank()) throw JdrFormatException("源 ${source.id} 的 script 不能为空")
        val unknown = source.capabilities - KNOWN_CAPABILITIES
        if (unknown.isNotEmpty()) {
          throw JdrFormatException("源 ${source.id} 含未知能力: $unknown")
        }
        if (source.description.length > MAX_DESCRIPTION_LENGTH) {
          throw JdrFormatException("源 ${source.id} 的说明超过 $MAX_DESCRIPTION_LENGTH 字")
        }
        val recommenderIds = HashSet<String>()
        source.recommenders.forEach { recommender ->
          if (!SOURCE_ID_REGEX.matches(recommender.id)) {
            throw JdrFormatException("推荐位 id 非法: ${recommender.id}")
          }
          if (!recommenderIds.add(recommender.id)) {
            throw JdrFormatException("源 ${source.id} 的推荐位 id 重复: ${recommender.id}")
          }
          if (recommender.name.isBlank()) {
            throw JdrFormatException("推荐位 ${recommender.id} 的 name 不能为空")
          }
          if (recommender.script.isBlank() || recommender.entry.isBlank()) {
            throw JdrFormatException("推荐位 ${recommender.id} 缺少 script 或 entry")
          }
          if (!ENTRY_REGEX.matches(recommender.entry)) {
            throw JdrFormatException("推荐位 ${recommender.id} 的 entry 非法: ${recommender.entry}")
          }
          if (recommender.description.length > MAX_DESCRIPTION_LENGTH) {
            throw JdrFormatException("推荐位 ${recommender.id} 的说明超过 $MAX_DESCRIPTION_LENGTH 字")
          }
          if (recommender.cacheMinutes !in 0..MAX_CACHE_MINUTES) {
            throw JdrFormatException(
              "推荐位 ${recommender.id} 的 cacheMinutes 需在 0-$MAX_CACHE_MINUTES 之间（0 表示不缓存）",
            )
          }
        }
      }
    }
  }
}
