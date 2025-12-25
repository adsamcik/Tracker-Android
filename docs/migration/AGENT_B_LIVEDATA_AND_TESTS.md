# Agent B: LiveData Cleanup & Test Infrastructure

**Assigned Plans**: 6, 8  
**Est. Time**: 5 hours  
**Dependencies**: None for Plan 6; Plan 8 depends on other agents completing Plans 2, 4, 6

---

## Plan 6: LiveData Cleanup

### Scope
Remove all LiveData usage and related dependencies.

### Discovery Tasks

1. **Find all LiveData usages**
   ```
   grep -r "import androidx.lifecycle.LiveData" --include="*.kt"
   grep -r "import androidx.lifecycle.MutableLiveData" --include="*.kt"
   grep -r "NonNullLiveData" --include="*.kt"
   grep -r "NonNullMutableLiveData" --include="*.kt"
   ```

2. **Verify deprecated methods have Flow alternatives**
   - `SessionDataDao.getLive()` → should have Flow equivalent
   - `PointsAwardedDao.countBetweenLive()` → should have `countBetweenFlow()`

### Deletion Tasks

3. **Delete deprecated DAO methods**

   File: `sbase/src/main/java/com/adsamcik/tracker/shared/base/database/dao/SessionDataDao.kt`
   - Find and delete `getLive(id: Long): LiveData<TrackerSession>` method
   - Keep Flow-based alternatives

   File: `points/src/main/java/com/adsamcik/tracker/points/database/PointsAwardedDao.kt`
   - Find and delete `countBetweenLive(from: Long, to: Long): LiveData<Int>` method
   - Keep `countBetweenFlow()` alternative

4. **Delete LiveData utility classes**
   
   Search and delete these files:
   - `sbase/src/main/java/.../misc/NonNullLiveData.kt`
   - `sbase/src/main/java/.../misc/NonNullMutableLiveData.kt`
   - `sbase/src/main/java/.../extension/LiveDataExtensions.kt`

5. **Remove ProGuard keep rule**
   
   File: `tracker/proguard-rules.pro`
   Delete the NonNullLiveData keep rule:
   ```
   -keep class com.adsamcik.tracker.shared.base.misc.NonNullLiveData { *; }
   ```

6. **Remove compose-runtime-livedata dependency**
   
   File: `app/build.gradle.kts`
   Delete (around line 186):
   ```kotlin
   implementation(libs.compose.runtime.livedata)
   ```

7. **Remove livedata-testing-ktx test dependency**
   
   File: `app/build.gradle.kts`
   Delete if present:
   ```kotlin
   androidTestImplementation(libs.livedata.testing.ktx)
   ```

8. **Clean up remaining LiveData imports**
   
   Search all .kt files for remaining imports and remove them.

### Build Verification

9. **Build and test**
   ```
   ./gradlew clean assembleDebug testDebugUnitTest
   ```

### Acceptance Criteria
- [ ] Zero LiveData imports in production code
- [ ] `compose-runtime-livedata` dependency removed
- [ ] Build succeeds
- [ ] All tests pass

---

## Plan 8: Test Infrastructure Cleanup

### Scope
Modernize test setup patterns after other migrations complete.

### Prerequisites
- Plan 2 (Proto DataStore) complete
- Plan 4 (Hilt DI) complete  
- Plan 6 (LiveData Cleanup) complete

### Tasks

1. **Migrate test SharedPreferences cleanup**

   Find tests using this pattern:
   ```kotlin
   PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
   ```
   
   Replace with DataStore file deletion pattern from:
   `spreferences/src/test/java/.../DefaultTrackerSettingsRepositoryTest.kt` (lines 28-35):
   ```kotlin
   val dsDir = context.filesDir.resolve("datastore")
   dsDir.deleteRecursively()
   ```

2. **Update to Hilt test patterns**

   For instrumentation tests, add:
   ```kotlin
   @HiltAndroidTest
   class MyTest {
       @get:Rule
       val hiltRule = HiltAndroidRule(this)
       
       @Before
       fun setup() {
           hiltRule.inject()
       }
   }
   ```

3. **Remove LiveData test utilities**

   Delete or update tests using:
   - `livedata-testing-ktx` assertions
   - `LiveDataTestUtil`
   
   Replace with Turbine for Flow testing:
   ```kotlin
   myFlow.test {
       assertEquals(expected, awaitItem())
       cancelAndIgnoreRemainingEvents()
   }
   ```

4. **Update FakePreferencesHelper**
   
   File: `tracker/src/test/java/.../FakePreferencesHelper.kt`
   - Update to work with Proto DataStore or typed repositories
   - Ensure tracking component tests still work

5. **Consolidate test setup patterns**
   
   Create shared test utilities if multiple tests need:
   - DataStore cleanup
   - Hilt injection
   - Coroutine test dispatchers

### Test Files to Update

Search for tests needing updates:
```
grep -r "getDefaultSharedPreferences" --include="*Test.kt"
grep -r "LiveData" --include="*Test.kt"
grep -r "@Before" --include="*Test.kt" -A 10 | grep -i "preference\|clear"
```

### Verification

6. **Run full test suite**
   ```
   ./gradlew testDebugUnitTest
   ./gradlew connectedDebugAndroidTest  # if device available
   ```

### Acceptance Criteria
- [ ] No SharedPreferences cleanup in test setup
- [ ] Hilt injection used in instrumentation tests
- [ ] Turbine used for Flow testing
- [ ] All tests pass

---

## Completion Checklist

- [ ] Commit: "Remove LiveData and related dependencies"
- [ ] Commit: "Modernize test infrastructure"
- [ ] Full test suite passes
- [ ] Document any test patterns for future reference
