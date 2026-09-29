package im.manus.universalhost

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Настройки, картинки и раскладка модулей. Всё лежит в приватном хранилище хоста. */
object ModuleStorage {

    const val KEY_ICON = "__icon"

    fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /** Имя SharedPreferences модуля: модуль читает свои настройки напрямую по этому имени. */
    fun prefsName(module: String) = "module_" + safe(module)

    fun prefs(ctx: Context, module: String): SharedPreferences =
        ctx.getSharedPreferences(prefsName(module), Context.MODE_PRIVATE)

    fun folderTarget(folderId: String) = "folder_" + safe(folderId)

    fun imagesDir(ctx: Context, module: String): File =
        File(ctx.filesDir, "module_images/" + safe(module)).also { it.mkdirs() }

    /** Путь к сохранённой картинке или null, если её нет. */
    fun imagePath(ctx: Context, module: String, key: String): String? {
        val p = prefs(ctx, module).getString(key, null) ?: return null
        return if (File(p).isFile) p else null
    }

    /** Копирует выбранную картинку в хранилище, уменьшая до [maxPx] по большей стороне. */
    fun saveImage(ctx: Context, module: String, key: String, uri: Uri, maxPx: Int = 512): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val out = File(imagesDir(ctx, module), safe(key) + ".png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            prefs(ctx, module).edit().putString(key, out.absolutePath).apply()
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun clearImage(ctx: Context, module: String, key: String) {
        val p = prefs(ctx, module).getString(key, null)
        if (p != null) File(p).delete()
        prefs(ctx, module).edit().remove(key).apply()
    }

    fun loadBitmap(path: String, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (t: Throwable) {
        null
    }

    // ---- включён ли модуль (тот же файл настроек, что и раньше) ----

    fun isEnabled(ctx: Context, module: String) =
        ctx.getSharedPreferences("plugin_prefs", Context.MODE_PRIVATE).getBoolean(module, true)

    fun setEnabled(ctx: Context, module: String, value: Boolean) {
        ctx.getSharedPreferences("plugin_prefs", Context.MODE_PRIVATE).edit().putBoolean(module, value).apply()
    }

    // ---- раскладка экрана модулей ----

    fun loadLayout(ctx: Context): ModuleLayout {
        val raw = ctx.getSharedPreferences("modules_layout", Context.MODE_PRIVATE).getString("layout", "") ?: ""
        return ModuleLayout.parse(raw)
    }

    fun saveLayout(ctx: Context, layout: ModuleLayout) {
        ctx.getSharedPreferences("modules_layout", Context.MODE_PRIVATE)
            .edit().putString("layout", layout.serialize()).apply()
        // прежнее хранилище порядка оставляем актуальным
        ctx.getSharedPreferences("plugin_order", Context.MODE_PRIVATE)
            .edit().putString("order", layout.flat().joinToString(",")).apply()
    }
}
