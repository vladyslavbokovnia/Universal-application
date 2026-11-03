package im.manus.universalhost

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityService

interface IPlugin {
    val name: String
    val version: Int
    val description: String
    val iconName: String
    
    fun init(context: Context)
    fun stop() {} // Остановка модуля и очистка ресурсов
    fun execute(data: Map<String, Any>?): Any?
    
    fun onAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {}
}
