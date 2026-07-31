package com.adsamcik.tracker.testing

/**
 * Robolectric Test Classification Guide
 *
 * This file documents which test categories legitimately require Robolectric
 * and which can be converted to pure JUnit 5 tests using fakes.
 *
 * ## Tests That REQUIRE Robolectric
 *
 * ### 1. Room Database / DAO Tests
 * - Need Android's in-memory SQLite implementation
 * - Examples:
 *   - `PointsAwardedDaoTest` - Room DAO operations
 *   - `SessionActivityActivityComposeTest` - Room persistence
 *
 * ### 2. DataStore Tests (Testing Migration/Integration)
 * - Need file system and Context for DataStore file operations
 * - Examples:
 *   - `DefaultTrackerSettingsRepositoryTest` - DataStore migration from SharedPreferences
 *   - `DefaultOnboardingRepositoryTest` - DataStore integration
 *
 * ### 3. Android Component Tests
 * - Services, ContentProviders
 * - Examples:
 *   - `TrackerServiceTimerUpdateIntegrationTest` - Service integration
 *
 * ### 4. WorkManager Tests
 * - Worker execution requires Android testing infrastructure
 * - Examples:
 *   - `DataRetentionWorkerTest` - WorkManager Worker
 *
 * ### 5. Location Services Tests (Requiring Real Location API Shadows)
 * - Tests that exercise actual Android LocationManager shadows
 * - Examples:
 *   - `AndroidLocationCollectionTriggerTest` - uses Android LocationManager
 *   - `FusedLocationCollectionTriggerTest` - uses FusedLocationProviderClient
 *
 * ### 6. Resource Access Tests
 * - Tests that need to resolve string resources, drawables, etc.
 * - Robolectric provides resource resolution
 *
 * ## Tests That CAN Use Pure JUnit 5 + Fakes
 *
 * ### 1. Repository Tests (With Injected Dependencies)
 * - If repository accepts injected dependencies, use fakes
 * - Pattern: Inject FakeTrackerSettingsRepository instead of real DataStore
 *
 * ### 2. ViewModel / State Tests
 * - Pure logic testing with injected repository fakes
 * - Use Turbine for Flow testing, MockK for mocking
 *
 * ### 3. Domain Logic / Use Case Tests
 * - Business logic that doesn't need Android
 * - Inject fakes for any Android-dependent collaborators
 *
 * ### 4. File Operation Tests (Using FakeFileResolver)
 * - Instead of Context.filesDir, inject FileResolver
 * - Use FakeFileResolver with temp directories
 *
 * ### 5. Location Processing Tests (Logic Only)
 * - Testing location calculations, filtering, aggregation
 * - Use FakeLocationSource to provide synthetic location data
 *
 * ## Migration Decision Tree
 *
 * When touching a Robolectric test, ask:
 *
 * 1. Does it test Room DAO operations? → Keep Robolectric
 * 2. Does it test Android Component lifecycle (Service/Receiver/Provider)? → Keep Robolectric
 * 3. Does it test WorkManager Workers? → Keep Robolectric
 * 4. Does it only need Context for passing to constructor? → Consider refactoring:
 *    - Extract interface
 *    - Create fake implementation
 *    - Convert to pure JUnit 5
 * 5. Does it mock most Android dependencies already? → Good candidate for pure JUnit 5
 *
 * ## Current Test Classification
 *
 * | Test | Module | Robolectric Reason | Convertible? |
 * |------|--------|-------------------|--------------|
 * | PointsAwardedDaoTest | points | Room DAO | No - Room needs Robolectric |
 * | SessionActivityActivityComposeTest | activity | Room DAO | No - Room needs Robolectric |
 * | DefaultTrackerSettingsRepositoryTest | spreferences | DataStore file | No - testing migration logic |
 * | DefaultOnboardingRepositoryTest | spreferences | DataStore file | No - testing DataStore integration |
 * | TrackingPolicyManagerTest | tracker | Context for manager | Maybe - if manager extracted interface |
 * | TrackingPolicyEdgeCaseTest | tracker | Context for manager | Maybe - if manager extracted interface |
 * | FusedLocationCollectionTriggerTest | tracker | FusedLocationProvider | No - testing real trigger |
 * | AndroidLocationCollectionTriggerTest | tracker | LocationManager | No - testing real trigger |
 * | HandlerCollectionTriggerTest | tracker | Handler/Looper | No - Android threading |
 * | TrackerServiceTimerUpdateIntegrationTest | tracker | Service integration | No |
 * | DataRetentionWorkerTest | app | WorkManager Worker | No |
 *
 * ## Best Practices
 *
 * 1. Keep Robolectric tests for integration-level testing of Android components
 * 2. Prefer pure JUnit 5 tests for business logic and domain operations
 * 3. When writing new code, design for testability:
 *    - Accept dependencies through constructor injection
 *    - Define interfaces for Android-dependent operations
 *    - Keep business logic separate from Android framework code
 * 4. Use the fakes in testing-common module:
 *    - FakeTrackerSettingsRepository for settings-dependent code
 *    - FakeFileResolver for file operations
 *    - FakeLocationSource for location streams
 */
object RobolectricTestClassification
