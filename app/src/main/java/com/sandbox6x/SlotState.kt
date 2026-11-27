package com.sandbox6x

import android.content.Context
import org.json.JSONArray

/** Arayüzün yayınladığı slot durumunun yerel özeti (widget ve ayar kutucuğu için). */
object SlotState {
    data class Sum(val total: Int, val free: Int, val nextAt: Long, val nextCode: String)

    fun array(c: Context): JSONArray = try {
        JSONArray(c.getSharedPreferences("app", Context.MODE_PRIVATE).getString("state", "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }

    fun summary(c: Context): Sum {
        val arr = array(c)
        val now = System.currentTimeMillis()
        var free = 0
        var nextAt = Long.MAX_VALUE
        var nextCode = ""
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val at = o.optLong("resetAt")
            if (at > now) {
                if (at < nextAt) { nextAt = at; nextCode = o.optString("code") }
            } else free++
        }
        return Sum(arr.length(), free, if (nextAt == Long.MAX_VALUE) 0L else nextAt, nextCode)
    }
}
