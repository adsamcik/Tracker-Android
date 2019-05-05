plugins {
    id("com.android.dynamic-feature")
}

android {
    compileSdkVersion $compile_sdk
    


    defaultConfig {
        minSdkVersion $min_sdk
        targetSdkVersion $compile_sdk
        versionCode = 1
        versionName = "1.0"


    }


}

dependencies {
    //Work
    def work_version = "2.0.1"
    implementation "androidx.work:work-runtime-ktx:$work_version"
    androidTestImplementation "androidx.work:work-testing:$work_version"
    implementation "androidx.core:core-ktx:1.1.0-alpha05"

    implementation fileTree(dir: "libs", include: ["*.jar"])
    implementation project(":app")
    compile("androidx.core:core-ktx:+")
    implementation(kotlinModule("stdlib-jdk7", kotlin_version))
}
apply {
    plugin("kotlin-android")
}
repositories {
    mavenCentral()
}
apply {
    plugin("kotlin-android-extensions")
}