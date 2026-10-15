// Top-level build file. AGP 9 compiles Kotlin itself; the Kotlin plugin line only pins
// the compiler version (LiteRT 2.1.5 ships Kotlin 2.3 metadata).
plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
