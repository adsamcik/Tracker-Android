plugins {
    id("com.android.dynamic-feature")
    kotlin("android")
    kotlin("android.extensions")
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
    //Work
    val work_version = "2.0.1"
    implementation("androidx.work:work-runtime-ktx:$work_version")
    androidTestImplementation("androidx.work:work-testing:$work_version")
    implementation("androidx.core:core-ktx:1.1.0-alpha05")
    implementation(Libraries.kotlinStdLib)

    implementation(fileTree("libs").include("*.jar"))
    implementation(project(":app"))
}