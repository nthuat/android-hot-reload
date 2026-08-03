plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
group = "dev.thuat"
version = "0.1.0-SNAPSHOT"
android {
    namespace = "dev.thuat.hotreload.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // compileOnly: the app supplies its own Compose runtime; we only reflect into it
    compileOnly(platform(libs.compose.bom))
    compileOnly("androidx.compose.runtime:runtime")
}
