package im.manus.universalhost

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Общий кэш загруженных модулей для сервиса и экранов: dex перечитывается только если файл изменился,
 * а не при каждом открытии экрана.
 */
object PluginRegistry {

    private class Loaded(val modified: Long, val size: Long, val plugin: IPlugin?)

    private val cache = HashMap<String, Loaded>()

    fun pluginDir(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UniversalPlugins")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun scan(context: Context): List<IPlugin> {
        val files = pluginDir().listFiles { f -> f.isFile && f.name.endsWith(".dex") }?.sortedBy { it.name }
            ?: emptyList()
        val result = ArrayList<IPlugin>()
        val seen = HashSet<String>()
        val loader = PluginLoader(context)
        for (f in files) {
            seen.add(f.absolutePath)
            var entry = cache[f.absolutePath]
            if (entry == null || entry.modified != f.lastModified() || entry.size != f.length()) {
                entry = Loaded(f.lastModified(), f.length(), loader.loadOne(f))
                cache[f.absolutePath] = entry
            }
            entry.plugin?.let { result.add(it) }
        }
        cache.keys.retainAll(seen)
        return result
    }

    fun find(context: Context, name: String): IPlugin? = scan(context).firstOrNull { it.name == name }
}
