package com.sandbox6x

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private lateinit var dash: WebView
    private lateinit var holder: FrameLayout
    private lateinit var webs: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var lockView: FrameLayout

    private val sessions = HashMap<Int, WebView>()
    private var current = 0
    private var dashReady = false
    private var pendingSlot = 0
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var micReq: PermissionRequest? = null
    private var foreground = false

    // Paylaşım / dış kaynaklı içerik
    private var shareText: String? = null
    private var shareUri: Uri? = null
    private var shareAnnouncePending = false
    private var pendingFileUri: Uri? = null

    private var needsLock = false
    private var credInFlight = false
    private var leftAt = 0L

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        val uris: Array<Uri>? = if (r.resultCode == RESULT_OK && d != null) {
            val cd = d.clipData
            if (cd != null && cd.itemCount > 0) Array(cd.itemCount) { i -> cd.getItemAt(i).uri }
            else d.data?.let { arrayOf(it) }
        } else null
        fileCb?.onReceiveValue(uris)
        fileCb = null
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micReq?.let { if (ok) it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else it.deny() }
        micReq = null
    }
    private val termuxPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        dash.evaluateJavascript("window.onNativeTermuxPerm && window.onNativeTermuxPerm($ok)", null)
    }
    private val credLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        credInFlight = false
        leftAt = SystemClock.elapsedRealtime()
        if (r.resultCode == RESULT_OK) {
            needsLock = false
            lockView.visibility = View.GONE
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private val genSince = HashMap<Int, Long>()

    // Tüm oturumları (arka plandakiler dahil) tarar: yanıt durumu her 3 sn, limit metni her 9 sn.
    private val scanner = object : Runnable {
        override fun run() {
            tick++
            val doLimit = tick % 3 == 0
            for ((id, w) in sessions.entries.toList()) {
                w.evaluateJavascript(GEN_JS) { r -> onGeneration(id, r == "true") }
                if (doLimit) {
                    w.evaluateJavascript(SCAN_JS) { r ->
                        dash.evaluateJavascript(
                            "window.onNativeLimitText && window.onNativeLimitText($id, ${JSONObject.quote(decodeJs(r))})", null
                        )
                    }
                }
            }
            handler.postDelayed(this, 3000)
        }
    }

    private fun onGeneration(id: Int, generating: Boolean) {
        val since = genSince[id]
        if (generating) {
            if (since == null) genSince[id] = System.currentTimeMillis()
            return
        }
        if (since == null) return
        genSince.remove(id)
        val longEnough = System.currentTimeMillis() - since >= 5000
        val watching = foreground && holder.visibility == View.VISIBLE && current == id
        if (longEnough && !watching && prefs.getBoolean("gennotify", true)) {
            ResetReceiver.show(
                this, id, Automation.label(this, id) + " yanıtı tamamladı",
                "Claude cevabını bitirdi. Açmak için dokun.", "done", "Yanıt tamamlandı"
            )
        }
    }

    private fun decodeJs(r: String?): String = try {
        (JSONTokener(r ?: "null").nextValue() as? String) ?: ""
    } catch (_: Exception) { "" }

    override fun onResume() {
        super.onResume()
        foreground = true
        Alarms.audit(this)
        handler.removeCallbacks(scanner)
        handler.postDelayed(scanner, 3000)
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    private val multiProfile get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    private fun lockEnabled() = prefs.getBoolean("lock", false)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun copyText(label: String, t: String) =
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, t))

    private fun applySecure() {
        if (prefs.getBoolean("secure", false)) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecure()

        val root = FrameLayout(this)
        dash = WebView(this)
        webs = FrameLayout(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(0xFFD97757.toInt())
        }
        holder = FrameLayout(this).apply { visibility = View.GONE }
        holder.addView(webs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        lockView = FrameLayout(this).apply {
            setBackgroundColor(0xFF0C0C0B.toInt())
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(context).apply {
                    text = "Claude Sandbox 6X kilitli"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER
                })
                addView(Button(context).apply {
                    text = "Kilidi Aç"
                    setOnClickListener { promptUnlock() }
                })
            }
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        root.addView(dash, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(lockView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        dash.settings.javaScriptEnabled = true
        dash.settings.domStorageEnabled = true
        dash.setBackgroundColor(0xFF0C0C0B.toInt())
        dash.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                dashReady = true
                deliverPending()
            }
        }
        dash.addJavascriptInterface(Bridge(), "AndroidBridge")
        Notes.onChange = {
            runOnUiThread { dash.evaluateJavascript("window.onNativeNotes && window.onNativeNotes()", null) }
        }
        Automation.onChange = {
            runOnUiThread { dash.evaluateJavascript("window.onNativeCommands && window.onNativeCommands()", null) }
        }
        Termux.onResult = { id, out, err, code, msg ->
            runOnUiThread {
                dash.evaluateJavascript(
                    "window.onNativeTermux && window.onNativeTermux($id, ${JSONObject.quote(out)}, ${JSONObject.quote(err)}, $code, ${JSONObject.quote(msg)})",
                    null
                )
            }
        }
        dash.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    lockView.visibility == View.VISIBLE -> Unit
                    holder.visibility == View.VISIBLE -> {
                        val w = sessions[current]
                        if (w != null && w.canGoBack()) w.goBack()
                        else dash.evaluateJavascript("closeFS()", null)
                    }
                    else -> {
                        // Ana ekranda iken önce açık pencereleri (terminal vb.) kapatmayı dene
                        dash.evaluateJavascript("window.onNativeBack ? window.onNativeBack() : false") { r ->
                            if (r != "true") {
                                isEnabled = false
                                onBackPressedDispatcher.onBackPressed()
                            }
                        }
                    }
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        needsLock = lockEnabled()
        handleIntent(intent)
    }

    // ---- Dış kaynaklı intent'ler: bildirim, kısayol, widget, paylaşım, otomasyon ----
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            "com.sandbox6x.SHARE" -> {
                shareText = i.getStringExtra("share_text")
                shareUri = i.getStringExtra("share_uri")?.let { Uri.parse(it) }
                if (shareText != null || shareUri != null) shareAnnouncePending = true
            }
            Automation.ACT_OPEN -> if (Automation.valid(this, i)) pendingSlot = Automation.num(i, "slot")
            else -> pendingSlot = i.getIntExtra("slot", 0)
        }
        deliverPending()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun deliverPending() {
        if (!dashReady) return
        if (pendingSlot > 0) {
            dash.evaluateJavascript("window.onNativeOpenSlot && window.onNativeOpenSlot($pendingSlot)", null)
            pendingSlot = 0
        }
        if (shareAnnouncePending) {
            shareAnnouncePending = false
            val preview = (shareText ?: "") + (if (shareUri != null) "\n[Ek dosya/resim]" else "")
            dash.evaluateJavascript("window.onNativeShare && window.onNativeShare(${JSONObject.quote(preview.take(300))})", null)
        }
    }

    // ---- Kilit ----
    private fun promptUnlock() {
        if (credInFlight) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val i = if (lockEnabled() && km.isDeviceSecure)
            km.createConfirmDeviceCredentialIntent("Claude Sandbox 6X", "Devam etmek için kilidi aç")
        else null
        if (i == null) {
            needsLock = false
            lockView.visibility = View.GONE
            return
        }
        lockView.visibility = View.VISIBLE
        credInFlight = true
        credLauncher.launch(i)
    }

    override fun onStart() {
        super.onStart()
        if (credInFlight) return
        if (!lockEnabled()) {
            needsLock = false
            lockView.visibility = View.GONE
            return
        }
        if (!needsLock && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > 30_000) needsLock = true
        if (needsLock) {
            lockView.visibility = View.VISIBLE
            promptUnlock()
        }
    }

    override fun onStop() {
        leftAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    // ---- Dosya seçici ----
    private fun buildPickIntent(params: WebChromeClient.FileChooserParams): Intent {
        val mimes = params.acceptTypes.filter { it.contains("/") }
        val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (mimes.size == 1) mimes[0] else "*/*"
        if (mimes.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        return Intent.createChooser(i, "Dosya seç")
    }

    // ---- Metin / dosya kaydetme ----
    private fun fetchBlob(id: Int, url: String) {
        val w = sessions[id]
        if (w == null || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            toast("Bu cihazın WebView'i blob indirmeyi desteklemiyor")
            return
        }
        val js = "(async function(u){try{var b=(window.__getBlob&&window.__getBlob(u))||await (await fetch(u)).blob();" +
            "var n=window.__lastName||'';var rd=new FileReader();" +
            "rd.onloadend=function(){NativeBlob.postMessage(JSON.stringify({name:n,mime:b.type||'',data:String(rd.result).split(',')[1]||''}))};" +
            "rd.readAsDataURL(b)}catch(e){NativeBlob.postMessage(JSON.stringify({error:String(e)}))}})(" +
            JSONObject.quote(url) + ")"
        w.evaluateJavascript(js, null)
    }

    private fun saveDataUrl(url: String) {
        try {
            val comma = url.indexOf(',')
            val header = url.substring(5, comma)
            val mime = header.substringBefore(';')
            val body = url.substring(comma + 1)
            val bytes = if (header.contains("base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
            var name = "claude-" + System.currentTimeMillis()
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name += ".$it" }
            finishSave(name, mime, bytes)
        } catch (_: Exception) { toast("Dosya kaydedilemedi") }
    }

    private fun onBlobMessage(raw: String?) {
        if (raw == null) return
        try {
            val o = JSONObject(raw)
            if (o.has("error")) { toast("Dosya okunamadı"); return }
            val mime = o.optString("mime")
            val data = o.optString("data")
            if (data.isEmpty()) { toast("Boş dosya"); return }
            var name = o.optString("name")
            if (name.isBlank()) name = "claude-" + System.currentTimeMillis()
            if (!name.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name += ".$it" }
            finishSave(name.replace(Regex("[\\\\/:*?\"<>|]"), "_"), mime, Base64.decode(data, Base64.DEFAULT))
        } catch (_: Exception) { toast("Dosya kaydedilemedi") }
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray): Pair<String, Uri?>? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Pair("İndirilenler/$name", uri)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
                val f = File(dir, name)
                f.writeBytes(bytes)
                Pair(f.path, null)
            }
        } catch (_: Exception) { null }
    }

    private fun finishSave(name: String, mime: String, bytes: ByteArray) {
        val r = saveBytes(name, mime, bytes)
        if (r == null) { toast("Dosya kaydedilemedi"); return }
        val uri = r.second
        Files.add(this, name, uri?.toString() ?: r.first, mime, bytes.size)
        val b = AlertDialog.Builder(this).setTitle("Kaydedildi").setMessage(r.first).setNegativeButton("Tamam", null)
        if (uri != null) {
            b.setPositiveButton("Aç") { _, _ -> viewUri(uri, mime) }
            b.setNeutralButton("Paylaş") { _, _ -> shareUriOut(uri, mime) }
        }
        b.show()
    }

    private fun viewUri(uri: Uri, mime: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifEmpty { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) { toast("Açacak uygulama yok") }
    }

    private fun shareUriOut(uri: Uri, mime: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType(mime.ifEmpty { "*/*" })
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Paylaş"))
        } catch (_: Exception) { toast("Paylaşılamadı") }
    }

    private fun fileUri(u: String): Uri? {
        if (u.startsWith("dm:")) {
            val id = u.substring(3).toLongOrNull() ?: return null
            return (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(id)
        }
        return if (u.startsWith("content://")) Uri.parse(u) else null
    }

    // ---- İndirme: telefona indir veya harici indirme uygulamasına gönder ----
    private fun handleDownload(id: Int, url: String, ua: String, cd: String?, mime: String?) {
        if (url.startsWith("blob:")) { fetchBlob(id, url); return }
        if (url.startsWith("data:")) { saveDataUrl(url); return }
        when (prefs.getString("dlmode", "ask")) {
            "builtin" -> startBuiltinDownload(id, url, ua, cd, mime)
            "external" -> openExternal(url)
            else -> {
                val name = URLUtil.guessFileName(url, cd, mime)
                AlertDialog.Builder(this)
                    .setTitle("İndirme")
                    .setMessage(name)
                    .setPositiveButton("Telefona indir") { _, _ -> startBuiltinDownload(id, url, ua, cd, mime) }
                    .setNeutralButton("Harici uygulama") { _, _ -> openExternal(url) }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    private fun startBuiltinDownload(id: Int, url: String, ua: String, cd: String?, mime: String?) {
        try {
            val cm = if (multiProfile) ProfileStore.getInstance().getOrCreateProfile("slot_$id").cookieManager
                     else CookieManager.getInstance()
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Cookie", cm.getCookie(url) ?: "")
                .addRequestHeader("User-Agent", ua)
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val did = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Files.add(this, name, "dm:$did", mime ?: "", 0)
            toast("İndirme başladı")
        } catch (_: Exception) {
            toast("İndirme başlatılamadı")
        }
    }

    private fun openExternal(url: String) {
        copyText("url", url)
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "İndirme uygulamasını seç"))
            toast("Bağlantı panoya da kopyalandı")
        } catch (_: Exception) {
            toast("Uygun uygulama bulunamadı, bağlantı panoya kopyalandı")
        }
    }

    // ---- Metni sohbet kutusuna yaz (paylaşım) ----
    private fun injectText(id: Int, text: String, attempt: Int = 0) {
        if (attempt > 14) {
            copyText("claude", text)
            toast("Metin panoya kopyalandı, mesaj kutusuna yapıştır")
            return
        }
        val w = sessions[id]
        if (w == null) {
            handler.postDelayed({ injectText(id, text, attempt + 1) }, 1000)
            return
        }
        val js = "(function(t){try{var el=document.querySelector('div[contenteditable=\"true\"]')||document.querySelector('textarea');" +
            "if(!el)return 'no';el.focus();" +
            "if(el.tagName==='TEXTAREA'){var s=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;s.call(el,t);el.dispatchEvent(new Event('input',{bubbles:true}));}" +
            "else{document.execCommand('insertText',false,t);}return 'ok'}catch(e){return 'err'}})(" + JSONObject.quote(text) + ")"
        w.evaluateJavascript(js) { r ->
            if (r == null || !r.contains("ok")) handler.postDelayed({ injectText(id, text, attempt + 1) }, 1000)
        }
    }

    // ---- Oturum WebView'leri ----
    @SuppressLint("SetJavaScriptEnabled")
    private fun sessionView(id: Int): WebView = sessions.getOrPut(id) {
        val w = WebView(this)
        if (multiProfile) {
            ProfileStore.getInstance().getOrCreateProfile("slot_$id")
            WebViewCompat.setProfile(w, "slot_$id")
        }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // "wv" işaretini kaldır: bazı giriş sayfaları WebView'i reddeder
            userAgentString = userAgentString.replace("; wv", "")
        }
        w.setBackgroundColor(0xFF0C0C0B.toInt())
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val host = u.host ?: return false
                val inside = u.scheme == "https" && listOf(
                    "claude.ai", "anthropic.com", "google.com", "gstatic.com", "apple.com", "googleusercontent.com"
                ).any { host == it || host.endsWith(".$it") }
                if (inside) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.loadDataWithBaseURL(null, ERR_HTML, "text/html", "utf-8", null)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                // Paylaşılan resim varsa seçici açmadan doğrudan ver
                val pu = pendingFileUri
                if (pu != null) {
                    pendingFileUri = null
                    cb.onReceiveValue(arrayOf(pu))
                    return true
                }
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { picker.launch(buildPickIntent(params)); true } catch (_: Exception) { fileCb = null; false }
            }

            override fun onProgressChanged(view: WebView, p: Int) {
                if (view === sessions[current]) {
                    progress.progress = p
                    progress.visibility = if (p < 100) View.VISIBLE else View.GONE
                }
            }

            override fun onPermissionRequest(req: PermissionRequest) {
                val host = req.origin.host ?: ""
                val okHost = host == "claude.ai" || host.endsWith(".claude.ai")
                val audio = req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!okHost || !audio) { req.deny(); return }
                runOnUiThread {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    else {
                        micReq = req
                        micPerm.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
        w.setDownloadListener { url, ua, cd, mime, _ -> handleDownload(id, url, ua, cd, mime) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(w, HOOK_JS, setOf("https://claude.ai"))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.addWebMessageListener(w, "NativeBlob", setOf("https://claude.ai")) { _, message, _, _, _ ->
                onBlobMessage(message.data)
            }
        w.loadUrl("https://claude.ai/")
        webs.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w
    }

    private fun postUpdate(r: JSONObject) {
        runOnUiThread {
            dash.evaluateJavascript("window.onNativeUpdate && window.onNativeUpdate(${JSONObject.quote(r.toString())})", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun supported(): Boolean = multiProfile

        // ---- Bildirim geçmişi ----
        @JavascriptInterface fun getNotes(): String = Notes.all(this@MainActivity)
        @JavascriptInterface fun markRead(slot: Int) { Notes.markRead(this@MainActivity, slot) }
        @JavascriptInterface fun clearNotes() { Notes.clear(this@MainActivity) }
        @JavascriptInterface fun notifyNow(slot: Int, title: String, body: String) {
            ResetReceiver.show(this@MainActivity, slot, title, body)
        }

        // ---- Durum yayını: widget, kutucuk, kısayollar ----
        @JavascriptInterface fun publishState(json: String) {
            prefs.edit().putString("state", json).apply()
            SlotWidget.refresh(this@MainActivity)
            SlotTileService.refresh(this@MainActivity)
            try { Shortcuts.publishDynamic(this@MainActivity, JSONArray(json)) } catch (_: Exception) { }
        }

        @JavascriptInterface fun pinShortcut(id: Int, label: String, color: String, emoji: String) = runOnUiThread {
            toast(
                if (Shortcuts.pin(this@MainActivity, id, label, color, emoji)) "Kısayol ekleme isteği gönderildi"
                else "Bu başlatıcı kısayol eklemeyi desteklemiyor"
            )
        }

        // ---- Sohbet: bağlam, dışa aktarma ----
        @JavascriptInterface fun grabContext(id: Int) = runOnUiThread {
            val w = sessions[id]
            if (w == null) {
                dash.evaluateJavascript("window.onNativeContext && window.onNativeContext('')", null)
                return@runOnUiThread
            }
            w.evaluateJavascript(CTX_JS) { r ->
                dash.evaluateJavascript(
                    "window.onNativeContext && window.onNativeContext(${JSONObject.quote(decodeJs(r))})", null
                )
            }
        }

        @JavascriptInterface fun exportChat(id: Int, label: String) = runOnUiThread {
            val w = sessions[id]
            if (w == null) { toast("Önce bu hesabın oturumunu aç"); return@runOnUiThread }
            w.evaluateJavascript(EXPORT_JS) { r ->
                val md = decodeJs(r)
                if (md.isBlank()) { toast("Sohbet metni okunamadı"); return@evaluateJavascript }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                val header = md + "\n\n---\n_" + label + " • " + stamp + "_\n"
                finishSave("Claude-" + label.replace(Regex("[^A-Za-z0-9]+"), "") + "-" + stamp + ".md", "text/markdown", header.toByteArray())
            }
        }

        // ---- Paylaşım ----
        @JavascriptInterface fun deliverShare(slotId: Int) = runOnUiThread {
            val t = shareText
            val u = shareUri
            pendingFileUri = u
            dash.evaluateJavascript("openFS($slotId)", null)
            if (t != null) injectText(slotId, t)
            else if (u != null) toast("Ataş düğmesine dokun: paylaşılan dosya otomatik eklenecek")
            shareText = null
            shareUri = null
        }

        // ---- Dosyalar ----
        @JavascriptInterface fun getFiles(): String = Files.all(this@MainActivity)

        @JavascriptInterface fun openFile(idx: Int) = runOnUiThread {
            val f = Files.get(this@MainActivity, idx) ?: return@runOnUiThread
            val uri = fileUri(f.optString("uri"))
            if (uri == null) toast("Dosya hazır değil: " + f.optString("uri")) else viewUri(uri, f.optString("mime"))
        }

        @JavascriptInterface fun shareFile(idx: Int) = runOnUiThread {
            val f = Files.get(this@MainActivity, idx) ?: return@runOnUiThread
            val uri = fileUri(f.optString("uri"))
            if (uri == null) toast("Dosya hazır değil") else shareUriOut(uri, f.optString("mime"))
        }

        @JavascriptInterface fun deleteFile(idx: Int) = runOnUiThread {
            val f = Files.get(this@MainActivity, idx) ?: return@runOnUiThread
            val u = f.optString("uri")
            try {
                if (u.startsWith("dm:")) {
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(u.substring(3).toLong())
                } else if (u.startsWith("content://")) {
                    contentResolver.delete(Uri.parse(u), null, null)
                }
            } catch (_: Exception) { }
            Files.remove(this@MainActivity, idx)
        }

        // ---- Ayarlar / sağlık ----
        @JavascriptInterface fun getSettings(): String {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
            return JSONObject()
                .put("lock", lockEnabled())
                .put("secure", prefs.getBoolean("secure", false))
                .put("profiles", multiProfile)
                .put("deviceSecure", km.isDeviceSecure)
                .put("exact", Alarms.canExact(this@MainActivity))
                .put("dl", prefs.getString("dlmode", "ask"))
                .put("alarmclock", prefs.getBoolean("alarmclock", true))
                .put("gennotify", prefs.getBoolean("gennotify", true))
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .put("overlay", Settings.canDrawOverlays(this@MainActivity))
                .put("token", Automation.token(this@MainActivity))
                .put("repo", prefs.getString("repo", ""))
                .put("tokenSet", !prefs.getString("ghtoken", "").isNullOrEmpty())
                .put("version", ver)
                .put("lateCount", prefs.getInt("lateCount", 0))
                .put("lastLate", prefs.getLong("lastLate", 0L) / 60000)
                .put("pending", Alarms.pendingCount(this@MainActivity))
                .put("webview", try { WebView.getCurrentWebViewPackage()?.versionName ?: "?" } catch (_: Throwable) { "?" })
                .toString()
        }

        @JavascriptInterface fun setSetting(key: String, v: Boolean) = runOnUiThread {
            if (key == "lock" || key == "secure" || key == "alarmclock" || key == "gennotify") {
                prefs.edit().putBoolean(key, v).apply()
                if (key == "secure") applySecure()
            }
        }

        @JavascriptInterface fun setDownloadMode(mode: String) {
            if (mode == "ask" || mode == "builtin" || mode == "external") prefs.edit().putString("dlmode", mode).apply()
        }

        @JavascriptInterface fun openExactAlarmSettings() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (_: Exception) { }
            }
        }

        @JavascriptInterface fun requestBattery() = runOnUiThread {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
            }
        }

        // ---- Otomasyon (Tasker / MacroDroid) ----
        @JavascriptInterface fun newToken(): String = Automation.newToken(this@MainActivity)
        @JavascriptInterface fun takeCommands(): String = Automation.take(this@MainActivity)

        // ---- Güncelleme ----
        @JavascriptInterface fun setUpdateConfig(repo: String, token: String) {
            val e = prefs.edit().putString("repo", repo.trim())
            if (token.isNotBlank()) e.putString("ghtoken", token.trim())
            e.apply()
        }

        @JavascriptInterface fun clearUpdateToken() { prefs.edit().remove("ghtoken").apply() }

        @JavascriptInterface fun checkUpdate() {
            val repo = prefs.getString("repo", "") ?: ""
            if (repo.isBlank()) {
                postUpdate(JSONObject().put("ok", false).put("error", "Depo adı girilmemiş (kullanıcı/depo)"))
                return
            }
            val token = prefs.getString("ghtoken", "") ?: ""
            Thread { postUpdate(Updater.check(this@MainActivity, repo, token)) }.start()
        }

        @JavascriptInterface fun downloadUpdate(assetUrl: String, pageUrl: String) {
            val token = prefs.getString("ghtoken", "") ?: ""
            if (assetUrl.isBlank()) {
                runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))) } catch (_: Exception) { } }
                return
            }
            runOnUiThread { toast("Güncelleme indiriliyor…") }
            Thread {
                val bytes = Updater.download(token, assetUrl)
                runOnUiThread {
                    if (bytes == null) toast("İndirilemedi. Sürüm sayfasını tarayıcıda aç.")
                    else finishSave("ClaudeSandbox6X-guncelleme.apk", "application/vnd.android.package-archive", bytes)
                }
            }.start()
        }

        // ---- Yüzen pencere ----
        @JavascriptInterface fun openFloating(id: Int, label: String) = runOnUiThread {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                toast("'Diğer uygulamaların üzerinde göster' iznini ver, sonra tekrar dene")
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) { }
                return@runOnUiThread
            }
            val i = Intent(this@MainActivity, FloatingService::class.java).putExtra("slot", id).putExtra("label", label)
            ContextCompat.startForegroundService(this@MainActivity, i)
            moveTaskToBack(true)
        }

        // ---- Termux köprüsü ----
        @JavascriptInterface fun termuxStatus(): String {
            val installed = try { packageManager.getPackageInfo(Termux.PKG, 0); true } catch (_: Exception) { false }
            val perm = ContextCompat.checkSelfPermission(this@MainActivity, Termux.PERM) == PackageManager.PERMISSION_GRANTED
            return JSONObject().put("installed", installed).put("perm", perm).toString()
        }

        @JavascriptInterface fun termuxRequestPerm() = runOnUiThread { termuxPerm.launch(Termux.PERM) }

        @JavascriptInterface fun termuxOpen() = runOnUiThread {
            val i = packageManager.getLaunchIntentForPackage(Termux.PKG)
            if (i == null) toast("Termux kurulu değil") else startActivity(i)
        }

        @JavascriptInterface fun termuxRun(execId: Int, cmd: String) = runOnUiThread {
            val pi = PendingIntent.getBroadcast(
                this@MainActivity, execId,
                Intent(this@MainActivity, TermuxResultReceiver::class.java).putExtra("execId", execId),
                PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            )
            val i = Intent("com.termux.RUN_COMMAND")
                .setClassName(Termux.PKG, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            try {
                ContextCompat.startForegroundService(this@MainActivity, i)
            } catch (e: Exception) {
                dash.evaluateJavascript(
                    "window.onNativeTermux && window.onNativeTermux($execId, '', '', -1, ${JSONObject.quote(e.message ?: "başlatılamadı")})", null
                )
            }
        }

        // ---- Oturum görünümü ----
        @JavascriptInterface fun showSession(id: Int, topCssPx: Float) = runOnUiThread {
            val top = ceil(topCssPx * resources.displayMetrics.density).toInt()
            (holder.layoutParams as FrameLayout.LayoutParams).topMargin = top
            holder.requestLayout()
            holder.visibility = View.VISIBLE
            val w = sessionView(id)
            sessions.forEach { (k, v) -> v.visibility = if (k == id) View.VISIBLE else View.GONE }
            if (current != id) progress.visibility = View.GONE
            current = id
            w.onResume()
        }

        @JavascriptInterface fun hideSession() = runOnUiThread { holder.visibility = View.GONE }
        @JavascriptInterface fun reload(id: Int) = runOnUiThread { sessions[id]?.reload() }

        @JavascriptInterface fun loadUrl(id: Int, url: String) = runOnUiThread {
            if (url.startsWith("https://")) sessionView(id).loadUrl(url)
            else toast("Yalnızca https:// bağlantıları")
        }

        @JavascriptInterface fun clearSlot(id: Int) = runOnUiThread {
            if (!multiProfile) {
                Toast.makeText(this@MainActivity, "Bu cihazın WebView'i profil desteklemiyor", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val p = ProfileStore.getInstance().getOrCreateProfile("slot_$id")
            p.cookieManager.removeAllCookies(null)
            p.webStorage.deleteAllData()
            sessions[id]?.let { it.clearCache(true); it.loadUrl("https://claude.ai/") }
        }

        @JavascriptInterface fun schedule(id: Int, epochMs: Double, title: String, body: String) {
            Alarms.schedule(this@MainActivity, id, epochMs.toLong(), title, body)
        }

        @JavascriptInterface fun cancel(id: Int) { Alarms.cancel(this@MainActivity, id) }

        @JavascriptInterface fun copy(text: String) = runOnUiThread { copyText("handoff", text) }
    }

    override fun onDestroy() {
        Notes.onChange = null
        Automation.onChange = null
        Termux.onResult = null
        handler.removeCallbacks(scanner)
        sessions.values.forEach { it.destroy() }
        dash.destroy()
        super.onDestroy()
    }

    companion object {
        private const val SCAN_JS = """(function(){try{var t=(document.body&&document.body.innerText)||'';t=t.slice(-4000);var re=/[^\n]{0,60}(limit|reset|resets|try again|tekrar|sıfırlan|aktif|until|kadar)[^\n]{0,80}/ig;var m,last='';while((m=re.exec(t))!==null){if(/\d{1,2}:\d{2}|\d+\s*(saat|dakika|hour|minute|min)/i.test(m[0]))last=m[0];}return last;}catch(e){return '';}})()"""
        private const val GEN_JS = """(function(){try{return !!document.querySelector('[data-is-streaming="true"],button[aria-label="Stop response"],button[aria-label="Stop Response"],button[aria-label="Yanıtı durdur"]');}catch(e){return false;}})()"""
        private const val HOOK_JS = """(function(){if(window.__bh)return;window.__bh=1;var m=new Map();var c=URL.createObjectURL;URL.createObjectURL=function(o){var u=c.call(URL,o);try{if(o instanceof Blob){m.set(u,o);if(m.size>10)m.delete(m.keys().next().value)}}catch(e){}return u};window.__getBlob=function(u){return m.get(u)};var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){try{if(this.download)window.__lastName=this.download}catch(e){}return oc.apply(this,arguments)};})();"""
        private const val CTX_JS = """(function(){try{var m=document.querySelector('main')||document.body;return (m.innerText||'').slice(-1500);}catch(e){return '';}})()"""
        private const val EXPORT_JS = """(function(){try{var t=document.title||'Claude';var nodes=document.querySelectorAll('[data-testid="user-message"],.font-claude-response');var out=[];nodes.forEach(function(n){var u=n.matches('[data-testid="user-message"]');var s=(n.innerText||'').trim();if(s)out.push((u?'**Sen:**\n':'**Claude:**\n')+s);});var body=out.length?out.join('\n\n---\n\n'):((document.querySelector('main')||document.body).innerText||'');return '# '+t+'\n\n'+body;}catch(e){return '';}})()"""
        private const val ERR_HTML = """<html><body style="background:#0C0C0B;color:#F5F5F2;font-family:sans-serif;text-align:center;padding:48px 24px">
<h3>Bağlantı kurulamadı</h3><p style="color:#9C9A92">İnternet bağlantını kontrol et.</p>
<p><a href="https://claude.ai/" style="color:#D97757;font-weight:bold;font-size:18px">Tekrar dene</a></p></body></html>"""
    }
}
