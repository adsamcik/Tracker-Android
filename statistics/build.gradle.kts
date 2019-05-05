plugins {
    id("com.android.dynamic-feature")
    kotlin("android.extensions")
    kotlin("android")
}


android {
    compileSdkVersion(Android.compile)

    defaultConfig {
        minSdkVersion(Android.min)
        targetSdkVersion(Android.target)
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation("com.github.PhilJay:MPAndroidChart:3.1.0")

    implementation(fileTree("libs").include("*.jar"))
    implementation(project(":app"))
    implementation("androidx.core:core-ktx:1.1.0-alpha05")
    implementation(Libraries.kotlinStdLib)

    implementation(fileTree("libs").include("*.jar"))
    implementation(project(":app"))
}