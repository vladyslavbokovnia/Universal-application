package im.manus.universalhost

import android.view.MotionEvent

/**
 * Необязательный интерфейс: модуль получает движения стиков, курков и крестовины геймпада
 * (AccessibilityService.onMotionEvent, только Android 14+). Хост передаёт их, когда модуль
 * включил перехват через CoreAccessibilityService.setMotionCapture(true). Перехваченные
 * события до остальных приложений не доходят.
 */
interface IMotionHandler {
    fun onMotionEvent(event: MotionEvent)
}
