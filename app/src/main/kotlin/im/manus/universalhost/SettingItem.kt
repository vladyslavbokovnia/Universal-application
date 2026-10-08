package im.manus.universalhost

/**
 * Описание одной настройки модуля. Модуль (в том числе на Java) возвращает список таких
 * элементов из ISettingsProvider.getSettingsSchema(), а рисует их и хранит значения хост.
 *
 * Значения лежат в SharedPreferences "module_<имя модуля>" под ключом [key]:
 * SWITCH — boolean, SLIDER и CHOICE — int, TEXT — String, IMAGE — путь к файлу (String),
 * APP_IMAGES — по одному пути на приложение под ключом "<key>:<пакет>".
 */
class SettingItem private constructor(
    val type: Int,
    val key: String,
    val title: String,
    val summary: String,
    val min: Int,
    val max: Int,
    val step: Int,
    val defInt: Int,
    val defStr: String,
    val options: List<String>
) {
    companion object {
        const val SECTION = 0
        const val SWITCH = 1
        const val SLIDER = 2
        const val CHOICE = 3
        const val TEXT = 4
        const val IMAGE = 5
        const val APP_IMAGES = 6
        const val ACTION = 7
        const val MONITOR = 8

        @JvmStatic
        fun section(title: String) =
            SettingItem(SECTION, "", title, "", 0, 0, 1, 0, "", emptyList())

        @JvmStatic
        fun toggle(key: String, title: String, summary: String, def: Boolean) =
            SettingItem(SWITCH, key, title, summary, 0, 1, 1, if (def) 1 else 0, "", emptyList())

        @JvmStatic
        fun slider(key: String, title: String, summary: String, min: Int, max: Int, step: Int, def: Int) =
            SettingItem(SLIDER, key, title, summary, min, max, if (step < 1) 1 else step, def, "", emptyList())

        @JvmStatic
        fun choice(key: String, title: String, summary: String, options: List<String>, def: Int) =
            SettingItem(CHOICE, key, title, summary, 0, options.size - 1, 1, def, "", options)

        @JvmStatic
        fun text(key: String, title: String, summary: String, def: String) =
            SettingItem(TEXT, key, title, summary, 0, 0, 1, 0, def, emptyList())

        @JvmStatic
        fun image(key: String, title: String, summary: String) =
            SettingItem(IMAGE, key, title, summary, 0, 0, 1, 0, "", emptyList())

        @JvmStatic
        fun appImages(key: String, title: String, summary: String) =
            SettingItem(APP_IMAGES, key, title, summary, 0, 0, 1, 0, "", emptyList())

        @JvmStatic
        fun action(key: String, title: String, summary: String) =
            SettingItem(ACTION, key, title, summary, 0, 0, 1, 0, "", emptyList())

        /**
         * Живой монитор: пока экран настроек открыт, хост дважды в секунду вызывает
         * plugin.execute({"command": "monitor:<key>"}) и ждёт Map с ключами:
         * "values" (int[] — выборки для графика), "value" (String — крупная подпись), "status" (String).
         */
        @JvmStatic
        fun monitor(key: String, title: String, summary: String) =
            SettingItem(MONITOR, key, title, summary, 0, 0, 1, 0, "", emptyList())
    }
}
