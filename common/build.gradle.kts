plugins {
	id("com.android.library")
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
	implementation(fileTree("libs").setIncludes(listOf("*.jar")))

	implementation("androidx.appcompat:appcompat:1.0.2")
	testImplementation("junit:junit:4.12")
	androidTestImplementation("androidx.test:runner:1.1.1")
	androidTestImplementation("androidx.test.espresso:espresso-core:3.1.1")
	implementation("androidx.core:core-ktx:1.1.0-alpha05")
	implementation(Libraries.kotlinStdLib)
}
repositories {
	mavenCentral()
}
