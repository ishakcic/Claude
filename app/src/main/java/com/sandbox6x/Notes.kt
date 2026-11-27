package com.sandbox6x

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bildirim geçmişi: uygulama kapalıyken gelen bildirimler de burada saklanır. */
object Notes {
    private const val KEY = "notes"
    var onChange: (() -> Unit)? = null

    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): JSONArray =
        try { JSONArray(prefs(c).getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    @Synchronized
    private fun store(c: Context, a: JSONArray) {
        prefs(c).edit().putString(KEY, a.toString()).apply()
        onChange?.invoke()
    }

    @Synchronized
    fun add(c: Context, slot: Int, title: String, body: String) {
        val a = load(c)
        a.put(
            JSONObject().put("slot", slot).put("t", title).put("b", body)
                .put("at", System.currentTimeMillis()).put("read", false)
        )
        while (a.length() > 100) a.remove(0)
        store(c, a)
    }

    fun all(c: Context): String = load(c).toString()

    /** slot = 0 ise hepsini okundu yapar. */
    @Synchronized
    fun markRead(c: Context, slot: Int) {
        val a = load(c)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (slot == 0 || o.optInt("slot") == slot) o.put("read", true)
        }
        store(c, a)
    }

    fun clear(c: Context) = store(c, JSONArray())
}
