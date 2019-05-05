plugins {
	id("com.android.library")
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
	implementation(fileTree("libs").setIncludes(listOf("*.jar")))

	testImplementation("junit:junit:4.12")
	androidTestImplementation("androidx.test:runner:1.1.1")
	androidTestImplementation("androidx.test.espresso:espresso-core:3.1.1")
}
repositories {
	mavenCentral()
}
