package com.sandbox6x

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hızlı ayarlar kutucuğu: kaç hesap müsait, en erken ne zaman açılacak. */
class SlotTileService : TileService() {
    override fun onStartListening() { update() }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun update() {
        val t = qsTile ?: return
        val s = SlotState.summary(this)
        t.label = if (s.total == 0) "Claude 6X" else "${s.free}/${s.total} müsait"
        t.state = if (s.free > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (s.nextAt > 0)
                s.nextCode + " " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.nextAt))
            else "Hepsi hazır"
        }
        t.updateTile()
    }

    companion object {
        fun refresh(c: Context) {
            try { TileService.requestListeningState(c, ComponentName(c, SlotTileService::class.java)) } catch (_: Exception) { }
        }
    }
}
