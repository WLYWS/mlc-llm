package ai.mlc.mlcchat

import android.app.Application

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // SLFAppTypeUtil.initAppType(this, SLFAppType.Psync)
        // PUNCheckService.checkService(PUNServiceType.TEST)
    }
}