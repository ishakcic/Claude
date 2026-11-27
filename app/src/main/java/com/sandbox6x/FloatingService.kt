package com.sandbox6x

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Başka uygulamaların üstünde sürüklenebilir küçük Claude penceresi. */
class FloatingService : Service() {
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var web: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var sizeIdx = 1
    private var collapsed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        show(intent?.getIntExtra("slot", 1) ?: 1, intent?.getStringExtra("label") ?: "Claude")
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("float", "Yüzen pencere", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FloatingService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, "float")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Yüzen Claude penceresi açık")
            .setContentText("Kapatmak için dokun")
            .setContentIntent(stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 2001, n, type)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun show(slot: Int, label: String) {
        hide()
        val ctx = ContextThemeWrapper(applicationContext, R.style.AppTheme)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics

        val w = WebView(ctx)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile("slot_$slot")
            WebViewCompat.setProfile(w, "slot_$slot")
        }
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
        w.setBackgroundColor(0xFF0C0C0B.toInt())
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val host = u.host ?: return false
                val inside = u.scheme == "https" && listOf("claude.ai", "anthropic.com", "google.com", "gstatic.com", "apple.com")
                    .any { host == it || host.endsWith(".$it") }
                if (inside) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                return true
            }
        }
        w.loadUrl("https://claude.ai/")
        web = w

        fun btn(t: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { onClick() }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1C1C19.toInt())
            setPadding(dp(10), dp(6), dp(4), dp(6))
        }
        header.addView(TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(btn("▢") { toggleCollapse() })
        header.addView(btn("⇔") { cycleSize() })
        header.addView(btn("⤢") { openApp(slot) })
        header.addView(btn("✕") { stopSelf() })

        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF0C0C0B.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), 0xFFD97757.toInt())
            }
            clipToOutline = true
        }
        r.addView(header)
        r.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root = r

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            (dm.widthPixels * 0.85).toInt(), (dm.heightPixels * 0.6).toInt(),
            type, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(80)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        params = p

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - tx).toInt()
                    p.y = sy + (e.rawY - ty).toInt()
                    wm?.updateViewLayout(r, p)
                    true
                }
                else -> false
            }
        }
        wm?.addView(r, p)
    }

    private fun applySize() {
        val p = params ?: return
        val dm = resources.displayMetrics
        val f = when (sizeIdx) { 0 -> 0.7 to 0.4; 1 -> 0.85 to 0.6; else -> 0.95 to 0.8 }
        p.width = (dm.widthPixels * f.first).toInt()
        p.height = if (collapsed) ViewGroup.LayoutParams.WRAP_CONTENT else (dm.heightPixels * f.second).toInt()
        root?.let { wm?.updateViewLayout(it, p) }
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        web?.visibility = if (collapsed) View.GONE else View.VISIBLE
        applySize()
    }

    private fun cycleSize() { sizeIdx = (sizeIdx + 1) % 3; applySize() }

    private fun openApp(slot: Int) {
        startActivity(Shortcuts.openIntent(this, slot).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        stopSelf()
    }

    private fun hide() {
        root?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        web?.destroy()
        root = null
        web = null
    }

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }
}
