package com.sandbox6x

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Alarmları AlarmManager'a ve kalıcı depoya yazar.
 * Güvenilir mod (varsayılan) setAlarmClock kullanır: Doze ve pil tasarrufundan etkilenmez,
 * özel izin istemez; yalnızca durum çubuğunda alarm simgesi görünür.
 */
object Alarms {
    private const val KEY = "alarms"

    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    private fun pending(c: Context, id: Int, title: String, body: String): PendingIntent {
        val i = Intent(c, ResetReceiver::class.java)
            .putExtra("slot", id).putExtra("title", title).putExtra("body", body)
        return PendingIntent.getBroadcast(
            c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun load(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun canExact(c: Context): Boolean {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun scheduledAt(c: Context, id: Int): Long =
        try { load(c).getJSONObject(id.toString()).getLong("at") } catch (_: Exception) { 0L }

    fun schedule(c: Context, id: Int, at: Long, title: String, body: String) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, id, title, body)
        try {
            if (prefs(c).getBoolean("alarmclock", true)) {
                val show = PendingIntent.getActivity(
                    c, 7000, Intent(c, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            } else if (canExact(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        val o = load(c)
        o.put(id.toString(), JSONObject().put("at", at).put("t", title).put("b", body))
        prefs(c).edit().putString(KEY, o.toString()).apply()
    }

    fun cancel(c: Context, id: Int) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, id, "", ""))
        forget(c, id)
    }

    fun forget(c: Context, id: Int) {
        val o = load(c)
        o.remove(id.toString())
        prefs(c).edit().putString(KEY, o.toString()).apply()
    }

    private fun keys(o: JSONObject): List<String> {
        val out = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    /** Telefon yeniden başladığında / uygulama güncellendiğinde çağrılır. */
    fun restore(c: Context) {
        val o = load(c)
        val now = System.currentTimeMillis()
        for (k in keys(o)) {
            val a = o.getJSONObject(k)
            val id = k.toInt()
            if (a.getLong("at") > now) {
                schedule(c, id, a.getLong("at"), a.getString("t"), a.getString("b"))
            } else {
                ResetReceiver.show(c, id, a.getString("t"), a.getString("b"))
                forget(c, id)
            }
        }
    }

    /** Kendini onarma: zamanı 90 sn'den fazla geçmiş ama tetiklenmemiş alarmları yakala ve bildir. */
    fun audit(c: Context) {
        val o = load(c)
        val now = System.currentTimeMillis()
        for (k in keys(o)) {
            val a = o.getJSONObject(k)
            val at = a.getLong("at")
            if (at < now - 90_000) {
                val id = k.toInt()
                ResetReceiver.show(c, id, a.getString("t"), a.getString("b") + " (alarm kaçırılmıştı)")
                forget(c, id)
                val p = prefs(c)
                p.edit().putInt("lateCount", p.getInt("lateCount", 0) + 1).putLong("lastLate", now - at).apply()
            }
        }
    }

    /** Hâlâ bekleyen (kurulu) alarm sayısı. */
    fun pendingCount(c: Context): Int = keys(load(c)).size
}
