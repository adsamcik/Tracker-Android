package com.adsamcik.tracker.points.database

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import javax.inject.Singleton

@DisplayName("PointsDatabaseModule")
class PointsDatabaseModuleTest {

	@Nested
	inner class ObjectSingleton {
		@Test
		fun `PointsDatabaseModule is a Kotlin object`() {
			// Verify the module is an object (singleton) by accessing it directly
			val module = PointsDatabaseModule
			module shouldNotBe null
		}
	}

	@Nested
	inner class ProviderMethods {
		@Test
		fun `providePointsDatabase method exists`() {
			val method = PointsDatabaseModule::class.java.methods
				.find { it.name == "providePointsDatabase" }
			method shouldNotBe null
		}

		@Test
		fun `providePointsDatabase returns PointsDatabase type`() {
			val method = PointsDatabaseModule::class.java.methods
				.find { it.name == "providePointsDatabase" }
			method!!.returnType shouldBe PointsDatabase::class.java
		}

		@Test
		fun `providePointsDatabase is annotated with Singleton`() {
			val method = PointsDatabaseModule::class.java.methods
				.find { it.name == "providePointsDatabase" }
			val singletonAnnotation = method!!.getAnnotation(Singleton::class.java)
			singletonAnnotation shouldNotBe null
		}

		@Test
		fun `providePointsDatabase accepts Context parameter`() {
			val method = PointsDatabaseModule::class.java.methods
				.find { it.name == "providePointsDatabase" }
			method!!.parameterTypes.size shouldBe 1
			method.parameterTypes[0].name shouldBe "android.content.Context"
		}
	}
}
