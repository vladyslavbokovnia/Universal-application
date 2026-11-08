package im.manus.universalhost

import android.content.Context
import android.view.View

/**
 * Необязательный интерфейс: у модуля есть главное окно (список загрузок, плеер). Оно открывается отдельной
 * карточкой в недавних (ModuleViewActivity) и показывает только этот вид; остальные настройки модуля
 * остаются в экране настроек хоста (кнопка-шестерёнка в окне).
 */
interface IMainViewProvider {
    fun createMainView(context: Context): View
}
