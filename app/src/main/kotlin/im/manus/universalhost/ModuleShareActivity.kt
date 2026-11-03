package im.manus.universalhost

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Приёмник «Поделиться» для модулей (PWA-стиль).
 *
 * Виден в системном меню шаринга (intent-filter — в манифесте), находит включённый
 * модуль с IShareHandler и подходящим MIME-типом и отдаёт ему файл.
 * Сам ничего не показывает и сразу закрывается.
 */
class ModuleShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var handled = false
        try {
            val inIntent = intent
            val type = inIntent?.type
            if (inIntent != null && Intent.ACTION_SEND == inIntent.action && type != null) {
                val plugins = PluginRegistry.scan(this)
                for (p in plugins) {
                    val sh = p as? IShareHandler ?: continue
                    if (!ModuleStorage.isEnabled(this, p.name)) continue
                    val ok = try {
                        sh.shareMimeTypes().any { mimeMatch(it, type) }
                    } catch (t: Throwable) { false }
                    if (!ok) continue
                    handled = try { sh.onShare(this, inIntent) } catch (t: Throwable) { false }
                    if (handled) break
                }
            }
        } catch (t: Throwable) { }
        if (!handled) {
            try { Toast.makeText(this, "Нечем принять", Toast.LENGTH_SHORT).show() } catch (t: Throwable) { }
        }
        finish()
    }

    private fun mimeMatch(pattern: String, type: String): Boolean {
        if (pattern == "*/*") return true
        if (pattern.endsWith("/*")) return type.startsWith(pattern.dropLast(1))
        return pattern.equals(type, ignoreCase = true)
    }
}
