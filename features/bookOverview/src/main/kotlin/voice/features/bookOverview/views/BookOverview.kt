package voice.features.bookOverview.views

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue.Expanded
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.core.ui.AnnouncementDialog
import voice.core.ui.PlayButton
import voice.core.ui.UpdateDialog
import voice.core.ui.VoiceTheme
import voice.core.ui.playButtonSharedBoundsModifier
import voice.features.bookOverview.bottomSheet.BottomSheetContent
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.deleteBook.DeleteBookDialog
import voice.features.bookOverview.di.BookOverviewGraph
import voice.features.bookOverview.discover.DiscoverContent
import voice.features.bookOverview.editTitle.EditBookTitleDialog
import voice.features.bookOverview.onlineActions.OnlineCacheDialog
import voice.features.bookOverview.onlineActions.OnlineRefreshDialog
import voice.features.bookOverview.overview.BookOverviewCategory
import voice.features.bookOverview.overview.BookOverviewItemViewState
import voice.features.bookOverview.overview.BookOverviewLayoutMode
import voice.features.bookOverview.overview.BookOverviewViewState
import voice.features.bookOverview.search.BookSearchViewState
import voice.features.bookOverview.views.topbar.BookOverviewTopBar
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import kotlin.uuid.Uuid
import voice.core.strings.R as StringsR
import voice.core.strings.R as StringsRParameter
import voice.core.strings.R as StringsRParameterProvider

@ContributesTo(AppScope::class)
interface BookOverviewProvider {

  @Provides
  @IntoSet
  fun bookOverviewNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.BookOverview> { key ->
    NavEntry(key) {
      BookOverviewScreen()
    }
  }
}

@Composable
fun BookOverviewScreen(modifier: Modifier = Modifier) {
  val bookGraph = retain<BookOverviewGraph> {
    rootGraphAs<BookOverviewGraph.Factory.Provider>()
      .bookOverviewGraphProviderFactory.create()
  }
  val bookOverviewViewModel = bookGraph.bookOverviewViewModel
  val editBookTitleViewModel = bookGraph.editBookTitleViewModel
  val bottomSheetViewModel = bookGraph.bottomSheetViewModel
  val deleteBookViewModel = bookGraph.deleteBookViewModel
  val fileCoverViewModel = bookGraph.fileCoverViewModel
  val onlineBookActionsViewModel = bookGraph.onlineBookActionsViewModel

  LaunchedEffect(Unit) {
    bookOverviewViewModel.attach()
  }
  val viewState = bookOverviewViewModel.state()

  val scope = rememberCoroutineScope()

  val getContentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
    onResult = { uri ->
      if (uri != null) {
        fileCoverViewModel.onImagePicked(uri)
      }
    },
  )

  var showBottomSheet by remember { mutableStateOf(false) }
  BookOverview(
    viewState = viewState,
    onBookClick = bookOverviewViewModel::onBookClick,
    onBookLongClick = { bookId ->
      bottomSheetViewModel.bookSelected(bookId)
      showBottomSheet = true
    },
    onRetryImport = bookOverviewViewModel::retryImport,
    onBookFolderClick = bookOverviewViewModel::onBookFolderClick,
    onAddBookClick = bookOverviewViewModel::onAddBookClick,
    onPlayButtonClick = bookOverviewViewModel::playPause,
    onSearchActiveChange = bookOverviewViewModel::onSearchActiveChange,
    onSearchQueryChange = bookOverviewViewModel::onSearchQueryChange,
    onSearchBookClick = bookOverviewViewModel::onSearchBookClick,
    onClearSearchHistory = bookOverviewViewModel::onClearSearchHistory,
    onPermissionBugCardClick = bookOverviewViewModel::onPermissionBugCardClick,
    onDiscoverHintShow = bookOverviewViewModel::onDiscoverHintShown,
  )
  val deleteBookViewState = deleteBookViewModel.state.value
  if (deleteBookViewState != null) {
    DeleteBookDialog(
      viewState = deleteBookViewState,
      onDismiss = deleteBookViewModel::onDismiss,
      onConfirmDeletion = deleteBookViewModel::onConfirmDeletion,
      onDeleteCheckBoxCheck = deleteBookViewModel::onDeleteCheckBoxCheck,
    )
  }
  val update = bookOverviewViewModel.updateNotifier.prompt.collectAsState().value
  if (update != null) {
    UpdateDialog(
      update = update,
      onUpdateClick = {
        bookOverviewViewModel.openLatestRelease()
        bookOverviewViewModel.updateNotifier.onUpdatePageOpened()
      },
      onDismissClick = {
        scope.launch {
          bookOverviewViewModel.updateNotifier.dismiss()
        }
      },
    )
  }
  val announcement = bookOverviewViewModel.announcementNotifier.prompt.collectAsState().value
  if (announcement != null) {
    AnnouncementDialog(
      announcement = announcement,
      onKnowClick = {
        bookOverviewViewModel.announcementNotifier.onKnow()
      },
      onNeverShowClick = {
        scope.launch {
          bookOverviewViewModel.announcementNotifier.neverShowAgain()
        }
      },
    )
  }
  val editBookTitleState = editBookTitleViewModel.state.value
  if (editBookTitleState != null) {
    EditBookTitleDialog(
      onDismissEditTitleClick = editBookTitleViewModel::onDismissEditTitle,
      onConfirmEditTitle = editBookTitleViewModel::onConfirmEditTitle,
      viewState = editBookTitleState,
      onUpdateEditTitle = editBookTitleViewModel::onUpdateEditTitle,
    )
  }
  val onlineRefreshState = onlineBookActionsViewModel.refreshState.value
  if (onlineRefreshState != null) {
    OnlineRefreshDialog(
      viewState = onlineRefreshState,
      onDismiss = onlineBookActionsViewModel::onDismissRefresh,
    )
  }
  val onlineCacheState = onlineBookActionsViewModel.cacheDialog.value
  if (onlineCacheState != null) {
    val progress = remember(onlineCacheState.bookId) {
      onlineBookActionsViewModel.cacheProgress(onlineCacheState.bookId.value)
    }.collectAsState(initial = null).value
    // a finished job changed what is cached: reload the dialog numbers
    LaunchedEffect(progress?.downloading) {
      if (progress != null && !progress.downloading) {
        onlineBookActionsViewModel.reloadCacheInfo(onlineCacheState.bookId)
      }
    }
    OnlineCacheDialog(
      viewState = onlineCacheState,
      progress = progress,
      onSelectCount = onlineBookActionsViewModel::onSelectCount,
      onCustomCountChange = onlineBookActionsViewModel::onCustomCountChange,
      onSelectDelay = onlineBookActionsViewModel::onSelectDelay,
      onCustomDelayChange = onlineBookActionsViewModel::onCustomDelayChange,
      onStartCache = onlineBookActionsViewModel::onStartCache,
      onCancelCache = onlineBookActionsViewModel::onCancelCache,
      onClearCache = onlineBookActionsViewModel::onClearCache,
      onDismiss = onlineBookActionsViewModel::onDismissCache,
    )
  }

  if (showBottomSheet) {
    val sheetState = rememberBottomSheetState(
      initialValue = Hidden,
      enabledValues = setOf(Hidden, Expanded),
    )
    ModalBottomSheet(
      modifier = modifier,
      sheetState = sheetState,
      content = {
        BottomSheetContent(
          state = bottomSheetViewModel.state.value,
          onItemClick = { item ->
            if (item == BottomSheetItem.FileCover) {
              getContentLauncher.launch("image/*")
            }
            scope.launch {
              sheetState.hide()
              bottomSheetViewModel.onItemClick(item)
              showBottomSheet = false
            }
          },
        )
      },
      onDismissRequest = {
        showBottomSheet = false
      },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookOverview(
  viewState: BookOverviewViewState,
  onBookClick: (BookId) -> Unit,
  onBookLongClick: (BookId) -> Unit,
  onRetryImport: (BookId) -> Unit,
  onBookFolderClick: () -> Unit,
  onAddBookClick: () -> Unit,
  onPlayButtonClick: () -> Unit,
  onSearchActiveChange: (Boolean) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onSearchBookClick: (BookId) -> Unit,
  onClearSearchHistory: () -> Unit,
  onPermissionBugCardClick: () -> Unit,
  onDiscoverHintShow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
  Scaffold(
    modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      BookOverviewTopBar(
        viewState = viewState,
        onBookFolderClick = onBookFolderClick,
        onActiveChange = onSearchActiveChange,
        onQueryChange = onSearchQueryChange,
        onSearchBookClick = onSearchBookClick,
        onClearSearchHistory = onClearSearchHistory,
      )
    },

    floatingActionButton = {
      if (viewState.playButtonState != null) {
        PlayButton(
          modifier = Modifier.navigationBarsPadding(),
          playing = viewState.playButtonState == BookOverviewViewState.PlayButtonState.Playing,
          fabSize = 56.dp,
          iconSize = 24.dp,
          onPlayClick = onPlayButtonClick,
          sharedElementModifier = Modifier.playButtonSharedBoundsModifier(),
        )
      }
    },
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
  ) { contentPadding ->
    val pageCount = if (viewState.discoverPageEnabled) 2 else 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    // Turning the discover page off while it is the current page must land the
    // pager back on the shelf instead of an out-of-range page.
    LaunchedEffect(pageCount) {
      if (pagerState.currentPage >= pageCount) {
        pagerState.scrollToPage(pageCount - 1)
      }
    }
    val pagerScope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
      HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = !viewState.searchActive,
      ) { page ->
        if (page == 0) {
          Box(
            Modifier
              // Scaffold used to force this Box to fill its content slot. Inside
              // a pager page it is measured against its content and centered,
              // so the size must be explicit to keep the shelf top-aligned.
              .fillMaxSize()
              .padding(contentPadding)
              .consumeWindowInsets(contentPadding),
          ) {
            val shelfEmpty = viewState.books.values.none { it.isNotEmpty() }
            if (shelfEmpty &&
              !viewState.showStoragePermissionBugCard &&
              !viewState.searchActive
            ) {
              // a running import is indicated by the progress on the book cards, so
              // the shelf shouldn't stay blank while the first books are imported
              EmptyShelf(onAddClick = onAddBookClick)
            } else {
              when (viewState.layoutMode) {
                BookOverviewLayoutMode.List -> {
                  ListBooks(
                    books = viewState.books,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookLongClick,
                    onRetryImport = onRetryImport,
                    showPermissionBugCard = viewState.showStoragePermissionBugCard,
                    onPermissionBugCardClick = onPermissionBugCardClick,
                  )
                }
                BookOverviewLayoutMode.Grid -> {
                  GridBooks(
                    books = viewState.books,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookLongClick,
                    onRetryImport = onRetryImport,
                    showPermissionBugCard = viewState.showStoragePermissionBugCard,
                    onPermissionBugCardClick = onPermissionBugCardClick,
                  )
                }
              }
            }
          }
        } else {
          DiscoverContent(
            onBookClick = onSearchBookClick,
            contentPadding = contentPadding,
          )
        }
      }
      // The indicator only exists while the discover page is reachable; with
      // the page disabled the pager has a single page and it would be a lie.
      if (!viewState.searchActive && viewState.discoverPageEnabled) {
        PagerIsland(
          currentPage = pagerState.currentPage,
          onToggle = { page ->
            pagerScope.launch { pagerState.animateScrollToPage(page) }
          },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        )
        if (viewState.showDiscoverHint) {
          DiscoverSwipeHint(
            onShow = onDiscoverHintShow,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .navigationBarsPadding()
              .padding(bottom = 52.dp),
          )
        }
      }
    }
  }
}

/**
 * Two ticks at the bottom edge: the active one is wider and tinted. Sits at
 * 55% opacity while idle and only fully appears on press, so it reads as a page
 * indicator rather than a floating control. The visual is 3dp tall but the
 * touch target stays 44dp.
 */
@Composable
private fun PagerIsland(
  currentPage: Int,
  onToggle: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val alpha by animateFloatAsState(
    targetValue = if (pressed) 1f else 0.55f,
    label = "pagerIndicatorAlpha",
  )
  val label = stringResource(
    if (currentPage == 1) StringsR.string.overview_page_discover else StringsR.string.overview_page_shelf,
  )
  Box(
    modifier = modifier
      .height(44.dp)
      .semantics {
        role = Role.Tab
        contentDescription = label
      }
      .clickable(
        interactionSource = interaction,
        indication = null,
      ) {
        onToggle(if (currentPage == 1) 0 else 1)
      },
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier.graphicsLayer { this.alpha = alpha },
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      repeat(2) { index ->
        val active = index == currentPage
        val width by animateDpAsState(
          targetValue = if (active) 28.dp else 20.dp,
          label = "pagerIndicatorWidth",
        )
        Box(
          Modifier
            .width(width)
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(
              if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
              },
            ),
        )
      }
    }
  }
}

/** One-shot bubble above the ticks: shown once, then never again. */
@Composable
private fun DiscoverSwipeHint(
  onShow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val currentOnShow by rememberUpdatedState(onShow)
  var visible by remember { mutableStateOf(true) }
  LaunchedEffect(Unit) {
    delay(2500)
    visible = false
    currentOnShow()
  }
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    Surface(
      shape = RoundedCornerShape(percent = 50),
      color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
    ) {
      Text(
        text = "向左滑，发现更多",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
      )
    }
  }
}

@Suppress("ktlint:compose:preview-public-check")
@Preview
@Composable
fun BookOverviewPreview(
  @PreviewParameter(BookOverviewPreviewParameterProvider::class)
  viewState: BookOverviewViewState,
) {
  VoiceTheme {
    BookOverview(
      viewState = viewState,
      onBookClick = {},
      onBookLongClick = {},
      onRetryImport = {},
      onBookFolderClick = {},
      onAddBookClick = {},
      onPlayButtonClick = {},
      onSearchActiveChange = {},
      onSearchQueryChange = {},
      onSearchBookClick = {},
      onClearSearchHistory = {},
      onPermissionBugCardClick = {},
      onDiscoverHintShow = {},
    )
  }
}

internal class BookOverviewPreviewParameterProvider : PreviewParameterProvider<BookOverviewViewState> {

  fun book(): BookOverviewItemViewState {
    return BookOverviewItemViewState(
      name = "Book",
      author = "Author",
      cover = null,
      progress = 0.8F,
      id = BookId(Uuid.random().toString()),
      remainingTime = "01:04",
    )
  }

  override val values = sequenceOf(
    BookOverviewViewState(
      books = mapOf(
        BookOverviewCategory.CURRENT to buildMap {
          repeat(10) {
            put(
              BookId(Uuid.random().toString()),
              mutableStateOf(book()),
            )
          }
        },
        BookOverviewCategory.FINISHED to buildMap {
          repeat(2) {
            put(
              BookId(Uuid.random().toString()),
              mutableStateOf(book()),
            )
          }
        },
      ),
      layoutMode = BookOverviewLayoutMode.List,
      playButtonState = BookOverviewViewState.PlayButtonState.Paused,
      showAddBookHint = false,
      showSearchIcon = true,
      isLoading = true,
      searchActive = true,
      searchViewState = BookSearchViewState.EmptySearch(
        recentQueries = emptyList(),
        query = "",
      ),
      showStoragePermissionBugCard = false,
      showFolderPickerIcon = true,
    ),
  )
}
