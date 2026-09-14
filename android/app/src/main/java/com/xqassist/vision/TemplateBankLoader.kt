package com.xqassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

object TemplateBankLoader {
    private const val TAG = "TplBank"
    private val codes = listOf(
        "wa", "wb", "wc", "wk", "wn", "wp", "wr",
        "ba", "bb", "bc", "bk", "bn", "bp", "br",
    )

    fun ensure(context: Context) {
        if (TemplateBank.map != null) return
        val map = HashMap<String, Bitmap>(codes.size)
        for (code in codes) {
            val live = open(context, "pieces_live/$code.webp")
                ?: open(context, "pieces/$code.webp")
                ?: continue
            map[code] = live
        }
        if (map.isNotEmpty()) {
            TemplateBank.map = map
            Log.i(TAG, "loaded ${map.size} templates")
        }
    }

    private fun open(context: Context, path: String): Bitmap? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) {
        null
    }
}
