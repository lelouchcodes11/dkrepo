plugins {
    id("com.android.application")
}

android {
    namespace = "com.sevenmovies.extension"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sevenmovies.extension"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // Extensions must be one classes.dex: the desktop host translates
        // classes.dex and nothing else.
        multiDexEnabled = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val csVersion = "4.8.0"
    // compileOnly: the host app supplies the provider API and networking stack.
    // This keeps the extension archive to a single classes.dex (community .cs3 layout).
    compileOnly("com.github.recloudstream:cloudstream:$csVersion") {
        exclude(group = "com.github.recloudstream.cloudstream", module = "library-jvm")
    }
    compileOnly("com.github.Blatzar:NiceHttp:0.4.18")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.13.1")
}
