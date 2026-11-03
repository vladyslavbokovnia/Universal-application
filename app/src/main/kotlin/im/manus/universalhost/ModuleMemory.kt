package im.manus.universalhost

import android.app.ActivityManager
import android.content.Context

/**
 * Выгрузка модуля из памяти. Закрывает окно модуля, останавливает работающий объект и забывает загруженный .dex:
 * следующее открытие (и сразу же служба, если модуль включён) берёт файл заново, то есть новую версию.
 *
 * Методы @JvmStatic: модуль «Недавние» (dex, Java) вызывает их напрямую.
 */
object ModuleMemory {

    @JvmStatic
    fun unload(context: Context, name: String): Boolean {
        closeWindows(context, name)
        val service = CoreAccessibilityService.instance
        if (service != null) {
            service.reloadModule(name)            // остановит работающий объект и загрузит .dex заново
        } else {
            PluginRegistry.evict(name, true)      // службы нет: просто забыть, следующий scan прочитает файл
        }
        return true
    }

    /** Закрывает карточки модуля в системных «Недавних» (окна из ярлыка и из модуля «Недавние»). */
    private fun closeWindows(context: Context, name: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (t in am.appTasks) {
                val intent = t.taskInfo?.baseIntent ?: continue
                val target = intent.getStringExtra(ModuleSettingsActivity.EXTRA_TARGET)
                val fromUri = intent.data?.takeIf { it.scheme == "module" }?.let { android.net.Uri.decode(it.host ?: "") }
                if (target == name || fromUri == name) t.finishAndRemoveTask()
            }
        } catch (e: Throwable) {
        }
    }
}
