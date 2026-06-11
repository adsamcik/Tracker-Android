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

include(":app")
include(":tracker")
include(":core:base", ":core:common", ":core:ui", ":core:logging", ":core:logging-api", ":core:network", ":core:testing")
include(":data:preferences")
include(":stats:api", ":stats:engine", ":stats:data")
include(":domain:points", ":domain:osm")
include(":sensor:activity-api", ":sensor:activity")
include(":feature:map", ":feature:statistics", ":feature:dashboard", ":feature:game", ":feature:activity", ":feature:import-export")
