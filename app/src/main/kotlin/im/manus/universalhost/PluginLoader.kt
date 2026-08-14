package im.manus.universalhost

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

class PluginLoader(private val context: Context) {

    fun getPluginsFromFolder(folderPath: String): List<IPlugin> {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return emptyList()

        val plugins = mutableListOf<IPlugin>()
        val dexFiles = folder.listFiles { _, name -> name.endsWith(".dex") } ?: return emptyList()

        for (file in dexFiles) {
            try {
                val optimizedDir = context.getCodeCacheDir()
                val classLoader = DexClassLoader(
                    file.absolutePath,
                    optimizedDir.absolutePath,
                    null,
                    context.classLoader
                )

                // Предполагаем, что имя класса совпадает с именем файла или есть манифест
                // Для простоты ищем классы в пакете im.manus.plugins
                val className = "im.manus.plugins." + file.nameWithoutExtension
                val pluginClass = classLoader.loadClass(className)
                val plugin = pluginClass.getDeclaredConstructor().newInstance() as IPlugin
                plugins.add(plugin)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return plugins
    }
}
