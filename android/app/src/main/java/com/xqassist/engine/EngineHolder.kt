package com.xqassist.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局共享的皮卡鱼实例。
 * MainActivity / OverlayService / ConnectSession 都从这里取，避免起两个引擎进程。
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
            val result = startEngineInternal(context)
            starting.set(false)
            onReady(result)
        }.start()
    }

    /**
     * 切换引擎：停旧进程，按新配置重启。
     * 失败时自动回退内置引擎，保证 engineReady 不会永久 false。
     */
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
            val eng = startEngineInternal(context)
            if (eng == null || !eng.isReady) {
                Log.w(TAG, "switch to $name failed, fallback builtin")
                EngineInstaller.setPreferredName(context, "builtin")
                engineLabel = "内置皮卡鱼"
                val fallback = startEngineInternal(context)
                engine = fallback
                onReady(fallback)
            } else {
                engine = eng
                onReady(eng)
            }
        }.start()
    }

    /** 启动引擎，失败返回 null（不在此处回退，由调用方决定） */
    private fun startEngineInternal(context: Context): UcciEngine? {
        return try {
            val app = context.applicationContext
            val (bin, nnue) = EngineInstaller.resolve(app)
            val prefName = EngineInstaller.preferredName(app)
            engineLabel = when (prefName) {
                "builtin" -> "内置皮卡鱼"
                else -> prefName
            }
            Log.i(TAG, "start engine bin=${bin.absolutePath} nnue=${nnue?.absolutePath}")
            val eng = UcciEngine(bin, nnue, 2, 128)
            val ok = eng.start()
            if (ok) {
                engine = eng
                eng
            } else {
                Log.w(TAG, "engine start failed: ${bin.absolutePath}")
                eng.stop()
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start engine exception", t)
            null
        }
    }
}
