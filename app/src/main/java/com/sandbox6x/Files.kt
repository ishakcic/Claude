package com.sandbox6x

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Uygulamanın kaydettiği/indirdiği dosyaların listesi. */
object Files {
    private const val KEY = "files"
    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): JSONArray =
        try { JSONArray(prefs(c).getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun store(c: Context, a: JSONArray) = prefs(c).edit().putString(KEY, a.toString()).apply()

    @Synchronized
    fun add(c: Context, name: String, uri: String, mime: String, size: Int) {
        val a = load(c)
        a.put(JSONObject().put("name", name).put("uri", uri).put("mime", mime).put("size", size).put("at", System.currentTimeMillis()))
        while (a.length() > 200) a.remove(0)
        store(c, a)
    }

    fun all(c: Context): String = load(c).toString()

    @Synchronized
    fun get(c: Context, idx: Int): JSONObject? {
        val a = load(c)
        return if (idx in 0 until a.length()) a.getJSONObject(idx) else null
    }

    @Synchronized
    fun remove(c: Context, idx: Int) {
        val a = load(c)
        if (idx in 0 until a.length()) { a.remove(idx); store(c, a) }
    }
}
