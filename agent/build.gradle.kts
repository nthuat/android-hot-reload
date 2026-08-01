plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.hotreload.agent"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}
