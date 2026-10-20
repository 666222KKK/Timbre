package voice.features.bookOverview.discovery

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CancellationException
import voice.core.common.rootGraphAs
import voice.core.online.OnlineDiscovery
import voice.core.online.OnlineDiscoveryCategory
import voice.core.online.OnlineDiscoveryPage
import voice.core.online.OnlineSearchResult
import voice.core.online.OnlineSourceInfo
import voice.core.online.OnlineSourceService
import voice.core.online.OnlineSourceServiceProvider
import voice.core.ui.icons.VoiceIcons
import voice.features.bookOverview.search.OnlineChaptersDialog
import voice.features.bookOverview.search.OnlineResultRow
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.navigation.Navigator
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface DiscoveryGraph {
  val navigator: Navigator

  @Provides
  @IntoSet
  fun discoveryNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.Discovery> { key ->
    NavEntry(key) { DiscoveryScreen() }
  }
}

@Composable
internal fun DiscoveryScreen() {
  val graph = remember { rootGraphAs<OnlineSourceServiceProvider>() }
  val navigator = remember { rootGraphAs<DiscoveryGraph>().navigator }
  val service = graph.onlineSourceService
  var refresh by remember { mutableIntStateOf(0) }
  var sources by remember { mutableStateOf<List<OnlineSourceInfo>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var failed by remember { mutableStateOf(false) }
  var selectedSource by rememberSaveable { mutableStateOf("") }
  LaunchedEffect(refresh) {
    loading = true
    failed = false
    try {
      sources = service.sources(reportServerErrors = true).filter { it.enabled }.distinctBy { it.name }
      if (sources.none { it.name == selectedSource }) selectedSource = sources.firstOrNull()?.name.orEmpty()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      failed = true
    } finally {
      loading = false
    }
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.discovery_title)) },
        actions = {
          TextButton(onClick = { navigator.goTo(Destination.ExtensionSources) }) {
            Text(stringResource(StringsR.string.discovery_manage_sources))
          }
          TextButton(onClick = { refresh++ }, enabled = !loading) {
            Text(stringResource(StringsR.string.discovery_refresh))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when {
        loading -> CircularProgressIndicator(Modifier.padding(24.dp))
        failed -> DiscoveryMessage(StringsR.string.search_online_error) { refresh++ }
        sources.isEmpty() -> {
          Text(stringResource(StringsR.string.discovery_no_sources), Modifier.padding(24.dp))
          TextButton(onClick = { navigator.goTo(Destination.ExtensionSources) }) {
            Text(stringResource(StringsR.string.discovery_manage_sources))
          }
        }
        else -> {
          Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            sources.forEach { source ->
              FilterChip(
                selected = selectedSource == source.name,
                onClick = { selectedSource = source.name },
                label = { Text(source.displayName.ifBlank { source.name }) },
              )
            }
          }
          DiscoverySource(service, selectedSource, refresh)
        }
      }
    }
  }
}

@Composable
private fun DiscoverySource(
  service: OnlineSourceService,
  source: String,
  refresh: Int,
) {
  var categories by remember(source, refresh) { mutableStateOf<List<OnlineDiscoveryCategory>>(emptyList()) }
  var loading by remember(source, refresh) { mutableStateOf(true) }
  var failed by remember(source, refresh) { mutableStateOf(false) }
  var retry by remember(source, refresh) { mutableIntStateOf(0) }
  var selectedId by rememberSaveable(source) { mutableStateOf("") }
  LaunchedEffect(source, refresh, retry) {
    loading = true
    failed = false
    try {
      categories = service.discoveryCategories(source)
      if (categories.none { it.id == selectedId }) selectedId = categories.firstOrNull()?.id.orEmpty()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      failed = true
    } finally {
      loading = false
    }
  }
  Column {
    when {
      loading -> CircularProgressIndicator(Modifier.padding(24.dp))
      failed -> DiscoveryMessage(StringsR.string.search_online_error) { retry++ }
      categories.isEmpty() -> Text(stringResource(StringsR.string.discovery_no_categories), Modifier.padding(24.dp))
      else -> {
        Row(
          Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          categories.forEach { category ->
            FilterChip(
              selected = selectedId == category.id,
              onClick = { selectedId = category.id },
              label = { Text(category.title) },
            )
          }
        }
        categories.firstOrNull { it.id == selectedId }?.let { category ->
          if (category.keyword != null) {
            Text(
              stringResource(StringsR.string.discovery_keyword_hint),
              Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
              style = MaterialTheme.typography.bodySmall,
            )
          }
          DiscoveryBooks(service, source, category, refresh)
        }
      }
    }
  }
}

@Composable
private fun DiscoveryBooks(
  service: OnlineSourceService,
  source: String,
  category: OnlineDiscoveryCategory,
  refresh: Int,
) {
  val graph = remember { rootGraphAs<OnlineSourceServiceProvider>() }
  val navigator = remember { rootGraphAs<DiscoveryGraph>().navigator }
  var results by remember(source, category, refresh) { mutableStateOf(OnlineDiscoveryPage(emptyList())) }
  var page by remember(source, category, refresh) { mutableIntStateOf(1) }
  var retry by remember(source, category, refresh) { mutableIntStateOf(0) }
  var loading by remember(source, category, refresh) { mutableStateOf(true) }
  var failed by remember(source, category, refresh) { mutableStateOf(false) }
  var chaptersFor by remember(source, category, refresh) { mutableStateOf<OnlineSearchResult?>(null) }
  LaunchedEffect(source, category, refresh, page, retry) {
    loading = true
    failed = false
    try {
      results = OnlineDiscovery.append(results.books, service.discover(source, category, page))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      failed = true
    } finally {
      loading = false
    }
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(results.books, key = { it.source to it.bookId }) { book ->
      OnlineResultRow(book, onClick = { chaptersFor = book })
    }
    item {
      when {
        loading -> CircularProgressIndicator(Modifier.padding(24.dp).size(24.dp))
        failed -> DiscoveryMessage(StringsR.string.search_online_error) { retry++ }
        results.books.isEmpty() -> Text(stringResource(StringsR.string.search_online_no_results), Modifier.padding(24.dp))
        results.hasMore -> TextButton(
          onClick = {
            loading = true
            page++
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(StringsR.string.discovery_load_more))
        }
        else -> Text(stringResource(StringsR.string.discovery_end), Modifier.padding(24.dp))
      }
    }
  }
  chaptersFor?.let { book ->
    OnlineChaptersDialog(
      service = service,
      catalog = graph.onlinePlaybackCatalog,
      book = book,
      onDismiss = { chaptersFor = null },
      onBookClick = { navigator.goTo(Destination.Playback(it)) },
    )
  }
}

@Composable
private fun DiscoveryMessage(
  message: Int,
  onRetry: () -> Unit,
) {
  Column(Modifier.padding(16.dp)) {
    Text(stringResource(message))
    TextButton(onClick = onRetry) { Text(stringResource(StringsR.string.search_online_retry)) }
  }
}
