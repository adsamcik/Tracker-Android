plugins {
	kotlin("jvm")
	application
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.xerial:sqlite-jdbc:3.45.1.0")
	implementation("com.google.code.gson:gson:2.10.1")
}

application {
	mainClass.set("SkiDataGeneratorKt")
}

tasks.named<JavaExec>("run") {
	// Default input path; override with --args="path/to/lifts.geojson"
	val defaultInput = project.file("input/lifts.geojson").absolutePath
	args = listOf(defaultInput)
	workingDir = project.projectDir
}

kotlin {
	jvmToolchain(17)
}
