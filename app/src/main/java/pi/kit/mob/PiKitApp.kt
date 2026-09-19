package pi.kit.mob

import android.app.Application
import pi.kit.mob.pi.PiAgentService

class PiKitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PiAgentService.createNotificationChannel(this)
    }
}
