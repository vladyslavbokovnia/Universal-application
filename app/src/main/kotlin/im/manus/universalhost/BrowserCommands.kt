package im.manus.universalhost

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Команды браузеру извне (Micro Gesture и подобные приложения): ярлыки приложения, ссылки
 * universal://browser/<команда> и broadcast im.manus.universalhost.BROWSER_COMMAND с параметром cmd.
 * Все три входа сходятся здесь и передают команду модулю «Браузер».
 */
object BrowserCommands {

    const val ACTION = "im.manus.universalhost.BROWSER_COMMAND"
    const val EXTRA_CMD = "cmd"
    private const val BROWSER_MODULE = "Браузер"

    /** Имя команды из интента: параметр cmd, иначе последний сегмент ссылки universal://browser/<команда>. */
    @JvmStatic
    fun nameFrom(intent: Intent?): String? {
        if (intent == null) return null
        val extra = intent.getStringExtra(EXTRA_CMD)
        if (!extra.isNullOrBlank()) return extra.trim()
        val data: Uri = intent.data ?: return null
        if (data.scheme != "universal" || data.host != "browser") return null
        val seg = data.lastPathSegment
        return if (seg.isNullOrBlank()) null else seg.trim()
    }

    /** Передать команду модулю. open_browser открывает окно браузера, если его ещё нет. */
    @JvmStatic
    fun dispatch(context: Context, name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return try {
            if (!ModuleStorage.isEnabled(context, BROWSER_MODULE)) return false
            val plugin = PluginRegistry.find(context, BROWSER_MODULE) ?: return false
            val data = HashMap<String, Any>()
            if (name == "open_browser") {
                data["cmd"] = "open"
                data["url"] = ""
            } else {
                data["cmd"] = "command"
                data["name"] = name
            }
            plugin.execute(data) == true
        } catch (t: Throwable) {
            false
        }
    }
}
