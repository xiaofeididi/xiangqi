package com.xqassist.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局共享的皮卡鱼实例。
 * MainActivity / OverlayService 都从这里取，避免起两个引擎进程。
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    @Volatile
    var engine: UcciEngine? = null
        private set

    @Volatile
    var engineLabel: String = "内置皮卡鱼"

    private val starting = AtomicBoolean(false)

    fun ensure(context: Context, onReady: (UcciEngine?) -> Unit) {
        val current = engine
        if (current?.isReady == true) {
            onReady(current)
            return
        }
        if (!starting.compareAndSet(false, true)) {
            Thread {
                var wait = 0
                while (engine?.isReady != true && wait < 80) {
                    Thread.sleep(200)
                    wait++
                }
                onReady(engine)
            }.start()
            return
        }
        Thread {
            try {
                val app = context.applicationContext
                val (bin, nnue) = EngineInstaller.resolve(app)
                engineLabel = when (val n = EngineInstaller.preferredName(app)) {
                    "builtin" -> "内置皮卡鱼"
                    else -> n
                }
                Log.i(TAG, "start engine bin=${bin.absolutePath} nnue=${nnue?.absolutePath}")
                val eng = UcciEngine(bin, nnue, 2, 128)
                eng.start()
                engine = eng
                starting.set(false)
                onReady(eng)
            } catch (t: Throwable) {
                Log.w(TAG, "start engine failed", t)
                starting.set(false)
                onReady(null)
            }
        }.start()
    }

    /** 切换引擎：停旧进程，按新配置重启 */
    fun switchTo(context: Context, name: String, onReady: (UcciEngine?) -> Unit) {
        EngineInstaller.setPreferredName(context, name)
        val old = engine
        engine = null
        Thread {
            try {
                old?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "stop old engine", t)
            }
            starting.set(false)
            ensure(context, onReady)
        }.start()
    }
}
