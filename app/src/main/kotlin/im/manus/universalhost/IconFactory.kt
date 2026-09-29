package im.manus.universalhost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Значки модулей для экрана модулей и экрана настроек. */
object IconFactory {

    /** Запасной значок: буква в скруглённом квадрате. */
    fun monogram(name: String, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = px * 0.045f
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.color = Color.WHITE
        val r = RectF(stroke, stroke, px - stroke, px - stroke)
        c.drawRoundRect(r, px * 0.22f, px * 0.22f, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = px * 0.5f
        val fm = p.fontMetrics
        val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        c.drawText(letter, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp
    }

    /** Порядок выбора: картинка пользователя, значок из модуля, монограмма. */
    fun forModule(ctx: Context, plugin: IPlugin?, name: String, px: Int): Bitmap {
        val custom = ModuleStorage.imagePath(ctx, name, ModuleStorage.KEY_ICON)
        if (custom != null) ModuleStorage.loadBitmap(custom, px)?.let { return it }
        val own = try {
            (plugin as? ISettingsProvider)?.createIcon(px)
        } catch (t: Throwable) {
            null
        }
        return own ?: monogram(name, px)
    }
}
