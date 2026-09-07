package com.xqassist.engine

import com.xqassist.core.Notation
import com.xqassist.core.Position
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** UCCI result from Pikafish. */
data class EngineResult(
    val bestmove: String = "",
    val scoreCp: Int? = null,
    val mateIn: Int? = null,
    val depth: Int = 0,
    val pv: List<String> = emptyList(),
) {
    fun chinese(pos: Position): String = Notation.moveToChinese(pos, bestmove)
}

/** Pikafish UCCI engine wrapper (child process, stdin/stdout). Run off main thread. */
class UcciEngine(
    private val engineFile: File,
    private val nnueFile: File? = null,
    private val threads: Int = 2,
    private val hashMb: Int = 128,
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private val dispatcher = Dispatchers.IO

    @Synchronized
    fun start() {
        if (process?.isAlive == true) return
        if (!engineFile.canExecute()) engineFile.setExecutable(true)
        val pb = ProcessBuilder(engineFile.absolutePath)
        android.util.Log.i("Ucci", "start ${engineFile.absolutePath} nnue=${nnueFile?.exists()}")
        if (nnueFile != null) pb.environment()["PIKAFISH_NNUE"] = nnueFile.absolutePath
        pb.redirectErrorStream(true)
        val proc = try { pb.start() } catch (t: Throwable) {
            android.util.Log.e("Ucci", "start failed", t); return
        }
        process = proc
        writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
        reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        send("ucci")
        if (!waitFor("ucciok", 6000)) { send("uci"); waitFor("uciok", 4000) }
        send("setoption name Threads value $threads")
        send("setoption name Hash value $hashMb")
        send("isready")
        waitFor("readyok", 6000)
        android.util.Log.i("Ucci", "ready")
    }

    fun send(cmd: String) {
        try { writer?.write(cmd + "\n"); writer?.flush() } catch (_: Throwable) {}
    }

    private fun waitFor(token: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = reader?.readLine() ?: break
            if (line.contains(token)) return true
        }
        return false
    }

    @Synchronized
    fun analyze(fen: String, movetimeMs: Int = 1000, depth: Int? = null): EngineResult {
        var bestmove = ""
        var score: Int? = null
        var mate: Int? = null
        var lastDepth = 0
        val pv = mutableListOf<String>()
        send("stop")
        send("position fen $fen")
        if (depth != null) send("go depth $depth") else send("go movetime $movetimeMs")

        val extended = maxOf(3000L, movetimeMs * 3L + 2000L)
        val deadline = System.currentTimeMillis() + extended
        while (System.currentTimeMillis() < deadline) {
            val line = reader?.readLine() ?: break
            if (line.startsWith("info") && line.contains(" pv ")) {
                Regex("depth (\\d+)").find(line)?.let { lastDepth = it.groupValues[1].toInt() }
                Regex("score (cp|mate) (-?\\d+)").find(line)?.let { mm ->
                    if (mm.groupValues[1] == "cp") { score = mm.groupValues[2].toInt(); mate = null }
                    else { mate = mm.groupValues[2].toInt(); score = null }
                }
                Regex(" pv (.+)").find(line)?.let { pv.clear(); pv.addAll(it.groupValues[1].split(" ")) }
            }
            if (line.startsWith("bestmove")) {
                bestmove = line.split(" ").getOrElse(1) { "" }
                break
            }
        }
        return EngineResult(bestmove, score, mate, lastDepth, pv)
    }

    fun analyzeSuspend(fen: String, movetimeMs: Int = 1000): Deferred<EngineResult> =
        CoroutineScope(dispatcher).async { analyze(fen, movetimeMs) }

    fun stop() {
        try { send("quit"); process?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
        catch (_: Throwable) {}
        finally {
            process?.destroy()
            process = null; writer = null; reader = null
        }
    }
}