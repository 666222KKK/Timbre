package voice.core.extension

import android.app.Application
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import voice.core.extension.engine.ExtensionManifest
import voice.core.extension.engine.JdrArchive
import voice.core.extension.engine.JdrFormatException
import java.io.File
import java.util.concurrent.TimeUnit

/** Thrown when installing a package fails with a user presentable reason. */
public class ExtensionInstallException public constructor(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Installs, updates and removes .jdr extension packages. Package files live
 * under `filesDir/extensions/<manifest.id>/`, the registry in a DataStore.
 */
@Inject
@SingleIn(AppScope::class)
public class ExtensionManager(
  private val application: Application,
  private val storeFactory: ExtensionStoreFactory,
) {

  private val packagesStore: DataStore<List<InstalledExtensionPackage>> by lazy {
    storeFactory.packages()
  }

  // serializes install/uninstall/enable mutations (read-modify-write on the
  // registry must not interleave) and reuses one download client
  private val mutationMutex = Mutex()

  private val downloadClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  public fun packagesStore(): DataStore<List<InstalledExtensionPackage>> = packagesStore

  public fun packageDir(manifestId: String): File = File(application.filesDir, "extensions/$manifestId")

  public suspend fun installed(): List<InstalledExtensionPackage> = packagesStore.data.first()

  /**
   * Validates and installs a .jdr package. A package with the same manifest
   * id is updated in place (keeping per-source enabled states); a source id
   * already claimed by another package is rejected.
   */
  public suspend fun install(bytes: ByteArray): ExtensionManifest {
    // inflating and validating a package is cpu and io heavy. It used to run on
    // the caller's dispatcher, which is the main thread of both import screens,
    // so a large package froze the ui or ran out of memory before anything was
    // even written to disk.
    return mutationMutex.withLock { withContext(Dispatchers.IO) { installLocked(bytes) } }
  }

  private suspend fun installLocked(bytes: ByteArray): ExtensionManifest {
    val archive = try {
      JdrArchive.parse(bytes)
    } catch (e: JdrFormatException) {
      throw ExtensionInstallException(e.message ?: "包格式错误", e)
    }
    val manifest = archive.manifest
    val current = installed()
    current
      .filter { it.manifest.id != manifest.id }
      .flatMap { it.manifest.sources }
      .firstOrNull { existing -> manifest.sources.any { it.id == existing.id } }
      ?.let {
        throw ExtensionInstallException("源 id ${it.id} 已被其他扩展包占用")
      }

    val dir = packageDir(manifest.id)
    // Leading dot keeps these out of the way of a legitimate package id such
    // as "a.staging" (ids must start with a letter or digit).
    val staging = File(dir.parentFile, ".${manifest.id}.staging")
    val backup = File(dir.parentFile, ".${manifest.id}.backup")
    withContext(Dispatchers.IO) {
      // Build the new version in a staging dir first. Deleting the live dir up
      // front meant an IO failure (disk full / process killed) left a half
      // written package while the registry still pointed at it.
      staging.deleteRecursively()
      if (!staging.mkdirs()) throw ExtensionInstallException("无法创建扩展目录")
      File(staging, JdrArchive.MANIFEST_ENTRY).writeText(
        storeFactory.json.encodeToString(ExtensionManifest.serializer(), manifest),
        Charsets.UTF_8,
      )
      archive.scripts.forEach { (name, content) ->
        File(staging, name).also { it.parentFile?.mkdirs() }.writeText(content, Charsets.UTF_8)
      }

      backup.deleteRecursively()
      if (dir.exists() && !dir.renameTo(backup)) {
        staging.deleteRecursively()
        throw ExtensionInstallException("替换扩展文件失败，已保留原版本")
      }
      if (!staging.renameTo(dir)) {
        // roll back so the previously installed version stays usable
        backup.renameTo(dir)
        staging.deleteRecursively()
        throw ExtensionInstallException("替换扩展文件失败，已回滚到原版本")
      }
      backup.deleteRecursively()
    }

    packagesStore.updateData { packages ->
      val existingIndex = packages.indexOfFirst { it.manifest.id == manifest.id }
      val existing = packages.getOrNull(existingIndex)
      // Keep the user's ordering for sources that still exist and append new
      // ones at the end, so updating a package never reshuffles the shelf.
      val previousOrder = existing?.sourceOrder.orEmpty()
      val order = previousOrder.filter { id -> manifest.sources.any { it.id == id } } +
        manifest.sources.map { it.id }.filter { id -> id !in previousOrder }
      val merged = InstalledExtensionPackage(
        manifest = manifest,
        installedAtMillis = System.currentTimeMillis(),
        // keep enabled flags of sources that still exist; new sources start enabled
        sourceEnabled = existing?.sourceEnabled.orEmpty()
          .filterKeys { key -> manifest.sources.any { it.id == key } },
        sourceOrder = order,
      )
      // Updating keeps the package where the user put it; a brand new package
      // is appended at the end instead of jumping to the top.
      if (existingIndex >= 0) {
        packages.toMutableList().also { it[existingIndex] = merged }
      } else {
        packages + merged
      }
    }
    return manifest
  }

  /** Downloads a .jdr package from [url] and installs it. */
  public suspend fun installFromUrl(url: String): ExtensionManifest {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      throw ExtensionInstallException("链接必须是 http(s) 地址")
    }
    val bytes = withContext(Dispatchers.IO) {
      downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) {
          throw ExtensionInstallException("下载失败: HTTP ${response.code}")
        }
        val source = response.body.source()
        source.request(JdrArchive.MAX_TOTAL_BYTES.toLong() + 1)
        if (source.buffer.size > JdrArchive.MAX_TOTAL_BYTES) {
          throw ExtensionInstallException("文件超过 ${JdrArchive.MAX_TOTAL_BYTES / 1024 / 1024}MB 上限")
        }
        source.readByteArray()
      }
    }
    return install(bytes)
  }

  /** Imports a package from a SAF content uri. */
  public suspend fun installFromUri(uri: android.net.Uri): ExtensionManifest {
    val bytes = withContext(Dispatchers.IO) {
      application.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
          val read = input.read(chunk)
          if (read < 0) break
          total += read
          if (total > JdrArchive.MAX_TOTAL_BYTES) {
            throw ExtensionInstallException("文件超过 ${JdrArchive.MAX_TOTAL_BYTES / 1024 / 1024}MB 上限")
          }
          buffer.write(chunk, 0, read)
        }
        buffer.toByteArray()
      } ?: throw ExtensionInstallException("无法读取所选文件")
    }
    return install(bytes)
  }

  public suspend fun uninstall(manifestId: String) {
    mutationMutex.withLock {
      // Drop the registry entry first: a failed delete then only leaves
      // orphaned bytes behind, instead of a package that is still listed but
      // whose files are already gone.
      packagesStore.updateData { packages -> packages.filterNot { it.manifest.id == manifestId } }
      withContext(Dispatchers.IO) {
        packageDir(manifestId).deleteRecursively()
      }
    }
  }

  public suspend fun setSourceEnabled(
    sourceId: String,
    enabled: Boolean,
  ) {
    mutationMutex.withLock {
      packagesStore.updateData { packages ->
        packages.map { pkg ->
          if (pkg.manifest.sources.any { it.id == sourceId }) {
            pkg.copy(sourceEnabled = pkg.sourceEnabled + (sourceId to enabled))
          } else {
            pkg
          }
        }
      }
    }
  }

  /** Persists whether a package is expanded in the management screen. */
  public suspend fun setPackageExpanded(
    manifestId: String,
    expanded: Boolean,
  ) {
    mutationMutex.withLock {
      packagesStore.updateData { packages ->
        packages.map { pkg ->
          if (pkg.manifest.id == manifestId) pkg.copy(expanded = expanded) else pkg
        }
      }
    }
  }

  /**
   * Flips [manifestId]'s expanded flag inside [DataStore.updateData], so two
   * rapid taps can't both read the same stale value and cancel each other out.
   */
  public suspend fun togglePackageExpanded(manifestId: String) {
    mutationMutex.withLock {
      packagesStore.updateData { packages ->
        packages.map { pkg ->
          if (pkg.manifest.id == manifestId) pkg.copy(expanded = !pkg.expanded) else pkg
        }
      }
    }
  }

  /**
   * Replaces the source order of one package with [orderedIds]. Ids that no
   * longer exist are dropped and unknown sources are appended, so a stale drag
   * can never lose a source.
   */
  public suspend fun setSourceOrder(
    manifestId: String,
    orderedIds: List<String>,
  ) {
    mutationMutex.withLock {
      packagesStore.updateData { packages ->
        packages.map { pkg ->
          if (pkg.manifest.id != manifestId) return@map pkg
          val known = pkg.manifest.sources.map { it.id }
          val ordered = orderedIds.filter { it in known }
          pkg.copy(sourceOrder = ordered + known.filter { it !in ordered })
        }
      }
    }
  }

  /** Replaces the package order; unknown ids are ignored, missing ones appended. */
  public suspend fun setPackageOrder(orderedManifestIds: List<String>) {
    mutationMutex.withLock {
      packagesStore.updateData { packages ->
        val byId = packages.associateBy { it.manifest.id }
        val ordered = orderedManifestIds.mapNotNull { byId[it] }
        val placed = ordered.mapTo(HashSet()) { it.manifest.id }
        ordered + packages.filter { it.manifest.id !in placed }
      }
    }
  }

  /** Finds the installed package that provides [sourceId], if enabled. */
  public suspend fun packageFor(sourceId: String): InstalledExtensionPackage? {
    return installed().firstOrNull { pkg ->
      pkg.manifest.sources.any { it.id == sourceId } && pkg.isSourceEnabled(sourceId)
    }
  }
}
