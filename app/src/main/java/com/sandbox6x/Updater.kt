package com.sandbox6x

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases üzerinden yeni sürüm denetimi ve indirme. Ağ çağrıları arka plan iş parçacığında yapılmalı. */
object Updater {
    fun check(c: Context, repo: String, token: String): JSONObject {
        val o = JSONObject()
        try {
            val cur = PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0))
            val h = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            h.setRequestProperty("Accept", "application/vnd.github+json")
            h.setRequestProperty("User-Agent", "ClaudeSandbox6X")
            if (token.isNotBlank()) h.setRequestProperty("Authorization", "Bearer $token")
            h.connectTimeout = 10000
            h.readTimeout = 15000
            if (h.responseCode != 200) return o.put("ok", false).put("error", "HTTP " + h.responseCode)
            val j = JSONObject(h.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name")
            val latest = tag.substringAfterLast('.').filter { it.isDigit() }.toLongOrNull() ?: 0L
            var asset = ""
            val assets = j.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) asset = a.optString("url")
            }
            o.put("ok", true).put("current", cur).put("latest", latest).put("tag", tag)
                .put("available", latest > cur).put("page", j.optString("html_url"))
                .put("asset", asset).put("notes", j.optString("body").take(400))
        } catch (e: Exception) {
            o.put("ok", false).put("error", e.message ?: "hata")
        }
        return o
    }

    fun download(token: String, assetApiUrl: String): ByteArray? {
        return try {
            val h = URL(assetApiUrl).openConnection() as HttpURLConnection
            h.instanceFollowRedirects = false
            h.setRequestProperty("Accept", "application/octet-stream")
            h.setRequestProperty("User-Agent", "ClaudeSandbox6X")
            if (token.isNotBlank()) h.setRequestProperty("Authorization", "Bearer $token")
            val code = h.responseCode
            val conn = if (code in 300..399) {
                val loc = h.getHeaderField("Location") ?: return null
                URL(loc).openConnection() as HttpURLConnection
            } else h
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) { null }
    }
}
