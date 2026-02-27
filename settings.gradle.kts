pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
	repositories {
		google()
		maven("https://jitpack.io")
		mavenCentral()
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include(":impexp")
include(":logger")
include(":logging-api")
include(":points")
include(":testing-common")
include(":app", ":statistics", ":game", ":map", ":sbase", ":spreferences", "sutils", ":tracker", ":activity", ":dashboard")
include(":stats-api", ":stats-engine", ":stats-data")
