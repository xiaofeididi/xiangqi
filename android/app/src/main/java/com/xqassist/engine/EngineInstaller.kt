package com.xqassist.engine

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream

/**
 * 引擎安装/切换：
 * - 内置：jniLibs libpikafish.so + assets NNUE
 * - 下载：filesDir/engine/{name}/ 可执行文件 + *.nnue
 */
object EngineInstaller {

    private const val PREFS = "engine_pref"
    private const val KEY_NAME = "engine_name"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun preferredName(context: Context): String =
        prefs(context).getString(KEY_NAME, "builtin") ?: "builtin"

    fun setPreferredName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name).apply()
    }

    fun dir(context: Context): File = File(context.filesDir, "engine").apply { mkdirs() }

    fun nativeEngineFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libpikafish.so")

    fun engineFile(context: Context): File = File(dir(context), "pikafish")

    fun nnueFile(context: Context): File = File(dir(context), "pikafish.nnue")

    /** 已下载引擎目录列表（不含 builtin） */
    fun listDownloaded(context: Context): List<String> =
        dir(context).listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    /**
     * 解析当前应使用的引擎二进制 + nnue。
     * name=builtin → 内置；否则 filesDir/engine/{name}/ 下找可执行文件和 nnue。
     * 找不到或无法执行时回退内置，绝不返回无效路径。
     */
    fun resolve(context: Context): Pair<File, File?> {
        val name = preferredName(context)
        if (name != "builtin") {
            val folder = File(dir(context), name)
            if (folder.isDirectory) {
                val bin = folder.listFiles()?.firstOrNull { f ->
                    f.isFile && f.length() > 1000 && !f.name.endsWith(".nnue", true) && !f.name.endsWith(".###")
                }
                val nnue = folder.listFiles()?.firstOrNull { it.name.endsWith(".nnue", true) && it.length() > 1000 }
                if (bin != null) {
                    makeExecutable(bin)
                    // 验证可执行，不行就回退
                    if (bin.canExecute()) {
                        return bin to nnue
                    }
                    Log.w("EngineInstaller", "downloaded engine not executable: ${bin.absolutePath}")
                }
            }
        }
        // fallback 内置
        val native = nativeEngineFile(context)
        if (native.exists() && native.length() > 0) {
            makeExecutable(native)
            extractNnue(context)
            return native to nnueFile(context)
        }
        val engine = engineFile(context)
        copyAsset(context, "engine/pikafish", engine)
        extractNnue(context)
        makeExecutable(engine)
        return engine to nnueFile(context)
    }

    fun install(context: Context): File? = resolve(context).first.takeIf { it.exists() }

    private fun extractNnue(context: Context) {
        copyAsset(context, "engine/pikafish.nnue", nnueFile(context))
    }

    private fun makeExecutable(file: File) {
        if (!file.canExecute()) file.setExecutable(true, false)
    }

    private fun copyAsset(ctx: Context, name: String, dest: File) {
        if (dest.exists() && dest.length() > 0L) return
        try {
            ctx.assets.open(name).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        } catch (_: Throwable) {
        }
    }
}
