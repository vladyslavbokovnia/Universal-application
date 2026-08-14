package im.manus.universalhost

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo

class CoreInputMethodService : InputMethodService() {
    override fun onCreateInputView(): View {
        // Здесь можно будет подгружать интерфейс клавиатуры из DEX-модуля
        return View(this) 
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
    }
}
