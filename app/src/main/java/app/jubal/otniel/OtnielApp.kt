package app.jubal.otniel

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class OtnielApp : Application(), ImageLoaderFactory {
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

    /** Pictures, including the site's SVG logo. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(true)
        .build()
}
