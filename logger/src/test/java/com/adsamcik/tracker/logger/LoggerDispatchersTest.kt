package com.adsamcik.tracker.logger

import com.adsamcik.tracker.logger.concurrency.LoggerDispatchers
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

@DisplayName("LoggerDispatchers")
class LoggerDispatchersTest {

	/**
	 * LoggerDispatchers is `internal object`, so we access it via reflection
	 * from the same package (tests share the package namespace).
	 */
	private fun getDispatchersClass(): Class<*> {
		return Class.forName("com.adsamcik.tracker.logger.concurrency.LoggerDispatchers")
	}

	private fun getInstance(): Any {
		val clazz = getDispatchersClass()
		val instanceField = clazz.getDeclaredField("INSTANCE")
		instanceField.isAccessible = true
		return instanceField.get(null)!!
	}

	@Nested
	@DisplayName("io dispatcher")
	inner class IoDispatcher {

		@Test
		fun `io returns Dispatchers IO`() {
			LoggerDispatchers.io shouldBe Dispatchers.IO
		}

		@Test
		fun `io is accessible via property`() {
			val instance = getInstance()
			val method = instance.javaClass.getDeclaredMethod("getIo")
			method.isAccessible = true
			val result = method.invoke(instance)
			result shouldBe Dispatchers.IO
		}
	}

	@Nested
	@DisplayName("default dispatcher")
	inner class DefaultDispatcher {

		@Test
		fun `default returns Dispatchers Default`() {
			LoggerDispatchers.default shouldBe Dispatchers.Default
		}

		@Test
		fun `default is accessible via property`() {
			val instance = getInstance()
			val method = instance.javaClass.getDeclaredMethod("getDefault")
			method.isAccessible = true
			val result = method.invoke(instance)
			result shouldBe Dispatchers.Default
		}
	}

	@Nested
	@DisplayName("Object structure")
	inner class ObjectStructure {

		@Test
		fun `is a singleton object`() {
			val clazz = getDispatchersClass()
			val instanceField = clazz.getDeclaredField("INSTANCE")
			Modifier.isStatic(instanceField.modifiers) shouldBe true
		}

		@Test
		fun `same instance on repeated access`() {
			val a = getInstance()
			val b = getInstance()
			(a === b) shouldBe true
		}
	}
}
