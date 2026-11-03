package im.manus.universalhost

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

class CoreAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CoreAccessibilityService? = null
    }

    private var activePlugins = mutableListOf<IPlugin>()
    private val ui = Handler(Looper.getMainLooper())
    private var dirObserver: FileObserver? = null

    /** Объекты модулей, чей init упал: их не перезапускаем при каждой проверке, пока файл не заменят (новый файл = новый объект). */
    private val failedInit: MutableSet<IPlugin> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<IPlugin, Boolean>())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshPlugins()
        watchPluginDir()
    }

    /**
     * Следим за папкой модулей: файл .dex заменили, добавили или удалили, и через 1,5 с работающие модули
     * сами перезагружаются (без выключения и включения). Наблюдатель на Android 11 иногда пропускает чужие записи,
     * поэтому та же проверка вызывается ещё при открытии хоста и окон модулей.
     */
    @Suppress("DEPRECATION")
    private fun watchPluginDir() {
        try {
            dirObserver?.stopWatching()
            val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
                FileObserver.CREATE or FileObserver.DELETE
            val check = Runnable { refreshIfStale() }
            val obs = object : FileObserver(PluginRegistry.pluginDir().absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || !path.endsWith(".dex")) return
                    ui.removeCallbacks(check)
                    ui.postDelayed(check, 1500)
                }
            }
            obs.startWatching()
            dirObserver = obs
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * Работающий модуль устарел, если в реестре уже другой объект с тем же именем (.dex заменили),
     * появился новый включённый модуль или работающий пропал. Тогда перезагружаем все модули.
     */
    fun refreshIfStale(): Boolean {
        return try {
            val current = PluginRegistry.scan(this)
            val stale = activePlugins.any { a -> current.none { it === a } } ||
                current.any { c ->
                    ModuleStorage.isEnabled(this, c.name) && activePlugins.none { it === c } && !failedInit.contains(c)
                }
            if (stale) refreshPlugins()
            stale
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /** Выгрузить модуль из памяти и загрузить его .dex заново; включённый модуль сразу запускается свежим. */
    fun reloadModule(name: String) {
        activePlugins.filter { it.name == name }.forEach {
            safeStop(it)
            activePlugins.remove(it)
        }
        PluginRegistry.evict(name, false)
        val fresh = PluginRegistry.scan(this).firstOrNull { it.name == name } ?: return
        if (ModuleStorage.isEnabled(this, name)) {
            try {
                fresh.init(this)
                activePlugins.add(fresh)
            } catch (e: Throwable) {
                failedInit.add(fresh)
                e.printStackTrace()
            }
        }
    }

    fun refreshPlugins() {
        // Останавливаем старые плагины перед очисткой
        activePlugins.forEach { safeStop(it) }
        activePlugins.clear()
        failedInit.clear()

        // Загружаем только включённые модули
        PluginRegistry.scan(this).forEach { plugin ->
            if (ModuleStorage.isEnabled(this, plugin.name)) {
                try {
                    plugin.init(this)
                    activePlugins.add(plugin)
                } catch (e: Throwable) {
                    failedInit.add(plugin)
                    e.printStackTrace()
                }
            }
        }
    }

    /** Экран настроек сообщает работающему модулю, что значение изменилось. */
    fun notifySettingChanged(module: String, key: String) {
        val provider = activePlugins.firstOrNull { it.name == module } as? ISettingsProvider ?: return
        try {
            provider.onSettingChanged(key)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun safeStop(p: IPlugin) {
        try {
            p.stop()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        activePlugins.forEach {
            try {
                it.onAccessibilityEvent(event, this)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    /** Кнопки (в том числе геймпада) отдаём модулям с IKeyHandler; true от любого из них поглощает событие. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        for (p in activePlugins) {
            val handler = p as? IKeyHandler ?: continue
            try {
                if (handler.onKeyEvent(event)) return true
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return false
    }

    /**
     * Включает или выключает получение движений джойстиков (стики, курки, крестовина) службой.
     * Работает только на Android 14+ (AccessibilityServiceInfo.setMotionEventSources); на более старых
     * версиях ничего не делает. Метод вызывается по имени, потому что compileSdk хоста ниже 34.
     * Пока перехват включён, эти события не доходят до остальных приложений.
     */
    fun setMotionCapture(on: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            val info = serviceInfo ?: return
            val setter = info.javaClass.getMethod("setMotionEventSources", Int::class.javaPrimitiveType)
            setter.invoke(info, if (on) InputDevice.SOURCE_JOYSTICK else 0)
            serviceInfo = info
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * Android 14+: AccessibilityService.onMotionEvent. compileSdk 33 этого метода не знает, но на устройстве
     * с API 34 он переопределяется по имени и сигнатуре. Движения отдаём модулям с IMotionHandler.
     */
    fun onMotionEvent(event: MotionEvent) {
        for (p in activePlugins) {
            val handler = p as? IMotionHandler ?: continue
            try {
                handler.onMotionEvent(event)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Система вызывает это, когда другая служба (например TalkBack) прерывает озвучку.
     * Модули при этом останавливать нельзя: вместе с ними пропадали панели, датчики и таймеры,
     * а перезапустить их было некому.
     */
    override fun onInterrupt() {
    }

    override fun onDestroy() {
        super.onDestroy()
        try { dirObserver?.stopWatching() } catch (e: Throwable) { }
        dirObserver = null
        activePlugins.forEach { safeStop(it) }
        instance = null
    }
}
