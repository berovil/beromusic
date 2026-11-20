plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.bero"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bero"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Clave de Last.fm integrada en la app para que NINGUN usuario tenga que crear la suya.
        // Se lee de la variable de entorno LASTFM_API_KEY (GitHub Actions -> Secrets) o de la propiedad de Gradle
        // LASTFM_API_KEY (gradle.properties local, NO subir al repositorio). Sin clave, la app pide la del usuario.
        val lastFmKey = (System.getenv("LASTFM_API_KEY") ?: (project.findProperty("LASTFM_API_KEY") as String?) ?: "").trim()
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmKey\"")

        // Clave publicable de Spicy Lyrics para cliente Android. Nunca usar una clave secreta sl_sk_ en el APK.
        // Configurarla en gradle.properties local o como variable de entorno de CI.
        val spicyLyricsKey = (System.getenv("SPICY_LYRICS_PUBLISHABLE_KEY")
            ?: (project.findProperty("SPICY_LYRICS_PUBLISHABLE_KEY") as String?) ?: "").trim()
        val escapedSpicyLyricsKey = spicyLyricsKey.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SPICY_LYRICS_PUBLISHABLE_KEY", "\"$escapedSpicyLyricsKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    // Pruebas unitarias locales (parser de letras)
    testImplementation("junit:junit:4.13.2")
}


tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
    }
}
