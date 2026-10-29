plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// -PoutDir=<name> builds into app/<name>, e.g. when another Gradle process holds files in app/build
providers.gradleProperty("outDir").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "app.stylusnotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.stylusnotes"
        minSdk = 26
        targetSdk = 34
        // tools/release.py bumps these; -PvName/-PvCode override them for one-off builds
        versionCode = (project.findProperty("vCode") as String?)?.toInt() ?: 30101
        versionName = project.findProperty("vName") as String? ?: "3.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources { localeFilters += "en" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Updates only install over the same signature, so every release must be signed with this PC's
            // ~/.android/debug.keystore (it signed every build so far). Back that file up; losing it means a reinstall.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation("com.anthropic:anthropic-java:3.3.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
