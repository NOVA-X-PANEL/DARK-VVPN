// Kotlin scripts resolve a bare `java` to Gradle's JavaPluginExtension, not to
// the JDK package root, so the two JDK types this script needs are imported by
// name instead of qualified.
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/* ---------------------------------------------------------------------------
 * The tunnel core.
 *
 * DARK VVPN uses Xray-core, compiled for Android as a Go mobile library by
 * AndroidLibXrayLite — the same artefact v2rayNG ships, so the integration path
 * is a well-trodden one rather than a guess.
 *
 * It is downloaded at build time rather than committed: it is ~62 MB, which
 * would live in every clone of this repository forever. The version and the
 * SHA-256 are pinned below, and a mismatch fails the build, because a substituted
 * core is a full device compromise — the one dependency in this project where
 * "download whatever the URL serves" is not acceptable.
 * --------------------------------------------------------------------------- */
val xrayCoreVersion = "26.9.9"
val xrayCoreSha256 = "9ecf4c921568d8f4cb8550d3bafe08ff6f1d1984f45a6ad183dcdf52ee9302de"
val xrayCoreUrl =
    "https://github.com/2dust/AndroidLibXrayLite/releases/download/v$xrayCoreVersion/libv2ray.aar"

val xrayCoreAar = layout.projectDirectory.file("libs/libv2ray-$xrayCoreVersion.aar")

/** Downloads and verifies the core, skipping the work when it is already right. */
val fetchXrayCore by tasks.registering {
    val target = xrayCoreAar.asFile
    outputs.file(xrayCoreAar)
    // Re-run only when the inputs change; the file itself is a cache.
    inputs.property("version", xrayCoreVersion)
    inputs.property("sha256", xrayCoreSha256)

    doLast {
        if (target.exists() && sha256(target) == xrayCoreSha256) {
            logger.lifecycle("Xray core $xrayCoreVersion already present and verified.")
            return@doLast
        }
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        logger.lifecycle("Downloading Xray core $xrayCoreVersion…")
        URI(xrayCoreUrl).toURL().openStream().use { input ->
            partial.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        val actual = sha256(partial)
        if (actual != xrayCoreSha256) {
            partial.delete()
            throw GradleException(
                "Xray core digest mismatch. Expected $xrayCoreSha256 but got $actual. " +
                    "The download was discarded; check the pinned version in app/build.gradle.kts.",
            )
        }
        target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            throw GradleException("Could not store the Xray core at ${target.absolutePath}.")
        }
        logger.lifecycle("Xray core $xrayCoreVersion downloaded and verified.")
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

// The core must exist before anything compiles against it. `builtBy` on the
// dependency covers the classpath consumers; these cover the packaging tasks,
// which also read the AAR's native libraries.
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchXrayCore) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
    .configureEach { dependsOn(fetchXrayCore) }

android {
    namespace = "com.darkvvpn.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.darkvvpn.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // The core ships four ABIs; x86 (32-bit) is the only one no device or
        // current emulator needs, and dropping it saves ~35 MB from the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time is used by the update checker's date parsing and is API 26+;
        // desugaring back-ports it to the minSdk 24 floor without a second
        // date library, and the release notes parser needs it too.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric runs the real Android resource system on the JVM, so a
            // test can load a drawable exactly the way the app does — which is how
            // the launch crash below is caught without a device.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // The core is a ~35 MB Go binary per ABI. Left uncompressed (the AGP
            // default for a modern minSdk) three of them dominate the APK; storing
            // them compressed and letting the installer unpack them cuts roughly
            // 70 MB off the download. Go libraries load the same either way.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // The tunnel core (Xray-core via gomobile). `builtBy` makes the download an
    // explicit dependency of everything that consumes this classpath, so Gradle
    // cannot start compiling against a file that has not been fetched yet.
    implementation(files(xrayCoreAar).builtBy(fetchXrayCore))

    // Core & lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Persistence
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Back-ports java.time to API 24 (used by the update checker)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric lets the startup path be exercised on the JVM: the real
    // Application, the real Activity, the real resources. The launch crash this
    // guards against is invisible to a pure unit test.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Compose UI testing on the JVM. Robolectric renders for real, so this proves
    // the update badge and banner actually draw — not merely that the state behind
    // them is correct, which is the gap that let the splash crash ship.
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
