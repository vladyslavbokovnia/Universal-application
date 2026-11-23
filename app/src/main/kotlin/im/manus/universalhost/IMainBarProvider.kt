package im.manus.universalhost

import android.content.Context
import android.view.View

/**
 * Необязательный интерфейс: модуль даёт нижнюю панель главного окна (рядом с шестерёнкой настроек).
 * Панель строит модуль целиком; окно только ставит её под списком. null: панели нет.
 */
interface IMainBarProvider {
    fun createBottomBar(context: Context): View?
}
