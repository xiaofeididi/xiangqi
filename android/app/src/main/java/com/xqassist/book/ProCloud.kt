package com.xqassist.book

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class CloudFileInfo(
    val name: String,
    val url: String,
    val size: Long,
    val fileName: String,
)

/**
 * 对接 Pro 公开列表接口（无需 token）：
 *  - http://api.pro.wqby.vip/user/getEngineList
 *  - http://api.pro.wqby.vip/user/getOpenBookList
 * 文件直链在 file.wqby.vip，下载到 app filesDir/{engine|books}/
 */
object ProCloud {
    private const val TAG = "ProCloud"
    private val bases = listOf(
        "http://api.pro.wqby.vip",
        "http://api1.pro.wqby.vip",
    )

    fun fetchList(kind: String): List<CloudFileInfo> {
        val path = if (kind == "engine") "/user/getEngineList" else "/user/getOpenBookList"
        var lastErr: Exception? = null
        for (base in bases) {
            try {
                val conn = URL(base + path).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("token", "")
                conn.setRequestProperty("version", "619")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                if (json.optInt("code", -1) != 200) {
                    lastErr = RuntimeException("code=${json.opt("code")}")
                    continue
                }
                val data = json.optJSONArray("data") ?: JSONArray()
                val out = mutableListOf<CloudFileInfo>()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val name = item.optString("name")
                    val subs = item.optJSONArray("cloudFileSubs") ?: continue
                    for (s in 0 until subs.length()) {
                        val sub = subs.getJSONObject(s)
                        out += CloudFileInfo(
                            name = name,
                            url = sub.optString("fileUrl"),
                            size = sub.optLong("fileSize"),
                            fileName = sub.optString("fileName"),
                        )
                    }
                }
                return out
            } catch (t: Throwable) {
                lastErr = t as? Exception ?: Exception(t)
                Log.w(TAG, "fetch $kind from $base failed", t)
            }
        }
        throw lastErr ?: RuntimeException("no base")
    }

    fun download(
        url: String,
        dest: File,
        onProgress: ((pct: Int) -> Unit)? = null,
    ): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".###")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var n: Int
                    var lastPct = -1
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0 && onProgress != null) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            if (tmp.exists()) {
                if (dest.exists()) dest.delete()
                return tmp.renameTo(dest)
            }
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "download failed $url", t)
            tmp.delete()
            return false
        }
    }
}
