import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
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
        versionCode = 74
        versionName = "2.4.39"
        // GitHub release marker is intentionally independent from Android versionName.
        // Update this marker whenever a new APK is published under GitHub Releases.
        buildConfigField("String", "RELEASE_MARKER", "\"2.4.39\"")
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
            // Never fall back to the shared AOSP debug key: an artifact signed that way
            // cannot be installed over an existing release install (signature mismatch),
            // which silently breaks the GitHub "Download update" path for every user.
            // The task-graph guard below fails the build outright instead.
            signingConfig = if (hasPersistentKeystore) signingConfigs.getByName("gndecPersistent") else null
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

// A release artifact must carry the production signature. AGP only discovers a
// missing signing config at packaging time, so fail here with an actionable
// message — and only when a release task was actually requested, so debug builds,
// unit tests and lint keep working without a keystore.
gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { it.name.contains("Release") }
    if (releaseRequested && !hasPersistentKeystore) {
        throw GradleException(
            "Release build requested but no production keystore is configured.\n" +
                "Add to local.properties (gitignored):\n" +
                "  gndec.keystore.path=/absolute/path/to/release.jks\n" +
                "  gndec.keystore.password=…\n" +
                "  gndec.keystore.alias=…\n" +
                "  gndec.keystore.keyPassword=…\n" +
                "The release variant will NOT be signed with the debug key, because a\n" +
                "debug-signed release cannot be installed over an existing install."
        )
    }
}

ksp {
    // Exported schemas make the (now mandatory) Room migrations reviewable in code.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
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
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.clarity:clarity-compose:3.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.jsoup:jsoup:1.18.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
}
