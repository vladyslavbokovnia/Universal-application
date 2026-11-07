package im.manus.universalhost

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление скриптов браузера: файлы *.user.js из Загрузки/UniversalPlugins/BrowserScripts. Правила те же, что у
 * кнопки «Обновить все скрипты» в самом браузере: адрес берётся из @downloadURL (если нет, из @updateURL), только
 * https, файл заменяется, когда версия (@version) на сервере выше. Скрипты без адреса не трогаются. Новый файл
 * сначала пишется во временный «.tmp» (браузер его не видит) и подменяет старый целиком.
 */
object ScriptUpdater {

    private const val SUBDIR = "BrowserScripts"
    private const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * [updated]: названия скриптов, которые обновлены (при проверке без записи: которые можно обновить);
     * [same]: актуальных; [failed]: не скачались; [total]: сколько скриптов имеют адрес обновления.
     */
    class Result(val updated: List<String>, val same: Int, val failed: List<String>, val total: Int)

    private class Header(val name: String, val version: String, val url: String?)

    private class Local(val file: File, val name: String, val version: String, val url: String?)

    private fun dir(): File = File(PluginRegistry.pluginDir(), SUBDIR)

    /** Блокирующий вызов: только проверить, ничего не записывая. */
    fun check(): Result = scan(false)

    /** Блокирующий вызов: скачать и заменить устаревшие скрипты. */
    fun run(): Result = scan(true)

    fun summary(r: Result): String {
        if (r.total == 0) return "Нет скриптов с @downloadURL или @updateURL"
        val sb = StringBuilder()
        if (r.updated.isNotEmpty()) sb.append("Скрипты обновлены: ").append(r.updated.joinToString(", "))
        if (r.failed.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Не скачалось: ").append(r.failed.joinToString("; "))
        }
        if (sb.isEmpty()) sb.append("Скрипты актуальны (").append(r.same).append(")")
        return sb.toString()
    }

    private fun scan(write: Boolean): Result {
        val updated = ArrayList<String>()
        val failed = ArrayList<String>()
        var same = 0
        var total = 0
        for (s in locals()) {
            val u = s.url ?: continue
            total++
            try {
                val text = fetch(u)
                val n = parse(s.file.name, text)
                if (n == null) {
                    failed.add(s.name + ": на сервере не скрипт")
                } else if (compareVersions(n.version, s.version) > 0) {
                    if (write) writeAtomic(s.file, text)
                    updated.add(s.name)
                } else {
                    same++
                }
            } catch (t: Throwable) {
                failed.add(s.name + ": " + (t.message ?: t.javaClass.simpleName))
            }
        }
        return Result(updated, same, failed, total)
    }

    private fun locals(): List<Local> {
        val fs = dir().listFiles { f -> f.isFile && f.name.lowercase().endsWith(".user.js") } ?: return emptyList()
        val out = ArrayList<Local>()
        for (f in fs.sortedBy { it.name.lowercase() }) {
            val text = try {
                f.readText(Charsets.UTF_8)
            } catch (t: Throwable) {
                continue
            }
            val h = parse(f.name, text) ?: continue
            out.add(Local(f, h.name, h.version, h.url))
        }
        return out
    }

    private fun parse(fileName: String, text: String): Header? {
        val a = text.indexOf("==UserScript==")
        val b = text.indexOf("==/UserScript==")
        if (a < 0 || b < a) return null
        var name: String? = null
        var version = ""
        var update: String? = null
        var download: String? = null
        for (line in text.substring(a + 14, b).split("\n")) {
            var t = line.trim()
            if (!t.startsWith("//")) continue
            t = t.substring(2).trim()
            if (!t.startsWith("@")) continue
            var sp = 1
            while (sp < t.length && !t[sp].isWhitespace()) sp++
            val key = t.substring(1, sp)
            val value = t.substring(sp).trim()
            when (key) {
                "name" -> if (name == null) name = value
                "version" -> version = value
                "updateURL" -> update = value
                "downloadURL" -> download = value
            }
        }
        val shown = name?.takeIf { it.isNotEmpty() } ?: fileName.removeSuffix(".user.js")
        return Header(shown, version, download ?: update)
    }

    /** Как в браузере: версии сравниваются по числам, разделённым любыми не цифрами. */
    private fun compareVersions(a: String, b: String): Int {
        val x = a.split(Regex("[^0-9]+"))
        val y = b.split(Regex("[^0-9]+"))
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)?.toLongOrNull() ?: 0L
            val r = y.getOrNull(i)?.toLongOrNull() ?: 0L
            if (p != r) return if (p > r) 1 else -1
        }
        return 0
    }

    /** Редиректы идут вручную, чтобы и после них требовать https. */
    private fun fetch(url: String): String {
        var cur = url
        var hops = 0
        while (hops < 6) {
            hops++
            val u = URL(cur)
            if (u.protocol != "https") throw IOException("нужна ссылка https")
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "UniversalHost")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("редирект без адреса")
                    cur = URL(u, loc).toString()
                    continue
                }
                if (code != 200) throw IOException("HTTP $code")
                return String(readLimited(c.inputStream), Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("слишком много перенаправлений")
    }

    private fun readLimited(ins: InputStream): ByteArray {
        return ins.use { src ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("файл больше 2 МБ")
            }
            out.toByteArray()
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (target.exists() && !target.delete()) throw IOException("не удалось заменить файл")
            if (!tmp.renameTo(target)) tmp.copyTo(target, true)
        } finally {
            tmp.delete()
        }
    }
}
