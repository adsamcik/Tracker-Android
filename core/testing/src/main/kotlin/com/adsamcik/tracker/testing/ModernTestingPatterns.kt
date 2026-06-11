package com.adsamcik.tracker.testing

/**
 * Modern Testing Patterns for Tracker Android
 *
 * This file documents the testing patterns to use for new and updated tests.
 *
 * ## JUnit 5 (Jupiter)
 *
 * Use JUnit 5 annotations for unit tests:
 * - `@Test` from `org.junit.jupiter.api.Test`
 * - `@BeforeEach` instead of JUnit 4's `@Before`
 * - `@AfterEach` instead of JUnit 4's `@After`
 * - `@BeforeAll` / `@AfterAll` for class-level setup
 * - `@Nested` for grouping related tests
 * - `@DisplayName` for human-readable test names
 * - `@ParameterizedTest` with `@ValueSource`, `@CsvSource`, etc.
 *
 * ## Assertions
 *
 * Prefer Kotest assertions for expressiveness:
 * ```kotlin
 * import io.kotest.matchers.shouldBe
 * import io.kotest.matchers.shouldNotBe
 * import io.kotest.matchers.collections.shouldContain
 * import io.kotest.matchers.collections.shouldHaveSize
 * import io.kotest.matchers.string.shouldStartWith
 *
 * result shouldBe expected
 * list shouldHaveSize 3
 * list shouldContain element
 * ```
 *
 * For simple assertions, kotlin.test is also fine:
 * ```kotlin
 * import kotlin.test.assertEquals
 * import kotlin.test.assertTrue
 *
 * assertEquals(expected, actual)
 * assertTrue(condition)
 * ```
 *
 * ## Mocking
 *
 * Use MockK for mocking (Kotlin-first):
 * ```kotlin
 * import io.mockk.*
 *
 * val mock = mockk<SomeClass>()
 * every { mock.someMethod() } returns value
 * verify { mock.someMethod() }
 *
 * // Relaxed mocks return default values
 * val relaxed = mockk<SomeClass>(relaxed = true)
 *
 * // Coroutine mocking
 * coEvery { mock.suspendMethod() } returns value
 * coVerify { mock.suspendMethod() }
 * ```
 *
 * ## Flow Testing with Turbine
 *
 * Use Turbine for testing Kotlin Flows:
 * ```kotlin
 * import app.cash.turbine.test
 *
 * flow.test {
 *     awaitItem() shouldBe expected1
 *     awaitItem() shouldBe expected2
 *     awaitComplete()
 * }
 * ```
 *
 * ## Coroutine Testing
 *
 * Use kotlinx-coroutines-test:
 * ```kotlin
 * import kotlinx.coroutines.test.runTest
 *
 * @Test
 * fun `test suspending function`() = runTest {
 *     val result = suspendFunction()
 *     result shouldBe expected
 * }
 * ```
 *
 * ## Test Naming
 *
 * Use backtick syntax for readable test names:
 * ```kotlin
 * @Test
 * fun `should return empty list when no data exists`() = runTest {
 *     // ...
 * }
 * ```
 *
 * ## Fake Implementations (Preferred over Robolectric)
 *
 * When possible, use fake implementations instead of Robolectric:
 *
 * ### FakeTrackerSettingsRepository
 * ```kotlin
 * import com.adsamcik.tracker.testing.fake.FakeTrackerSettingsRepository
 *
 * @Test
 * fun `test settings-dependent code`() = runTest {
 *     val fakeSettings = FakeTrackerSettingsRepository(
 *         TrackerSettingsState(autoUnitSwitch = true, ...)
 *     )
 *     val viewModel = MyViewModel(fakeSettings)
 *
 *     fakeSettings.setAutoUnitSwitch(false)
 *     viewModel.state.first().useAutoUnits shouldBe false
 * }
 * ```
 *
 * ### FakeFileResolver
 * ```kotlin
 * import com.adsamcik.tracker.testing.fake.FakeFileResolver
 *
 * @Test
 * fun `test file operations`() {
 *     FakeFileResolver.withTempDir { resolver ->
 *         val exporter = CrashExporter(resolver)
 *         exporter.export(data)
 *         resolver.resolveInternal("crashes").exists() shouldBe true
 *     }
 * }
 * ```
 *
 * ### FakeLocationSource
 * ```kotlin
 * import com.adsamcik.tracker.testing.fake.FakeLocationSource
 *
 * @Test
 * fun `test location tracking`() = runTest {
 *     val locationSource = FakeLocationSource()
 *     val tracker = LocationTracker(locationSource)
 *
 *     locationSource.emitLocation(lat = 37.7749, lon = -122.4194)
 *     tracker.lastLocation?.latitude shouldBe 37.7749
 * }
 * ```
 *
 * ## Robolectric (Android Unit Tests on JVM)
 *
 * For Android components that need context (use as last resort):
 * ```kotlin
 * @RunWith(RobolectricTestRunner::class)
 * @Config(sdk = [34])
 * class MyAndroidTest {
 *     // ...
 * }
 * ```
 *
 * When to use Robolectric vs Fakes:
 * - Prefer fakes: Pure logic, repositories, state management
 * - Use Robolectric: Android lifecycle, Intent/Bundle, resources, Room migrations
 * - Use Instrumented: Compose UI, system services, hardware features
 *
 * ## Migration Notes
 *
 * When updating existing tests:
 * 1. Change imports from `org.junit.Test` to `org.junit.jupiter.api.Test`
 * 2. Change `@Before` to `@BeforeEach`
 * 3. Change `@After` to `@AfterEach`
 * 4. Replace `org.junit.Assert.*` with Kotest matchers or kotlin.test
 * 5. Replace Mockito with MockK where applicable
 * 6. Consider replacing Robolectric with fake implementations
 *
 * Note: JUnit 4 tests still work during the migration period via the
 * JUnit Vintage engine.
 */
object ModernTestingPatterns
