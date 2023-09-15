import org.gradle.api.JavaVersion
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * Android specific build properties
 */
object Android {
	const val min: Int = 24
	const val compile: Int = 34
	const val target: Int = 34

	const val buildTools: String = "34.0.0"

	val javaTarget: JavaVersion = JavaVersion.VERSION_17
	val javaKotlinTarget: JavaLanguageVersion = JavaLanguageVersion.of(javaTarget.majorVersion)
}
