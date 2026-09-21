plugins { id("com.android.application") }

android {
    namespace = "ist.solo.notifications"
    compileSdk = 34

    defaultConfig {
        applicationId = "ist.solo.notifications"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("soloist") {
            storeFile = file(
                System.getenv("NOTIFICATIONS_SIGNING_STORE")
                    ?: "${System.getProperty("user.home")}/.android-keys/soloist-notifications.jks",
            )
            storePassword = System.getenv("NOTIFICATIONS_SIGNING_PASSWORD").orEmpty()
            keyAlias = "soloist-notifications"
            keyPassword = System.getenv("NOTIFICATIONS_SIGNING_PASSWORD").orEmpty()
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            // Never the release identity, and a separate applicationId: a
            // debuggable build must not be able to replace the release app.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("soloist")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
