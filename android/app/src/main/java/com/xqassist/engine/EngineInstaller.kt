package com.xqassist.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Extracts the bundled arm64 Pikafish NNUE; engine binary also ships as a native lib. */
object EngineInstaller {

    private fun dir(context: Context): File = File(context.filesDir, "engine").apply { mkdirs() }

    /**
     * Engine binary shipped as a native lib (jniLibs/arm64-v8a/libpikafish.so).
     * Files under nativeLibraryDir live on an exec-mounted partition, so the
     * binary can run there even when filesDir execution is blocked (Android 10+).
     */
    fun nativeEngineFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libpikafish.so")

    fun engineFile(context: Context): File = File(dir(context), "pikafish")

    fun nnueFile(context: Context): File = File(dir(context), "pikafish.nnue")

    fun install(context: Context): File? {
        val native = nativeEngineFile(context)
        if (native.exists() && native.canExecute()) {
            makeExecutable(native)
            extractNnue(context)
            return native
        }
        val engine = engineFile(context)
        copyAsset(context, "engine/pikafish", engine)
        extractNnue(context)
        makeExecutable(engine)
        return engine.takeIf { it.exists() }
    }

    private fun extractNnue(context: Context) {
        copyAsset(context, "engine/pikafish.nnue", nnueFile(context))
    }

    private fun makeExecutable(file: File) {
        if (!file.canExecute()) file.setExecutable(true, false)
    }

    private fun copyAsset(ctx: Context, name: String, dest: File) {
        if (dest.exists() && dest.length() > 0L) return
        ctx.assets.open(name).use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
    }
}