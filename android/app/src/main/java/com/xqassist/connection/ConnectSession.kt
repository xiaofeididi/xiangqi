package com.xqassist.connection

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xqassist.book.BookManager
import com.xqassist.capture.CaptureService
import com.xqassist.core.Position
import com.xqassist.core.Quad
import com.xqassist.engine.EngineResult
import com.xqassist.engine.UcciEngine
import com.xqassist.overlay.OverlayService
import com.xqassist.vision.BoardAutoDetector
import com.xqassist.vision.BoardRect
import com.xqassist.vision.ChessboardReader
import com.xqassist.vision.PieceFirstReader
import com.xqassist.vision.TemplateBankLoader
import com.xqassist.vision.YoloDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 连线分析会话（对齐 Pro FloatingWindowService 的识别线程）。
 * 不依赖 Activity：引擎/识别器由 OverlayService 注入，状态直接刷到悬浮窗。
 */
object ConnectSession {

    private const val TAG = "Connect"
    private const val STABLE_HITS = 2
    private const val AUTO_COOLDOWN_MS = 900L
    private const val PREFS = "connect_session"
    private const val KEY_LEFT = "rect_left"
    private const val KEY_TOP = "rect_top"
    private const val KEY_RIGHT = "rect_right"
    private const val KEY_BOTTOM = "rect_bottom"
    private const val KEY_SIDE = "side"
    private const val KEY_AUTO = "auto_move"
    private const val KEY_INTERVAL = "interval_ms"

    enum class State {
        IDLE, WAITING_FRAME, RECOGNIZING, ANALYZING, AUTO_PLAYING, PAUSED, ERROR
    }

    @Volatile
    var boardRect: BoardRect? = null
        private set

    @Volatile
    var sideToMove: String = "w"

    /** 屏幕是否为黑方视角（帅在上半区）；出子坐标需 180° 镜像 */
    @Volatile
    var screenFlipped: Boolean = false
        private set

    @Volatile
    var autoMoveOn: Boolean = false

    @Volatile
    var intervalMs: Int = 2000

    @Volatile
    var tapGapMs: Int = 150

    @Volatile
    var thinkMs: Int = 3000

    @Volatile
    var searchDepth: Int = 0

    @Volatile
    var useEngineLimits: Boolean = false

    @Volatile
    var isRunning: Boolean = false
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
    var lastRecognizedOk: Boolean = false
        private set

    @Volatile
    var lastRecognizedSummary: String = ""
        private set

    /** 迷你棋盘箭头：引擎 PV 前 2 步 */
    @Volatile
    var lastHintMoves: List<com.xqassist.core.Quad> = emptyList()
        private set

    /** 主界面可选监听（本地棋盘跟随）；悬浮窗由 publish 直接刷新 */
    var onSnapshot: ((State, String, String, EngineResult) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var engine: UcciEngine? = null
    private var reader: ChessboardReader? = null
    private var appContext: Context? = null

    private var pendingFen = ""
    private var pendingHits = 0
    private var lastAutoFen = ""
    private var lastAutoAt = 0L

    @Volatile
    private var emptyBoardTicks = 0

    @Volatile
    private var lastDetectNote = ""

    fun clearBoardRect() {
        boardRect = null
        appContext?.let { savePrefs(it) }
        publish("棋盘范围已清空，将重新自动找盘")
    }


    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadPrefs(appContext!!)
    }

    fun setBoardRect(rect: BoardRect?) {
        boardRect = rect
        appContext?.let { savePrefs(it) }
        if (rect != null) publish("棋盘范围已更新") else publish("棋盘范围已清空，将自动找盘")
    }

    fun provideEngine(e: UcciEngine?) {
        engine = e
    }

    fun provideReader(r: ChessboardReader?) {
        reader = r
    }

    fun toggleSide() {
        sideToMove = if (sideToMove == "w") "b" else "w"
        appContext?.let { savePrefs(it) }
        publish("行棋方：${if (sideToMove == "w") "红方" else "黑方"}")
    }

    fun setAutoMove(enabled: Boolean) {
        autoMoveOn = enabled
        appContext?.let { savePrefs(it) }
        publish(if (enabled) "自动走：开" else "自动走：关")
    }

    fun applyIntervalMs(value: Int) {
        intervalMs = value.coerceIn(400, 8000)
        appContext?.let { savePrefs(it) }
        publish("识别间隔 ${intervalMs}ms")
    }

    /** 启动识别循环（对齐 Pro 的 g 线程） */
    fun start() {
        if (isRunning) {
            stop()
            return
        }
        if (engine?.isReady != true) {
            publish("引擎未就绪", State.ERROR)
            return
        }
        if (!YoloDetector.isReady && reader == null) {
            publish("识别器未就绪", State.ERROR)
            return
        }
        if (!CaptureService.isRunning) {
            publish("屏幕识别未开，请回助手授权", State.ERROR)
            return
        }
        pendingFen = ""
        pendingHits = 0
        lastAutoFen = ""
        lastRecognizedOk = false
        lastRecognizedSummary = ""
        isRunning = true
        loopJob = scope.launch {
            publish("连线分析已启动" + if (boardRect == null) "，自动找棋盘…" else "")
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

    /** 手动出招：用当前分析结果点子 */
    fun playBestNow(onDone: ((Boolean) -> Unit)? = null) {
        val eng = engine
        val fen = lastFen
        val rect = boardRect
        if (eng?.isReady != true || fen.isBlank() || rect == null) {
            onDone?.invoke(false)
            return
        }
        scope.launch {
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
                publish("已出子 ${iccs}", State.AUTO_PLAYING, fen = fen, result = result)
            }
            withContext(Dispatchers.Main) { onDone?.invoke(ok) }
        }
    }

    private suspend fun tick() {
        val eng = engine
        val yoloOk = YoloDetector.isReady
        val rd = reader
        if (eng?.isReady != true) {
            publish("引擎未就绪", State.ERROR)
            return
        }
        if (!yoloOk && rd == null) {
            publish("识别器未就绪", State.ERROR)
            return
        }
        if (!CaptureService.isRunning) {
            publish("屏幕识别已断开，请回助手重新授权", State.ERROR)
            return
        }
        if (autoMoveOn && System.currentTimeMillis() - lastAutoAt < AUTO_COOLDOWN_MS) {
            publish("走子冷却中…", State.PAUSED)
            return
        }

        val frame = withContext(Dispatchers.IO) { CaptureService.copyLatestBitmap() }
        if (frame == null) {
            publish("等待截屏画面…", State.WAITING_FRAME)
            return
        }

        val started = System.currentTimeMillis()
        // 优先 Pro YOLO；失败再回退模板格点
        val recognized = withContext(Dispatchers.IO) {
            if (yoloOk) recognizeByYolo(frame) else recognizeByTemplate(frame, rd)
        }
        val elapsed = System.currentTimeMillis() - started

        if (recognized is RecognizeResult.NeedRect) {
            clearStaleResult()
            publish(recognized.message, State.ERROR)
            return
        }
        if (recognized is RecognizeResult.Fail) {
            clearStaleResult()
            publish(recognized.message, State.ERROR)
            return
        }
        val ok = recognized as RecognizeResult.Ok
        val board = ok.board
        val pieceCount = countPieces(board)
        val hasWhiteKing = hasKing(board, "w")
        val hasBlackKing = hasKing(board, "b")
        if (pieceCount < 4 || !hasWhiteKing || !hasBlackKing) {
            lastRecognizedOk = false
            lastRecognizedSummary = ""
            clearStaleResult()
            if (pieceCount < 2) {
                emptyBoardTicks++
                if (emptyBoardTicks >= 3 && boardRect != null) {
                    emptyBoardTicks = 0
                    boardRect = null
                    appContext?.let { savePrefs(it) }
                    publish("连续未识别到棋子，已清空棋盘范围并重新找盘", State.ERROR)
                    return
                }
            } else {
                emptyBoardTicks = 0
            }
            val why = when {
                pieceCount < 4 -> "子数过少(${pieceCount})·请对准棋盘"
                !hasWhiteKing && !hasBlackKing -> "未识别到双方将帅(${pieceCount}子)"
                !hasWhiteKing -> "未识别到红帅(${pieceCount}子)"
                else -> "未识别到黑将(${pieceCount}子)"
            }
            publish("非有效局面：$why", State.ERROR)
            return
        }
        emptyBoardTicks = 0

        val boardFen = boardFenWithSide(board)
        if (boardFen == lastAutoFen && System.currentTimeMillis() - lastAutoAt < AUTO_COOLDOWN_MS * 2) {
            publish("等待对方走子…", State.PAUSED)
            return
        }
        if (boardFen != pendingFen) {
            pendingFen = boardFen
            pendingHits = 1
            publish("识别中…（校验 $pendingHits/$STABLE_HITS · ${pieceCount}子）", State.RECOGNIZING)
            return
        }
        pendingHits++
        if (pendingHits < STABLE_HITS) {
            publish("识别中…（校验 $pendingHits/$STABLE_HITS · ${pieceCount}子）", State.RECOGNIZING)
            return
        }

        lastBoard = board
        lastFen = boardFen
        lastRecognizedOk = true
        lastRecognizedSummary = summarizeBoard(board, elapsed) +
            if (YoloDetector.isReady) " · ${YoloDetector.lastDetectSummary}" else ""
        Log.i(TAG, "ok pieces=$pieceCount fen=$boardFen")

        // 局面变了：立刻清掉旧着法，避免悬浮窗一直显示上一步「象四进六」
        if (lastResult.fen != boardFen) {
            lastResult = EngineResult()
            lastHintMoves = emptyList()
            BookManager.clearHit()
        }
        publish("已正常识别 · ${lastRecognizedSummary}", State.RECOGNIZING, fen = boardFen, board = board)

        // 同一 FEN 已有结果：直接复用，不再 YOLO/查库/引擎
        if (boardFen == lastResult.fen && lastResult.bestmove.isNotBlank()) {
            publish("已正常识别 · 皮卡鱼:${resultText(lastResult)}", State.ANALYZING, fen = boardFen, board = board, result = lastResult)
            return
        }

        // 开局库：启用时本地优先；云库仅开局子多时查
        val bookHits = withContext(Dispatchers.IO) {
            try {
                val ctx = appContext ?: return@withContext emptyList()
                if (!BookManager.enabled) return@withContext emptyList()
                val localOk = BookManager.mode == 1 && BookManager.bookName.isNotBlank()
                if (localOk || pieceCount >= 26) {
                    BookManager.query(ctx, board)
                } else {
                    emptyList()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "book query failed", t)
                emptyList()
            }
        }
        if (bookHits.isNotEmpty()) {
            val best = bookHits.first()
            val bookHint = iccsToQuad(best.move)
            lastHintMoves = listOfNotNull(bookHint)
            lastResult = EngineResult(bestmove = best.move, fen = boardFen)
            publish(
                "已正常识别 · 开局库:${best.chinese(board)} ${best.score}分 [${best.source}] · ${lastRecognizedSummary}",
                State.ANALYZING,
                fen = boardFen,
                board = board,
                result = lastResult,
            )
            if (autoMoveOn && isRunning) {
                val rect = boardRect ?: return
                publish("开局库自动走子…", State.AUTO_PLAYING, fen = boardFen, board = board, result = lastResult)
                val played = autoPlay(best.move, rect)
                if (played) {
                    lastAutoFen = boardFen
                    lastAutoAt = System.currentTimeMillis()
                    Thread.sleep(500)
                    publish("已走 ${best.move}", State.AUTO_PLAYING, fen = boardFen, board = board, result = lastResult)
                }
            }
            return
        }
        BookManager.clearHit()

        publish("皮卡鱼分析中…", State.ANALYZING, fen = boardFen, board = board)
        // 分析：固定短算，忽略深度/时间设置；仅出子(useEngineLimits)才用设置
        val result = eng.analyze(
            fen = boardFen,
            movetimeMs = if (useEngineLimits) thinkMs else 800,
            depth = if (useEngineLimits) searchDepth else 0,
            multiPv = 3,
            infinite = false,
            history = emptyList(),
            onInfo = { partial ->
                if (partial.bestmove.isNotBlank() && isRunning) {
                    lastResult = partial
                    lastHintMoves = pvToHints(partial, board)
                    publish(
                        "已正常识别 · 皮卡鱼:${resultText(partial)}",
                        State.ANALYZING,
                        fen = boardFen,
                        board = board,
                        result = partial,
                    )
                }
            },
        )
        if (result.bestmove.isBlank()) {
            publish("已正常识别 · 引擎无着法（局面可能非法）", State.ERROR, fen = boardFen, board = board)
            return
        }
        lastResult = result
        lastHintMoves = pvToHints(result, board)
        publish("已正常识别 · 皮卡鱼:${resultText(result)}", State.ANALYZING, fen = boardFen, board = board, result = result)

        if (autoMoveOn && isRunning) {
            val rect = boardRect ?: return
            publish("自动走子…", State.AUTO_PLAYING, fen = boardFen, board = board, result = result)
            val played = autoPlay(result.bestmove, rect)
            if (played) {
                lastAutoFen = boardFen
                lastAutoAt = System.currentTimeMillis()
                Thread.sleep(500)
                publish("已走 ${result.bestmove}", State.AUTO_PLAYING, fen = boardFen, board = board, result = result)
            } else {
                publish("自动走子失败（检查无障碍）", State.ERROR, fen = boardFen, board = board, result = result)
            }
        }
    }

    private sealed class RecognizeResult {
        data class Ok(val board: Position) : RecognizeResult()
        data class Fail(val message: String) : RecognizeResult()
        data class NeedRect(val message: String) : RecognizeResult()
    }

    /** 识别失败时清掉旧着法，避免悬浮窗一直显示上一步的「象四进六」 */
    /** 识别失败/新局时清掉旧着法，避免悬浮窗一直显示上一步「象四进六」或误报绝杀 */
    fun clearStaleResult() {
        lastResult = EngineResult()
        lastFen = ""
        lastBoard = null
        lastRecognizedOk = false
        lastHintMoves = emptyList()
        BookManager.clearHit()
    }

    private fun iccsToQuad(iccs: String): Quad? {
        val m = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(iccs.trim().lowercase()) ?: return null
        val fromFile = Position.FILE_NAMES.indexOf(m.groupValues[1])
        val toFile = Position.FILE_NAMES.indexOf(m.groupValues[3])
        val fromRank = 9 - m.groupValues[2].toInt()
        val toRank = 9 - m.groupValues[4].toInt()
        if (fromFile !in 0..8 || toFile !in 0..8) return null
        return Quad(fromRank, fromFile, toRank, toFile)
    }

    private fun pvToHints(result: EngineResult, board: Position?): List<Quad> {
        if (board == null || result.pv.isEmpty()) return emptyList()
        val sim = board.copy()
        val out = mutableListOf<Quad>()
        for (m in result.pv.take(2)) {
            val q = iccsToQuad(m) ?: break
            out += q
            try { sim.applyIccs(m) } catch (_: Throwable) { break }
        }
        return out
    }

    /** Pro 路径：YOLO 检测 → 优先棋盘框(classId=14) → 格点 → 帅位翻转 */
    private fun recognizeByYolo(frame: android.graphics.Bitmap): RecognizeResult {
        val dets = YoloDetector.detectPieces(frame)
            ?: return RecognizeResult.Fail("YOLO 检测失败：${YoloDetector.lastError.ifBlank { "无结果" }}")
        if (dets.isEmpty()) {
            return RecognizeResult.Fail("YOLO 0 子，请确认画面是棋盘")
        }
        // 优先用 Pro 的棋盘框（classId==14），fallback 到子心拟合
        val outer = YoloDetector.findBoardRect(dets)
            ?: YoloDetector.outerFromPieces(dets)
            ?: return RecognizeResult.Fail("YOLO 无法定位棋盘(n=${dets.size})")
        boardRect = outer
        appContext?.let { savePrefs(it) }
        val gridRaw = YoloDetector.toGrid(dets, outer)
        val oriented = YoloDetector.orient(gridRaw)
        screenFlipped = oriented !== gridRaw
        return RecognizeResult.Ok(YoloDetector.gridToPosition(oriented))
    }

    /** 回退：先找子再归格 + 宫位规则 */
    private fun recognizeByTemplate(frame: android.graphics.Bitmap, rd: com.xqassist.vision.ChessboardReader?): RecognizeResult {
        var rect = boardRect
        if (rect == null) {
            rect = try {
                BoardAutoDetector.detect(frame)
            } catch (t: Throwable) {
                Log.w(TAG, "BoardAutoDetector failed", t)
                null
            }
            if (rect == null) {
                return RecognizeResult.NeedRect("未找到棋盘，请回助手校准")
            }
            boardRect = rect
            appContext?.let { savePrefs(it) }
        }
        // 优先：墨迹找子 → 归格 → 宫位规则
        appContext?.let { TemplateBankLoader.ensure(it) }
        val pieceFirst = try {
            PieceFirstReader.read(frame, rect)
        } catch (t: Throwable) {
            Log.w(TAG, "pieceFirst failed", t)
            null
        }
        if (pieceFirst != null) {
            val (pos, summary) = pieceFirst
            lastDetectNote = summary
            return RecognizeResult.Ok(orientBoard(pos))
        }
        val reader = rd ?: return RecognizeResult.Fail("识别器未就绪")
        val board = try {
            reader.readBoard(frame, rect)
        } catch (t: Throwable) {
            Log.w(TAG, "readBoard failed", t)
            return RecognizeResult.Fail("识别失败：画面异常或棋盘范围不对")
        }
        return RecognizeResult.Ok(orientBoard(board))
    }

    /** 红帅在上半区 → 黑方视角，整盘 180° 旋转，使 FEN 始终红方在底 */
    private fun orientBoard(board: Position): Position {
        var redKingRank = -1
        for (r in 0 until 10) {
            for (f in 0 until 9) {
                if (board.pieceAt(r, f) == "wk") redKingRank = r
            }
        }
        screenFlipped = redKingRank in 0..4
        if (!screenFlipped) return board
        val flipped = board.copy()
        for (r in 0 until 10) {
            for (f in 0 until 9) {
                flipped.setPiece(r, f, board.pieceAt(9 - r, 8 - f))
            }
        }
        return flipped
    }

    private fun boardFenWithSide(board: Position): String {
        val parts = board.toFen().trim().split(Regex("\\s+")).toMutableList()
        while (parts.size < 2) parts.add("w")
        parts[1] = sideToMove
        return parts.joinToString(" ")
    }

    private fun countPieces(board: Position): Int {
        var n = 0
        for (r in 0 until 10) for (f in 0 until 9) if (board.pieceAt(r, f) != null) n++
        return n
    }

    private fun hasKing(board: Position, side: String): Boolean {
        for (r in 0 until 10) for (f in 0 until 9) {
            val p = board.pieceAt(r, f) ?: continue
            if (p == side + "k") return true
        }
        return false
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
        for (r in 0 until 10) for (f in 0 until 9) if (board.pieceAt(r, f) != null) pieces++
        val side = if (sideToMove == "w") "红方" else "黑方"
        return "${side}行棋 · ${pieces}子 · ${elapsedMs}ms"
    }

    /** Pro 坐标方案点子：width=w/10, height=h/11，黑方镜像，短按间隔 tapGapMs */
    private fun autoPlay(iccs: String, rect: BoardRect): Boolean {
        if (!LiveLinkService.isConnected) return false
        val match = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(iccs.trim().lowercase()) ?: return false
        val fromFile = Position.FILE_NAMES.indexOf(match.groupValues[1])
        val toFile = Position.FILE_NAMES.indexOf(match.groupValues[3])
        if (fromFile < 0 || toFile < 0) return false
        var fromRank = 9 - match.groupValues[2].toInt()
        var toRank = 9 - match.groupValues[4].toInt()
        var fromCol = fromFile
        var toCol = toFile
        // 屏幕是黑方视角时，所有着法都要 180° 镜像到实际画面
        if (sideToMove == "b" || screenFlipped) {
            fromRank = 9 - fromRank; fromCol = 8 - fromCol
            toRank = 9 - toRank; toCol = 8 - toCol
        }
        val (fx, fy) = proScreen(rect, fromCol, fromRank)
        val (tx, ty) = proScreen(rect, toCol, toRank)
        if (!LiveLinkService.tapAtSync(fx, fy)) return false
        Thread.sleep(tapGapMs.coerceIn(80, 2000).toLong())
        return LiveLinkService.tapAtSync(tx, ty)
    }

    private fun proScreen(rect: BoardRect, file: Int, rank: Int): Pair<Int, Int> {
        val w = ((rect.right - rect.left) / 10).coerceAtLeast(1)
        val h = ((rect.bottom - rect.top) / 11).coerceAtLeast(1)
        val cx = (rect.left + rect.right) / 2
        val x = cx - ((4 - file) * w)
        val y = if (rank <= 4) rect.top + h + rank * h else rect.bottom - h - ((9 - rank) * h)
        return x to y
    }

    private fun publish(
        message: String,
        state: State = State.IDLE,
        fen: String = lastFen,
        board: Position? = lastBoard,
        result: EngineResult = lastResult,
    ) {
        lastMessage = message
        try {
            OverlayService.overlayDisplay?.updateConnect(
                autoOn = autoMoveOn,
                delayMs = tapGapMs,
                sideLabel = if (sideToMove == "w") "红方" else "黑方",
                running = isRunning,
                message = message,
            )
        } catch (_: Throwable) {
        }
        onSnapshot?.invoke(state, message, fen, result)
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
        sideToMove = p.getString(KEY_SIDE, "w") ?: "w"
        intervalMs = p.getInt(KEY_INTERVAL, 2000)
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
            } else {
                remove(KEY_LEFT)
                remove(KEY_TOP)
                remove(KEY_RIGHT)
                remove(KEY_BOTTOM)
            }
            putString(KEY_SIDE, sideToMove)
            putInt(KEY_INTERVAL, intervalMs)
            putBoolean(KEY_AUTO, autoMoveOn)
            apply()
        }
    }
}
