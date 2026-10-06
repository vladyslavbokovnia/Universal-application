package im.manus.universalhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки и обновления APK возвращает службу специальных возможностей (если это включено в модуле Shizuku). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                ShizukuBridge.onBootOrUpdate(app)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
