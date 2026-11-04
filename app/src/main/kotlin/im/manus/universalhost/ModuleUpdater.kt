package im.manus.universalhost

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление модулей одним нажатием: с GitHub (релиз «latest», его пересоздаёт сборка android.yml при каждом
 * пуше в main) скачиваются те .dex, которые отличаются от лежащих в Загрузки/UniversalPlugins, и кладутся туда
 * же. Сравнение по контрольной сумме SHA-256, которую GitHub указывает у файла релиза; если её нет, по размеру
 * и номеру файла релиза, записанному при прошлой загрузке. Файл сначала качается во временный «.tmp» (его
 * загрузчик модулей не видит) и подменяется целиком, так что оборванная загрузка не портит рабочий модуль.
 * Модули, которых нет в релизе, не удаляются. Хост (APK) не обновляется: если сборка меняет и хост, его
 * нужно поставить отдельно.
 */
object ModuleUpdater {

    private const val REPO = "vladyslavbokovnia/Universal-application"
    private const val API = "https://api.github.com/repos/$REPO/releases/tags/latest"
    private val NAME = Regex("^[A-Za-z0-9_]+\\.dex$")

    private class Asset(val name: String, val id: String, val size: Long, val url: String, val digest: String?)

    class Result(val updated: List<String>, val same: Int, val failed: List<String>, val error: String?)

    /** Блокирующий вызов: только из фонового потока. */
    fun run(context: Context): Result {
        val updated = ArrayList<String>()
        val failed = ArrayList<String>()
        var same = 0
        val assets = try {
            fetchAssets()
        } catch (t: Throwable) {
            return Result(updated, same, failed, t.message ?: t.javaClass.simpleName)
        }
        if (assets.isEmpty()) return Result(updated, same, failed, "в релизе нет модулей")
        val dir = PluginRegistry.pluginDir()
        val prefs = context.getSharedPreferences("module_updater", Context.MODE_PRIVATE)
        for (a in assets) {
            val label = a.name.removeSuffix(".dex")
            try {
                val local = File(dir, a.name)
                val need = when {
                    !local.exists() -> true
                    a.digest != null -> !sha256(local).equals(a.digest, ignoreCase = true)
                    else -> local.length() != a.size || prefs.getString("id_" + a.name, "") != a.id
                }
                if (!need) {
                    same++
                    continue
                }
                download(a, dir)
                prefs.edit().putString("id_" + a.name, a.id).apply()
                updated.add(label)
            } catch (t: Throwable) {
                failed.add(label + ": " + (t.message ?: t.javaClass.simpleName))
            }
        }
        return Result(updated, same, failed, null)
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
        return sb.toString()
    }

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
            if (!NAME.matches(name)) continue
            val digest = o.optString("digest", "")
            out.add(
                Asset(
                    name,
                    o.optLong("id", 0L).toString(),
                    o.optLong("size", 0L),
                    o.optString("browser_download_url", ""),
                    if (digest.startsWith("sha256:")) digest.substring(7) else null
                )
            )
        }
        return out.filter { it.url.isNotEmpty() }
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

    private fun download(a: Asset, dir: File) {
        val tmp = File(dir, a.name + ".tmp")
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
            val local = File(dir, a.name)
            if (local.exists() && !local.delete()) throw IOException("не удалось заменить файл")
            if (!tmp.renameTo(local)) tmp.copyTo(local, true)
        } finally {
            tmp.delete()
        }
    }
}
