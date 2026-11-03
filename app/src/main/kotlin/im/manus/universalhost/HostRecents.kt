package im.manus.universalhost

import android.app.ActivityManager
import android.content.Context

/**
 * Карточка хоста в системных «Недавних» нужна, только когда открыт сам хост. Пока работает окно модуля,
 * задачу хоста исключаем из Недавних, чтобы рядом с карточкой модуля не висел ещё и значок хоста;
 * когда открывается сам хост, карточка возвращается.
 */
object HostRecents {

    @JvmStatic
    fun hideHostCard(context: Context) = set(context, true)

    @JvmStatic
    fun showHostCard(context: Context) = set(context, false)

    private fun set(context: Context, exclude: Boolean) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (t in am.appTasks) {
                val comp = t.taskInfo?.baseIntent?.component ?: continue
                if (comp.className == MainActivity::class.java.name) t.setExcludeFromRecents(exclude)
            }
        } catch (e: Throwable) {
        }
    }
}
