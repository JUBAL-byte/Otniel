package app.jubal.otniel

import android.app.Application

class OtnielApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Updates.createChannel(this)
        Updates.schedule(this)
    }
}
