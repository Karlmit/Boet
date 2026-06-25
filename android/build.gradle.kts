plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.2.0+ required: ML Kit GenAI (genai-prompt/common betas) ship Kotlin
    // 2.2.0 metadata, which a 2.0.x compiler refuses to read.
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
