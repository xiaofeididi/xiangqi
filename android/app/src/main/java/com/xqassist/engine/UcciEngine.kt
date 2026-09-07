package com.xqassist.engine

import com.xqassist.core.Notation
import com.xqassist.core.Position
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** 皮卡鱼返回的一次完整分析结果 */
data class EngineResult(
    val bestmove: String = "",
    val scoreCp: Int? = null,
    val mateIn: Int? = null,
    val depth: Int = 0,
    val pv: List<String> = emptyList(),
) {
    fun chinese(pos: Position): String =
        if (bestmove.isBlank()) "无着法" else Notation.moveToChinese(pos, bestmove)

    fun scoreText(): String = when {
        mateIn != null -> "杀棋 ${mateIn}"
        scoreCp != null -> String.format("%.2f 兵", scoreCp / 100.0)
        else -> "无评分"
    }
}

/** Pikafish UCI 进程封装；所有分析都应在 IO 线程调用 */
class UcciEngine(
    private val engineFile: File,
    private val nnueFile: File? = null,
    private val threads: Int = 2,
    private val hashMb: Int = 128,
) {
    var isReady: Boolean = false
        private set

    /** UI/调试日志回调 */
    var logger: ((String) -> Unit)? = null

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    private fun log(message: String) {
        android.util.Log.i("Ucci", message)
        logger?.invoke(message)
    }

    @Synchronized
    fun start(): Boolean {
        if (process?.isAlive == true) return isReady
        if (!engineFile.canExecute()) engineFile.setExecutable(true)
        val builder = ProcessBuilder(engineFile.absolutePath)
        builder.redirectErrorStream(true)
        nnueFile?.parentFile?.let { builder.directory(it) }
        log("启动：${engineFile.absolutePath} NNUE=${nnueFile?.exists() == true}")
        val started = try {
            builder.start()
        } catch (t: Throwable) {
            android.util.Log.e("Ucci", "启动失败", t)
            log("启动失败：${t.message ?: t.javaClass.simpleName}")
            null
        } ?: return false

        process = started
        writer = OutputStreamWriter(started.outputStream, Charsets.UTF_8)
        reader = BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8))

        send("ucci")
        val ucciOk = waitFor("ucciok", 8000)
        if (!ucciOk) {
            send("uci")
            waitFor("uciok", 5000)
        }
        send("setoption name Threads value ${threads.coerceIn(1, 8)}")
        send("setoption name Hash value ${hashMb.coerceIn(16, 512)}")
        if (nnueFile != null && nnueFile.exists()) {
            // 工作目录是 /，必须显式指定 NNUE 绝对路径，否则搜索时引擎静默失败
            send("setoption name EvalFile value ${nnueFile.absolutePath}")
        }
        send("isready")
        val readyOk = waitFor("readyok", 8000)
        isReady = readyOk
        log(if (isReady) "皮卡鱼就绪" else "皮卡鱼握手失败")
        return isReady
    }

    @Synchronized
    fun send(command: String) {
        try {
            writer?.write(command + "\n")
            writer?.flush()
        } catch (t: Throwable) {
            log("发送失败：$command")
        }
    }

    private fun waitFor(token: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = reader?.readLine() ?: break
            if (line.isNotBlank()) log(line)
            if (line.contains(token)) return true
        }
        return false
    }

    /**
     * 同步分析。depth > 0 时限制深度；movetimeMs > 0 时限制用时。
     * onInfo 在读取到 info 行时回调，可用于持续刷新。
     */
    @Synchronized
    fun analyze(
        fen: String,
        movetimeMs: Int = 1000,
        depth: Int = 0,
        onInfo: ((EngineResult) -> Unit)? = null,
    ): EngineResult {
        if (process?.isAlive != true) {
            log("引擎进程未运行")
            return EngineResult()
        }

        var best = ""
        var score: Int? = null
        var mate: Int? = null
        var currentDepth = 0
        val currentPv = mutableListOf<String>()

        send("stop")
        send("position fen $fen")
        val go = when {
            depth > 0 && movetimeMs > 0 -> "go depth $depth movetime $movetimeMs"
            depth > 0 -> "go depth $depth"
            else -> "go movetime ${movetimeMs.coerceIn(100, 30000)}"
        }
        log("分析开始：$go")
        send(go)

        val limitMs = when {
            depth > 0 -> maxOf(20000L, movetimeMs * 4L + 5000L)
            else -> movetimeMs + 5000L
        }
        val deadline = System.currentTimeMillis() + limitMs

        while (System.currentTimeMillis() < deadline) {
            val line = reader?.readLine() ?: break
            if (line.startsWith("info")) {
                Regex("(?<= depth )(\\d+)").find(line)?.let {
                    currentDepth = it.groupValues[1].toInt()
                }
                Regex("score (cp|mate) (-?\\d+)").find(line)?.let { match ->
                    if (match.groupValues[1] == "cp") {
                        score = match.groupValues[2].toInt()
                        mate = null
                    } else {
                        mate = match.groupValues[2].toInt()
                        score = null
                    }
                }
                Regex(" pv (.+)").find(line)?.let { match ->
                    currentPv.clear()
                    currentPv.addAll(match.groupValues[1].trim().split(Regex("\\s+")))
                }
                if (line.contains(" pv ")) {
                    onInfo?.invoke(EngineResult(currentPv.firstOrNull() ?: "", score, mate, currentDepth, currentPv.toList()))
                }
            } else if (line.startsWith("bestmove")) {
                best = line.split(Regex("\\s+")).getOrElse(1) { "" }
                log("分析结束：$best")
                break
            }
        }

        val finalBest = best.ifBlank { currentPv.firstOrNull() ?: "" }
        return EngineResult(finalBest, score, mate, currentDepth, currentPv.toList())
    }

    @Synchronized
    fun stop() {
        try {
            send("quit")
            process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
        } finally {
            process?.destroy()
            process = null
            writer = null
            reader = null
            isReady = false
        }
    }
}