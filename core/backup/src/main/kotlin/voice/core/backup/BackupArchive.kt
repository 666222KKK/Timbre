package voice.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupManifest(
  val format: Int = 1,
  val createdAt: Long,
  val filesPath: String,
  val databaseVersion: Int,
)

data class BackupContents(
  val manifest: BackupManifest,
  val files: Map<String, ByteArray>,
)

object BackupArchive {
  const val MAX_BYTES = 64 * 1024 * 1024
  private const val MAX_EXPANDED = 256 * 1024 * 1024
  private val magic = "TMBK0001".toByteArray(Charsets.US_ASCII)
  val directories = listOf(
    "datastore",
    "extensions",
    "online_chapter_lists",
    "online_covers",
    "bookCovers",
    "bookCoversUserChosen",
  )

  fun allowed(name: String): Boolean {
    if (name.contains('\\') || name.startsWith('/') || name.split('/').any { it == ".." || it.isEmpty() }) return false
    return name == "manifest.json" || name == "webdav-secrets.json" ||
      name in setOf("databases/autoBookDB", "databases/autoBookDB-wal") ||
      directories.any { name.startsWith("files/$it/") }
  }

  fun encode(
    contents: BackupContents,
    password: CharArray,
  ): ByteArray {
    require(password.size >= 8) { "备份密码至少需要 8 个字符" }
    require(contents.files.keys.all(::allowed) && "manifest.json" !in contents.files)
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zip ->
      val manifest = Json.encodeToString(BackupManifest.serializer(), contents.manifest).toByteArray()
      (contents.files + ("manifest.json" to manifest)).forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
    cipher.updateAAD(magic + salt + nonce)
    return (magic + salt + nonce + cipher.doFinal(buffer.toByteArray())).also {
      require(it.size <= MAX_BYTES) { "备份超过 64 MB 上限" }
    }
  }

  fun decode(
    bytes: ByteArray,
    password: CharArray,
  ): BackupContents {
    require(bytes.size in 52..MAX_BYTES && bytes.copyOfRange(0, 8).contentEquals(magic)) { "不是有效的 Timbre 备份" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(password, bytes.copyOfRange(8, 24)), GCMParameterSpec(128, bytes, 24, 12))
    cipher.updateAAD(bytes.copyOfRange(0, 36))
    val plaintext = try {
      cipher.doFinal(bytes, 36, bytes.size - 36)
    } catch (e: Exception) {
      throw IllegalArgumentException("密码不正确或备份文件损坏", e)
    }
    val files = linkedMapOf<String, ByteArray>()
    var total = 0
    ZipInputStream(ByteArrayInputStream(plaintext)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        require(!entry.isDirectory && allowed(entry.name) && entry.name !in files && files.size < 20000) {
          "备份含有不允许的文件或重复条目"
        }
        val data = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
          val count = zip.read(chunk)
          if (count < 0) break
          total += count
          require(total <= MAX_EXPANDED) { "备份解压内容过大" }
          data.write(chunk, 0, count)
        }
        files[entry.name] = data.toByteArray()
      }
    }
    val manifest = Json.decodeFromString(BackupManifest.serializer(), requireNotNull(files.remove("manifest.json")).decodeToString())
    require(manifest.format == 1 && manifest.databaseVersion > 0) { "不支持的备份版本" }
    require(manifest.filesPath.startsWith("/") && manifest.filesPath.length in 2..4096) { "备份目录信息无效" }
    require("databases/autoBookDB" in files) { "备份缺少书架数据库" }
    return BackupContents(manifest, files)
  }

  private fun key(
    password: CharArray,
    salt: ByteArray,
  ): SecretKeySpec {
    val spec = PBEKeySpec(password, salt, 210000, 256)
    return try {
      SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    } finally {
      spec.clearPassword()
    }
  }
}
