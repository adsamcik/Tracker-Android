val kotlin_version: String by extra
plugins {
	id("com.android.library")
	kotlin("kotlin-android-extensions")
	kotlin("kotlin-android")
}
apply {
	plugin("kotlin-android")
	plugin("kotlin-android-extensions")
}

android {
	compileSdkVersion $compile_sdk


	defaultConfig {
		minSdkVersion $min_sdk
		targetSdkVersion $compile_sdk
		versionCode 1
		versionName "1.0"

		testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"

	}

	buildTypes {
		release {
			minifyEnabled false
			proguardFiles getDefaultProguardFile ("proguard-android-optimize.txt"), "proguard-rules.pro"
		}
	}

}

dependencies {
	implementation(fileTree("libs").setIncludes(listOf("*.jar")))

	implementation "androidx.appcompat:appcompat:1.0.2"
	testImplementation "junit:junit:4.12"
	androidTestImplementation "androidx.test:runner:1.1.1"
	androidTestImplementation "androidx.test.espresso:espresso-core:3.1.1"
	implementation "androidx.core:core-ktx:1.1.0-alpha05"
	implementation "org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlin_version"
	compile("androidx.core:core-ktx:+")
	implementation(kotlinModule("stdlib-jdk7", kotlin_version))
}
repositories {
	mavenCentral()
}
