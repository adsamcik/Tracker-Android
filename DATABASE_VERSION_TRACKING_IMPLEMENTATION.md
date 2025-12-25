# Database Version Tracking Implementation

## Summary

Added comprehensive version tracking to correlate database schema versions with app version codes. This helps determine when to create new migrations versus updating existing ones.

## Changes Made

### 1. AppDatabaseMigrations.kt Header Documentation

Added detailed header comment block with:
- **Version mapping table** showing DB version → App version code → Release status
- **Clear rules** for when to create new migrations vs update existing ones
- **Workflow examples** demonstrating both scenarios
- **Update instructions** for maintaining the table

### 2. AppDatabase.kt Version Annotation

Added version tracking to the database class KDoc:
- Current DB version
- Current app version code
- Release status
- Reference to full migration documentation

### 3. DATABASE_VERSION_TRACKING.md

Created standalone tracking document with:
- Complete version history table
- Detailed migration rules and examples
- Workflow checklists for adding/updating migrations
- Quick reference for common tasks
- Best practices and notes

## Key Rules Established

### Create NEW Migration When:
- ✅ Current DB version has been **RELEASED** to production/beta
- ✅ Users could have the current schema on their devices
- ✅ Backward compatibility required

### Update EXISTING Migration When:
- ✅ DB version is **UNRELEASED** (dev/internal builds only)
- ✅ No production/beta users have this schema
- ✅ Still iterating on the same feature set

## Current Status

- **DB Version:** 13
- **App Version Code:** 385
- **Status:** 🚧 UNRELEASED (dev/v10 branch)
- **Latest Migration:** MIGRATION_12_13 (sessionless tracking foundation)

## Usage

Before making any database schema changes:

1. Check `DATABASE_VERSION_TRACKING.md` for current release status
2. Follow the workflow checklist for your scenario
3. Update all three locations (AppDatabase.kt, AppDatabaseMigrations.kt header, tracking doc)

## Benefits

- ✅ Clear guidance on migration strategy
- ✅ Prevents accidental schema conflicts
- ✅ Documents migration history
- ✅ Simplifies decision-making for contributors
- ✅ Maintains consistency across releases

## Files Modified

- `sbase/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt`
- `sbase/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabaseMigrations.kt`
- `DATABASE_VERSION_TRACKING.md` (new file)

---

**Date:** 2025-10-11  
**Branch:** dev/v10
