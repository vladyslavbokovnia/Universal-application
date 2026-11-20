package im.manus.universalhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Broadcast im.manus.universalhost.BROWSER_COMMAND с параметром cmd (например next_tab): команда браузеру. */
class BrowserCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { BrowserCommands.dispatch(context, BrowserCommands.nameFrom(intent)) } catch (t: Throwable) { }
    }
}
