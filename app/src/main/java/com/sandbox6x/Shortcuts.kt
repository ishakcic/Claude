package com.sandbox6x

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONArray

/** Ana ekran kısayolları ve simgeleri. */
object Shortcuts {
    fun openIntent(c: Context, id: Int): Intent =
        Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("slot", id)

    fun icon(code: String, colorHex: String, emoji: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = try { Color.parseColor(colorHex) } catch (_: Exception) { 0xFFD97757.toInt() }
        cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        val hasEmoji = emoji.isNotBlank()
        p.textSize = if (hasEmoji) 100f else 72f
        val y = size / 2f - (p.descent() + p.ascent()) / 2f
        cv.drawText(if (hasEmoji) emoji else code, size / 2f, y, p)
        return bmp
    }

    private fun info(c: Context, id: Int, label: String, color: String, emoji: String, code: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(c, "slot_$id")
            .setShortLabel(label.take(12))
            .setLongLabel(label)
            .setIcon(IconCompat.createWithBitmap(icon(code, color, emoji)))
            .setIntent(openIntent(c, id))
            .build()

    fun pin(c: Context, id: Int, label: String, color: String, emoji: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(c)) return false
        return ShortcutManagerCompat.requestPinShortcut(c, info(c, id, label, color, emoji, "S%02d".format(id)), null)
    }

    /** Uygulama simgesine uzun basınca çıkan ilk 4 hesap kısayolu. */
    fun publishDynamic(c: Context, arr: JSONArray) {
        try {
            val list = ArrayList<ShortcutInfoCompat>()
            for (i in 0 until minOf(4, arr.length())) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("id", i + 1)
                list.add(info(c, id, o.optString("name", "Hesap $id"), o.optString("color", "#D97757"), o.optString("icon", ""), o.optString("code", "S%02d".format(id))))
            }
            ShortcutManagerCompat.setDynamicShortcuts(c, list)
        } catch (_: Exception) { }
    }
}
