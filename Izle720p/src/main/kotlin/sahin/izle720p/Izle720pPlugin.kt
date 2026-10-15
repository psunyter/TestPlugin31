package sahin.izle720p

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Izle720pPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Izle720pProvider())
    }
}
