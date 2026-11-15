package voice.features.bookOverview.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlineRecommendGroup
import voice.core.online.OnlineSearchResult
import voice.core.online.OnlineSourceService
import voice.core.online.OnlineSourceServiceProvider
import voice.features.bookOverview.search.OnlineChaptersDialog
import voice.features.bookOverview.search.OnlineResultRow

private data class RecommendSource(
  val source: String,
  val name: String,
  val description: String,
  val groups: List<OnlineRecommendGroup>,
)

/** Turns raw engine errors into something that tells the user what to do. */
private fun loadErrorMessage(e: Exception): String {
  val raw = e.message.orEmpty().trim()
  return when {
    raw.contains("timeout", ignoreCase = true) ->
      "连接接口源超时：站点或网络暂时没响应，可点「重试」再试一次。"
    raw.isBlank() -> "加载失败，可点「重试」再试一次。"
    else -> "加载失败：$raw"
  }
}

/** Second page of the shelf pager. Only sources that declare recommenders appear. */
@Composable
internal fun DiscoverContent(
  onBookClick: (BookId) -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val graph = remember { rootGraphAs<OnlineSourceServiceProvider>() }
  val service: OnlineSourceService = graph.onlineSourceService
  val catalog: OnlinePlaybackCatalog = graph.onlinePlaybackCatalog
  var groups by remember { mutableStateOf(emptyList<OnlineRecommendGroup>()) }
  var groupsLoading by remember { mutableStateOf(true) }
  var selectedSource by remember { mutableStateOf<String?>(null) }
  var selectedGroup by remember { mutableStateOf<OnlineRecommendGroup?>(null) }
  var books by remember { mutableStateOf(emptyList<OnlineSearchResult>()) }
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var retryToken by remember { mutableStateOf(0) }
  var cachedBooks by remember { mutableStateOf(emptyMap<String, List<OnlineSearchResult>>()) }
  var chaptersFor by remember { mutableStateOf<OnlineSearchResult?>(null) }

  // groupBy keeps only sources that actually declared recommendation slots.
  val sources = remember(groups) {
    groups.groupBy { it.source }.entries.map { (source, list) ->
      RecommendSource(
        source = source,
        name = list.first().sourceName,
        description = list.first().sourceDescription,
        groups = list,
      )
    }
  }
  val currentSource = sources.firstOrNull { it.source == selectedSource }
  val visibleGroups = currentSource?.groups.orEmpty()

  LaunchedEffect(Unit) {
    try {
      groups = service.recommendGroups()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      groups = emptyList()
    }
    groupsLoading = false
    val first = groups.firstOrNull()
    selectedSource = first?.source
    selectedGroup = first
  }
  LaunchedEffect(selectedGroup, retryToken) {
    val group = selectedGroup ?: return@LaunchedEffect
    // Rapid slot switches cancel this effect during the window, so only the
    // slot the user actually settles on issues a request. Without it every tap
    // started its own connection and they all timed out together.
    delay(250)
    val cacheKey = "${group.source}/${group.groupId}"
    loading = true
    error = null
    books = emptyList()
    try {
      val result = service.browse(group.source, group.groupId, 1)
      books = result
      cachedBooks = cachedBooks + (cacheKey to result)
    } catch (e: CancellationException) {
      // Switching slots (or swiping away) cancels the previous load.
      // Cancellation is not a failure and its message must never be shown.
      throw e
    } catch (e: Exception) {
      error = loadErrorMessage(e)
      // A flaky link must not blank the page: fall back to the last good list.
      books = cachedBooks[cacheKey].orEmpty()
    }
    loading = false
  }

  fun selectSource(source: String) {
    selectedSource = source
    selectedGroup = sources.firstOrNull { it.source == source }?.groups?.firstOrNull()
  }

  Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
    Text(
      text = "接口源",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      sources.forEach { source ->
        FilterChip(
          selected = selectedSource == source.source,
          onClick = { selectSource(source.source) },
          label = { Text(source.name) },
        )
      }
    }
    currentSource?.description?.takeIf { it.isNotBlank() }?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
      )
    }
    if (visibleGroups.isNotEmpty()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        visibleGroups.forEach { group ->
          FilterChip(
            selected = selectedGroup == group,
            onClick = { selectedGroup = group },
            label = { Text(group.name) },
          )
        }
      }
    }
    selectedGroup?.description?.takeIf { it.isNotBlank() }?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
    if (groupsLoading) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else {
      if (error != null) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = error.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = { retryToken++ }) { Text("重试") }
        }
      }
      when {
        loading && books.isEmpty() -> {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        sources.isEmpty() -> Text(text = "未安装带推荐位的接口源", modifier = Modifier.padding(16.dp))
        books.isEmpty() -> Text(text = "暂无推荐书籍", modifier = Modifier.padding(16.dp))
        else -> {
          LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(books, key = { it.source + it.bookId + it.title }) { result ->
              OnlineResultRow(result = result, onClick = { chaptersFor = result })
            }
          }
        }
      }
    }
  }

  chaptersFor?.let { book ->
    OnlineChaptersDialog(
      service = service,
      catalog = catalog,
      book = book,
      onDismiss = { chaptersFor = null },
      onBookClick = onBookClick,
    )
  }
}
