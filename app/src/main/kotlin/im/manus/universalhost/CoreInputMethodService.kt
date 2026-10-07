package im.manus.universalhost

import android.graphics.Color
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
 *   destroy
 * Если модуль выключен, не найден или вернул ошибку, показывается пустое поле, ввод не ломается.
 */
class CoreInputMethodService : InputMethodService() {

    companion object {
        const val MODULE = "Keyboard"
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

    /** Без этого в альбомной ориентации система показывает клавиатуру на весь экран. */
    override fun onEvaluateFullscreenMode(): Boolean = false

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
