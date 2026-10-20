package voice.core.online

public data class OnlineDiscoveryCategory(
  val id: String,
  val title: String,
  val keyword: String? = null,
)

public data class OnlineDiscoveryPage(
  val books: List<OnlineSearchResult>,
  val hasMore: Boolean = false,
)

public object OnlineDiscovery {
  public val searchCategories: List<OnlineDiscoveryCategory> = listOf(
    "玄幻", "修仙", "都市", "悬疑", "历史", "言情", "科幻", "儿童", "相声", "评书",
  ).map { OnlineDiscoveryCategory(id = it, title = it, keyword = it) }

  public fun append(
    current: List<OnlineSearchResult>,
    page: OnlineDiscoveryPage,
  ): OnlineDiscoveryPage {
    val merged = (current + page.books).distinctBy { it.source to it.bookId }
    return OnlineDiscoveryPage(merged, page.hasMore && merged.size > current.size)
  }
}
