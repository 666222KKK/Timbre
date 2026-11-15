package voice.core.extension

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import voice.core.extension.engine.SearchItem
import voice.core.extension.engine.SourceContract
import voice.core.online.ExtensionOnlineSource
import voice.core.online.OnlineChapter
import voice.core.online.OnlineRecommendGroup
import voice.core.online.OnlineSearchResult
import voice.core.online.OnlineSourceInfo
import voice.core.online.ResolvedStream
import voice.core.logging.api.Logger
import java.util.LinkedHashMap

/** sourceId/bookId 级别的透传字段缓存；容量有限，防止长会话内存无限增长。 */
private class BoundedCache(private val maxSize: Int = 200) {

  private val map = object : LinkedHashMap<String, JsonObject>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>): Boolean = size > maxSize
  }

  @Synchronized
  public fun get(key: String): JsonObject? = map[key]

  @Synchronized
  public fun put(
    key: String,
    value: JsonObject,
  ) {
    map[key] = value
  }
}

/** One cached recommendation page; extras are kept for the chapters stage. */
private data class CachedBrowse(
  val items: List<SearchItem>,
  val expiresAtMillis: Long,
)

/**
 * Bridges the `jdr:` prefixed online sources to locally executed extension
 * scripts. Search/chapter extras (custom fields) are remembered in memory and
 * merged into the next stage's params, mirroring the reference contract.
 */
@Inject
@SingleIn(AppScope::class)
public class ExtensionSourceBackend(
  private val engineProvider: ExtensionEngineProvider,
  private val manager: ExtensionManager,
) : ExtensionOnlineSource {

  /** "<sourceId>/<bookId>" → search result extras. */
  private val searchExtras = BoundedCache()

  /** "<sourceId>/<bookId>/<chapterId>" → chapter extras. */
  private val chapterExtras = BoundedCache()

  /** "<sourceId>/<groupId>/<page>" → cached page. Only slots with TTL land here. */
  private val browseCache = object : LinkedHashMap<String, CachedBrowse>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedBrowse>): Boolean = size > 200
  }

  @Synchronized
  private fun cachedBrowse(key: String, nowMillis: Long): List<SearchItem>? {
    val entry = browseCache[key] ?: return null
    if (entry.expiresAtMillis <= nowMillis) {
      browseCache.remove(key)
      return null
    }
    return entry.items
  }

  @Synchronized
  private fun storeBrowse(key: String, items: List<SearchItem>, ttlMinutes: Int, nowMillis: Long) {
    browseCache[key] = CachedBrowse(items, nowMillis + ttlMinutes * 60_000L)
  }

  private fun SearchItem.toOnlineResult(source: String): OnlineSearchResult = OnlineSearchResult(
    source = source,
    bookId = id,
    title = title,
    author = author,
    cover = cover,
    intro = intro,
    trackCount = trackCount,
  )

  override fun handles(source: String): Boolean = source.startsWith(ExtensionEngineProvider.SOURCE_PREFIX)

  override suspend fun hasEnabledSources(): Boolean = manager.installed().any { it.enabledSources().isNotEmpty() }

  override suspend fun enabledSourceInfos(): List<OnlineSourceInfo> {
    return manager.installed().flatMap { pkg ->
      pkg.enabledSources().map { meta ->
        OnlineSourceInfo(
          name = ExtensionEngineProvider.SOURCE_PREFIX + meta.id,
          displayName = meta.name.ifBlank { meta.id },
          enabled = true,
        )
      }
    }
  }

  override suspend fun recommendGroups(): List<OnlineRecommendGroup> {
    return manager.installed().flatMap { pkg ->
      pkg.enabledSources().flatMap { meta ->
        val source = ExtensionEngineProvider.SOURCE_PREFIX + meta.id
        meta.recommenders.map { rec ->
          OnlineRecommendGroup(
            source = source,
            sourceName = meta.name.ifBlank { meta.id },
            sourceDescription = meta.description,
            groupId = rec.id,
            name = rec.name,
            description = rec.description,
            cacheMinutes = rec.cacheMinutes,
          )
        }
      }
    }
  }

  override suspend fun browse(
    source: String,
    groupId: String,
    page: Int,
  ): List<OnlineSearchResult> {
    val sourceId = sourceId(source)
    // enabledSources() sorts and allocates several collections; this lookup
    // runs on every discover page load, so read the manifest directly instead.
    val pkg = manager.installed().firstOrNull { installed ->
      installed.isSourceEnabled(sourceId) && installed.manifest.sources.any { it.id == sourceId }
    } ?: return emptyList()
    val recommender = pkg.manifest.sources
      .firstOrNull { it.id == sourceId }
      ?.recommenders
      ?.firstOrNull { it.id == groupId }
      ?: return emptyList()
    // Re-importing a package must not keep serving pages cached by the old
    // build, so the install timestamp is part of the key.
    val cacheKey = "$sourceId/$groupId/$page@${pkg.installedAtMillis}"
    val nowMillis = System.currentTimeMillis()
    if (recommender.cacheMinutes > 0) {
      cachedBrowse(cacheKey, nowMillis)?.let { cached ->
        // Cached pages must repopulate extras too, otherwise chapters()/audio()
        // lose fields such as albumId for books opened from the cache.
        cached.forEach { item -> searchExtras.put("$sourceId/${item.id}", item.extra) }
        Logger.i("[discover-cache] hit $cacheKey (${cached.size} 条, ttl=${recommender.cacheMinutes}m)")
        return cached.map { it.toOnlineResult(source) }
      }
    }
    val params = params(
      base = linkedMapOf("groupId" to groupId, "page" to page, "limit" to 30),
    )
    val json = engineProvider.useEngine(sourceId) {
      it.invokeEntry(recommender.entry, params)
    }
    val items = SourceContract.parseSearchResults(json)
    // Same extras cache as search(): chapters()/audio() read custom
    // fields such as albumId from here.
    items.forEach { item ->
      searchExtras.put("$sourceId/${item.id}", item.extra)
    }
    if (recommender.cacheMinutes > 0 && items.isNotEmpty()) {
      storeBrowse(cacheKey, items, recommender.cacheMinutes, nowMillis)
      Logger.i("[discover-cache] store $cacheKey (${items.size} 条, ttl=${recommender.cacheMinutes}m)")
    }
    return items.map { it.toOnlineResult(source) }
  }

  override suspend fun search(
    source: String,
    keyword: String,
  ): List<OnlineSearchResult> {
    val sourceId = sourceId(source)
    val params = params(
      base = linkedMapOf("keyword" to keyword, "page" to 1, "limit" to 30),
    )
    val json = engineProvider.useEngine(sourceId) {
      // no stage-level cap: sources control timing themselves via per-request
      // timeoutMs options; OkHttp still bounds each request (connect 15s /
      // read 30s unless the script overrides)
      it.invoke("search", params)
    }
    val items = SourceContract.parseSearchResults(json)
    items.forEach { item ->
      searchExtras.put("$sourceId/${item.id}", item.extra)
    }
    return items.map { item ->
      OnlineSearchResult(
        source = source,
        bookId = item.id,
        title = item.title,
        author = item.author,
        cover = item.cover,
        intro = item.intro,
        trackCount = item.trackCount,
      )
    }
  }

  override suspend fun chapters(
    source: String,
    bookId: String,
  ): List<OnlineChapter> {
    val sourceId = sourceId(source)
    val params = params(
      base = linkedMapOf("bookId" to bookId, "page" to 1, "size" to 0),
      extras = searchExtras.get("$sourceId/$bookId"),
    )
    val json = engineProvider.useEngine(sourceId) {
      it.invoke("chapters", params)
    }
    val items = SourceContract.parseChapters(json)
    items.forEach { item ->
      chapterExtras.put("$sourceId/$bookId/${item.id}", item.extra)
    }
    return items.map { item ->
      OnlineChapter(
        id = item.id,
        title = item.title,
        durationSeconds = item.durationSeconds.toInt(),
        order = item.order,
      )
    }
  }

  override suspend fun resolveDirectUrl(
    source: String,
    bookId: String,
    chapterId: String,
    order: Int?,
  ): ResolvedStream? {
    val sourceId = sourceId(source)
    val extras = chapterExtras.get("$sourceId/$bookId/$chapterId")
    val base = linkedMapOf<String, Any>("bookId" to bookId, "chapterId" to chapterId)
    // 章节附加字段是进程内 LRU（200 条）：冷启动、大书一次塞满之后，
    // 有些源在 audio 阶段就只剩 bookId/chapterId 可用，只能自己回头一页页
    // 翻找集号。集号 App 本来就持久化着，补给它就不用再翻。源自己声明过
    // order 时一律以源为准，这里绝不覆盖。
    if (extras?.containsKey("order") != true) {
      order?.takeIf { it > 0 }?.let { base["order"] = it }
    }
    val params = params(
      base = base,
      extras = extras,
    )
    val json = engineProvider.useEngine(sourceId) {
      it.invoke("audio", params)
    }
    val parsed = SourceContract.parseAudio(json)
    return ResolvedStream(parsed.url, parsed.headers)
  }

  private fun sourceId(source: String): String = source.removePrefix(ExtensionEngineProvider.SOURCE_PREFIX)

  private fun params(
    base: LinkedHashMap<String, Any>,
    extras: JsonObject? = null,
  ): String {
    val merged = HashMap<String, JsonPrimitive>()
    extras?.forEach { (name, value) -> (value as? JsonPrimitive)?.let { merged[name] = it } }
    base.forEach { (name, value) ->
      merged[name] = when (value) {
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
      }
    }
    return JsonObject(merged).toString()
  }
}
