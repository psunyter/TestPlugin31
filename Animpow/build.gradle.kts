plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Animpow Türkçe Altyazılı Anime Sağlayıcısı"
    authors = listOf("Sahin")
    status = 1
    tvTypes = listOf("Anime")
    requiresResources = false
    language = "tr"
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/Korduene_Logo.png"
}

dependencies {
    implementation(kotlin("stdlib"))
}
