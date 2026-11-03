package im.manus.universalhost

import android.app.Application

class UniversalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuBridge.init(this)
    }
}
