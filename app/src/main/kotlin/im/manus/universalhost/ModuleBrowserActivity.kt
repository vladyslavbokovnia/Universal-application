package im.manus.universalhost

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout

/**
 * Обычное окно (с записью в «Недавних», поворотом, без флагов экрана блокировки) для модулей-браузеров.
 * Модуль задаётся extra "module"; сам вид строит модуль командой createView.
 * Команды модулю: createView, newIntent, back, destroyView, activityResult.
 */
class ModuleBrowserActivity : Activity() {

    private companion object {
        const val BROWSER_MODULE = "Браузер"
    }

    private var plugin: IPlugin? = null
    private var name: String? = null

    private fun call(cmd: String, extra: Map<String, Any> = emptyMap()): Any? {
        val plugin = plugin ?: return null
        val data = HashMap<String, Any>()
        data["cmd"] = cmd
        data["activity"] = this
        data["intent"] = intent
        data.putAll(extra)
        return try { plugin.execute(data) } catch (t: Throwable) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = moduleName()
        plugin = name?.let { PluginRegistry.find(this, it) }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val v = call("createView") as? View
        if (v == null) {
            finishAndRemoveTask()
            return
        }
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applyTaskDescription()
    }

    /** Своя карточка в недавних: название и значок модуля, а не хоста. */
    @Suppress("DEPRECATION")
    private fun applyTaskDescription() {
        val n = name ?: return
        try {
            setTaskDescription(android.app.ActivityManager.TaskDescription(n, ModuleWindows.icon(this, n, 192), Color.BLACK))
        } catch (t: Throwable) { }
    }

    override fun onResume() {
        super.onResume()
        val n = name ?: return
        // как открыть это окно снова из модуля «Недавние»
        ModuleWindows.record(this, n, Intent().setClassName(packageName, "im.manus.universalhost.BrowserApp"))
    }

    /**
     * Запуск через мини-приложение «Браузер» (значок, «Поделиться», «Открыть с помощью») приходит без extra "module":
     * имя модуля тогда берётся из настройки хоста, а чужие extra не принимаются. Внутренние запуски задают модуль сами.
     */
    private fun moduleName(): String? {
        val viaAlias = intent.component?.className?.endsWith(".BrowserApp") == true
        return if (viaAlias) BROWSER_MODULE else intent.getStringExtra("module")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        call("newIntent")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        call("back")
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val extra = HashMap<String, Any>()
        extra["requestCode"] = requestCode
        extra["resultCode"] = resultCode
        if (data != null) extra["data"] = data
        call("activityResult", extra)
    }

    override fun onDestroy() {
        call("destroyView")
        super.onDestroy()
    }
}
