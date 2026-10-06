package im.manus.universalhost

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Экран-контейнер поверх блокировки для модулей (заставка ClockWallpaper). DEX-модуль не может
 * объявить activity в манифесте, поэтому окно принадлежит хосту, а содержимое создаёт модуль:
 *   execute("cmd" = "createView", "activity", "intent")  -> View
 *   execute("cmd" = "newIntent" | "back" | "destroyView", "intent")
 * Имя модуля передаётся в extra "module".
 */
class ModuleWakeActivity : Activity() {

    private var plugin: IPlugin? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val found = intent.getStringExtra("module")?.let { PluginRegistry.find(this, it) }
        val view = try {
            found?.execute(mapOf("cmd" to "createView", "activity" to this, "intent" to intent)) as? View
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
        if (found == null || view == null) {
            finishAndRemoveTask()
            return
        }
        plugin = found
        setContentView(FrameLayout(this).apply { addView(view) })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        call("newIntent", intent)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        call("back", intent)
        finish()
    }

    override fun onDestroy() {
        call("destroyView", intent)
        plugin = null
        super.onDestroy()
    }

    private fun call(cmd: String, data: Intent) {
        try {
            plugin?.execute(mapOf("cmd" to cmd, "intent" to data))
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}
