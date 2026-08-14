package im.manus.universalhost

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.os.Environment
import android.content.Context
import java.io.File

class CoreAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CoreAccessibilityService? = null
    }

    private var activePlugins = mutableListOf<IPlugin>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshPlugins()
    }

    fun refreshPlugins() {
        // Останавливаем старые плагины перед очисткой
        activePlugins.forEach { it.stop() }
        activePlugins.clear()

        val pluginDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UniversalPlugins")
        if (!pluginDir.exists()) pluginDir.mkdirs()
        
        val loader = PluginLoader(this)
        val allPlugins = loader.getPluginsFromFolder(pluginDir.absolutePath)
        
        val prefs = getSharedPreferences("plugin_prefs", Context.MODE_PRIVATE)
        
        // Загружаем только активные плагины
        allPlugins.forEach { plugin ->
            if (prefs.getBoolean(plugin.name, true)) {
                try {
                    plugin.init(this)
                    activePlugins.add(plugin)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        activePlugins.forEach { it.onAccessibilityEvent(event, this) }
    }

    override fun onInterrupt() {
        activePlugins.forEach { it.stop() }
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        activePlugins.forEach { it.stop() }
        instance = null
    }
}
