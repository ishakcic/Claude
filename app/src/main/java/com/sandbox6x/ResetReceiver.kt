package com.sandbox6x

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getIntExtra("slot", 0)
        val at = Alarms.scheduledAt(context, slot)
        val late = if (at > 0) System.currentTimeMillis() - at else 0L
        Alarms.forget(context, slot)

        show(
            context, slot,
            intent.getStringExtra("title") ?: "Oturum aktif",
            intent.getStringExtra("body") ?: "Yenileme saati geldi."
        )
        if (late > 120_000) {
            val p = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            p.edit().putInt("lateCount", p.getInt("lateCount", 0) + 1).putLong("lastLate", late).apply()
            Notes.add(
                context, 0, "Alarm gecikti",
                "Bir alarm ${late / 60000} dk geç geldi. Ayarlar > Alarm sağlığı bölümünden pil kısıtlamasını kaldır."
            )
        }
        SlotWidget.refresh(context)
        SlotTileService.refresh(context)
    }

    companion object {
        const val CHANNEL = "reset"

        fun show(
            context: Context, slot: Int, title: String, body: String,
            channel: String = CHANNEL, channelName: String = "Oturum yenilendi"
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH))
            }
            val open = Shortcuts.openIntent(context, slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                context, slot, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            val nid = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            try { nm.notify(nid, n) } catch (_: SecurityException) { }
            Notes.add(context, slot, title, body)
        }
    }
}
