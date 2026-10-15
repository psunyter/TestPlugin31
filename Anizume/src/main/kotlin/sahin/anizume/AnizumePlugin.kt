package sahin.anizume

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnizumePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnizumeProvider())
    }
}
