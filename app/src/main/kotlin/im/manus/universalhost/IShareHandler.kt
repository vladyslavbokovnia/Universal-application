package im.manus.universalhost

import android.content.Context
import android.content.Intent

/**
 * Необязательный интерфейс: модуль, который умеет принимать «Поделиться».
 *
 * Хост показывает такие модули в системном меню шаринга через ModuleShareActivity:
 * она находит включённый модуль с подходящим MIME-типом и отдаёт ему интент.
 * Реализуется вместе с IPlugin (удобно на Java).
 */
interface IShareHandler {
    /** MIME-типы, которые модуль принимает, например listOf("audio/*"). */
    fun shareMimeTypes(): List<String>

    /**
     * Входящий share-интент (ACTION_SEND, файл — в Intent.EXTRA_STREAM).
     * [context] — активити-приёмник, она сразу завершится; для долгой работы
     * бери applicationContext. Верни true, если принял.
     */
    fun onShare(context: Context, intent: Intent): Boolean
}
