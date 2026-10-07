package im.manus.universalhost

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * Клавиатура-контейнер. Вид и логика — в DEX-модуле с именем "Keyboard" (modules/KeyboardModule.java),
 * поэтому дальше клавиатуру можно менять, не переустанавливая APK.
 *
 * Хост передаёт модулю саму службу (InputMethodService) в параметре "service": через неё модуль берёт
 * currentInputConnection, окно, ресурсы и размеры. Команды (параметр "cmd"):
 *   createInputView          -> View клавиатуры (обязательно)
 *   startInput               + "info" (EditorInfo?), "restarting" (Boolean)
 *   finishInput
 *   touchRegion              -> Rect или Region в координатах окна клавиатуры (только в режиме «поверх содержимого»):
 *                               остальная часть окна пропускает касания в приложение под ней
 *   destroy
 * Если модуль выключен, не найден или вернул ошибку, показывается пустое поле, ввод не ломается.
 *
 * Режим «поверх содержимого»: настройка модуля Keyboard с ключом overlay_mode (SharedPreferences
 * "module_Keyboard", boolean). Тогда приложение под клавиатурой не сдвигается и не сжимается, а клавиатура
 * (например, свёрнутая в маленькую прозрачную кнопку) лежит поверх него.
 */
class CoreInputMethodService : InputMethodService() {

    companion object {
        const val MODULE = "Keyboard"
        private const val PREFS = "module_Keyboard"
        private const val KEY_OVERLAY = "overlay_mode"
    }

    private var plugin: IPlugin? = null

    private fun keyboard(): IPlugin? {
        if (!ModuleStorage.isEnabled(this, MODULE)) return null
        plugin?.let { return it }
        plugin = PluginRegistry.find(this, MODULE)
        return plugin
    }

    private fun call(cmd: String, extra: Map<String, Any> = emptyMap()): Any? {
        val p = keyboard() ?: return null
        return try {
            p.execute(mapOf<String, Any>("cmd" to cmd, "service" to this) + extra)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    private fun overlayMode(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OVERLAY, false)

    /** Без этого в альбомной ориентации система показывает клавиатуру на весь экран. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * В режиме «поверх содержимого» клавиатура не занимает место в приложении: contentTopInsets равен
     * высоте окна, как в полноэкранном режиме. Если модуль вернул touchRegion, касания принимает только он.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!overlayMode()) return
        val decor = window?.window?.decorView ?: return
        outInsets.contentTopInsets = decor.height
        when (val area = call("touchRegion")) {
            is Rect -> {
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                outInsets.touchableRegion.set(area)
            }
            is Region -> {
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                outInsets.touchableRegion.set(area)
            }
            else -> Unit
        }
    }

    override fun onCreateInputView(): View {
        // Окно клавиатуры прозрачное: вид модуля сам решает, что и как рисовать.
        try {
            window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        return (call("createInputView") as? View) ?: View(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val extra = mutableMapOf<String, Any>("restarting" to restarting)
        if (info != null) extra["info"] = info
        call("startInput", extra)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        call("finishInput")
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        call("destroy")
        plugin = null
        super.onDestroy()
    }
}
