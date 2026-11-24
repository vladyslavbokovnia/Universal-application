package im.manus.universalhost

import android.content.Context
import android.os.Build

/**
 * Единый отчёт для диагностики: заголовок приложения и отчёты всех работающих модулей.
 * Модуль участвует, если у него есть открытый метод `String diagnosticsReport()`; интерфейс не нужен,
 * поэтому модули можно обновлять отдельно от приложения.
 */
object DiagnosticsCollector {
    fun collect(ctx: Context): String {
        val sb = StringBuilder()
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (t: Throwable) { "?" }
        sb.append("Universal host ").append(ver).append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        val plugins = try { PluginRegistry.scan(ctx) } catch (t: Throwable) { emptyList() }
        var any = false
        for (p in plugins) {
            val text = try {
                p.javaClass.getMethod("diagnosticsReport").invoke(p) as? String
            } catch (t: NoSuchMethodException) { null } catch (t: Throwable) { "ошибка отчёта: $t" }
            if (text.isNullOrBlank()) continue
            any = true
            sb.append("\n######## ").append(p.name).append(" v").append(p.version).append(" ########\n").append(text.trim()).append('\n')
        }
        if (!any) sb.append("\nНи один модуль не передал отчёт (модули обновляются кнопкой «модули»).\n")
        return sb.toString()
    }
}
