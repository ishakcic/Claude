package com.sandbox6x

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Termux {
    const val PKG = "com.termux"
    const val PERM = "com.termux.permission.RUN_COMMAND"
    var onResult: ((Int, String, String, Int, String) -> Unit)? = null
}

/** Termux, komut bitince sonucu buraya gönderir. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("execId", 0)
        val b = intent.getBundleExtra("result")
        val out = b?.getString("stdout") ?: ""
        val err = b?.getString("stderr") ?: ""
        val code = b?.getInt("exitCode", -1) ?: -1
        val msg = b?.getString("errmsg") ?: ""
        Termux.onResult?.invoke(id, out, err, code, msg)
    }
}
