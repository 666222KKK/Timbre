package voice.features.extensions

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import voice.core.common.rootGraphAs
import voice.core.extension.ExtensionWebSessions

@ContributesTo(AppScope::class)
interface ExtensionVerificationGraph {
  val extensionWebSessions: ExtensionWebSessions
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
internal fun ExtensionWebVerificationDialog(
  pkg: ExtensionPackageUi,
  onDismiss: () -> Unit,
) {
  val graph = remember { rootGraphAs<ExtensionVerificationGraph>() }
  val origin = ExtensionWebSessions.origin(pkg.homepage)
  if (origin == null || pkg.allowInsecure) return
  val scope = rememberCoroutineScope()
  var web by remember { mutableStateOf<WebView?>(null) }
  var message by remember { mutableStateOf("请在网页内完成验证或登录，再点保存会话。") }
  var busy by remember { mutableStateOf(false) }
  DisposableEffect(Unit) {
    onDispose {
      web?.stopLoading()
      web?.destroy()
      web = null
    }
  }
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("网站验证") },
          navigationIcon = { TextButton(onClick = onDismiss) { Text("返回") } },
          actions = {
            TextButton(
              enabled = !busy,
              onClick = {
                scope.launch {
                  busy = true
                  try {
                    withContext(Dispatchers.IO) { graph.extensionWebSessions.clear(pkg.manifestId) }
                    message = "已清除该接口源保存的请求会话。网页自身的登录状态不受影响。"
                  } catch (e: CancellationException) {
                    throw e
                  } catch (e: Exception) {
                    message = e.message ?: "清除会话失败，请重试"
                  } finally {
                    busy = false
                  }
                }
              },
            ) { Text("清除") }
            TextButton(
              enabled = !busy && web != null,
              onClick = {
                val view = web
                if (view != null) {
                  val cookies = CookieManager.getInstance()
                  cookies.flush()
                  val cookie = cookies.getCookie(pkg.homepage).orEmpty()
                  val agent = view.settings.userAgentString
                  busy = true
                  scope.launch {
                    try {
                      withContext(Dispatchers.IO) {
                        graph.extensionWebSessions.save(pkg.manifestId, pkg.installedAtMillis, pkg.homepage, cookie, agent)
                      }
                      onDismiss()
                    } catch (e: CancellationException) {
                      throw e
                    } catch (e: Exception) {
                      message = e.message ?: "保存会话失败，请重试"
                    } finally {
                      busy = false
                    }
                  }
                }
              },
            ) { Text("保存会话") }
          },
        )
      },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        Text(pkg.homepage, Modifier.padding(horizontal = 16.dp))
        Text(message, Modifier.padding(16.dp))
        Text("会话仅供此接口源访问该网站使用；重新导入源或换设备后需重新验证。", Modifier.padding(horizontal = 16.dp))
        AndroidView(
          modifier = Modifier.weight(1f),
          factory = { context ->
            WebView(context).apply {
              web = this
              // The site's verification runs JavaScript. No native JS bridge,
              // local files, mixed content or external top-level pages are exposed.
              settings.javaScriptEnabled = true
              settings.domStorageEnabled = true
              settings.allowFileAccess = false
              settings.allowContentAccess = false
              settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
              settings.setSupportMultipleWindows(false)
              settings.javaScriptCanOpenWindowsAutomatically = false
              CookieManager.getInstance().setAcceptCookie(true)
              CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
              webChromeClient = WebChromeClient()
              webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                  view: WebView,
                  request: WebResourceRequest,
                ): Boolean = request.isForMainFrame && ExtensionWebSessions.origin(request.url.toString()) != origin
              }
              loadUrl(pkg.homepage)
            }
          },
        )
      }
    }
  }
}
