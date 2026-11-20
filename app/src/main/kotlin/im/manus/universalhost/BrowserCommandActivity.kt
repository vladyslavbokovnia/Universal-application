package im.manus.universalhost

import android.app.Activity
import android.os.Bundle

/**
 * Прозрачная activity без интерфейса: принимает ярлыки приложения и ссылки universal://browser/<команда>,
 * передаёт команду браузеру и сразу закрывается.
 */
class BrowserCommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { BrowserCommands.dispatch(this, BrowserCommands.nameFrom(intent)) } catch (t: Throwable) { }
        finish()
        overridePendingTransition(0, 0)
    }
}
