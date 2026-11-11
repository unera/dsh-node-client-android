plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.uvw.dshnc"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.uvw.dshnc"
        minSdk = 33
        targetSdk = 34
        versionCode = 104
        versionName = "0.10.11"
    }

    signingConfigs {
        create("release") {
            // ключ подписи лежит вне репозитория: без него нельзя выпустить
            // обновление поверх установленного приложения
            val store = System.getenv("DSHNC_KEYSTORE") ?: "/home/dsh/work/ai/keys/dshnc-release.jks"
            val pass = System.getenv("DSHNC_KEYSTORE_PASS") ?: ""
            storeFile = file(store)
            storePassword = pass
            keyAlias = "dshnc"
            keyPassword = pass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // подпись настоящим ключом: debug-подпись Android справедливо
            // считает подозрительной и пугает человека
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        // версию печатаем на экране: при отладке по скриншотам это первое, что нужно
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // сканер QR: готовый экран камеры, без сервисов Google
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
