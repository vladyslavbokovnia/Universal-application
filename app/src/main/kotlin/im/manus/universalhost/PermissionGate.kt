package im.manus.universalhost

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Пошаговый запрос разрешений при запуске хоста. За один вызов [next] открывается ровно одно окно
 * настроек (или системный диалог); когда пользователь возвращается в приложение, MainActivity.onResume
 * вызывает [next] снова, и показывается следующее недостающее разрешение. Каждый шаг за время жизни
 * процесса запрашивается один раз, поэтому отказ не приводит к бесконечному циклу.
 *
 * Порядок: доступ к файлам (без него модули из Download/UniversalPlugins не видны), служба специальных
 * возможностей, доступ к уведомлениям, поверх других окон, статистика использования, точные будильники,
 * Bluetooth. Shizuku запрашивает ShizukuBridge сам.
 */
object PermissionGate {

    private val asked = HashSet<String>()

    private class Step(val id: String, val granted: (Context) -> Boolean, val intent: (Context) -> Intent)

    private fun pkgUri(c: Context) = Uri.parse("package:" + c.packageName)

    private val steps: List<Step> = listOf(
        Step("all_files", { c ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        }, { c ->
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(c))
        }),
        Step("accessibility", { c ->
            val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val me = ComponentName(c.packageName, CoreAccessibilityService::class.java.name)
            enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }, { _ ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }),
        Step("notification_listener", { c ->
            NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
        }, { _ ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }),
        Step("overlay", { c ->
            Settings.canDrawOverlays(c)
        }, { c ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c))
        }),
        Step("usage_stats", { c ->
            val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }, { c ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }),
        Step("exact_alarm", { c ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }, { c ->
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(c))
        })
    )

    /** Открывает следующее недостающее разрешение; ничего не делает, если все выданы или уже запрашивались. */
    @JvmStatic
    fun next(a: Activity) {
        try {
            // Android 6-10: обычные разрешения на память
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                a.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
                asked.add("legacy_storage")
            ) {
                a.requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 41
                )
                return
            }
            for (s in steps) {
                if (!s.granted(a) && asked.add(s.id)) {
                    a.startActivity(s.intent(a))
                    return
                }
            }
            // Android 12+: Bluetooth для модуля RadioGestures
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val need = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                    .filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (need.isNotEmpty() && asked.add("bluetooth")) {
                    a.requestPermissions(need.toTypedArray(), 42)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
