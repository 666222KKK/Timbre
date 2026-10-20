package voice.features.backup

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.navigation.Navigator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface BackupGraph {
  val backupViewModel: BackupViewModel
  val navigator: Navigator
  val backupApplication: Application

  @Provides
  @IntoSet
  fun backupNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.Backup> { key ->
    NavEntry(key) { BackupScreen() }
  }
}

@Composable
private fun BackupScreen() {
  val graph = remember { rootGraphAs<BackupGraph>() }
  val model = retain { graph.backupViewModel }
  val snackbar = remember { SnackbarHostState() }
  val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
    if (uri != null) model.createLocal(uri)
  }
  val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) model.inspectLocal(uri)
  }
  LaunchedEffect(model.message) {
    model.message?.let {
      snackbar.showSnackbar(it)
      model.clearMessage()
    }
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.backup_title)) },
        navigationIcon = {
          IconButton(onClick = graph.navigator::goBack) {
            Icon(VoiceIcons.ArrowBack, stringResource(StringsR.string.common_action_close))
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      item {
        Column(Modifier.padding(horizontal = 16.dp)) {
          Text("备份书架/播放列表、播放进度、书签、设置、已导入接口源与 WebDAV 配置。不包含音频和缓存。")
          Text("备份使用密码加密，包含服务器凭据。请妥善保存密码；遗忘后无法恢复。", style = MaterialTheme.typography.bodySmall)
          OutlinedTextField(
            value = model.password,
            onValueChange = { model.password = it },
            label = { Text("备份文件密码（至少 8 位；恢复时输入原密码）") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy && !model.restartRequired,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
          )
          OutlinedTextField(
            value = model.confirmation,
            onValueChange = { model.confirmation = it },
            label = { Text("创建备份时再次确认密码") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy && !model.restartRequired,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
          )
          if (model.busy) CircularProgressIndicator(Modifier.padding(16.dp))
          Row {
            TextButton(
              onClick = { if (model.validateCreate()) create.launch(model.name()) },
              enabled =
              !model.busy && !model.restartRequired,
            ) {
              Text("本地备份")
            }
            TextButton(
              onClick = { if (model.validateRestore()) open.launch(arrayOf("*/*")) },
              enabled =
              !model.busy && !model.restartRequired,
            ) {
              Text("本地恢复")
            }
          }
          Text("WebDAV 数据备份", style = MaterialTheme.typography.titleMedium)
          Text("独立于 WebDAV 音频播放。请在此配置备份账号，备份按时间保存在指定目录，可选择历史文件恢复。")
          Text("坚果云：账号填写邮箱，应用密码填写安全选项中生成的第三方应用密码。上方备份文件密码用于加密文件，两者用途不同。", style = MaterialTheme.typography.bodySmall)
          OutlinedTextField(
            value = model.serverUrl,
            onValueChange = { model.serverUrl = it },
            label = { Text("备份服务器地址") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy && !model.restartRequired,
            singleLine = true,
          )
          OutlinedTextField(
            value = model.username,
            onValueChange = { model.username = it },
            label = { Text("备份 WebDAV 账号") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy && !model.restartRequired,
            singleLine = true,
          )
          OutlinedTextField(
            value = model.applicationPassword,
            onValueChange = { model.applicationPassword = it },
            label = { Text("备份 WebDAV 应用密码") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy && !model.restartRequired,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
          )
          OutlinedTextField(
            value = model.directory,
            onValueChange = { model.directory = it },
            label = { Text("备份目录（相对于服务器根目录）") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy && !model.restartRequired,
            singleLine = true,
          )
          TextButton(onClick = model::testConnection, enabled = !model.busy && !model.restartRequired) {
            Text("保存并测试备份连接")
          }
          model.remoteStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          Text("创建备份需填写并确认至少 8 位的备份文件密码；测试连接不会创建正式备份。", style = MaterialTheme.typography.bodySmall)
          Row {
            TextButton(
              onClick = model::upload,
              enabled =
              !model.busy && !model.restartRequired,
            ) { Text("备份到 WebDAV") }
            TextButton(
              onClick = model::listRemote,
              enabled =
              !model.busy && !model.restartRequired,
            ) { Text("从 WebDAV 恢复") }
          }
          Text("恢复会替换当前数据，并在下次启动时生效。本地音频文件不会恢复，换设备后需要重新授权原音频目录。", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
  model.preview?.let { contents ->
    AlertDialog(
      onDismissRequest = model::dismissPreview,
      title = { Text("确认恢复备份") },
      text = {
        val date = DateTimeFormatter.ofPattern(
          "yyyy-MM-dd HH:mm",
        ).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(contents.manifest.createdAt))
        Text("创建于 $date，共 ${contents.files.size} 个数据文件。继续将替换当前书架、设置和书源，随后需要重启应用。")
      },
      confirmButton = { TextButton(onClick = model::confirmRestore, enabled = !model.busy) { Text("确认恢复") } },
      dismissButton = { TextButton(onClick = model::dismissPreview, enabled = !model.busy) { Text("取消") } },
    )
  }
  model.remoteFiles?.let { files ->
    AlertDialog(
      onDismissRequest = model::dismissRemote,
      title = { Text("选择远程备份") },
      text = {
        Column {
          OutlinedTextField(
            value = model.password,
            onValueChange = { model.password = it },
            label = { Text("所选备份文件的密码") },
            enabled = !model.busy,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
          )
          LazyColumn {
            if (files.isEmpty()) item { Text("此目录没有备份文件") }
            items(files, key = { it.url }) { file ->
              ListItem(modifier = Modifier.clickable { model.inspectRemote(file) }) { Text(file.name) }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = model::dismissRemote) { Text("关闭") } },
    )
  }
  if (model.restartRequired) {
    AlertDialog(
      onDismissRequest = {},
      title = { Text("重启以完成恢复") },
      text = { Text("备份已校验。应用将重启并应用数据；若应用未自动打开，请手动重新打开。") },
      confirmButton = { TextButton(onClick = { restart(graph.backupApplication) }) { Text("立即重启") } },
    )
  }
}

private fun restart(application: Application) {
  val intent = requireNotNull(application.packageManager.getLaunchIntentForPackage(application.packageName))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
  val pending = PendingIntent.getActivity(application, 314, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  application.getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pending)
  Process.killProcess(Process.myPid())
}
