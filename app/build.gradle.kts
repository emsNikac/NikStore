plugins {
    alias(libs.plugins.android.application)
}

val catalogUrl = providers.gradleProperty("nikstoreCatalogUrl")
    .orElse("https://emsnikac.github.io/NikStore/catalog.json")

android {
    namespace = "com.nikac.nikstore"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nikac.nikstore"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        buildConfigField("String", "CATALOG_URL", "\"${catalogUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
