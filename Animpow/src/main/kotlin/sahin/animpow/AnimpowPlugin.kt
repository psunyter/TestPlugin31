package sahin.animpow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimpowPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimpowProvider())
    }
}
