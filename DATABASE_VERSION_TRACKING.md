# Database Version Tracking

This document tracks the relationship between database schema versions and app version codes to ensure proper migration management.

## Current Status

**Latest Database Version:** 13  
**Latest App Version Code:** 385  
**Release Status:** 🚧 UNRELEASED (dev/v10 branch)

## Version History

| DB Version | App Version Code | Release Date | Status | Major Changes |
|------------|------------------|--------------|--------|---------------|
| 13 | 385 | TBD | 🚧 UNRELEASED | Sessionless tracking foundation: 7 new time-series tables, snake_case columns, E7 coordinates |
| 12 | 384 | 2025-09-XX | ✅ RELEASED | Last session-based schema |
| 11 | 380-383 | 2025-08-XX | ✅ RELEASED | Session improvements |
| 10 | 370-379 | 2025-07-XX | ✅ RELEASED | Performance optimizations |
| 9 | 360-369 | 2025-06-XX | ✅ RELEASED | Activity tracking enhancements |
| 8 | 350-359 | 2025-05-XX | ✅ RELEASED | Cell tower data |
| 7 | 340-349 | 2025-04-XX | ✅ RELEASED | WiFi improvements |
| 6 | 330-339 | 2025-03-XX | ✅ RELEASED | Heatmap optimizations |
| 5 | 320-329 | 2025-02-XX | ✅ RELEASED | Statistics tables |
| 4 | 310-319 | 2025-01-XX | ✅ RELEASED | Index additions |
| 3 | 300-309 | 2024-12-XX | ✅ RELEASED | Map layer support |
| 2 | < 300 | 2024-11-XX | ✅ RELEASED | Initial Room migration |

## Migration Rules

### When to CREATE a NEW Migration

Create a **new migration** (e.g., `MIGRATION_13_14`) when:

- ✅ The current database version has been **released to production or beta**
- ✅ Any user could have the current schema on their device
- ✅ You need backward compatibility from the released version

**Example:**

```kotlin
// DB v13 shipped in versionCode 385 to Google Play
// Now adding a new table in development
// Action: Bump version to 14, create MIGRATION_13_14
val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // New schema changes here
    }
}
```

### When to UPDATE an EXISTING Migration

Update an **existing migration** (e.g., modify `MIGRATION_12_13`) when:

- ✅ The database version is **UNRELEASED** (dev/internal builds only)
- ✅ No production/beta users have this schema version
- ✅ You're still iterating on the same feature set

**Example:**

```kotlin
// DB v13 is still in dev/v10 branch, not released
// Discovered a column naming issue
// Action: Fix MIGRATION_12_13 directly, no version bump needed
val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Update SQL with corrected column names
    }
}
```

## Workflow Checklist

### Before Adding Schema Changes

- [ ] Check current DB version in `AppDatabase.kt`
- [ ] Check release status in this document
- [ ] Determine: new migration or update existing?

### When Adding New Migration

- [ ] Bump `version` in `AppDatabase.kt` `@Database` annotation
- [ ] Create `MIGRATION_X_Y` in `AppDatabaseMigrations.kt`
- [ ] Register migration in `setupDatabase()` in `AppDatabase.kt`
- [ ] Update version tracking table in `AppDatabaseMigrations.kt` header
- [ ] Update this document with new version row
- [ ] Write migration test in `AppDatabaseTest.kt`
- [ ] Test migration on device with previous schema

### When Updating Existing Migration

- [ ] Verify DB version is UNRELEASED
- [ ] Update SQL in existing `MIGRATION_X_Y`
- [ ] Update entity classes if schema changed
- [ ] Rerun migration tests
- [ ] Update "Major Changes" notes if needed

### Before Release

- [ ] Mark current DB version as RELEASED in this document
- [ ] Update release date
- [ ] Verify all migrations from previous released version work
- [ ] Test on devices with all previous schema versions (if possible)

## Quick Reference

**View current DB version:**

```kotlin
// sbase/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt
@Database(version = 13, ...)
```

**View current app version:**

```kotlin
// app/build.gradle.kts
versionCode = 385
```

**Migration location:**

```text
sbase/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabaseMigrations.kt
```

**Migration tests:**

```text
sbase/src/androidTest/java/com/adsamcik/tracker/shared/base/database/AppDatabaseTest.kt
```

## Notes

- Database version changes are **permanent** once released. Never reuse a version number.
- Always maintain backward compatibility in migrations—users may skip versions.
- Migration tests are **mandatory** for each migration before release.
- Keep migration logic simple; complex data transformations should log warnings for edge cases.
- Document any data loss or transformations in migration comments and release notes.

---

**Last Updated:** 2025-10-11  
**Maintained By:** Development Team  
**Related Files:**

- `AppDatabase.kt` - Database definition and version
- `AppDatabaseMigrations.kt` - All migration implementations
- `AppDatabaseTest.kt` - Migration tests
