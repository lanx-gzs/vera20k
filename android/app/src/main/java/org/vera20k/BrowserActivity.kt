package org.vera20k

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * An in-app browser for the community resource site.
 *
 * The point of keeping it inside the app is the download: whatever the page
 * hands over — 7z, ZIP, or a single loose file — lands in this app's own
 * `filesDir/.download-*` instead of the phone's Download folder, and the
 * activity returns that path so the launcher can ask whether it is the game
 * or a mod. Nothing here installs, executes or unpacks by itself.
 *
 * Two download paths exist because pages do not all behave the same:
 *
 *  - a plain link (Content-Disposition or a normal href) is fetched here with
 *    the page's cookies and referer, so hotlink checks on the site still pass;
 *  - a `blob:` or `data:` href never reaches DownloadListener at all, so a
 *    small injected script reads it in slices and hands the bytes over through
 *    [BlobBridge] instead.
 *
 * Browsing is deliberately narrow: only http(s) is followed. `intent://`,
 * `market://` and the rest are refused rather than handed to the system, since
 * a resource site is exactly where those get pushed.
 */
class BrowserActivity : Activity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FILE = "file"

        /** The community site, and the only entry point the launcher offers. */
        const val START_URL = "https://www.mycnc.net/"

        /** Slices handed across the JS bridge; big enough to be quick, small
         *  enough that the base64 string does not blow the binder limit. */
        private const val BLOB_SLICE = 512 * 1024
    }

    private lateinit var web: WebView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var busy: LinearLayout
    private lateinit var busyLabel: TextView
    private lateinit var busyBar: ProgressBar

    /** Where this session's downloads go; one folder, so cancel is one delete. */
    private lateinit var downloadDir: File

    /** Whether a file was handed back; false means the session left nothing. */
    private var handedOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COL_BG
        window.navigationBarColor = COL_BG

        downloadDir = File(filesDir, ".download-" + System.currentTimeMillis()).apply { mkdirs() }

        setContentView(buildLayout())
        configureWeb()
        val start = intent?.getStringExtra(EXTRA_URL) ?: START_URL
        web.loadUrl(start)
    }

    // --- layout -------------------------------------------------------------

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(6))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            sp(20f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(square(COL_SURFACE_HI, 11))
            contentDescription = "返回"
            setOnClickListener { goBack() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        title = TextView(this).apply {
            text = "资源站"
            sp(16f)
            setTextColor(COL_TEXT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitle = TextView(this).apply {
            text = "第三方站点 · 下载会直接进入本应用的导入流程"
            sp(10f)
            setTextColor(COL_TEXT_DIM)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titles.addView(title)
        titles.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(this).apply {
            text = "外部"
            sp(11f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(pill(COL_SURFACE_HI, 10, COL_OUTLINE))
            contentDescription = "用系统浏览器打开"
            setOnClickListener { openOutside() }
        }, LinearLayout.LayoutParams(dp(48), dp(38)))
        root.addView(header)

        val frame = android.widget.FrameLayout(this)
        web = WebView(this).apply { setBackgroundColor(COL_BG) }
        frame.addView(web, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // A quiet strip over the page, not a dialog: downloads here can take
        // minutes and the page must stay readable underneath.
        busy = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = pill(COL_SURFACE_HI, 12, COL_OUTLINE)
            visibility = View.GONE
        }
        busyBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        busyBar.isIndeterminate = true
        busyLabel = TextView(this).apply {
            sp(11f)
            setTextColor(COL_TEXT)
        }
        busy.addView(busyBar, LinearLayout.LayoutParams(0, dp(4), 1f))
        busy.addView(busyLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(10) })
        frame.addView(busy, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ).apply { setMargins(dp(12), dp(8), dp(12), 0) })

        root.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return root
    }

    // --- webview ------------------------------------------------------------

    private fun configureWeb() {
        CookieManager.getInstance().setAcceptCookie(true)
        @Suppress("SetJavaScriptEnabled")
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString + " VeraZip"
        }
        web.setBackgroundColor(COL_BG)
        web.addJavascriptInterface(BlobBridge(), "VeraBlob")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                // Everything else ("intent://", "market://", "weixin://") stays
                // outside: this is a reader for a resource site, not a launcher.
                toast("已拦截非网页跳转：" + request.url.scheme)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                subtitle.text = hostOf(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectBlobHook()
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                // No "proceed anyway": a certificate failure is the one case
                // where browsing and downloading should both stop.
                handler.cancel()
                toast("证书错误，已停止加载：" + error.url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, value: String?) {
                val shown = value?.takeIf { it.isNotBlank() } ?: return
                title.text = shown
            }
        }

        web.setDownloadListener(DownloadListener { url, agent, disposition, mime, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                // The injected hook normally catches these before the click
                // reaches the page; this is only the fallback message.
                toast("页面内生成的文件没能取回，请在网页里长按链接另存")
                return@DownloadListener
            }
            downloadDirect(url, agent, disposition, mime)
        })
    }

    private fun goBack() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goBack()
    }

    private fun openOutside() {
        val url = web.url ?: START_URL
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("没有可用的浏览器") }
    }

    // --- direct downloads ---------------------------------------------------

    /**
     * Fetch a link ourselves instead of using DownloadManager.
     *
     * DownloadManager writes to the public Downloads folder and hands back a
     * content URI; the game folder is private, and a copy out of a public
     * folder needs permissions this app does not ask for. Fetching here keeps
     * the file private from the first byte, and lets the page's cookies and
     * referer come along.
     */
    private fun downloadDirect(url: String, agent: String?, disposition: String?, mime: String?) {
        val name = fileName(disposition, url, mime)
        busyLabel.text = "正在下载 $name"
        busy.visibility = View.VISIBLE
        busyBar.isIndeterminate = true
        Thread {
            var saved: File? = null
            var failure: String? = null
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    agent ?: "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 VeraZip",
                )
                CookieManager.getInstance().getCookie(url)?.let {
                    connection.setRequestProperty("Cookie", it)
                }
                web.url?.let { connection.setRequestProperty("Referer", it) }
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = connection.contentLengthLong
                val target = File(downloadDir, name)
                connection.inputStream.use { input ->
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(1 shl 16)
                        var copied = 0L
                        var lastPaint = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastPaint > 250) {
                                lastPaint = now
                                paintDownload(name, copied, total)
                            }
                        }
                    }
                }
                if (target.length() == 0L) throw IOException("下载到的是空文件")
                saved = target
            } catch (error: Throwable) {
                failure = error.message ?: "下载失败"
            }
            val done = saved
            runOnUiThread {
                busy.visibility = View.GONE
                if (done != null) finishWith(done) else {
                    toast("下载失败：$failure")
                    Importers.deleteTree(downloadDir)
                }
            }
        }.start()
    }

    private fun paintDownload(name: String, copied: Long, total: Long) {
        val label = if (total > 0) {
            "正在下载 $name\n" + (copied shr 20) + " / " + (total shr 20) + " MB"
        } else {
            "正在下载 $name\n" + (copied shr 20) + " MB"
        }
        runOnUiThread {
            busyLabel.text = label.replace("\n", "  ")
            if (total > 0) {
                busyBar.isIndeterminate = false
                busyBar.progress = ((copied * 100) / total).toInt().coerceIn(0, 100)
            }
        }
    }

    /** The name a browser would save under, made safe for a private folder. */
    private fun fileName(disposition: String?, url: String, mime: String?): String {
        val fromHeader = disposition?.let { header ->
            // filename*=UTF-8''percent-encoded comes first, filename="…" second.
            val encoded = Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.getOrNull(1)
            val plain = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.getOrNull(1)
                ?: Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
                    .find(header)?.groupValues?.getOrNull(1)
            encoded?.let { runCatching { java.net.URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull() }
                ?: plain?.trim()?.trim('"')
        }
        val guessed = fromHeader?.takeIf { it.isNotBlank() }
            ?: URLUtil.guessFileName(url, disposition, mime)
        var safe = sanitize(guessed)
        if (safe.isBlank()) safe = "download"
        // A name without an extension would look like an opaque blob in the
        // import sheet; borrow one from the mime type when the site omits it.
        if (!safe.contains('.')) {
            val ext = when (mime) {
                "application/x-7z-compressed" -> ".7z"
                "application/zip", "application/x-zip-compressed" -> ".zip"
                "application/x-rar-compressed" -> ".rar"
                else -> ""
            }
            safe += ext
        }
        return safe.take(120)
    }

    /** Strips a name down to what may sit inside a private folder. */
    private fun sanitize(raw: String): String = raw
        .replace('\\', '_')
        .replace('/', '_')
        .filter { !it.isISOControl() }
        .trim()
        .trimStart('.')
        .take(120)

    // --- blob downloads -----------------------------------------------------

    /**
     * Hands `blob:` files over from the page.
     *
     * Called on a binder thread, so the writes happen here without touching
     * the UI; the page sends base64 slices one after another and a `done` call
     * closes the file.
     */
    /** One open blob file: the stream the slices go into, and where it lives. */
    private class BlobEntry(val stream: java.io.OutputStream, val file: File)

    private inner class BlobBridge {

        private val open = HashMap<Int, BlobEntry>()
        private var next = 1

        @JavascriptInterface
        fun begin(name: String?, size: Long): Int {
            val safe = sanitize(name ?: "download.bin").ifBlank { "download.bin" }
            val file = File(downloadDir, safe)
            val entry = BlobEntry(file.outputStream(), file)
            val id = synchronized(open) {
                val index = next++
                open[index] = entry
                index
            }
            runOnUiThread {
                busyBar.isIndeterminate = true
                busyLabel.text = "正在取回 ${file.name}"
                busy.visibility = View.VISIBLE
            }
            return id
        }

        @JavascriptInterface
        fun chunk(id: Int, base64: String?) {
            if (base64.isNullOrEmpty()) return
            val entry = synchronized(open) { open[id] } ?: return
            try {
                entry.stream.write(Base64.decode(base64, Base64.DEFAULT))
            } catch (_: Throwable) {
                // A bad slice leaves the entry open; the page's fail() closes
                // and removes the file.
            }
        }

        @JavascriptInterface
        fun done(id: Int) {
            val entry = synchronized(open) { open.remove(id) } ?: return
            runCatching { entry.stream.close() }
            val file = entry.file
            runOnUiThread {
                busy.visibility = View.GONE
                if (file.length() > 0) {
                    finishWith(file)
                } else {
                    file.delete()
                    toast("页面内文件是空的，站点可能要求登录")
                }
            }
        }

        @JavascriptInterface
        fun fail(id: Int, message: String?) {
            val entry = synchronized(open) { open.remove(id) }
            if (entry != null) {
                runCatching { entry.stream.close() }
                entry.file.delete()
            }
            runOnUiThread {
                busy.visibility = View.GONE
                toast("页面内文件取回失败：" + (message ?: "未知原因"))
            }
        }
    }

    private fun finishWith(file: File) {
        handedOff = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_FILE, file.absolutePath))
        finish()
    }

    /**
     * A session closed without handing anything over (back, or a failed
     * download) leaves an empty folder behind otherwise.
     */
    override fun onDestroy() {
        if (!handedOff) Importers.deleteTree(downloadDir)
        super.onDestroy()
    }

    private fun hostOf(url: String?): String = try {
        Uri.parse(url ?: "").host?.removePrefix("www.") ?: "第三方站点"
    } catch (_: Throwable) {
        "第三方站点"
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    // --- the page-side hook -------------------------------------------------

    /**
     * Reads `blob:`/`data:` downloads in slices.
     *
     * Installed on every page finish (the object is visible only to this
     * WebView). It runs in the page's own context, so `fetch` there can read
     * the blob; reading it in Kotlin is impossible, since the blob lives in
     * the renderer.
     */
    private fun injectBlobHook() {
        val script = """
            (function(){
              if (window.__veraBlobHook) return;
              window.__veraBlobHook = true;
              var SLICE = $BLOB_SLICE;
              function grab(url, name){
                fetch(url, {credentials:'include'}).then(function(r){ return r.blob(); })
                  .then(function(blob){
                    var id = VeraBlob.begin(name || 'download.bin', blob.size);
                    var pos = 0;
                    (function next(){
                      if (pos >= blob.size) { VeraBlob.done(id); return; }
                      var slice = blob.slice(pos, Math.min(pos + SLICE, blob.size));
                      var reader = new FileReader();
                      reader.onload = function(){
                        var text = String(reader.result);
                        var comma = text.indexOf(',');
                        VeraBlob.chunk(id, comma >= 0 ? text.substring(comma + 1) : '');
                        pos += SLICE;
                        next();
                      };
                      reader.onerror = function(){ VeraBlob.fail(id, '读取分片失败'); };
                      reader.readAsDataURL(slice);
                    })();
                  })
                  .catch(function(e){ VeraBlob.fail(0, String(e)); });
              }
              document.addEventListener('click', function(ev){
                var a = ev.target && ev.target.closest ? ev.target.closest('a') : null;
                if (!a || !a.href) return;
                if (a.href.indexOf('blob:') === 0 || a.href.indexOf('data:') === 0) {
                  ev.preventDefault();
                  ev.stopPropagation();
                  var name = a.getAttribute('download') || a.download || 'download.bin';
                  grab(a.href, name);
                }
              }, true);
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }
}
