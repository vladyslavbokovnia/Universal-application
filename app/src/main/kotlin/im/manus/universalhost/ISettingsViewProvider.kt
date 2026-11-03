package im.manus.universalhost

import android.content.Context
import android.view.View

/**
 * Необязательный интерфейс: модуль строит собственный вид внутри экрана настроек
 * (элемент схемы SettingItem.CUSTOM). [context] — активность настроек, её можно
 * использовать для диалогов. Значения модуль хранит сам в SharedPreferences "module_<имя>".
 */
interface ISettingsViewProvider {
    fun createSettingsView(context: Context, key: String): View
}
