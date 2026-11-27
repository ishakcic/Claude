package com.sandbox6x

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Tasker / MacroDroid gibi uygulamalar için komut arayüzü.
 * Her komut, ayarlardaki gizli anahtarı (token) taşımak zorundadır.
 */
object Automation {
    const val ACT_OPEN = "com.sandbox6x.OPEN_SLOT"
    const val ACT_SET = "com.sandbox6x.SET_TIMER"
    const val ACT_CLEAR = "com.sandbox6x.CLEAR_TIMER"

    var onChange: (() -> Unit)? = null
    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun token(c: Context): String {
        var t = prefs(c).getString("token", null)
        if (t == null) t = newToken(c)
        return t
    }

    fun newToken(c: Context): String {
        val t = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs(c).edit().putString("token", t).apply()
        return t
    }

    fun extra(i: Intent, k: String): String? = i.extras?.get(k)?.toString()
    fun num(i: Intent, k: String): Int = extra(i, k)?.toIntOrNull() ?: 0
    fun valid(c: Context, i: Intent): Boolean = extra(i, "token") == token(c)

    fun label(c: Context, slot: Int): String {
        val a = SlotState.array(c)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optInt("id", i + 1) == slot) return o.optString("code") + " • " + o.optString("name")
        }
        return "S%02d".format(slot)
    }

    @Synchronized
    private fun queue(c: Context, cmd: JSONObject) {
        val a = try { JSONArray(prefs(c).getString("cmds", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(cmd)
        prefs(c).edit().putString("cmds", a.toString()).apply()
        onChange?.invoke()
    }

    @Synchronized
    fun take(c: Context): String {
        val s = prefs(c).getString("cmds", "[]") ?: "[]"
        prefs(c).edit().putString("cmds", "[]").apply()
        return s
    }

    private fun nextClock(hhmm: String): Long {
        val p = hhmm.split(":")
        if (p.size != 2) return 0L
        val h = p[0].toIntOrNull() ?: return 0L
        val m = p[1].toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** Zamanlayıcı komutlarını işler. OPEN_SLOT, MainActivity tarafından işlenir. */
    fun handle(c: Context, i: Intent): Boolean {
        if (!valid(c, i)) return false
        val slot = num(i, "slot")
        if (slot <= 0) return false
        when (i.action) {
            ACT_SET -> {
                val minutes = num(i, "minutes")
                val at = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L
                         else nextClock(extra(i, "time") ?: "")
                if (at <= 0) return false
                val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
                Alarms.schedule(c, slot, at, label(c, slot) + " aktif!", "$clock yenileme saati geldi.")
                queue(c, JSONObject().put("cmd", "timer").put("slot", slot).put("at", at))
            }
            ACT_CLEAR -> {
                Alarms.cancel(c, slot)
                queue(c, JSONObject().put("cmd", "clear").put("slot", slot))
            }
            else -> return false
        }
        return true
    }
}

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Automation.handle(context, intent)
    }
}
