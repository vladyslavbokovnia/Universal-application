package im.manus.universalhost

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONObject

/**
 * Реестр окон модулей (PWA-стиль): окно, которое открывалось отдельной карточкой в недавних (браузер, экран модуля
 * из ярлыка), записывается сюда со временем и способом повторного запуска. Модуль «Недавние» показывает эти окна
 * рядом с приложениями со значком самого модуля и запускает их тем же интентом.
 *
 * Все методы @JvmStatic: модули (dex, Java) вызывают их напрямую.
 */
object ModuleWindows {

    private const val PREFS = "module_windows"
    private const val KEY = "w:"

    /** Запоминает окно модуля: [launch] — интент, которым его можно открыть снова. Вызывать из onResume окна. */
    @JvmStatic
    fun record(context: Context, name: String, launch: Intent) {
        try {
            val o = JSONObject()
            o.put("t", System.currentTimeMillis())
            o.put("i", launch.toUri(0))
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY + name, o.toString()).apply()
        } catch (t: Throwable) { }
    }

    /** Имена модулей, чьи окна открывались. */
    @JvmStatic
    fun names(context: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val all = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            for (k in all.keys) if (k.startsWith(KEY)) out.add(k.substring(KEY.length))
        } catch (t: Throwable) { }
        return out
    }

    /** Когда окно модуля использовалось в последний раз (мс), 0 если неизвестно. */
    @JvmStatic
    fun lastUsed(context: Context, name: String): Long {
        return try {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY + name, null)
            if (raw == null) 0L else JSONObject(raw).optLong("t", 0L)
        } catch (t: Throwable) { 0L }
    }

    /** Открывает окно модуля (поднимает существующую карточку или создаёт новую). */
    @JvmStatic
    fun launch(context: Context, name: String): Boolean {
        return try {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY + name, null)
                ?: return false
            val uri = JSONObject(raw).optString("i", "")
            if (uri.isEmpty()) return false
            val i = Intent.parseUri(uri, 0)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (t: Throwable) { false }
    }

    /** Значок модуля: картинка пользователя, значок из модуля или монограмма. */
    @JvmStatic
    fun icon(context: Context, name: String, px: Int): Bitmap {
        val plugin = try { PluginRegistry.find(context, name) } catch (t: Throwable) { null }
        return IconFactory.forModule(context, plugin, name, px)
    }

    @JvmStatic
    fun iconDrawable(context: Context, name: String, px: Int): Drawable =
        BitmapDrawable(context.resources, icon(context, name, px))
}
