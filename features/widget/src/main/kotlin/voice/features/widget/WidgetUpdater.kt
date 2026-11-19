package voice.features.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import coil.imageLoader
import coil.request.ImageRequest
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.app.features.widget.BaseWidgetProvider
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.online.OnlineBook
import voice.core.online.OnlineChapterStore
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlineSourceBooksStore
import voice.core.online.OnlineUri
import voice.core.playback.notification.MainActivityIntentProvider
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.receiver.WidgetButtonReceiver
import voice.core.ui.dpToPxRounded
import voice.core.strings.R as StringsR
import voice.core.ui.R as UiR

@SingleIn(AppScope::class)
@Inject
class WidgetUpdater(
  private val context: Context,
  private val repo: BookRepository,
  @CurrentBookStore
  private val currentBookStore: DataStore<BookId?>,
  @OnlineSourceBooksStore
  private val onlineBooksStore: DataStore<List<OnlineBook>>,
  private val onlineChapterStore: OnlineChapterStore,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
  private val playStateManager: PlayStateManager,
  private val mainActivityIntentProvider: MainActivityIntentProvider,
) {

  private val appWidgetManager = AppWidgetManager.getInstance(context)

  private val scope = CoroutineScope(Dispatchers.IO)

  /** Ticker and state changes can overlap; serialize RemoteViews assembly. */
  private val updateMutex = Mutex()

  private var cachedCoverFile: java.io.File? = null
  private var cachedCoverBitmap: Bitmap? = null
  private var cachedBackdrop: Bitmap? = null

  /** Everything the card renders, resolved once per update. */
  private data class WidgetContent(
    val book: Book,
    val chapterTitle: String,
    val positionMs: Long,
    val durationMs: Long,
  )

  fun update() {
    scope.launch {
      updateMutex.withLock {
        val content = currentBookStore.data.first()?.let { loadContent(it) }
        val componentName = ComponentName(this@WidgetUpdater.context, BaseWidgetProvider::class.java)
        val ids = appWidgetManager.getAppWidgetIds(componentName)

        for (widgetId in ids) {
          updateWidgetForId(content, widgetId)
        }
      }
    }
  }

  /**
   * Online books never enter the local repository, so looking them up there
   * made the widget fall back to the "nothing playing" state even while they
   * were playing. Resolve them from the shelf store and pull the chapter title
   * and length from the chapter cache.
   */
  private suspend fun loadContent(id: BookId): WidgetContent? {
    repo.get(id)?.let { book ->
      return WidgetContent(
        book = book,
        chapterTitle = book.currentChapter.name.orEmpty(),
        positionMs = book.content.positionInChapter,
        durationMs = book.currentChapter.duration,
      )
    }
    val ref = OnlineUri.parseBookUri(id.value) ?: return null
    val online = onlineBooksStore.data.first()
      .firstOrNull { it.source == ref.source && it.bookId == ref.bookId }
      ?: return null
    val book = onlinePlaybackCatalog.localBook(online)
    // The live position in memory is ahead of the throttled persistence and
    // is what makes the progress bar actually move while playing.
    val live = onlinePlaybackCatalog.livePosition(online.key)
    val currentChapterId = live?.chapterId ?: online.currentChapterId
    val current = onlineChapterStore.chapters(online.key)
      .firstOrNull { it.id == currentChapterId }
    return WidgetContent(
      book = book,
      chapterTitle = current?.title?.takeIf { it.isNotBlank() } ?: book.currentChapter.name.orEmpty(),
      positionMs = live?.positionMs ?: online.positionMs,
      durationMs = current?.durationSeconds?.takeIf { it > 0 }?.times(1_000L)
        ?: book.currentChapter.duration,
    )
  }

  private suspend fun updateWidgetForId(
    content: WidgetContent?,
    widgetId: Int,
  ) {
    val opts = appWidgetManager.getAppWidgetOptions(widgetId)
    val minHeightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
    val compact = minHeightDp > 0 && minHeightDp < 62
    if (content != null) {
      initWidgetForPresentBook(widgetId, content, compact)
    } else {
      initWidgetForAbsentBook(widgetId, compact)
    }
  }

  private suspend fun initWidgetForPresentBook(
    widgetId: Int,
    content: WidgetContent,
    compact: Boolean,
  ) {
    val layoutId = if (compact) R.layout.widget_compact else R.layout.widget
    val remoteViews = RemoteViews(context.packageName, layoutId)
    initElements(remoteViews = remoteViews, content = content, compact = compact)

    appWidgetManager.updateAppWidget(widgetId, remoteViews)
  }

  private fun initWidgetForAbsentBook(
    widgetId: Int,
    compact: Boolean,
  ) {
    val layoutId = if (compact) R.layout.widget_compact else R.layout.widget
    val remoteViews = RemoteViews(context.packageName, layoutId)
    val wholeWidgetClickPI = mainActivityIntentProvider.toCurrentBook()
    remoteViews.setImageViewResource(R.id.imageView, UiR.drawable.album_art)
    // With no book the transport buttons have nothing to control, and leaving
    // the two text views empty made the widget look broken.
    remoteViews.setTextViewText(R.id.title, context.getString(StringsR.string.widget_empty_title))
    remoteViews.setViewVisibility(R.id.summary, View.GONE)
    remoteViews.setViewVisibility(R.id.rewind, View.GONE)
    remoteViews.setViewVisibility(R.id.playPause, View.GONE)
    remoteViews.setViewVisibility(R.id.fastForward, View.GONE)
    remoteViews.setViewVisibility(R.id.progress, View.GONE)
    remoteViews.setViewVisibility(R.id.elapsed, View.GONE)
    remoteViews.setViewVisibility(R.id.total, View.GONE)
    remoteViews.setOnClickPendingIntent(R.id.wholeWidget, wholeWidgetClickPI)
    appWidgetManager.updateAppWidget(widgetId, remoteViews)
  }

  private suspend fun initElements(
    remoteViews: RemoteViews,
    content: WidgetContent,
    compact: Boolean,
  ) {
    val book = content.book
    val playPausePI = WidgetButtonReceiver.pendingIntent(context, WidgetButtonReceiver.Action.PlayPause)
    remoteViews.setOnClickPendingIntent(R.id.playPause, playPausePI)

    val fastForwardPI = WidgetButtonReceiver.pendingIntent(context, WidgetButtonReceiver.Action.FastForward)
    remoteViews.setOnClickPendingIntent(R.id.fastForward, fastForwardPI)

    val rewindPI = WidgetButtonReceiver.pendingIntent(context, WidgetButtonReceiver.Action.Rewind)
    remoteViews.setOnClickPendingIntent(R.id.rewind, rewindPI)

    val playIcon = if (playStateManager.playState == PlayStateManager.PlayState.Playing) {
      R.drawable.widget_pause
    } else {
      R.drawable.widget_play
    }
    remoteViews.setImageViewResource(R.id.playPause, playIcon)

    remoteViews.setTextViewText(R.id.title, book.content.name)

    // A source without a chapter list yields a single placeholder chapter
    // titled after the book; repeating the title in the subtitle looks broken.
    if (compact) {
      val duration = content.durationMs.coerceAtLeast(0L)
      val position = content.positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
      val progress = if (duration > 0) ((position * 1000) / duration).toInt().coerceIn(0, 1000) else 0
      remoteViews.setProgressBar(R.id.progress, 1000, progress, false)
    } else {
      val chapter = content.chapterTitle
      if (chapter.isNotBlank() && chapter != book.content.name) {
        remoteViews.setTextViewText(R.id.summary, chapter)
        remoteViews.setViewVisibility(R.id.summary, View.VISIBLE)
      } else {
        remoteViews.setViewVisibility(R.id.summary, View.GONE)
      }

      val duration = content.durationMs.coerceAtLeast(0L)
      val position = content.positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
      val progress = if (duration > 0) ((position * 1000) / duration).toInt().coerceIn(0, 1000) else 0
      remoteViews.setProgressBar(R.id.progress, 1000, progress, false)
      remoteViews.setTextViewText(R.id.elapsed, formatTime(position))
      remoteViews.setTextViewText(R.id.total, formatTime(duration))
    }

    val coverFile = book.content.cover
    val coverSize = context.dpToPxRounded(COVER_SIZE_DP)
    // The ticker rebuilds the card every 2s while playing; reusing the decoded
    // cover keeps that cadence from becoming a decode loop.
    val bitmap = if (coverFile != null && coverFile == cachedCoverFile && cachedCoverBitmap != null) {
      cachedCoverBitmap
    } else {
      // a null drawable means even the fallback failed to decode: fall back to
      // the static resource instead of crashing the whole widget update
      coverFile?.takeIf { coverSize > 0 }?.let { cover ->
        context.imageLoader
          .execute(
            ImageRequest.Builder(context)
              .data(cover)
              .size(coverSize, coverSize)
              .fallback(UiR.drawable.album_art)
              .error(UiR.drawable.album_art)
              .allowHardware(false)
              .build(),
          )
          .drawable
          ?.toBitmap()
      }?.also {
        cachedCoverBitmap = it
        cachedCoverFile = coverFile
      }
    }
    if (bitmap != null) {
      remoteViews.setImageViewBitmap(R.id.imageView, bitmap)
    } else {
      remoteViews.setImageViewResource(R.id.imageView, UiR.drawable.album_art)
    }

    // Some launchers (Mi Home among them) paint an opaque plate behind the
    // widget and swallow the alpha written into the shape drawable, so the
    // gradient is delivered as a bitmap the launcher cannot restyle.
    val backdrop = cachedBackdrop ?: gradientBackdrop().also { cachedBackdrop = it }
    remoteViews.setImageViewBitmap(R.id.backdrop, backdrop)

    if (compact) {
      // One-row placement keeps the play controls that matter: cover, title,
      // a full-width progress line and play/pause. Chapter text, the time
      // stamps and the skip buttons have no room there.
      remoteViews.setViewVisibility(R.id.summary, View.GONE)
      remoteViews.setViewVisibility(R.id.elapsed, View.GONE)
      remoteViews.setViewVisibility(R.id.total, View.GONE)
      remoteViews.setViewVisibility(R.id.rewind, View.GONE)
      remoteViews.setViewVisibility(R.id.fastForward, View.GONE)
    }
    remoteViews.setOnClickPendingIntent(R.id.wholeWidget, wholeWidgetClickIntent())
  }

  /**
   * The blue-to-violet wash, drawn once. Colors carry the transparency so no
   * launcher-side background plate can flatten it.
   */
  private fun gradientBackdrop(): Bitmap {
    val width = 1_200
    val height = 400
    val colors = intArrayOf(
      Color.argb(BACKDROP_ALPHA, 0x29, 0xA8, 0xDC),
      Color.argb(BACKDROP_ALPHA, 0x53, 0x98, 0xE4),
      Color.argb(BACKDROP_ALPHA, 0x7C, 0x6A, 0xE8),
    )
    val paint = Paint().apply {
      shader = LinearGradient(
        0f,
        0f,
        width * 0.7f,
        height.toFloat(),
        colors,
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
      )
    }
    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = context.dpToPxRounded(20f).toFloat()
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
    return bitmap
  }

  private fun wholeWidgetClickIntent(): PendingIntent = mainActivityIntentProvider.toCurrentBook()

  private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
      "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
      "%02d:%02d".format(minutes, seconds)
    }
  }

  private companion object {
    const val COVER_SIZE_DP = 64f
    const val BACKDROP_ALPHA = 191 // 75 percent
  }
}
