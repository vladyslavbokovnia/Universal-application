package im.manus.universalhost

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * PWA-стиль для модулей: ярлык на рабочий стол -> экран модуля отдельным окном
 * (своя карточка в недавних благодаря FLAG_ACTIVITY_NEW_DOCUMENT и data-Uri).
 *
 * Ярлык создаётся из кода модуля (dex): ModuleShortcuts.pin(context, plugin).
 */
object ModuleShortcuts {

    /**
     * Запросить закрепление ярлыка модуля на рабочем столе.
     * Тап по ярлыку открывает экран модуля отдельным окном; повторный тап
     * переиспользует то же окно (карточка в недавних — одна на модуль).
     */
    @JvmStatic
    fun pin(context: Context, plugin: IPlugin) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(context, "Нужно Android 8+", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return
            if (!sm.isRequestPinShortcutSupported) {
                Toast.makeText(context, "Лаунчер не умеет ярлыки", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(context, ModuleSettingsActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                // data отличает «документы»: одна карточка в недавних на модуль
                data = Uri.parse("module://" + Uri.encode(plugin.name))
                putExtra(ModuleSettingsActivity.EXTRA_TARGET, plugin.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
            // Значок: createIcon живёт на ISettingsProvider (как в IconFactory), иначе монограмма.
            val bmp = try { (plugin as? ISettingsProvider)?.createIcon(192) } catch (t: Throwable) { null }
                ?: monogram(plugin.name)
            val info = ShortcutInfo.Builder(context, "module_" + plugin.name)
                .setShortLabel(plugin.name)
                .setLongLabel(plugin.description)
                .setIcon(Icon.createWithBitmap(bmp))
                .setIntent(intent)
                .build()
            sm.requestPinShortcut(info, null)
        } catch (t: Throwable) {
            try { Toast.makeText(context, "Не получилось", Toast.LENGTH_SHORT).show() } catch (x: Throwable) { }
        }
    }

    /** Заглушка значка: первая буква названия на тёмном фоне. */
    private fun monogram(name: String): Bitmap {
        val s = 192
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor("#3A3A5C")
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.color = Color.WHITE
        p.textSize = s * 0.55f
        p.textAlign = Paint.Align.CENTER
        val ch = if (name.isNotEmpty()) name.substring(0, 1) else "?"
        val r = Rect()
        p.getTextBounds(ch, 0, ch.length, r)
        c.drawText(ch, s / 2f, s / 2f + r.height() / 2f, p)
        return b
    }
}
