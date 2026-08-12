import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName = requireNotNull(appVersionProperties.getProperty("versionName")) {
    "version.properties must define versionName"
}.also {
    require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) {
        "versionName must be a SemVer-compatible value: $it"
    }
}
val appVersionCode = requireNotNull(appVersionProperties.getProperty("versionCode")) {
    "version.properties must define versionCode"
}.toInt().also {
    require(it > 0) { "versionCode must be positive" }
}

val releaseSigningPropertyNames = listOf(
    "lenswake.release.storeFile",
    "lenswake.release.storePassword",
    "lenswake.release.keyAlias",
    "lenswake.release.keyPassword",
)
val releaseSigningProperties = releaseSigningPropertyNames.associateWith {
    providers.gradleProperty(it).orNull
}
val configuredReleaseSigningProperties = releaseSigningProperties.filterValues { !it.isNullOrBlank() }
require(
    configuredReleaseSigningProperties.isEmpty() ||
        configuredReleaseSigningProperties.size == releaseSigningPropertyNames.size,
) {
    val missing = releaseSigningProperties.filterValues { it.isNullOrBlank() }.keys.sorted()
    "Release signing is partially configured; missing: ${missing.joinToString()}"
}
val releaseSigningEnabled = configuredReleaseSigningProperties.isNotEmpty()

android {
    namespace = "dev.po4yka.lenswake"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.po4yka.lenswake"
        minSdk = 35
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getValue("lenswake.release.storeFile")!!)
                storePassword = releaseSigningProperties.getValue("lenswake.release.storePassword")
                keyAlias = releaseSigningProperties.getValue("lenswake.release.keyAlias")
                keyPassword = releaseSigningProperties.getValue("lenswake.release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = false
        buildConfig = true
        compose = true
        shaders = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":automation"))
    implementation(project(":core"))
    implementation(project(":data"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
