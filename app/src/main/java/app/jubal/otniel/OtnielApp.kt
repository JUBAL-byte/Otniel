package app.jubal.otniel

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class OtnielApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Settings.applyTheme(this)
        NewPipe.init(NewPipeDownloader(), Localization("he", "IL"), ContentCountry("IL"))

        // No point fetching a picture taller than the screen is wide (Qin-size phones get 480p)
        val m = resources.displayMetrics
        val short = minOf(m.widthPixels, m.heightPixels)
        YouTube.maxHeight = if (short <= 540) 480 else 720

        Updates.createChannel(this)
        Updates.schedule(this)
    }
}
