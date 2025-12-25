# Database Migration Quick Reference Card

## Current Status (as of 2025-10-11)

```
DB Version:     13
App Version:    385
Status:         🚧 UNRELEASED (dev/v10)
Latest Change:  Sessionless tracking foundation
```

## Decision Tree: Should I Create a New Migration?

```
Has the current DB version been released to production/beta?
│
├─ YES → CREATE NEW MIGRATION
│        1. Bump version in AppDatabase.kt
│        2. Create MIGRATION_X_Y in AppDatabaseMigrations.kt
│        3. Register in setupDatabase()
│        4. Update version tracking docs
│        5. Write migration test
│
└─ NO  → UPDATE EXISTING MIGRATION
         1. Verify status is UNRELEASED
         2. Modify existing MIGRATION_X_Y
         3. Update entity classes if needed
         4. Rerun tests
         5. Update change notes
```

## Files to Check/Update

| Action | File | What to Change |
|--------|------|----------------|
| Bump DB version | `AppDatabase.kt` | `@Database(version = X)` |
| Check app version | `app/build.gradle.kts` | `versionCode = X` |
| Add/modify migration | `AppDatabaseMigrations.kt` | `val MIGRATION_X_Y = ...` |
| Update tracking | `AppDatabaseMigrations.kt` | Header comment table |
| Check release status | `DATABASE_VERSION_TRACKING.md` | Version history table |
| Write tests | `AppDatabaseTest.kt` | Migration test methods |

## Common Commands

### Check current schema version

```bash
# View DB version
grep -n "version = " sbase/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt

# View app version
grep "versionCode" app/build.gradle.kts
```

### Run migration tests

```bash
./gradlew :sbase:connectedDebugAndroidTest --tests "AppDatabaseTest"
```

### Verify schema compiles

```bash
./gradlew :sbase:kspDebugKotlin --no-daemon
```

## Migration Checklist

Before committing DB changes:

- [ ] Updated `@Database(version = X)`
- [ ] Created or updated migration in `AppDatabaseMigrations.kt`
- [ ] Registered migration in `setupDatabase()`
- [ ] Updated version tracking table in code header
- [ ] Updated `DATABASE_VERSION_TRACKING.md`
- [ ] Wrote migration test in `AppDatabaseTest.kt`
- [ ] Verified migration runs on test device
- [ ] Tested both fresh install and upgrade paths
- [ ] Updated AppDatabase.kt KDoc with new version

## Red Flags 🚩

NEVER do these:

- ❌ Change a migration that has been released
- ❌ Reuse a database version number
- ❌ Skip version numbers (always sequential)
- ❌ Modify entity without updating migration
- ❌ Release without testing migration on real device
- ❌ Change column types without data migration plan

## Snake Case Reminder 🐍

All new database columns MUST use snake_case:

```kotlin
// ✅ CORRECT
@ColumnInfo(name = "created_at")
val createdAt: Long

@ColumnInfo(name = "sensor_value_start")
val sensorValueStart: Int

// ❌ WRONG
@ColumnInfo(name = "createdAt")
val createdAt: Long

@ColumnInfo(name = "sensorValueStart")
val sensorValueStart: Int
```

## Contact

Questions? Check:
1. `DATABASE_VERSION_TRACKING.md` - Full documentation
2. `AppDatabaseMigrations.kt` - Header comment with version table
3. `.github/copilot-instructions.md` - Section 6 (Database & Data Access)

---

**Keep this card updated when DB version changes!**
