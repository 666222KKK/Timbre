package voice.features.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.navigation.Origin
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface ExtensionsGraph {
  val extensionsViewModelFactory: ExtensionsViewModel.Factory
}

@ContributesTo(AppScope::class)
interface ExtensionsProvider {

  @Provides
  @IntoSet
  fun extensionsNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.ExtensionSources> { key ->
    NavEntry(key) {
      ExtensionsScreen(origin = Origin.Default)
    }
  }
}

@Composable
fun ExtensionsScreen(origin: Origin) {
  val viewModel = retain(origin.name) {
    rootGraphAs<ExtensionsGraph>()
      .extensionsViewModelFactory
      .create(origin)
  }
  val viewState = viewModel.viewState()
  ExtensionsView(
    viewState = viewState,
    listener = viewModel,
  )
  if (viewState.showUrlDialog) {
    ExtensionsUrlDialog(viewState = viewState, listener = viewModel)
  }
  viewState.uninstallManifestId?.let {
    ExtensionsUninstallConfirmView(listener = viewModel)
  }
}

@Composable
private fun ExtensionsView(
  viewState: ExtensionsViewState,
  listener: ExtensionsViewModel,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      listener.onFilePicked(uri)
    }
  }
  LaunchedEffect(viewState.message) {
    viewState.message?.let { snackbarHostState.showSnackbar(it) }
  }
  val listState = rememberLazyListState()
  var draggingKey by remember { mutableStateOf<String?>(null) }
  var dragOffset by remember { mutableFloatStateOf(0f) }
  // Local order while dragging: the DataStore is only written on drag end, so
  // the list never re-sorts from under the finger.
  var packageOrder by remember { mutableStateOf<List<String>>(emptyList()) }
  var sourceOrders by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

  // Keyed by the set of ids, not the whole list: committing a drag changes the
  // order in DataStore, and re-syncing on that change would snap the list back
  // to the old order for a frame.
  val packageIds = viewState.packages.map { it.manifestId }
  LaunchedEffect(packageIds) {
    packageOrder = viewState.packages.map { it.manifestId }
    sourceOrders = viewState.packages.associate { pkg ->
      pkg.manifestId to pkg.sources.map { it.sourceId }
    }
  }

  val byId = viewState.packages.associateBy { it.manifestId }
  val orderedPackages = packageOrder.mapNotNull { byId[it] } +
    viewState.packages.filter { it.manifestId !in packageOrder }
  val rows = remember(orderedPackages, sourceOrders) {
    buildList {
      orderedPackages.forEach { pkg ->
        add(ExtensionRow.PackageRow(pkg))
        if (pkg.expanded) {
          val order = sourceOrders[pkg.manifestId].orEmpty()
          val srcById = pkg.sources.associateBy { it.sourceId }
          (order.mapNotNull { srcById[it] } + pkg.sources.filter { it.sourceId !in order })
            .forEach { add(ExtensionRow.SourceRow(it)) }
        }
      }
    }
  }

  fun startDrag(key: String) {
    // A second finger on another handle would otherwise replace draggingKey
    // while the first gesture keeps sending deltas.
    if (draggingKey != null) return
    draggingKey = key
    dragOffset = 0f
  }

  fun dragBy(deltaY: Float) {
    val key = draggingKey ?: return
    dragOffset += deltaY
    val info = listState.layoutInfo.visibleItemsInfo
    val mine = info.firstOrNull { it.key == key } ?: return
    val center = mine.offset + mine.size / 2f + dragOffset
    val target = info.firstOrNull {
      it.key != key && center >= it.offset && center < it.offset + it.size
    } ?: return
    val targetKey = target.key as? String ?: return

    if (key.startsWith("pkg:") && targetKey.startsWith("pkg:")) {
      val from = packageOrder.indexOf(key.removePrefix("pkg:"))
      val to = packageOrder.indexOf(targetKey.removePrefix("pkg:"))
      if (from < 0 || to < 0 || from == to) return
      packageOrder = packageOrder.toMutableList().also { it.add(to, it.removeAt(from)) }
      dragOffset += mine.offset - target.offset
    } else if (key.startsWith("src:") && targetKey.startsWith("src:")) {
      val srcId = key.removePrefix("src:")
      val targetSrc = targetKey.removePrefix("src:")
      // Sources can only move inside their own package.
      val pkgId = sourceOrders.entries.firstOrNull { srcId in it.value }?.key ?: return
      val order = sourceOrders[pkgId] ?: return
      if (targetSrc !in order) return
      val from = order.indexOf(srcId)
      val to = order.indexOf(targetSrc)
      if (from < 0 || to < 0 || from == to) return
      sourceOrders = sourceOrders + (pkgId to order.toMutableList().also { it.add(to, it.removeAt(from)) })
      dragOffset += mine.offset - target.offset
    }
  }

  fun finishDrag() {
    val key = draggingKey ?: return
    draggingKey = null
    dragOffset = 0f
    when {
      key.startsWith("pkg:") -> listener.onPackageReorder(packageOrder)
      key.startsWith("src:") -> {
        val srcId = key.removePrefix("src:")
        val pkgId = sourceOrders.entries.firstOrNull { srcId in it.value }?.key ?: return
        listener.onSourceReorder(pkgId, sourceOrders[pkgId].orEmpty())
      }
    }
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.extensions_title)) },
        navigationIcon = {
          IconButton(onClick = listener::back) {
            Icon(imageVector = VoiceIcons.ArrowBack, contentDescription = stringResource(StringsR.string.common_action_close))
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { contentPadding ->
    LazyColumn(contentPadding = contentPadding) {
      item {
        ListItem(
          modifier = Modifier.clickable {
            filePicker.launch(arrayOf("*/*"))
          },
          supportingContent = { Text(stringResource(StringsR.string.extensions_import_file_summary)) },
          trailingContent = {
            if (viewState.busy) {
              CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }
          },
        ) {
          Text(stringResource(StringsR.string.extensions_import_file))
        }
        ListItem(
          modifier = Modifier.clickable(onClick = listener::showUrlDialog),
          supportingContent = { Text(stringResource(StringsR.string.extensions_import_url_summary)) },
        ) {
          Text(stringResource(StringsR.string.extensions_import_url))
        }
      }
      if (viewState.packages.isEmpty()) {
        item {
          Text(
            modifier = Modifier.padding(24.dp),
            text = stringResource(StringsR.string.extensions_empty),
          )
        }
      }
      items(rows, key = { it.key }) { row ->
        val dragging = draggingKey == row.key
        // A dragged row already follows the finger through graphicsLayer; if
        // animateItem also animated its placement the offset would apply twice
        // and the card would jump ahead of the pointer.
        val base = if (dragging) {
          Modifier.animateItem(placementSpec = null)
        } else {
          Modifier.animateItem()
        }
        val shared = base
          .zIndex(if (dragging) 1f else 0f)
          .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
        when (row) {
          is ExtensionRow.PackageRow -> PackageRow(
            pkg = row.pkg,
            modifier = shared,
            onToggle = { listener.toggleExpanded(row.pkg.manifestId) },
            onUninstall = { listener.requestUninstall(row.pkg.manifestId) },
            onDragStart = { startDrag(row.key) },
            onDrag = ::dragBy,
            onDragEnd = ::finishDrag,
          )
          is ExtensionRow.SourceRow -> SourceRow(
            source = row.source,
            modifier = shared,
            onToggle = { checked -> listener.setSourceEnabled(row.source.sourceId, checked) },
            onDragStart = { startDrag(row.key) },
            onDrag = ::dragBy,
            onDragEnd = ::finishDrag,
          )
        }
      }
    }
  }
}

private sealed interface ExtensionRow {
  val key: String

  data class PackageRow(val pkg: ExtensionPackageUi) : ExtensionRow {
    override val key: String get() = "pkg:${pkg.manifestId}"
  }

  data class SourceRow(val source: ExtensionSourceUi) : ExtensionRow {
    override val key: String get() = "src:${source.sourceId}"
  }
}

@Composable
private fun PackageRow(
  pkg: ExtensionPackageUi,
  modifier: Modifier,
  onToggle: () -> Unit,
  onUninstall: () -> Unit,
  onDragStart: () -> Unit,
  onDrag: (Float) -> Unit,
  onDragEnd: () -> Unit,
) {
  ListItem(
    modifier = modifier,
    leadingContent = {
      FilledTonalIconButton(onClick = onToggle) {
        Icon(
          imageVector = if (pkg.expanded) VoiceIcons.ExpandLess else VoiceIcons.ExpandMore,
          contentDescription = null,
        )
      }
    },
    supportingContent = {
      Column {
        Text(stringResource(StringsR.string.extensions_package_version, pkg.version))
        if (pkg.author.isNotBlank()) {
          Text(stringResource(StringsR.string.extensions_package_author, pkg.author))
        }
      }
    },
    trailingContent = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        DragHandle(onDragStart, onDrag, onDragEnd)
        IconButton(onClick = onUninstall) {
          Icon(
            imageVector = VoiceIcons.Delete,
            contentDescription = stringResource(StringsR.string.extensions_uninstall),
          )
        }
      }
    },
  ) {
    Text(pkg.name)
  }
}

@Composable
private fun SourceRow(
  source: ExtensionSourceUi,
  modifier: Modifier,
  onToggle: (Boolean) -> Unit,
  onDragStart: () -> Unit,
  onDrag: (Float) -> Unit,
  onDragEnd: () -> Unit,
) {
  ListItem(
    modifier = modifier,
    supportingContent = { Text("jdr:${source.sourceId}") },
    trailingContent = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        DragHandle(onDragStart, onDrag, onDragEnd)
        Switch(checked = source.enabled, onCheckedChange = onToggle)
      }
    },
  ) {
    Text(source.name)
  }
}

/** Drag handle: a plain Box so it never competes with the switch beside it. */
@Composable
private fun DragHandle(
  onDragStart: () -> Unit,
  onDrag: (Float) -> Unit,
  onDragEnd: () -> Unit,
) {
  val currentStart by rememberUpdatedState(onDragStart)
  val currentDrag by rememberUpdatedState(onDrag)
  val currentEnd by rememberUpdatedState(onDragEnd)
  Box(
    modifier = Modifier
      .size(40.dp)
      .pointerInput(Unit) {
        detectDragGestures(
          onDragStart = { currentStart() },
          onDragEnd = { currentEnd() },
          onDragCancel = { currentEnd() },
          onDrag = { change, amount ->
            change.consume()
            currentDrag(amount.y)
          },
        )
      },
    contentAlignment = Alignment.Center,
  ) {
    Text("≡", style = MaterialTheme.typography.titleLarge)
  }
}

@Composable
private fun ExtensionsUninstallConfirmView(listener: ExtensionsViewModel) {
  AlertDialog(
    onDismissRequest = listener::dismissUninstall,
    title = { Text(stringResource(StringsR.string.extensions_uninstall)) },
    text = { Text(stringResource(StringsR.string.extensions_uninstall_confirm)) },
    confirmButton = {
      TextButton(onClick = listener::confirmUninstall) {
        Text(stringResource(StringsR.string.extensions_uninstall))
      }
    },
    dismissButton = {
      TextButton(onClick = listener::dismissUninstall) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}

@Composable
private fun ExtensionsUrlDialog(
  viewState: ExtensionsViewState,
  listener: ExtensionsViewModel,
) {
  AlertDialog(
    onDismissRequest = listener::dismissUrlDialog,
    title = { Text(stringResource(StringsR.string.extensions_import_url)) },
    text = {
      Column {
        OutlinedTextField(
          modifier = Modifier.fillMaxWidth(),
          value = viewState.urlInput,
          onValueChange = listener::updateUrlInput,
          label = { Text(stringResource(StringsR.string.extensions_import_url_hint)) },
          singleLine = true,
        )
      }
    },
    confirmButton = {
      Button(onClick = listener::importFromUrl, enabled = viewState.urlInput.isNotBlank()) {
        Text(stringResource(StringsR.string.extensions_import))
      }
    },
    dismissButton = {
      TextButton(onClick = listener::dismissUrlDialog) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}
