import org.gradle.api.JavaVersion

/**
 * Android specific build properties
 */
object Android {
	const val min: Int = 24
	const val compile: Int = 34
	const val target: Int = 34

	const val buildTools: String = "34.0.0"

	val javaTarget: JavaVersion = JavaVersion.VERSION_1_8
	const val jvmTarget: String = "1.8"
}
