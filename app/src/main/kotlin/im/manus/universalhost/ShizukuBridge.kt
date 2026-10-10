package im.manus.universalhost

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException

/** Результат команды, выполненной через Shizuku. */
class ShizukuResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
}

/**
 * Клиент Shizuku для хоста и модулей. Сервер — отдельное приложение (форк thedjchi/Shizuku: старт после
 * перезагрузки по Wi-Fi, сторожевой сервис). Модули (Java, DEX) вызывают статические методы:
 *   ShizukuBridge.exec("команда")  — только из фонового потока.
 *
 * Возможности хоста:
 *  - при появлении Shizuku (после перезагрузки тоже) запрашивает разрешение, если его ещё нет;
 *  - выдаёт себе WRITE_SECURE_SETTINGS (pm grant);
 *  - если включена настройка модуля Shizuku «Включать службу сам», возвращает службу
 *    специальных возможностей в список включённых (после перезагрузки и обновления APK).
 */
object ShizukuBridge {

    private const val REQUEST_CODE = 4107
    private const val PREFS = "module_Shizuku"          // настройки модуля Shizuku, которые пишет хост
    private const val KEY_KEEP = "keep_accessibility"
    private val SERVICE = ComponentName("im.manus.universalhost", "im.manus.universalhost.CoreAccessibilityService")

    private var appContext: Context? = null
    private var started = false
    private var asked = false

    @JvmStatic
    fun init(app: Application) {
        if (started) return
        started = true
        appContext = app.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky { onBinder() }
            Shizuku.addRequestPermissionResultListener { _, result ->
                if (result == PackageManager.PERMISSION_GRANTED) onGranted()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    @JvmStatic
    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Показывает окно запроса доступа в приложении Shizuku. */
    @JvmStatic
    fun requestPermission() {
        try {
            if (isRunning() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun hasSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    @JvmStatic
    fun status(ctx: Context): String {
        val secure = if (hasSecureSettings(ctx)) "WRITE_SECURE_SETTINGS выдано" else "WRITE_SECURE_SETTINGS не выдано"
        return when {
            !isRunning() -> "Shizuku не запущен: запустите приложение Shizuku. $secure"
            !hasPermission() -> "Shizuku запущен, но у хоста нет разрешения. $secure"
            else -> {
                val mode = try {
                    if (Shizuku.getUid() == 0) "root" else "adb"
                } catch (e: Throwable) {
                    "?"
                }
                "Shizuku готов ($mode). $secure"
            }
        }
    }

    /** Выполняет команду оболочки от имени Shizuku. Вызывать только из фонового потока. */
    @JvmStatic
    fun exec(command: String): ShizukuResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ShizukuResult(-2, "", "Вызов из главного потока недопустим")
        }
        if (!hasPermission()) {
            return ShizukuResult(-1, "", "Shizuku не запущен или у хоста нет разрешения")
        }
        return try {
            // с API 13.1 Shizuku.newProcess закрыт, поэтому вызываем его через рефлексию
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            ShizukuResult(process.waitFor(), out.trim(), err.trim())
        } catch (e: InvocationTargetException) {
            ShizukuResult(-1, "", (e.targetException ?: e).toString())
        } catch (e: Throwable) {
            ShizukuResult(-1, "", e.toString())
        }
    }

    /**
     * Запускает долгоживущий процесс оболочки от имени Shizuku (например, чтение сырых событий ввода через getevent)
     * и возвращает его, не дожидаясь завершения. null, если Shizuku не готов. Вызывать из фонового потока;
     * вызывающий читает process.inputStream и сам вызывает process.destroy().
     */
    @JvmStatic
    fun startProcess(command: String): Process? {
        if (Looper.myLooper() == Looper.getMainLooper() || !hasPermission()) return null
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    @JvmStatic
    fun grantSecureSettings(ctx: Context): ShizukuResult {
        if (hasSecureSettings(ctx)) return ShizukuResult(0, "уже выдано", "")
        return exec("pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
    }

    @JvmStatic
    fun keepAccessibility(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP, false)

    /**
     * Добавляет службу в список включённых. Сначала пробует напрямую (если выдано WRITE_SECURE_SETTINGS), иначе через
     * Shizuku (только из фонового потока).
     */
    @JvmStatic
    fun ensureAccessibility(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val entries = current.split(':').filter { it.isNotBlank() }
        val already = entries.any { ComponentName.unflattenFromString(it) == SERVICE }
        val flag = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (already && flag == 1) return true

        val value = (if (already) entries else entries + SERVICE.flattenToString()).joinToString(":")
        if (hasSecureSettings(ctx)) {
            return try {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            } catch (e: Throwable) {
                false
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper() || !hasPermission()) return false
        return exec(
            "settings put secure enabled_accessibility_services '$value' && settings put secure accessibility_enabled 1"
        ).ok
    }

    /** Вызывается после перезагрузки и обновления приложения (фоновый поток). */
    @JvmStatic
    fun onBootOrUpdate(ctx: Context) {
        if (keepAccessibility(ctx)) ensureAccessibility(ctx)
    }

    private fun onBinder() {
        if (appContext == null) return
        if (!hasPermission()) {
            if (!asked) {
                asked = true
                requestPermission()
            }
            return
        }
        onGranted()
    }

    private fun onGranted() {
        val ctx = appContext ?: return
        Thread {
            grantSecureSettings(ctx)
            if (keepAccessibility(ctx)) ensureAccessibility(ctx)
        }.start()
    }
}
