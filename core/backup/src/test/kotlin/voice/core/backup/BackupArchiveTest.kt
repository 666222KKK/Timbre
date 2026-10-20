package voice.core.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupArchiveTest {
  private val password = "backup-test-password".toCharArray()
  private fun contents() = BackupContents(
    BackupManifest(createdAt = 123, filesPath = "/old/files", databaseVersion = 61),
    mapOf("files/datastore/themeMode" to "\"Dark\"".toByteArray(), "databases/autoBookDB" to byteArrayOf(1, 2)),
  )

  @Test
  fun encryptedRoundTripPreservesMetadataAndFiles() {
    val original = contents()
    val result = BackupArchive.decode(BackupArchive.encode(original, password), password)
    assertEquals(original.manifest, result.manifest)
    original.files.forEach { (path, bytes) -> assertContentEquals(bytes, result.files.getValue(path)) }
  }

  @Test
  fun wrongPasswordAndTamperingNeverProduceRestorableData() {
    val encrypted = BackupArchive.encode(contents(), password)
    assertFailsWith<IllegalArgumentException> { BackupArchive.decode(encrypted, "wrong-password".toCharArray()) }
    encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
    assertFailsWith<IllegalArgumentException> { BackupArchive.decode(encrypted, password) }
  }

  @Test
  fun archivesUseFreshSaltAndRejectInvalidVersions() {
    assertFalse(BackupArchive.encode(contents(), password).contentEquals(BackupArchive.encode(contents(), password)))
    val newer = contents().copy(manifest = contents().manifest.copy(format = 2))
    assertFailsWith<IllegalArgumentException> { BackupArchive.decode(BackupArchive.encode(newer, password), password) }
  }

  @Test
  fun pathPolicyExcludesTraversalAudioAndArbitraryPrivateFiles() {
    listOf(
      "../datastore/a",
      "files/datastore/../a",
      "/files/datastore/a",
      "files\\datastore\\a",
      "files/online_audio_cache/audio.mp3",
      "shared_prefs/secret.xml",
      "databases/other",
      "files/datastore//a",
    ).forEach {
      assertFalse(BackupArchive.allowed(it), it)
    }
    assertTrue(BackupArchive.allowed("files/extensions/demo/source.js"))
    assertFailsWith<IllegalArgumentException> { BackupArchive.encode(contents(), "short".toCharArray()) }
  }
}
