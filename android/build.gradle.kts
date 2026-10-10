// AGP 9 has built-in Kotlin: org.jetbrains.kotlin.android is intentionally not applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
