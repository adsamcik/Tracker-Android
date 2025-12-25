pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

include(":impexp")
include(":logger")
include(":points")
include(":testing-common")
include(":app", ":statistics", ":game", ":map", ":sbase", ":smap", ":spreferences", "sutils", ":tracker", ":activity", ":macrobenchmark")
