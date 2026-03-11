pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include(":impexp")
include(":logger")
include(":points")
include(":testing-common")
include(":app", ":statistics", ":game", ":map", ":sbase", ":spreferences", "sutils", ":tracker", ":activity", ":dashboard")
include(":stats-api", ":stats-engine", ":stats-data")
