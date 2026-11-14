package im.manus.universalhost

import android.app.Application

class UniversalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Deps.init(this)
        ShizukuBridge.init(this)
    }
}
