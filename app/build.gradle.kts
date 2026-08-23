import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val signingProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val persistentKeystore = signingProperties.getProperty("gndec.keystore.path")?.let(::file)
val hasPersistentKeystore = persistentKeystore?.isFile == true

android {
    namespace = "com.gndec.timetable"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gndec.timetable"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "2.3.8"
        // GitHub release marker is intentionally independent from Android versionName.
        // Update this marker whenever a new APK is published under GitHub Releases.
        buildConfigField("String", "RELEASE_MARKER", "\"2.3.8\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasPersistentKeystore && persistentKeystore != null) {
            create("gndecPersistent") {
                storeFile = persistentKeystore
                storePassword = signingProperties.getProperty("gndec.keystore.password", "android")
                keyAlias = signingProperties.getProperty("gndec.keystore.alias", "androiddebugkey")
                keyPassword = signingProperties.getProperty("gndec.keystore.keyPassword", "android")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Keep bundled notification audio in every release artifact; R8 code shrinking remains enabled.
            isShrinkResources = false
            signingConfig = if (hasPersistentKeystore) signingConfigs.getByName("gndecPersistent") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/*.kotlin_module") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.jsoup:jsoup:1.18.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
}
