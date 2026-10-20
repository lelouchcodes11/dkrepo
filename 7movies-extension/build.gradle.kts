plugins {
    id("com.android.application") version "9.1.1" apply false
}

subprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
