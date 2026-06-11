package com.adsamcik.tracker.testing.junit5

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolutionException
import org.junit.jupiter.api.extension.ParameterResolver

/**
 * JUnit 5 extension that installs a [StandardTestDispatcher] as [Dispatchers.Main]
 * and provides injectable [TestScope] / [TestDispatcher] parameters.
 *
 * Usage:
 * ```kotlin
 * @ExtendWith(CoroutineTestExtension::class)
 * class MyViewModelTest {
 *
 *     @Test
 *     fun `test with injected scope`(testScope: TestScope) = testScope.runTest {
 *         // Dispatchers.Main is already set to the test dispatcher
 *         val vm = MyViewModel()
 *         vm.doWork()
 *         advanceUntilIdle()
 *         vm.state.value shouldBe expected
 *     }
 *
 *     @Test
 *     fun `test with injected dispatcher`(dispatcher: TestDispatcher) {
 *         // Use dispatcher directly if needed
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineTestExtension : BeforeEachCallback, AfterEachCallback, ParameterResolver {

	private val dispatcher: TestDispatcher = StandardTestDispatcher()
	private val testScope: TestScope = TestScope(dispatcher)

	override fun beforeEach(context: ExtensionContext) {
		Dispatchers.setMain(dispatcher)
	}

	override fun afterEach(context: ExtensionContext) {
		Dispatchers.resetMain()
	}

	override fun supportsParameter(
		parameterContext: ParameterContext,
		extensionContext: ExtensionContext,
	): Boolean {
		return parameterContext.parameter.type in listOf(
			TestScope::class.java,
			TestDispatcher::class.java,
		)
	}

	override fun resolveParameter(
		parameterContext: ParameterContext,
		extensionContext: ExtensionContext,
	): Any {
		return when (parameterContext.parameter.type) {
			TestScope::class.java -> testScope
			TestDispatcher::class.java -> dispatcher
			else -> throw ParameterResolutionException(
				"Unsupported parameter type: ${parameterContext.parameter.type}"
			)
		}
	}
}
