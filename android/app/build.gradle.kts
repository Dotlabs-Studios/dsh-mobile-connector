import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.dsh.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dsh.mobile"
        minSdk = 26
        targetSdk = 36
        // The languages this app ships, and the reason the list is written out
        // rather than left implicit: without it the resource merger walks every
        // library on the classpath and copies their translations in, so the APK
        // would carry Norwegian strings for the fifteen words AndroidX draws.
        // Adding a language is one line here — the wrapper, the fallback and the
        // untranslated strings all behave with no further change.
        //
        // `localeFilters` è il nome nuovo di `resourceConfigurations`: con AGP
        // 8.13 il vecchio è un errore di compilazione dello script, non un
        // avviso, e il progetto non si configura più.

        // The build number is what Android compares when deciding whether an APK
        // is an update: leaving it at 1 made every rebuild look like the version
        // already on the phone, and an installer that is asked to install the
        // same version twice is where "non me la dà come aggiornamento" comes
        // from. It moves with the feature, and the name beside it is the label a
        // person reads. The name tracks the plugin's own version, so the two
        // halves of the wire cannot disagree about which build they are.
        versionCode = 4
        versionName = "0.3.0"
    }

    // La riga qui sopra dice quali lingue servono; questa la esegue.
    androidResources {
        localeFilters += listOf("it", "en")
    }

    signingConfigs {
        // La chiave di release è di chi pubblica l'app, non del repository:
        // `android/keystore.properties` (ignorato da git) la descrive, e la sua
        // assenza è il caso normale, non un errore. Senza quel file la release
        // si firma con la chiave di debug — che è pubblica e va bene per
        // un'installazione via `adb install`, non per uno store.
        if (rootProject.file("keystore.properties").exists()) {
            create("release") {
                val secret = Properties().apply {
                    rootProject.file("keystore.properties").inputStream().use { load(it) }
                }
                // `storeFile` è relativo ad `android/app/` se non è assoluto.
                storeFile = file(secret.getProperty("storeFile"))
                storePassword = secret.getProperty("storePassword")
                keyAlias = secret.getProperty("keyAlias")
                keyPassword = secret.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Un APK di release non firmato non si installa: `adb install` lo
            // rifiuta, e l'unica versione che un telefono accetta è una firmata.
            // La chiave di debug (`~/.android/debug.keystore`, creata da Android
            // Studio o dal primo build) rende la release installabile senza
            // chiedere a nessuno di custodire un segreto; una chiave propria,
            // quando c'è, ha la precedenza.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // The data layer is plain JVM code, so it is covered by local unit tests
    // rather than an instrumented run.
    testImplementation("junit:junit:4.13.2")
}
