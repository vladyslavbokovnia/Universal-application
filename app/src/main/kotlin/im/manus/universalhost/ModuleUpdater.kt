package im.manus.universalhost

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление одним нажатием: с GitHub (релиз «latest», его пересоздаёт сборка android.yml при каждом пуше в
 * main) скачиваются те модули .dex, которые отличаются от лежащих в Загрузки/UniversalPlugins, и кладутся туда
 * же. Сравнение по контрольной сумме SHA-256, которую GitHub указывает у файла релиза; если её нет, по размеру
 * и номеру файла релиза, записанному при прошлой загрузке. Файл сначала качается во временный «.tmp» (его
 * загрузчик модулей не видит) и подменяется целиком, так что оборванная загрузка не портит рабочий модуль.
 * Модули, которых нет в релизе, не удаляются.
 *
 * Нужно ли обновлять само приложение (APK), определяется так: сборка кладёт в приложение и в релиз файл
 * host_hash.txt, хэш исходников хоста (без модулей), и если хэши разные, значит хост изменился. Для сборок без
 * этого файла сравнивается контрольная сумма установленного APK с APK в релизе (тогда любая пересборка считается
 * новой версией). Само обновление (нажатие на значок приложения): APK скачивается и ставится через системный
 * установщик, подтверждение за пользователем.
 */
object ModuleUpdater {

    private const val REPO = "vladyslavbokovnia/Universal-application"
    private const val API = "https://api.github.com/repos/$REPO/releases/tags/latest"
    private const val APK_NAME = "app-debug.apk"
    private const val HASH_NAME = "host_hash.txt"
    private const val CHECK_EVERY_MS = 10 * 60 * 1000L
    private const val ACTION_INSTALL = "im.manus.universalhost.INSTALL_RESULT"
    private val NAME = Regex("^[A-Za-z0-9_]+\\.dex$")

    private class Asset(val name: String, val id: String, val size: Long, val url: String, val digest: String?)

    class Result(
        val updated: List<String>,
        val same: Int,
        val failed: List<String>,
        val error: String?,
        /** true: в релизе новая версия приложения; false: актуальна; null: узнать не удалось. */
        val hostNeedsUpdate: Boolean?
    )

    /** Что есть нового: приложение (true/false), число модулей и число скриптов. null: узнать не удалось. */
    class Status(val host: Boolean?, val modules: Int?, val scripts: Int?)

    private fun prefs(c: Context) = c.getSharedPreferences("module_updater", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ модули

    /** Блокирующий вызов: только из фонового потока. */
    fun run(context: Context): Result {
        val updated = ArrayList<String>()
        val failed = ArrayList<String>()
        var same = 0
        val all = try {
            fetchAssets()
        } catch (t: Throwable) {
            return Result(updated, same, failed, t.message ?: t.javaClass.simpleName, null)
        }
        val assets = all.filter { NAME.matches(it.name) }
        val host = hostNeedsUpdate(context, all)
        storeHost(context, host)
        if (assets.isEmpty()) return Result(updated, same, failed, "в релизе нет модулей", host)
        val dir = PluginRegistry.pluginDir()
        val p = prefs(context)
        for (a in assets) {
            val label = a.name.removeSuffix(".dex")
            try {
                if (!moduleNeeded(a, dir, p)) {
                    same++
                    continue
                }
                download(a, dir, File(dir, a.name + ".tmp"), File(dir, a.name))
                p.edit().putString("id_" + a.name, a.id).apply()
                updated.add(label)
            } catch (t: Throwable) {
                failed.add(label + ": " + (t.message ?: t.javaClass.simpleName))
            }
        }
        storeCount(context, "mod_pending", failed.size)
        return Result(updated, same, failed, null, host)
    }

    /** Нужно ли скачивать модуль: нет файла, или контрольная сумма (а без неё размер и номер файла релиза) другие. */
    private fun moduleNeeded(a: Asset, dir: File, p: SharedPreferences): Boolean {
        val local = File(dir, a.name)
        return when {
            !local.exists() -> true
            a.digest != null -> !sha256(local).equals(a.digest, ignoreCase = true)
            else -> local.length() != a.size || p.getString("id_" + a.name, "") != a.id
        }
    }

    /** Текст для всплывающей подсказки. */
    fun summary(r: Result): String {
        if (r.error != null) return "Не удалось обновить: " + r.error
        val sb = StringBuilder()
        if (r.updated.isNotEmpty()) sb.append("Обновлено: ").append(r.updated.joinToString(", "))
        if (r.failed.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Не скачалось: ").append(r.failed.joinToString("; "))
        }
        if (sb.isEmpty()) sb.append("Модули актуальны (").append(r.same).append(")")
        if (r.hostNeedsUpdate == true) sb.append("\nЕсть новая версия приложения: нажми значок приложения")
        return sb.toString()
    }

    // ------------------------------------------------------------ нужно ли обновлять приложение

    /** Последний известный ответ «приложение устарело?» для установленной сейчас сборки; null: не знаем. */
    fun cachedHostNeed(context: Context): Boolean? {
        val p = prefs(context)
        if (!p.contains("host_need") || p.getString("host_stamp", "") != hostStamp(context)) return null
        return p.getBoolean("host_need", false)
    }

    /** Пора ли проверять заново: прошло 10 минут или приложение переустановили. */
    fun hostCheckDue(context: Context): Boolean {
        val p = prefs(context)
        if (p.getString("host_stamp", "") != hostStamp(context)) return true
        return System.currentTimeMillis() - p.getLong("host_checked", 0L) >= CHECK_EVERY_MS
    }

    /** Блокирующий вызов: сверить установленную сборку с релизом. null: узнать не удалось (нет сети и т. п.). */
    fun checkHost(context: Context): Boolean? {
        val all = try {
            fetchAssets()
        } catch (t: Throwable) {
            return null
        }
        val need = hostNeedsUpdate(context, all)
        storeHost(context, need)
        return need
    }

    /** Последнее известное число модулей, которые отличаются от релиза; null: не знаем. */
    fun cachedModules(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains("mod_pending")) p.getInt("mod_pending", 0) else null
    }

    /** Последнее известное число скриптов, у которых на сервере версия выше; null: не знаем. */
    fun cachedScripts(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains("scr_pending")) p.getInt("scr_pending", 0) else null
    }

    fun storeScriptsPending(context: Context, n: Int) = storeCount(context, "scr_pending", n)

    private fun storeCount(c: Context, key: String, n: Int?) {
        if (n == null) return
        prefs(c).edit().putInt(key, n).apply()
    }

    /**
     * Блокирующий вызов: ничего не скачивая, узнать, что устарело. Приложение и модули сверяются по одному запросу
     * к GitHub, скрипты по одному запросу на каждый скрипт с адресом обновления.
     */
    fun checkAll(context: Context): Status {
        var host: Boolean? = null
        var modules: Int? = null
        try {
            val all = fetchAssets()
            host = hostNeedsUpdate(context, all)
            storeHost(context, host)
            val dir = PluginRegistry.pluginDir()
            val p = prefs(context)
            modules = all.count { NAME.matches(it.name) && moduleNeeded(it, dir, p) }
            storeCount(context, "mod_pending", modules)
        } catch (t: Throwable) {
        }
        var scripts: Int? = null
        try {
            val r = ScriptUpdater.check()
            if (r.total == 0 || r.failed.size < r.total) scripts = r.updated.size
            storeCount(context, "scr_pending", scripts)
        } catch (t: Throwable) {
        }
        if (host != null || modules != null || scripts != null) {
            prefs(context).edit().putLong("host_checked", System.currentTimeMillis()).apply()
        }
        return Status(host, modules, scripts)
    }

    private fun hostStamp(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime.toString()
    } catch (t: Throwable) {
        "0"
    }

    private fun storeHost(c: Context, need: Boolean?) {
        if (need == null) return
        prefs(c).edit()
            .putBoolean("host_need", need)
            .putString("host_stamp", hostStamp(c))
            .putLong("host_checked", System.currentTimeMillis())
            .apply()
    }

    private fun hostNeedsUpdate(context: Context, all: List<Asset>): Boolean? {
        try {
            val hashAsset = all.firstOrNull { it.name == HASH_NAME }
            val local = try {
                context.assets.open(HASH_NAME).bufferedReader().use { it.readText().trim() }
            } catch (t: Throwable) {
                ""
            }
            if (hashAsset != null && local.isNotEmpty()) {
                val c = open(hashAsset.url)
                if (c.responseCode == 200) {
                    val remote = c.inputStream.bufferedReader().use { it.readText().trim() }
                    if (remote.isNotEmpty()) return !remote.equals(local, ignoreCase = true)
                }
            }
            val apk = all.firstOrNull { it.name == APK_NAME }
            if (apk?.digest != null) {
                return !sha256(File(context.applicationInfo.sourceDir)).equals(apk.digest, ignoreCase = true)
            }
        } catch (t: Throwable) {
        }
        return null
    }

    // ------------------------------------------------------------ обновление приложения

    /**
     * Блокирующий вызов: скачивает APK из релиза и открывает системный установщик. Возвращает текст ошибки или
     * null, если установщик запущен (дальше подтверждает пользователь, итог покажет всплывающая подсказка).
     */
    fun updateApp(context: Context): String? {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            try {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
            }
            return "разреши установку из этого приложения и нажми значок приложения ещё раз"
        }
        val apk = try {
            fetchAssets().firstOrNull { it.name == APK_NAME }
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        } ?: return "в релизе нет APK"
        val tmp = File(app.cacheDir, "update.part")
        val file = File(app.cacheDir, "update.apk")
        try {
            download(apk, app.cacheDir, tmp, file)
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }
        return try {
            install(app, file)
            null
        } catch (t: Throwable) {
            file.delete()
            t.message ?: t.javaClass.simpleName
        }
    }

    @Suppress("DEPRECATION")
    private fun install(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            apk.inputStream().use { ins ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    ins.copyTo(out)
                    session.fsync(out)
                }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            c.startActivity(confirm)
                        }
                        return
                    }
                    try {
                        c.applicationContext.unregisterReceiver(this)
                    } catch (t: Throwable) {
                    }
                    apk.delete()
                    if (st != PackageInstaller.STATUS_SUCCESS) {
                        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $st"
                        Toast.makeText(c.applicationContext, "Не установилось: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
            val filter = IntentFilter(ACTION_INSTALL)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(app, id, Intent(ACTION_INSTALL).setPackage(app.packageName), flags)
            session.commit(pi.intentSender)
        } catch (t: Throwable) {
            session.abandon()
            throw t
        } finally {
            session.close()
        }
    }

    // ------------------------------------------------------------ сеть и файлы

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "UniversalHost")
        return c
    }

    private fun fetchAssets(): List<Asset> {
        val c = open(API)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val code = c.responseCode
        if (code != 200) {
            throw IOException(
                when (code) {
                    404 -> "релиз latest не найден"
                    403 -> "лимит запросов GitHub, повтори позже"
                    else -> "GitHub ответил $code"
                }
            )
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(body).getJSONArray("assets")
        val out = ArrayList<Asset>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name", "")
            val url = o.optString("browser_download_url", "")
            if (name.isEmpty() || url.isEmpty()) continue
            val digest = o.optString("digest", "")
            out.add(
                Asset(
                    name,
                    o.optLong("id", 0L).toString(),
                    o.optLong("size", 0L),
                    url,
                    if (digest.startsWith("sha256:")) digest.substring(7) else null
                )
            )
        }
        return out
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(16384)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Качает во временный файл, проверяет размер и контрольную сумму, потом целиком подменяет [target]. */
    private fun download(a: Asset, @Suppress("UNUSED_PARAMETER") dir: File, tmp: File, target: File) {
        try {
            val c = open(a.url)
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                    }
                }
            }
            if (a.size > 0 && tmp.length() != a.size) throw IOException("размер не совпал")
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (a.digest != null && !hex.equals(a.digest, ignoreCase = true)) throw IOException("контрольная сумма не совпала")
            if (target.exists() && !target.delete()) throw IOException("не удалось заменить файл")
            if (!tmp.renameTo(target)) tmp.copyTo(target, true)
        } finally {
            tmp.delete()
        }
    }
}
