# Agent A: Predictive Back & Fragment Removal

**Assigned Plans**: 1, 3  
**Est. Time**: 3 hours  
**Dependencies**: None (start immediately)

---

## Plan 1: Predictive Back Gestures

### Scope
Enable Android 14+ predictive back gesture support.

### Tasks

1. **Add manifest attribute**  
   File: `app/src/main/AndroidManifest.xml`  
   Add `android:enableOnBackInvokedCallback="true"` to the `<application>` tag (around line 30-39).

2. **Verify BackHandler compatibility**  
   File: `app/src/main/java/com/adsamcik/tracker/app/ui/MainRoot.kt`  
   The existing `BackHandler` at line 145 should work with predictive back. Verify no changes needed.

3. **Search for conflicting back handlers**  
   ```
   grep -r "OnBackPressedCallback" --include="*.kt"
   grep -r "onBackPressed" --include="*.kt"
   ```
   Ensure no custom implementations conflict.

4. **Test navigation paths** (manual verification):
   - Tracker → Stats → back gesture (partial + complete)
   - Tracker → Map → back
   - Tracker → Game → back
   - Tracker → Settings → nested → back chain
   - Verify gesture cancellation works cleanly

### Acceptance Criteria
- [ ] Manifest has `android:enableOnBackInvokedCallback="true"`
- [ ] Back gesture shows preview animation on Android 14+
- [ ] Partial gestures cancel without side effects
- [ ] All navigation behavior preserved

---

## Plan 3: Fragment Dependency Removal

### Scope
Remove all Fragment library dependencies from the codebase.

### Pre-Check Tasks

1. **Verify MaterialDatePicker is unused**  
   ```
   grep -r "MaterialDatePicker" --include="*.kt"
   grep -r "DateTimeRangeDialog" --include="*.kt"
   grep -r "createDateTimeDialog" --include="*.kt"
   ```
   Expected: No matches in source code (only docs).

2. **Verify Map uses Compose DateRangePicker**  
   File: `map/src/main/java/com/adsamcik/tracker/map/ui/MapSheet.kt`  
   Confirm `DateRangePicker` from `androidx.compose.material3` is used (lines 32, 340).

3. **Search for Fragment imports**  
   ```
   grep -r "import androidx.fragment" --include="*.kt"
   grep -r "FragmentActivity" --include="*.kt"
   grep -r "FragmentManager" --include="*.kt"
   ```
   Document any remaining usages.

### Removal Tasks

4. **Remove Fragment dependencies from app/build.gradle.kts**  
   Delete these lines (around 155-156):
   ```kotlin
   implementation(libs.androidx.fragment)
   implementation(libs.androidx.fragment.ktx)
   ```

5. **Search other modules for Fragment dependencies**  
   Check build.gradle.kts in: `tracker/`, `map/`, `statistics/`, `game/`, `sutils/`

6. **Update PermissionManager documentation**  
   File: `sutils/src/main/java/com/adsamcik/tracker/shared/utils/permission/PermissionManager.kt`  
   Line 29 mentions FragmentActivity - update to reference only ComponentActivity.

7. **Delete DateTimeRangeDialog.kt if exists**  
   Check: `sutils/src/main/java/.../DateTimeRangeDialog.kt`  
   May already be deleted.

8. **Optional: Remove from version catalog**  
   File: `gradle/libs.versions.toml`  
   Lines 119-120: Remove `androidx-fragment` entries if no module uses them.

### Build Verification

9. **Clean build**  
   ```
   ./gradlew clean assembleDebug
   ```
   Verify no Fragment-related compilation errors.

### Acceptance Criteria
- [ ] Zero Fragment library dependencies in any build.gradle.kts
- [ ] No Fragment imports in Kotlin source
- [ ] Build succeeds without Fragment libraries
- [ ] Map date picker works with Compose DateRangePicker

---

## Completion Checklist

After completing both plans:
- [ ] Commit: "Enable predictive back gestures"
- [ ] Commit: "Remove Fragment dependencies"
- [ ] Run full test suite: `./gradlew testDebugUnitTest`
- [ ] Manual test on Android 14+ device/emulator
