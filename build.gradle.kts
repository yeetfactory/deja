plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

spotless {
    val ktlint = libs.versions.ktlint.get()
    val composeRules =
        libs.compose.rules.ktlint
            .get()
            .let { "${it.module}:${it.version}" }
    val generated = listOf("**/build/**", "**/.gradle/**", "**/.kotlin/**")
    val editorConfig = rootProject.file(".editorconfig")
    // Spotless passes only ktlint's own settings from .editorconfig, so hand the compose-rules ones over too.
    val composeSettings =
        editorConfig
            .readLines()
            .map { it.trim() }
            .filter { it.startsWith("compose_") }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

    kotlin {
        target("**/*.kt")
        targetExclude(generated)
        ktlint(ktlint)
            .customRuleSets(listOf(composeRules))
            .setEditorConfigPath(editorConfig)
            .editorConfigOverride(composeSettings)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(generated)
        ktlint(ktlint).setEditorConfigPath(editorConfig)
    }
}
