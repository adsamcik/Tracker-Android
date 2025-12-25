# Compose Migration - Remaining Polish Items

**Date:** October 8, 2025  
**Status:** Migration 95% Complete - Polish Phase  
**Priority:** Low to Medium (No blockers for production)

---

## Overview

All user-facing Compose migration work is complete. The remaining items are **polish, optimization, and architectural refinement**. None of these items block production release.

---

## High-Level Summary

| Category | Items | Priority | Blocking Release? |
|----------|-------|----------|------------------|
| Performance | 2 | Medium | ❌ No |
| Accessibility | 1 | Medium | ❌ No |
| Architecture | 2 | Low | ❌ No |
| Features | 2 | Low | ❌ No |
| Cleanup | 2 | Low | ❌ No |

**Total:** 9 polish items

---

## 1. Performance Optimization

### 1.1 Baseline Profiles Creation

**Priority:** Medium  
**Effort:** 2-3 days  
**Blocking:** No

**Current State:**
- Status unknown if baseline profiles exist
- No macrobenchmark suite in project

**Target State:**
- Macrobenchmark module with startup, scroll, and transition benchmarks
- Generated `baseline-prof.txt` in app module
- CI integration for performance regression detection

**Tasks:**
- [ ] Create `macrobenchmark` module with benchmarking dependency
- [ ] Implement startup benchmark (cold start → first frame)
- [ ] Implement map scroll benchmark (smooth scrolling)
- [ ] Implement stats list scroll benchmark (large dataset)
- [ ] Generate baseline profile from benchmarks
- [ ] Add baseline profile to app module
- [ ] Integrate into CI pipeline with regression thresholds

**Acceptance Criteria:**
- Cold start to first frame: <2 seconds
- Scroll jank: 0% (60fps stable)
- Baseline profile covers critical user flows

**References:**
- [Android Baseline Profiles Guide](https://developer.android.com/topic/performance/baselineprofiles)
- Evergreen §7: Performance & Memory

---

### 1.2 Recomposition Profiling

**Priority:** Medium  
**Effort:** 1-2 days  
**Blocking:** No

**Current State:**
- No recomposition tracking in place
- Unknown if excessive recompositions occur

**Target State:**
- Recomposition metrics for main routes
- Identified and fixed unnecessary recompositions
- Performance budgets established

**Tasks:**
- [ ] Enable Layout Inspector recomposition highlighting
- [ ] Profile MainRoot for animation-triggered recompositions
- [ ] Profile StatsRoute during paging operations
- [ ] Profile GameRoute during live data updates
- [ ] Profile MapRoute during marker updates
- [ ] Optimize stable parameter usage
- [ ] Add `derivedStateOf` where beneficial
- [ ] Document performance budgets

**Acceptance Criteria:**
- <5 recompositions per user action on stable screens
- No full-tree recompositions on partial state changes
- Animation state changes isolated to relevant subtrees

**References:**
- Evergreen §4: UI & Compose Standards
- Evergreen §7: Performance & Memory

---

## 2. Accessibility

### 2.1 WCAG AA Compliance Audit

**Priority:** Medium  
**Effort:** 2-3 days  
**Blocking:** No

**Current State:**
- Basic semantics present (content descriptions for primary actions)
- Dynamic font scaling supported
- Contrast ratios unverified
- TalkBack compatibility untested

**Target State:**
- Full WCAG AA compliance
- Verified contrast ratios (4.5:1 normal, 3:1 large)
- TalkBack tested and functional
- Semantic labels on all dynamic content
- 48dp minimum hit targets

**Tasks:**
- [ ] Run automated accessibility scanner on all routes
- [ ] Verify contrast ratios in light/dark themes
- [ ] Test with TalkBack on:
  - [ ] MainRoot navigation
  - [ ] StatsRoute list and interactions
  - [ ] GameRoute cards and actions
  - [ ] MapRoute controls
  - [ ] SettingsRoute preferences
  - [ ] DebugRoute sections
- [ ] Add semantic labels to dynamic content:
  - [ ] Stats session metadata (date, duration, steps)
  - [ ] Game challenge progress percentages
  - [ ] Map marker rotation states
  - [ ] Settings slider current values
- [ ] Verify hit targets ≥48dp for all interactive elements
- [ ] Test with 200% font scaling
- [ ] Document accessibility features in user guide

**Acceptance Criteria:**
- Zero accessibility scanner errors
- All contrast ratios meet WCAG AA thresholds
- TalkBack navigates all routes successfully
- All dynamic content announced correctly
- All interactive elements ≥48dp or have expanded touch targets

**References:**
- [WCAG 2.1 AA Guidelines](https://www.w3.org/WAI/WCAG21/quickref/)
- Evergreen §4: UI & Compose Standards
- Evergreen §15: Code Style & Documentation

---

## 3. Architectural Refinement

### 3.1 Repository Layer for Stats & Game

**Priority:** Low  
**Effort:** 1-2 days  
**Blocking:** No

**Current State:**
- `StatsViewModel` accesses database directly
- `GameViewModel` accesses DAOs directly
- Functional but violates north star DI pattern

**Target State:**
- Repository interfaces in respective modules
- Constructor injection via AppGraph
- Test fakes for repositories

**Tasks:**
- [ ] Create `SessionRepository` interface in statistics module
  - [ ] Methods: `getSessionsPager()`, `getSessionById()`, etc.
  - [ ] Default implementation: `DefaultSessionRepository`
- [ ] Create `GameRepository` interface in game module
  - [ ] Methods: `getPointsToday()`, `getActiveGoals()`, `getActiveChallenges()`
  - [ ] Default implementation: `DefaultGameRepository`
- [ ] Update `StatsViewModel` constructor to accept `SessionRepository`
- [ ] Update `GameViewModel` constructor to accept `GameRepository`
- [ ] Update `AppGraph.ViewModelFactory` with new constructors
- [ ] Create test fakes:
  - [ ] `FakeSessionRepository` with in-memory data
  - [ ] `FakeGameRepository` with controllable state
- [ ] Update existing tests to use fakes

**Acceptance Criteria:**
- Zero direct DAO access in ViewModels
- Constructor injection for all data dependencies
- Test fakes available and documented

**References:**
- Evergreen §16A: Dependency Injection & Composition Root
- Evergreen §6: Database & Data Access

---

### 3.2 LiveData Deprecation

**Priority:** Low  
**Effort:** 1 day  
**Blocking:** No

**Current State:**
- TrackerService exposes both Flow (primary) and LiveData (legacy)
- TrackerLocker uses `NonNullLiveMutableData`
- SessionUpdateReceiver uses LiveData

**Target State:**
- LiveData APIs marked `@Deprecated` with migration guides
- TrackerLocker migrated to StateFlow
- SessionUpdateReceiver migrated to Flow

**Tasks:**
- [ ] Mark `TrackerService.sessionInfo` (LiveData) as `@Deprecated`
  - [ ] Add KDoc migration guide pointing to `sessionFlow`
- [ ] Migrate TrackerLocker from `NonNullLiveMutableData` to `MutableStateFlow`
  - [ ] Update all internal usages
  - [ ] Update DebugRoute to observe Flow instead of LiveData
- [ ] Migrate SessionUpdateReceiver to Flow
  - [ ] Replace `MutableLiveData` with `MutableStateFlow`
  - [ ] Update consumers to collect Flow
- [ ] Plan for full LiveData removal in next major version

**Acceptance Criteria:**
- All LiveData APIs marked deprecated with migration guides
- Zero new LiveData usage in codebase
- Flow as primary reactive pattern throughout

**References:**
- Evergreen §5: State & Concurrency
- Evergreen §19: Anti-Patterns

---

## 4. Feature Enhancements

### 4.1 Stats Dialogs

**Priority:** Low  
**Effort:** 1-2 days  
**Blocking:** No

**Current State:**
- Summary dialog placeholder exists but not implemented
- Week dialog placeholder exists but not implemented
- Core stats functionality fully operational

**Target State:**
- Summary statistics dialog showing aggregates
- Week view dialog showing 7-day breakdown
- Material 3 dialog components
- Test coverage for interactions

**Tasks:**
- [ ] Implement `SummaryStatisticsDialog` composable
  - [ ] Display total sessions, total distance, total time
  - [ ] Display averages per day/week/month
  - [ ] Use Material 3 `AlertDialog` or `BasicAlertDialog`
- [ ] Implement `WeekViewDialog` composable
  - [ ] Display 7-day breakdown (day, sessions, distance, time)
  - [ ] Use `LazyColumn` for day list
  - [ ] Material 3 card styling
- [ ] Add ViewModel state for dialog visibility
- [ ] Wire dialogs to header button actions in `StatsScreen`
- [ ] Add instrumentation tests:
  - [ ] Summary dialog opens and displays data
  - [ ] Week dialog opens and displays 7 days
  - [ ] Dialogs dismiss correctly

**Acceptance Criteria:**
- Summary dialog displays accurate aggregates
- Week dialog displays 7-day breakdown
- Dialogs use Material 3 components
- Test coverage for open/close/data display

**References:**
- Evergreen §4: UI & Compose Standards
- `COMPOSE_MIGRATION_PROGRESS.md` (documented TODO)

---

### 4.2 Game Empty/Progress States

**Priority:** Low  
**Effort:** 1 day  
**Blocking:** No

**Current State:**
- GameRoute displays cards for points, goals, challenges
- No empty state when no challenges active
- No loading state during data fetch

**Target State:**
- Empty state message when no challenges
- Loading indicator during initial fetch
- Error state for data fetch failures

**Tasks:**
- [ ] Add loading state to `GameViewModel`
  - [ ] Expose `isLoading: StateFlow<Boolean>`
- [ ] Add error state to `GameViewModel`
  - [ ] Expose `error: StateFlow<String?>`
- [ ] Update `GameScreen` to display:
  - [ ] `CircularProgressIndicator` when loading
  - [ ] Empty state message when no challenges
  - [ ] Error message when fetch fails
- [ ] Add tests for state variations

**Acceptance Criteria:**
- Loading state displays during fetch
- Empty state displays when no challenges
- Error state displays on failure
- Transitions between states smooth

**References:**
- Evergreen §4: UI & Compose Standards
- `COMPOSE_MIGRATION_PROGRESS.md` (documented TODO)

---

## 5. Code Cleanup

### 5.1 String Resource Migration

**Priority:** Low  
**Effort:** 1 day  
**Blocking:** No

**Current State:**
- Most user-facing text uses `stringResource()`
- Some hardcoded literals remain (GameScreen "Details", debug screens)

**Target State:**
- 100% user-facing text in `strings.xml`
- All composables use `stringResource(id, args)`

**Tasks:**
- [ ] Audit for hardcoded strings: `grep -r '"[A-Z][a-z]' **/*.kt`
- [ ] Migrate GameScreen literals to strings.xml
- [ ] Migrate DebugRoute literals (if user-facing)
- [ ] Verify all `Text()` calls use `stringResource()`
- [ ] Run lint check for hardcoded strings

**Acceptance Criteria:**
- Zero hardcoded user-facing strings in production code
- All strings use `stringResource()` for localization
- Debug-only literals acceptable if documented

**References:**
- Evergreen §15: Code Style & Documentation
- Android i18n best practices

---

### 5.2 TODO Cleanup

**Priority:** Low  
**Effort:** 1-2 days  
**Blocking:** No

**Current State:**
- 30+ TODO/FIXME comments scattered across codebase
- Mix of feature requests, low-priority enhancements, and obsolete notes

**Target State:**
- TODOs converted to GitHub issues
- Obsolete TODOs removed
- Critical TODOs addressed or scheduled

**Tasks:**
- [ ] Audit all TODO/FIXME comments: `grep -r 'TODO\|FIXME\|XXX\|HACK' **/*.kt`
- [ ] Categorize:
  - [ ] Feature requests → Create GitHub issues
  - [ ] Low-priority enhancements → Create GitHub issues or close
  - [ ] Obsolete notes → Remove
  - [ ] Critical items → Schedule or address
- [ ] Remove in-code TODOs after creating issues
- [ ] Link issues in commit messages if addressing

**Example TODOs Found:**
- `TrackerService`: "add only components that can actually be used"
- `BackgroundTrackingApi`: "add option for this in settings"
- `NotificationComponent`: "add localization support"
- `RawLocationWriter`: "Extract from android.location.Location if available"
- `PermissionRequest`: "Replace with Activity Result API"

**Acceptance Criteria:**
- All actionable TODOs converted to tracked issues
- Obsolete TODOs removed
- Remaining TODOs have clear justification

**References:**
- Evergreen §15: Code Style & Documentation

---

## 6. Documentation

### 6.1 Archive Outdated Migration Docs

**Priority:** High (Quick)  
**Effort:** 15 minutes  
**Blocking:** No

**Current State:**
- `COMPOSE_MIGRATION_QUALITY_ASSESSMENT.md` (Sept 21) contains false claims
- Superseded by newer evaluations and final status report

**Target State:**
- Outdated docs clearly marked or moved to archive folder
- Current docs referenced as source of truth

**Tasks:**
- [ ] Add "OUTDATED" prefix to `COMPOSE_MIGRATION_QUALITY_ASSESSMENT.md` filename
- [ ] Add note at top of file pointing to current documentation
- [ ] Update `COMPOSE_MIGRATION_PROGRESS.md` to reference final status report
- [ ] Consider creating `docs/archive/` folder for historical docs

**Acceptance Criteria:**
- No confusion about which docs are current
- Clear references to authoritative status documents

---

## Priority Matrix

### Do First (High Impact, Quick Wins)
1. ✅ Archive outdated documentation (15 min)

### Do Next (Medium Priority)
2. Baseline Profiles Creation (2-3 days) - Performance
3. Accessibility Audit (2-3 days) - Compliance
4. Recomposition Profiling (1-2 days) - Performance

### Do Later (Low Priority, Low Effort)
5. Repository Layer Refactor (1-2 days) - Architecture
6. LiveData Deprecation (1 day) - Architecture
7. String Resource Migration (1 day) - Cleanup
8. TODO Cleanup (1-2 days) - Cleanup

### Do Eventually (Nice-to-Have)
9. Stats Dialogs (1-2 days) - Feature
10. Game Empty/Progress States (1 day) - Feature

---

## Success Metrics

### Performance
- [ ] Cold start: <2s to first interactive frame
- [ ] Scroll jank: 0% (60fps stable)
- [ ] Recompositions: <5 per user action

### Accessibility
- [ ] WCAG AA compliance: 100%
- [ ] TalkBack compatibility: All routes navigable
- [ ] Contrast ratios: All pass (4.5:1 normal, 3:1 large)

### Architecture
- [ ] DI compliance: 100% constructor injection
- [ ] LiveData usage: 0% (Flow only)
- [ ] Test fakes: Available for all repositories

### Quality
- [ ] String resources: 100% usage
- [ ] TODO cleanup: <5 remaining (all justified)
- [ ] Documentation: Current and accurate

---

## Estimated Total Effort

**Total:** 12-17 days of focused work
- Performance: 3-5 days
- Accessibility: 2-3 days
- Architecture: 2-3 days
- Features: 2-3 days
- Cleanup: 2-3 days
- Documentation: 15 minutes

**Recommendation:** Spread over 3-4 sprints as polish work alongside new features.

---

## Next Steps

1. **Immediate:** Archive outdated documentation (15 min)
2. **This Sprint:** Baseline Profiles + Accessibility Audit (4-6 days)
3. **Next Sprint:** Recomposition Profiling + Repository Refactor (3-4 days)
4. **Future Sprints:** Remaining low-priority items as time permits

---

**Document Status:** CURRENT as of October 8, 2025  
**Owner:** Development Team  
**Review Cadence:** Monthly until all items complete

