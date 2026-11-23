package im.manus.universalhost

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Выбор команды браузера для приложений, которые создают ярлыки старым способом CREATE_SHORTCUT
 * (список «App shortcut» в Micro Gesture и подобных). Показывает список команд и возвращает ярлык,
 * который открывает universal://browser/<команда>.
 */
class BrowserShortcutPickerActivity : Activity() {

    private val commands = arrayOf(
        "next_tab" to "Следующая вкладка",
        "prev_tab" to "Предыдущая вкладка",
        "tabs" to "Панель вкладок",
        "new_tab" to "Новая вкладка",
        "close_tab" to "Закрыть вкладку",
        "back" to "Назад",
        "reload" to "Обновить страницу",
        "scroll_down" to "Страница вниз",
        "scroll_up" to "Страница вверх",
        "scroll_top" to "В начало страницы",
        "scroll_bottom" to "В конец страницы",
        "hub" to "Показать или скрыть кнопку",
        "fullscreen" to "Полный экран",
        "close_layer" to "Закрыть панель или меню",
        "open_browser" to "Открыть браузер"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val labels = Array(commands.size) { commands[it].second }
        AlertDialog.Builder(this)
            .setTitle("Команда браузера")
            .setItems(labels) { _, i -> deliver(commands[i].first, commands[i].second) }
            .setOnCancelListener { setResult(RESULT_CANCELED); finish() }
            .show()
    }

    private fun deliver(cmd: String, label: String) {
        val launch = Intent(Intent.ACTION_VIEW, Uri.parse("universal://browser/$cmd"))
            .setClassName(packageName, "im.manus.universalhost.BrowserCommandActivity")
        val result = Intent()
            .putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch)
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, "Браузер: $label")
            .putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_browser_app)
            )
        setResult(RESULT_OK, result)
        finish()
    }
}
