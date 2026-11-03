package im.manus.universalhost

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

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
        activePlugins.forEach { safeStop(it) }
        activePlugins.clear()

        // Загружаем только включённые модули
        PluginRegistry.scan(this).forEach { plugin ->
            if (ModuleStorage.isEnabled(this, plugin.name)) {
                try {
                    plugin.init(this)
                    activePlugins.add(plugin)
                } catch (e: Throwable) {
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
        activePlugins.forEach { safeStop(it) }
        instance = null
    }
}
