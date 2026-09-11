package com.xqassist.engine

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局共享的皮卡鱼实例。
 * MainActivity / OverlayService 都从这里取，避免起两个引擎进程。
 */
object EngineHolder {

    @Volatile
    var engine: UcciEngine? = null
        private set

    private val starting = AtomicBoolean(false)

    /** 已就绪则同步回调；否则后台安装并启动，完成后在回调线程返回（可能是工作线程） */
    fun ensure(context: Context, onReady: (UcciEngine?) -> Unit) {
        val current = engine
        if (current?.isReady == true) {
            onReady(current)
            return
        }
        if (!starting.compareAndSet(false, true)) {
            // 已有线程在启动；稍后再查
            Thread {
                var wait = 0
                while (engine?.isReady != true && wait < 60) {
                    Thread.sleep(200)
                    wait++
                }
                onReady(engine)
            }.start()
            return
        }
        Thread {
            try {
                val file = EngineInstaller.install(context.applicationContext)
                if (file == null) {
                    starting.set(false)
                    onReady(null)
                    return@Thread
                }
                val eng = UcciEngine(file, EngineInstaller.nnueFile(context.applicationContext), 2, 128)
                eng.start()
                engine = eng
                starting.set(false)
                onReady(eng)
            } catch (t: Throwable) {
                starting.set(false)
                onReady(null)
            }
        }.start()
    }
}
