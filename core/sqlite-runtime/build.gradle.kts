plugins {
	id("tracker.android.library")
}

android {
	namespace = "com.adsamcik.tracker.sqlite.runtime"

	defaultConfig {
		consumerProguardFiles("consumer-rules.pro")
	}
}

dependencies {
	// The SupportSQLite contracts exposed by this module's public factory.
	api(libs.androidx.sqlite)

	// Official SQLite Android binding containing org.sqlite classes and libsqliteX.so.
	implementation(files("libs/sqlite-android-3530300.aar"))
}
