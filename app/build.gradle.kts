import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is optional. CI supplies it through environment variables that
// originate from GitHub Secrets; a local developer may supply a keystore.properties.
// When neither is present the release variant is still assembled, but unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("storeFile", "ANDROID_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFile != null &&
    file(releaseStoreFile).exists() &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.reweb.browser"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.reweb.browser"
        // API 21 is the oldest level on which the system WebView is an
        // independently updatable Chromium APK. Below that the WebView is frozen
        // to the ROM's WebKit build and cannot render the modern web at all,
        // which makes this app pointless there. See COMPATIBILITY.md.
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables.useSupportLibrary = true
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            buildConfigField("boolean", "VERBOSE_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "VERBOSE_LOGGING", "false")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required for java.time / try-with-resources style APIs used by the
        // stores to behave identically down to API 21.
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // Report to a stable path so CI can archive it.
        htmlReport = true
        xmlReport = true
        sarifReport = false
        disable += setOf(
            // The app deliberately ships English-only strings to keep the APK small.
            "MissingTranslation",
            // Gradle plugin/dependency freshness is governed by the minSdk floor
            // documented in gradle/libs.versions.toml, not by "newest available".
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable"
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "reweb-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    // Custom Tabs — used only for the OAuth handoff to a real browser.
    implementation(libs.androidx.browser)
    // WebViewCompat.addDocumentStartJavaScript, for the legacy-CDM shim in
    // engine/webview/LegacyCdmShim. Feature-detected at runtime.
    implementation(libs.androidx.webkit)
    // MediaSessionCompat + media-style notification for background web audio.
    implementation(libs.androidx.media)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Prints the size of every APK produced, so both local builds and CI logs record it.
tasks.register("reportApkSize") {
    val apkDir = layout.buildDirectory.dir("outputs/apk")
    doLast {
        val dir = apkDir.get().asFile
        if (!dir.exists()) {
            logger.lifecycle("No APK output directory at ${dir.path}; build an APK first.")
            return@doLast
        }
        val apks = dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        if (apks.isEmpty()) {
            logger.lifecycle("No APKs found under ${dir.path}.")
            return@doLast
        }
        logger.lifecycle("=== ReWeb APK sizes ===")
        apks.sortedBy { it.path }.forEach {
            logger.lifecycle(String.format("%-40s %,d bytes (%.2f MiB)", it.name, it.length(), it.length() / 1048576.0))
        }
    }
}
