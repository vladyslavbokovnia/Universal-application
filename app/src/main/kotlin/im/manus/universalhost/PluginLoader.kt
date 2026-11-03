package im.manus.universalhost

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

class PluginLoader(private val context: Context) {

    fun getPluginsFromFolder(folderPath: String): List<IPlugin> {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return emptyList()
        val dexFiles = folder.listFiles { _, name -> name.endsWith(".dex") } ?: return emptyList()
        return dexFiles.mapNotNull { loadOne(it) }
    }

    fun loadOne(file: File): IPlugin? {
        return try {
            val optimizedDir = context.getCodeCacheDir()
            val classLoader = DexClassLoader(
                file.absolutePath,
                optimizedDir.absolutePath,
                null,
                context.classLoader
            )
            // Класс модуля должен называться так же, как файл, и лежать в пакете im.manus.plugins
            val className = "im.manus.plugins." + file.nameWithoutExtension
            classLoader.loadClass(className).getDeclaredConstructor().newInstance() as IPlugin
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }
}
