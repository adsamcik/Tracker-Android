plugins {
    id("com.android.dynamic-feature")
    Libraries.corePlugins(this)
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
    Libraries.core(this)

    implementation("com.github.PhilJay:MPAndroidChart:3.1.0")

    implementation(fileTree("libs").include("*.jar"))
    implementation(project(":app"))
}