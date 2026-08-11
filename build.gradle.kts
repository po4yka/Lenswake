plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

private val volatileDependencyRecommendationChecks =
    setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")

private fun com.android.build.api.dsl.Lint.configureProjectPolicy() {
    warningsAsErrors = true
    // These volatile update recommendations are reviewed during dependency maintenance.
    // Source and project correctness checks remain enabled and strict.
    disable += volatileDependencyRecommendationChecks
}

subprojects {
    apply(plugin = "dev.detekt")

    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint.configureProjectPolicy()
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint.configureProjectPolicy()
        }
    }

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        basePath.set(rootProject.projectDir)
    }
}
