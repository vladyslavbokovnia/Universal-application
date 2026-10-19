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

    private var plugin: IPlugin? = null

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
        plugin = intent.getStringExtra("module")?.let { PluginRegistry.find(this, it) }
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
