package im.manus.universalhost

import android.graphics.Bitmap

/**
 * Необязательный интерфейс: модуль, который хочет иметь экран настроек в хосте,
 * реализует его вместе с IPlugin. Все три метода обязательны (так проще для Java-модулей).
 */
interface ISettingsProvider {
    /** Список настроек. Вызывается при каждом открытии экрана настроек, состояние init() не требуется. */
    fun getSettingsSchema(): List<SettingItem>

    /** Хост уже записал новое значение в SharedPreferences "module_<имя>"; для ACTION значение не пишется. */
    fun onSettingChanged(key: String)

    /** Значок модуля для экрана модулей (квадрат [sizePx]), либо null — тогда хост нарисует монограмму. */
    fun createIcon(sizePx: Int): Bitmap?
}
