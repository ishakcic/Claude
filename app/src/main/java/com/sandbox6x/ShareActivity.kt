package com.sandbox6x

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

/** "Paylaş" ve "metin seçimi" menülerinden gelen içeriği MainActivity'ye aktarır. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var text: String? = null
        var uri: Uri? = null
        when (intent.action) {
            Intent.ACTION_SEND -> {
                text = intent.getStringExtra(Intent.EXTRA_TEXT)
                uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                      else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_PROCESS_TEXT -> text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }
        val out = Intent(this, MainActivity::class.java)
            .setAction("com.sandbox6x.SHARE")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (text != null) out.putExtra("share_text", text)
        if (uri != null) {
            out.putExtra("share_uri", uri.toString())
            out.clipData = ClipData.newRawUri("", uri)
            out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(out)
        finish()
    }
}
