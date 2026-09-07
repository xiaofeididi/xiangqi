package com.xqassist.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Extracts the bundled arm64 Pikafish binary + NNUE from assets into app files dir. */
object EngineInstaller {

    private fun dir(context: Context): File = File(context.filesDir, "engine").apply { mkdirs() }

    fun engineFile(context: Context): File = File(dir(context), "pikafish")

    fun nnueFile(context: Context): File = File(dir(context), "pikafish.nnue")

    fun install(context: Context): File? {
        val engine = engineFile(context)
        val nnue = nnueFile(context)
        copyAsset(context, "engine/pikafish", engine)
        copyAsset(context, "engine/pikafish.nnue", nnue)
        if (!engine.canExecute()) engine.setExecutable(true, false)
        return if (engine.exists()) engine else null
    }

    private fun copyAsset(ctx: Context, name: String, dest: File) {
        if (dest.exists() && dest.length() > 0L) return
        ctx.assets.open(name).use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
    }
}