package com.xqassist.connection

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.xqassist.capture.CaptureService
import com.xqassist.core.Position
import com.xqassist.engine.EngineResult
import com.xqassist.engine.UcciEngine
import com.xqassist.vision.BoardRect
import com.xqassist.vision.ChessboardReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 连线分析会话：截屏 → 识盘 → 引擎分析 → 悬浮展示 → 可选无障碍点子。
 * 不绑定 Activity 生命周期，悬浮窗可在主界面后台时继续跑。
 */
object ConnectSession {

    enum class State {
        IDLE, WAITING_PERMISSION, WAITING_FRAME, RECOGNIZING, ANALYZING, AUTO_PLAYING, PAUSED, ERROR
    }

    data class Snapshot(
        val state: State = State.IDLE,
        val message: String = "",
        val fen: String = "",
        val board: Position? = null,
        val recognizeMs: Long = 0,
        val result: EngineResult = EngineResult(),
        val autoMove: Boolean = false,
        val delayMs: Int = 1200,
        val sideToMove: String = "w",
        val running: Boolean = false,
    )

    @Volatile
    var boardRect: BoardRect? = null
        private set

    @Volatile
    var flipped: Boolean = false
        private set

    @Volatile
    var autoMoveOn: Boolean = false

    /** 识盘间隔（非 Pro 点子间隔） */
    @Volatile
    var intervalMs: Int = 1200

    /** 起点→终点点按间隔：Pro 默认 150ms；慢动画可调到 800ms */
    @Volatile
    var tapGapMs: Int = 150

    /** 识别结果里写入的行棋方（外部棋盘侧向无法可靠识别时由用户配置） */
    @Volatile
    var sideToMove: String = "w"

    @Volatile
    var thinkMs: Int = 3000

    @Volatile
    var searchDepth: Int = 0

    /** true = 出子模式，使用 thinkMs/searchDepth；false = 纯分析，不套用深度/时间设置 */
    @Volatile
    var useEngineLimits: Boolean = false

    @Volatile
    var multiPv: Int = 1

    @Volatile
    var visionWide: Boolean = false

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 最近一次识别是否成功（稳定局面） */
    @Volatile
    var lastRecognizedOk: Boolean = false
        private set

    @Volatile
    var lastRecognizedSummary: String = ""
        private set

    @Volatile
    var lastFen: String = ""
        private set

    @Volatile
    var lastResult: EngineResult = EngineResult()
        private set

    @Volatile
    var lastBoard: Position? = null
        private set

    @Volatile
    var lastMessage: String = ""
        private set

    @Volatile
    var lastState: State = State.IDLE
        private set

    var onSnapshot: ((Snapshot) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var engine: UcciEngine? = null
    private var reader: ChessboardReader? = null
    private var appContext: Context? = null

    private const val PREFS = "connect_session"
    private const val KEY_LEFT = "rect_left"
    private const val KEY_TOP = "rect_top"
    private const val KEY_RIGHT = "rect_right"
    private const val KEY_BOTTOM = "rect_bottom"
    private const val KEY_FLIPPED = "flipped"
    private const val KEY_SIDE = "side"
    private const val KEY_DELAY = "delay_ms"
    private const val KEY_AUTO = "auto_move"

    /** 连续相同 FEN 次数达到该阈值才认为局面稳定，降低闪烁误判 */
    private const val STABLE_HITS = 2

    /** 自动走子后的冷却，避免动画中途重复识别 */
    private const val AUTO_COOLDOWN_MS = 900L

    private var pendingFen = ""
    private var pendingHits = 0
    private var lastAutoFen = ""
    private var lastAutoAt = 0L

    fun attach(context: Context) {
        appContext = context.applicationContext
        loadPrefs(context.applicationContext)
    }

    fun provideEngine(value: UcciEngine?) {
        engine = value
    }

    fun provideReader(value: ChessboardReader?) {
        reader = value
    }

    fun setBoardRect(rect: BoardRect?, flippedBoard: Boolean = flipped) {
        boardRect = rect
        flipped = flippedBoard
        appContext?.let { savePrefs(it) }
        publish("棋盘范围已更新")
    }

    fun setSide(side: String) {
        if (side !in setOf("w", "b")) return
        sideToMove = side
        appContext?.let { savePrefs(it) }
        publish("行棋方：${if (side == "w") "红" else "黑"}")
    }

    fun toggleSide() = setSide(if (sideToMove == "w") "b" else "w")

    fun setAutoMove(enabled: Boolean) {
        autoMoveOn = enabled
        appContext?.let { savePrefs(it) }
        publish(if (enabled) "自动走：开" else "自动走：关")
    }

    fun setDelayMs(value: Int) {
        intervalMs = value.coerceIn(400, 8000)
        appContext?.let { savePrefs(it) }
        publish("识别间隔 ${intervalMs}ms")
    }

    fun start(engineValue: UcciEngine? = engine, readerValue: ChessboardReader? = reader) {
        if (isRunning) return
        engineValue?.let { engine = it }
        readerValue?.let { reader = it }
        if (engine?.isReady != true) {
            publish("引擎未就绪", State.ERROR)
            return
        }
        if (reader == null) {
            publish("识别器未就绪", State.ERROR)
            return
        }
        if (!CaptureService.isRunning) {
            publish("屏幕识别未开启，请回助手重新授权截屏", State.ERROR)
            return
        }
        if (boardRect == null) {
            publish("请先在助手内校准棋盘（点识别后的两点标定）", State.ERROR)
            return
        }
        pendingFen = ""
        pendingHits = 0
        lastAutoFen = ""
        lastRecognizedOk = false
        lastRecognizedSummary = ""
        isRunning = true
        loopJob = scope.launch {
            publish("连线分析已启动")
            while (isActive && isRunning) {
                tick()
                delay(intervalMs.toLong())
            }
        }
    }

    fun stop() {
        isRunning = false
        loopJob?.cancel()
        loopJob = null
        pendingFen = ""
        pendingHits = 0
        lastRecognizedOk = false
        lastRecognizedSummary = ""
        publish("连线分析已停止", State.IDLE)
    }

    fun toggle(engineValue: UcciEngine? = engine, readerValue: ChessboardReader? = reader) {
        if (isRunning) stop() else start(engineValue, readerValue)
    }

    /** 立即识别一次（不启动循环），用于校准后验证 */
    suspend fun recognizeOnce(readerValue: ChessboardReader? = reader): Position? {
        val r = readerValue ?: reader ?: return null
        val rect = boardRect ?: return null
        val frame = withContext(Dispatchers.IO) { CaptureService.copyLatestBitmap() } ?: return null
        return withContext(Dispatchers.IO) { readBoardStable(r, frame, rect) }
    }

    /** 手动出招：用当前分析结果在目标 App 上点一步（此路径才套用深度/时间） */
    fun playBestNow(onDone: ((Boolean) -> Unit)? = null) {
        val eng = engine
        val fen = lastFen
        val rect = boardRect
        if (eng?.isReady != true || fen.isBlank() || rect == null) {
            onDone?.invoke(false)
            return
        }
        scope.launch {
            // 出子：使用设置里的深度/时间
            val result = eng.analyze(
                fen = fen,
                movetimeMs = thinkMs,
                depth = searchDepth,
                multiPv = 1,
                infinite = false,
                history = emptyList(),
            )
            val iccs = result.bestmove
            if (iccs.isBlank()) {
                withContext(Dispatchers.Main) { onDone?.invoke(false) }
                return@launch
            }
            lastResult = result
            val ok = autoPlay(iccs, rect)
            if (ok) {
                lastAutoFen = fen
                lastAutoAt = System.currentTimeMillis()
                publish("已出子 ${iccs} · 深度${result.depth}", State.AUTO_PLAYING, fen = fen, result = result)
            }
            withContext(Dispatchers.Main) { onDone?.invoke(ok) }
        }
    }

    private fun resultText(result: EngineResult): String {
        val score = when {
            result.mateIn != null -> "杀${result.mateIn}"
            result.scoreCp != null -> "${result.scoreCp}分"
            else -> "-"
        }
        return "深度${result.depth} $score"
    }

    private fun summarizeBoard(board: Position, elapsedMs: Long): String {
        var pieces = 0
        for (r in 0 until 10) {
            for (f in 0 until 9) {
                if (board.pieceAt(r, f) != null) pieces++
            }
        }
        val side = if (sideToMove == "w") "红方" else "黑方"
        return "${side}行棋 · ${pieces}子 · ${elapsedMs}ms"
    }

    private suspend fun tick() {
        val eng = engine
        val rd = reader
        val rect = boardRect
        if (eng?.isReady != true || rd == null || rect == null) {
            publish(
                when {
                    eng?.isReady != true -> "引擎未就绪"
                    rd == null -> "识别器未就绪"
                    else -> "请先标定棋盘范围"
                },
                State.WAITING_PERMISSION,
            )
            return
        }
        if (!CaptureService.isRunning) {
            publish("屏幕识别已断开，请回助手点悬浮重新授权", State.ERROR)
            return
        }

        // 自动走子冷却，避免读到动画中间态
        if (autoMoveOn && System.currentTimeMillis() - lastAutoAt < AUTO_COOLDOWN_MS) {
            publish("走子动画等待中…", State.PAUSED)
            return
        }

        val frame = withContext(Dispatchers.IO) { CaptureService.copyLatestBitmap() }
        if (frame == null) {
            publish("等待截屏画面…", State.WAITING_FRAME)
            return
        }

        publish("识别中…", State.RECOGNIZING)
        val started = System.currentTimeMillis()
        val board = withContext(Dispatchers.IO) { readBoardStable(rd, frame, rect) }
        val elapsed = System.currentTimeMillis() - started
        if (board == null) {
            publish("识别失败：画面异常", State.ERROR)
            return
        }

        val boardFen = boardFenWithSide(board)
        if (boardFen == lastAutoFen && System.currentTimeMillis() - lastAutoAt < AUTO_COOLDOWN_MS * 2) {
            publish("等待对方走子…", State.PAUSED)
            return
        }

        // 稳定窗口：连续相同才认定
        if (boardFen != pendingFen) {
            pendingFen = boardFen
            pendingHits = 1
            publish("识别中…（校验 $pendingHits/$STABLE_HITS）", State.RECOGNIZING)
            return
        }
        pendingHits++
        if (pendingHits < STABLE_HITS) {
            publish("识别中…（校验 $pendingHits/$STABLE_HITS）", State.RECOGNIZING)
            return
        }

        lastBoard = board
        lastFen = boardFen
        lastRecognizedOk = true
        lastRecognizedSummary = summarizeBoard(board, elapsed)
        publish(
            "已正常识别 · ${lastRecognizedSummary}",
            State.RECOGNIZING,
            fen = boardFen,
            board = board,
            recognizeMs = elapsed,
        )

        // 局面未变且已有结果 → 只刷新，不重算
        if (boardFen == lastResult.fen && lastResult.bestmove.isNotBlank()) {
            publish(
                "已正常识别 · 皮卡鱼:${resultText(lastResult)}",
                State.ANALYZING,
                fen = boardFen,
                board = board,
                result = lastResult,
            )
            return
        }

        publish("皮卡鱼分析中…", State.ANALYZING, fen = boardFen, board = board)
        val result = eng.analyze(
            fen = boardFen,
            // 纯分析：不套用设置里的深度/时间，固定短算一轮
            movetimeMs = if (useEngineLimits) thinkMs else 800,
            depth = if (useEngineLimits) searchDepth else 0,
            multiPv = multiPv.coerceIn(1, 5),
            infinite = false,
            history = emptyList(),
        )
        if (result.bestmove.isBlank()) {
            publish("已正常识别 · 引擎无着法", State.ERROR, fen = boardFen, board = board)
            return
        }
        lastResult = result
        publish(
            "已正常识别 · 皮卡鱼:${resultText(result)}",
            State.ANALYZING,
            fen = boardFen,
            board = board,
            result = result,
            recognizeMs = elapsed,
        )

        if (autoMoveOn && isRunning) {
            publish("自动走子…", State.AUTO_PLAYING, fen = boardFen, board = board, result = result)
            val ok = autoPlay(result.bestmove, rect)
            if (ok) {
                lastAutoFen = boardFen
                lastAutoAt = System.currentTimeMillis()
                // Pro：走完再等 500ms 再进入下一轮识别
                Thread.sleep(500)
                publish("已走 ${result.bestmove}", State.AUTO_PLAYING, fen = boardFen, board = board, result = result)
            } else {
                publish("自动走子失败（检查无障碍）", State.ERROR, fen = boardFen, board = board, result = result)
            }
        }
    }

    private fun readBoardStable(reader: ChessboardReader, frame: Bitmap, rect: BoardRect): Position? {
        return try {
            reader.readBoard(frame, rect)
        } catch (t: Throwable) {
            android.util.Log.w("Connect", "recognize failed", t)
            null
        }
    }

    private fun boardFenWithSide(board: Position): String {
        val parts = board.toFen().trim().split(Regex("\\s+")).toMutableList()
        while (parts.size < 2) parts.add("w")
        parts[1] = sideToMove
        return parts.joinToString(" ")
    }

    /**
     * 按 Pro象棋 FloatingWindowService.touchMove 的坐标方案点子：
     * width=rect.w/10, height=rect.h/11；
     * x = centerX - (4-file)*width；
     * rank<=4: y=top+height+rank*height，否则 y=bottom-height-(9-rank)*height；
     * 黑方走子时对 rank/file 做镜像；先点起点，再 150ms，后点终点。
     */
    private fun autoPlay(iccs: String, rect: BoardRect): Boolean {
        if (!LiveLinkService.isConnected) return false
        val match = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntentOrNull(iccs) ?: return false
        val fromFile = Position.FILE_NAMES.indexOf(match.first)
        val toFile = Position.FILE_NAMES.indexOf(match.third)
        if (fromFile < 0 || toFile < 0) return false
        var fromRank = 9 - match.second
        var toRank = 9 - match.fourth
        var fromCol = fromFile
        var toCol = toFile
        if (sideToMove == "b") {
            fromRank = 9 - fromRank
            fromCol = 8 - fromCol
            toRank = 9 - toRank
            toCol = 8 - toCol
        }
        val fx = proScreenX(rect, fromCol)
        val fy = proScreenY(rect, fromRank)
        val tx = proScreenX(rect, toCol)
        val ty = proScreenY(rect, toRank)
        val tappedFrom = LiveLinkService.tapAtSync(fx, fy)
        if (!tappedFrom) return false
        Thread.sleep(tapGapMs.coerceIn(80, 2000).toLong())
        return LiveLinkService.tapAtSync(tx, ty)
    }

    private fun proScreenX(rect: BoardRect, file: Int): Int {
        val width = ((rect.right - rect.left) / 10).coerceAtLeast(1)
        val centerX = (rect.left + rect.right) / 2
        return centerX - ((4 - file) * width)
    }

    private fun proScreenY(rect: BoardRect, rank: Int): Int {
        val height = ((rect.bottom - rect.top) / 11).coerceAtLeast(1)
        return if (rank <= 4) {
            rect.top + height + rank * height
        } else {
            rect.bottom - height - ((9 - rank) * height)
        }
    }

    private fun Regex.matchEntentOrNull(input: String): IccsParts? {
        val m = matchEntire(input.trim().lowercase()) ?: return null
        return IccsParts(
            m.groupValues[1],
            m.groupValues[2].toInt(),
            m.groupValues[3],
            m.groupValues[4].toInt(),
        )
    }

    private data class IccsParts(val first: String, val second: Int, val third: String, val fourth: Int)

    private fun publish(
        message: String,
        state: State = lastState,
        fen: String = lastFen,
        board: Position? = lastBoard,
        result: EngineResult = lastResult,
        recognizeMs: Long = 0,
    ) {
        lastMessage = message
        lastState = state
        val snap = Snapshot(
            state = state,
            message = message,
            fen = fen,
            board = board,
            recognizeMs = recognizeMs,
            result = result,
            autoMove = autoMoveOn,
            delayMs = tapGapMs,
            sideToMove = sideToMove,
            running = isRunning,
        )
        // 直接刷悬浮窗，MainActivity 销毁后仍可用
        try {
            val overlay = com.xqassist.overlay.OverlayService.overlayDisplay
            overlay?.updateConnect(
                autoOn = autoMoveOn,
                delayMs = tapGapMs,
                sideLabel = if (sideToMove == "w") "红方" else "黑方",
                running = isRunning,
                message = message,
            )
            val engText = if (result.bestmove.isBlank()) {
                if (lastRecognizedSummary.isNotBlank()) lastRecognizedSummary else "-"
            } else {
                "深度${result.depth} ${result.bestmove}"
            }
            overlay?.updateInfo(
                if (appContext != null) "" else "-",
                engText,
                "",
            )
        } catch (_: Throwable) {
        }
        onSnapshot?.invoke(snap)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPrefs(context: Context) {
        val p = prefs(context)
        val left = p.getInt(KEY_LEFT, Int.MIN_VALUE)
        val top = p.getInt(KEY_TOP, Int.MIN_VALUE)
        val right = p.getInt(KEY_RIGHT, Int.MIN_VALUE)
        val bottom = p.getInt(KEY_BOTTOM, Int.MIN_VALUE)
        if (left != Int.MIN_VALUE && top != Int.MIN_VALUE && right != Int.MIN_VALUE && bottom != Int.MIN_VALUE) {
            boardRect = BoardRect(left, top, right, bottom)
        }
        flipped = p.getBoolean(KEY_FLIPPED, false)
        sideToMove = p.getString(KEY_SIDE, "w") ?: "w"
        intervalMs = p.getInt(KEY_DELAY, 1200)
        autoMoveOn = p.getBoolean(KEY_AUTO, false)
    }

    private fun savePrefs(context: Context) {
        val rect = boardRect
        prefs(context).edit().apply {
            if (rect != null) {
                putInt(KEY_LEFT, rect.left)
                putInt(KEY_TOP, rect.top)
                putInt(KEY_RIGHT, rect.right)
                putInt(KEY_BOTTOM, rect.bottom)
            }
            putBoolean(KEY_FLIPPED, flipped)
            putString(KEY_SIDE, sideToMove)
            putInt(KEY_DELAY, intervalMs)
            putBoolean(KEY_AUTO, autoMoveOn)
            apply()
        }
    }
}
