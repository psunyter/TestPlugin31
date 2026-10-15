package sahin.hdfilmcehennemi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HdFilmCehennemiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HdFilmCehennemiProvider())
    }
}
