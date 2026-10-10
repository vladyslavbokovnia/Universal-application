package im.manus.universalhost

import android.view.KeyEvent

/**
 * Необязательный интерфейс: модуль, который хочет получать кнопки (например, геймпада),
 * реализует его вместе с IPlugin. Хост передаёт события из AccessibilityService.onKeyEvent
 * до приложений. Вернуть true значит «событие обработано, дальше не передавать».
 */
interface IKeyHandler {
    fun onKeyEvent(event: KeyEvent): Boolean
}
