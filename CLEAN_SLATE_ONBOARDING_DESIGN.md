# Tracker Android - Clean Slate Onboarding System Design

## Executive Summary

This document outlines a complete redesign of the Tracker Android app's initialization and onboarding experience. Moving away from the current technically-focused, multi-dialog approach, we propose a modern, user-centered onboarding flow that prioritizes value demonstration, progressive disclosure, and just-in-time permission requests.

## Design Principles

### 1. Value-First Approach
- **Show, Don't Tell**: Demonstrate app value before requesting permissions
- **Benefit-Oriented**: Frame features around user benefits, not technical capabilities  
- **Progressive Disclosure**: Build understanding gradually rather than overwhelming users

### 2. Modern UX Patterns
- **Visual Storytelling**: Use engaging graphics and animations
- **Interactive Tutorials**: Let users experience features safely
- **Celebration States**: Positive reinforcement for progress and completion

### 3. Respect User Agency
- **Just-in-Time Permissions**: Request permissions when relevant and beneficial
- **Clear Control**: Always explain why and provide opt-out options
- **No Dark Patterns**: Honest, transparent communication

### 4. Technical Excellence
- **Accessibility-First**: Full accessibility support from day one
- **Performance**: Smooth, responsive interactions
- **Analytics-Driven**: Data-informed optimization and iteration

## New Onboarding Flow Architecture

### Flow Overview

```
Welcome → Value Demo → Core Setup → Feature Discovery → Permission Grants → Success
   ↓         ↓           ↓              ↓                ↓               ↓
 Brand    Show App    Basic Prefs   Optional Features  As Needed     Celebrate
```

### Detailed Flow Specification

#### Stage 1: Welcome & Brand Introduction (30 seconds)
**Goal**: Create positive first impression and set expectations

**Screen 1: Welcome**
- Hero image with app branding
- Simple value proposition: "Track your life, discover patterns"
- Single CTA: "Get Started"
- Skip option for returning users

**Screen 2: What is Tracker?**
- Visual explanation with icons/animations
- Three core benefits:
  - 📍 "See where you go" (Location tracking)
  - 🏃 "Track your activities" (Activity recognition) 
  - 📊 "Discover insights" (Data visualization)
- Progress indicator: 1/5

#### Stage 2: Value Demonstration (60 seconds)
**Goal**: Show app value through interactive preview

**Screen 3: Interactive Preview**
- Simulated tracking data visualization
- Interactive map with sample journey
- Mock statistics showing insights
- "This is what Tracker can do for you"
- CTA: "Set up my tracking"
- Progress indicator: 2/5

#### Stage 3: Core Setup (45 seconds)
**Goal**: Configure essential settings without permissions

**Screen 4: Privacy First**
- Privacy-focused messaging
- Data storage explanation (local-first)
- Optional cloud backup choice
- Link to full privacy policy
- CTA: "Configure tracking"
- Progress indicator: 3/5

**Screen 5: What to Track**
- Simple toggles for tracking types (Location, Activity, Wi‑Fi, Steps)
- Clear descriptions of each tracking type with benefits
- Hardware capability detection (e.g., step sensor availability)
- No permissions requested yet - just preference collection
- CTA: "Continue setup"
- Progress indicator: 4/5

#### Stage 4: Feature Discovery & Permission Grants (90 seconds)
**Goal**: Enable core features with contextual permission requests

**Screen 6: Location Tracking Setup**
- Visual explanation of location benefits
- Interactive map showing accuracy levels
- "Why we need location access" explanation
- Primary: "Enable location tracking" (requests ACCESS_FINE_LOCATION)
- Secondary: "Skip for now"
- Progress indicator: 4/5

**Screen 7: Activity Recognition (Conditional)**
- Only shown if user enabled activity tracking
- Visual demo of activity detection
- Benefits: automatic tracking, better insights
- "Why we need activity access" explanation
- Primary: "Enable activity detection" (requests ACTIVITY_RECOGNITION)
- Secondary: "Manual tracking only"

**Screen 8: Enhanced Features (Optional)**
- Optional advanced features
- WiFi tracking for better indoor location
- Cell tower data for coverage analysis
- Presented as "power user" features
- Individual permission requests with clear benefits

#### Stage 5: Background Permissions (Conditional, 30 seconds)
**Goal**: Enable background tracking if user wants automatic mode

**Screen 9: Background Location (Android 10+)**
- Only shown if user wants automatic tracking
- Clear explanation of background location use
- Battery optimization guidance
- Visual guide to Android permission dialog
- Required for automatic tracking feature

#### Stage 6: Success & Next Steps (30 seconds)
**Goal**: Celebrate completion and guide to first use

**Screen 10: Setup Complete**
- Celebration animation
- Summary of enabled features
- Quick start guide for first tracking session
- CTA: "Start my first tracking session"
- Secondary: "Explore the app"

### Technical Architecture

#### Component Structure

```
OnboardingCoordinator
├── OnboardingViewModel (State management)
├── OnboardingRepository (Data persistence)
├── PermissionManager (Permission handling)
├── AnalyticsTracker (User behavior tracking)
└── Screens/
    ├── WelcomeScreen
    ├── ValueDemoScreen
    ├── PrivacyScreen
    ├── WhatToTrackScreen
    ├── LocationSetupScreen
    ├── ActivitySetupScreen
    ├── EnhancedFeaturesScreen
    ├── BackgroundLocationScreen
    └── SuccessScreen
```

#### State Management

```kotlin
data class OnboardingState(
    val currentStep: OnboardingStep,
    val completedSteps: Set<OnboardingStep>,
    val userPreferences: UserPreferences,
    val grantedPermissions: Set<Permission>,
    val skipReasons: Map<OnboardingStep, SkipReason>
)

sealed class OnboardingStep {
    object Welcome : OnboardingStep()
    object ValueDemo : OnboardingStep()
    object Privacy : OnboardingStep()
    object WhatToTrack : OnboardingStep()
    object LocationSetup : OnboardingStep()
    object ActivitySetup : OnboardingStep()
    object EnhancedFeatures : OnboardingStep()
    object BackgroundLocation : OnboardingStep()
    object Success : OnboardingStep()
}
```

#### Permission Strategy

```kotlin
class OnboardingPermissionManager {
    fun requestLocationPermission(context: Context, rationale: String): Flow<PermissionResult>
    fun requestActivityPermission(context: Context, rationale: String): Flow<PermissionResult>
    fun requestBackgroundLocation(context: Context, rationale: String): Flow<PermissionResult>
    
    fun shouldShowLocationSetup(preferences: UserPreferences): Boolean
    fun shouldShowActivitySetup(preferences: UserPreferences): Boolean
    fun shouldShowBackgroundLocationSetup(preferences: UserPreferences): Boolean
}
```

## Implementation Specifications

### UI Framework: Jetpack Compose

**Benefits**:
- Modern declarative UI
- Better animation support
- Accessibility built-in
- Easier testing

**Key Components**:
```kotlin
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onEvent: (OnboardingEvent) -> Unit
) {
    // Screen implementations
}

@Composable
fun ProgressIndicator(current: Int, total: Int)

@Composable
fun PermissionRequestCard(
    permission: Permission,
    rationale: String,
    onGrant: () -> Unit,
    onSkip: () -> Unit
)
```

### Analytics Integration

**Key Metrics**:
- Completion rate by step
- Permission grant rates
- Skip reasons and patterns
- Time spent per step
- Drop-off points

**Implementation**:
```kotlin
class OnboardingAnalytics {
    fun trackStepStarted(step: OnboardingStep)
    fun trackStepCompleted(step: OnboardingStep, timeSpent: Duration)
    fun trackStepSkipped(step: OnboardingStep, reason: SkipReason)
    fun trackPermissionGranted(permission: Permission)
    fun trackPermissionDenied(permission: Permission, reason: String?)
    fun trackOnboardingCompleted(totalTime: Duration, enabledFeatures: Set<Feature>)
}
```

## 2025 Android permission guidance applied to Tracker

### Just-in-time triggers mapped to app features

- Start Tracking button (record a route)
    - Prompt: ACCESS_FINE_LOCATION (precise). On API 31+, offer Approximate vs Precise toggle; default to Precise with clear benefit copy (accurate routes and distance).
    - If user selects Approximate, allow a degraded mode: no detailed route line, only coarse heatmaps/visit counts.

- Enable Automatic Tracking (Settings toggle or first use of auto mode)
    - Step 1: ACTIVITY_RECOGNITION when enabling auto-tracking.
    - Step 2 (only if user opts into true background tracking): Show prominent in‑app disclosure, then request ACCESS_BACKGROUND_LOCATION (Android 10+). Request foreground first, background later per policy.

- Enable Wi‑Fi features (Wi‑Fi heatmap, Wi‑Fi list, or toggle "Wi‑Fi tracking")
    - API 33+: request NEARBY_WIFI_DEVICES when the user opens a Wi‑Fi feature or enables the toggle.
    - API <33: request ACCESS_FINE_LOCATION (Wi‑Fi scans are gated by location).
    - Denied → feature still opens with placeholder data and a small nudge to enable scans.

- Enable Notifications (e.g., tracking status alerts, background watcher)
    - After the user explicitly toggles notifications in settings or starts a session requiring ongoing alerts, request POST_NOTIFICATIONS (API 33+). Never on first launch.

### Short rationales only when needed

Show a Material bottom sheet when:
- shouldShowRequestPermissionRationale() is true (user denied before), or
- the need isn’t self‑evident (e.g., background location).

Keep it to 2–4 lines: benefit → what we don’t do → reversibility ("change anytime in Settings"). Make it skippable.

### Prefer platform alternatives

- Media access: If future features need photos/videos, launch Photo Picker (backported via Play services) before considering any READ_MEDIA_* runtime permission.
- Storage/files: Use the system document picker (SAF) for exports/imports; no storage permissions.
- Wi‑Fi: Use NEARBY_WIFI_DEVICES (API 33+) rather than piggybacking on location; for our use (location insights), do not mark "neverForLocation".
- Bluetooth (future): Use BLUETOOTH_SCAN/CONNECT/ADVERTISE (API 31+); location usually not required.

### Sensitive permissions handling

- Location precision (API 31+): Ask for Precise only if user wants route recording. If they pick Approximate, keep the app useful (coarse insights) and allow upgrade later.
- Background location: Two‑step flow with prominent in‑app disclosure explaining continuous tracking, battery impact, and controls. Expect Play review.
- Notifications (API 33+): Only after an explicit user action enabling alerts.

### Denial handling and recovery

- On denial: degrade only that feature, show a small inline banner/chip with "Try again" and "Open Settings". Don’t loop requests. After two denials, stop prompting unless user re‑engages.
- Respect "Don’t ask again": surface a Settings deep link when we detect permanent denial.

### UI patterns

- Use Material 3 modal bottom sheets or dialogs for rationales; minimal neutral copy and two clear actions: Continue / Not now.
- Never mimic system permission dialogs.

## Permission triggers and flows (concrete)

### Location (foreground)

Flow:
1) User taps Start Tracking → show optional rationale (only if not obvious or after a prior denial).
2) Request ACCESS_FINE_LOCATION via Activity Result APIs.
3) Granted → start session. Denied → keep app usable; show inline nudge with Settings link.

Precision choice (API 31+):
- Present a simple toggle: Approximate vs Precise. Explain that precise enables route lines and accurate distance. Remember the choice and allow upgrading.

### Background location (only for auto‑tracking)

Flow:
1) User enables Auto‑tracking → ensure foreground location is granted.
2) Show prominent in‑app disclosure (reason, battery, controls) → Continue.
3) Request ACCESS_BACKGROUND_LOCATION.
4) If denied, keep auto‑tracking in "foreground only" mode or disable automatic starts; offer a later upgrade path.

### Activity Recognition

Flow:
1) User enables Auto‑tracking or Activity insights → optional short rationale.
2) Request ACTIVITY_RECOGNITION.
3) Granted → (optionally) ask for background location if user opted into always‑on tracking.

### Wi‑Fi scanning

Flow:
1) User opens Wi‑Fi list/heatmap or enables Wi‑Fi tracking → short rationale.
2) API 33+: request NEARBY_WIFI_DEVICES. API <33: request ACCESS_FINE_LOCATION.
3) Granted → run scans with throttling; Denied → show placeholder + nudge.

Notes:
- Update `WifiDataProducer` to gate scans on the new permission checks and prefer NEARBY_WIFI_DEVICES on API 33+.
- Keep scans conservative (respect platform throttling) and user‑initiated where possible.

### Notifications (API 33+)

Flow:
1) User toggles "Enable tracking alerts" or starts a foreground service requiring user‑visible notifications → request POST_NOTIFICATIONS.
2) Denied → keep running, but do not rely on notifications; offer a subtle nudge in the relevant screen.

## APIs and implementation details

### Activity Result APIs

- Compose: `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission)` and `ActivityResultContracts.RequestMultiplePermissions`.
- Views: `registerForActivityResult` in `Fragment`/`Activity`.
- Prefer Google’s Accompanist `accompanist-permissions` for reactive Compose UIs.

Rationale gating:
- Use `shouldShowRequestPermissionRationale()` to decide whether to show the educational bottom sheet after a denial.

### Manifest and Gradle checklist

- Add `android.permission.NEARBY_WIFI_DEVICES` (API 33+). Don’t mark as `neverForLocation` since Wi‑Fi contributes to location insights in this app.
- Keep `ACCESS_FINE_LOCATION` for route tracking and pre‑33 Wi‑Fi scanning.
- Keep `ACCESS_BACKGROUND_LOCATION` gated by the in‑app disclosure flow.
- Keep `ACTIVITY_RECOGNITION` for auto‑tracking.
- Keep `POST_NOTIFICATIONS` (API 33+) but request only after an explicit user action.
- Remove legacy `READ_EXTERNAL_STORAGE`; only add `READ_MEDIA_*` if we truly need library‑wide access. Prefer Photo Picker.

## Migration from current FirstRun to just‑in‑time

Stage 0: Purge legacy onboarding and permission scaffolding (clean slate)

- Delete legacy First-Run system (classes and wiring):
    - app/src/main/java/com/adsamcik/tracker/module/AppFirstRun.kt
    - tracker/src/main/java/com/adsamcik/tracker/tracker/module/TrackerFirstRun.kt
    - sutils/src/main/java/com/adsamcik/tracker/shared/utils/module/FirstRun.kt
    - sutils/src/main/java/com/adsamcik/tracker/shared/utils/dialog/FirstRunDialogBuilder.kt

- Remove first-run wiring and flags:
    - app/src/main/java/com/adsamcik/tracker/app/activity/MainActivity.kt: remove firstRun() invocation and all FirstRunDialogBuilder usage; delete the conditional that checks R.string.settings_first_run_key.
    - app/src/main/res/values/strings.xml: delete `settings_first_run_key` and all `first_run_*` strings; replicate deletion across localized `values-*/strings.xml`.
    - tracker/src/main/res/values/strings.xml: delete all `first_run_*` strings (e.g., background location disclosure, automatic tracking, what to track).

- Delete legacy permission UX layer tied to dialogs:
    - sutils/src/main/java/com/adsamcik/tracker/shared/utils/permission/PermissionManager.kt (Dexter + MaterialDialogs–based rationale/request flow)
    - sutils/src/main/java/com/adsamcik/tracker/shared/utils/fragment/CorePermissionFragment.kt (old permission request plumbing)
    - Update call sites to migrate off these immediately (temporary: use Activity Result APIs on the feature screens that need them):
        - tracker/src/main/java/com/adsamcik/tracker/tracker/ui/fragment/FragmentTracker.kt (extends CorePermissionFragment and calls requestPermissions)
        - map/src/main/java/com/adsamcik/tracker/map/fragment/FragmentMap.kt (extends CorePermissionFragment)
        - app/src/main/java/com/adsamcik/tracker/preference/pages/TrackerPreferencePage.kt (PermissionManager checks), tracker/src/main/java/com/adsamcik/tracker/tracker/component/TrackerTimerManager.kt (PermissionManager checks), and other direct PermissionManager usages

- Remove legacy “Introduction” framework (tooltip/walkthrough system) to avoid overlapping onboarding patterns:
    - sutils/src/main/java/com/adsamcik/tracker/shared/utils/introduction/Introduction.kt
    - sutils/src/main/java/com/adsamcik/tracker/shared/utils/introduction/IntroductionManager.kt
    - app/src/main/java/com/adsamcik/tracker/app/HomeIntroduction.kt
    - app/src/main/java/com/adsamcik/tracker/app/activity/MainActivity.kt: remove uiIntroduction() and IntroductionManager wiring
    - If any strings are exclusive to introductions (e.g., `skip_introduction`), remove them; otherwise keep shared ones

- Remove libraries exclusively used for old permission/onboarding flows:
    - Dexter: remove dependencies and versions
        - sutils/build.gradle.kts: remove implementation(libs.dexter)
        - app/build.gradle.kts: remove implementation(libs.dexter)
        - buildSrc/src/main/kotlin/Dependencies.kt: remove Versions.DEXTER and the dependency hook
        - gradle/libs.versions.toml: remove the dexter library alias if present
    - Material Dialogs stays (used across preferences/statistics/export); accompanist stays

- PR hygiene for Stage 0:
    - Build must remain green after removal: replace deleted APIs at call sites with no-op or new Activity Result flows where trivial; otherwise guard with TODOs and temporary minimal shims only where necessary to compile
    - Run lint to ensure removed string keys are not referenced
    - Verify app launches and core navigation works without first-run

Stage 1: Replace first‑run dialogs
- Remove `AppFirstRun` and `TrackerFirstRun` from `MainActivity.firstRun()` wiring.
- Introduce `OnboardingCoordinator` and a lightweight welcome/value screens (no permissions).

Stage 2: Wire just‑in‑time permission triggers
- Start Tracking → foreground location.
- Enable Auto‑tracking → activity recognition → background location (with disclosure).
- Open Wi‑Fi screens / enable Wi‑Fi tracking → NEARBY_WIFI_DEVICES (or location on <33).
- Enable alerts → POST_NOTIFICATIONS.

Stage 3: Update producers/consumers
- `WifiDataProducer`: guard scans via new checks; prefer NEARBY_WIFI_DEVICES on API 33+.
- Background tracking: ensure background location request is totally decoupled and two‑step.

Stage 4: Remove legacy code
- Delete `FirstRunDialogBuilder`, `FirstRun` implementations once the new flow is stable.

## Denial UX spec (snippets)

- Inline chip/banner example:
    - Text: "Location is off — routes won’t be accurate."
    - Actions: "Try again" (re‑request) | "Settings"
    - Auto‑dismiss after navigation; never loop repeatedly.

## Copy templates (concise, skippable)

Location (Precise)
"Accurate route tracking"
"To draw your route and measure distance, we need precise location. We don’t upload your location. You can change this anytime in Settings."
Actions: Continue / Not now

Background location
"Automatic tracking in the background"
"This lets Tracker detect movement and log routes even when the app is closed. You can pause or disable this anytime. May increase battery use."
Actions: Continue / Not now

Wi‑Fi scanning
"Improve indoor accuracy"
"Nearby Wi‑Fi helps estimate location indoors. We don’t join networks or send data off your device."
Actions: Continue / Not now

Notifications (after user enables alerts)
"Allow alerts"
"We’ll use notifications to show tracking status and timely updates. You can turn these off later."
Actions: Allow / Not now

### Accessibility Features

**Requirements**:
- Full screen reader support
- High contrast mode support
- Large text support
- Keyboard navigation
- Voice commands where applicable

**Implementation**:
```kotlin
@Composable
fun AccessibleOnboardingScreen(
    state: OnboardingState,
    onEvent: (OnboardingEvent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Onboarding step ${state.currentStep}" }
    ) {
        // Accessible content implementation
    }
}
```

### Content Strategy

#### Writing Guidelines

**Tone**: Friendly, clear, helpful
**Language Level**: 8th grade reading level
**Localization**: Translation-friendly, avoid idioms
**Technical Terms**: Always explained in user terms

#### Copy Examples

**Location Permission Request**:
```
"Better location tracking"

"To track your journeys accurately, Tracker needs access to your location. This lets us:
• Show your routes on maps
• Calculate distances traveled  
• Identify places you visit frequently

Your location data stays private and is stored only on your device."

[Enable Location] [Skip for now]
```

**Background Location Request**:
```
"Automatic tracking"

"For hands-free tracking, we need background location access. This means:
• Tracking starts automatically when you move
• No need to remember to start tracking
• Better coverage of your daily activities

You can disable this anytime in settings."

[Enable Background Tracking] [Manual tracking only]
```

### Testing Strategy

#### A/B Testing Framework

**Test Scenarios**:
- Permission request timing
- Copy variations
- Visual design alternatives
- Flow order optimization

**Implementation**:
```kotlin
class OnboardingExperiments {
    fun getLocationPermissionTiming(): PermissionTiming
    fun getValueDemoVariant(): ValueDemoVariant
    fun getCopyVariant(key: String): String
}
```

#### Metrics for Optimization

**Primary Metrics**:
- Overall completion rate
- Permission grant rate
- Feature adoption rate
- User retention after onboarding

**Secondary Metrics**:
- Time to complete onboarding
- User satisfaction scores
- Support ticket volume
- App store review sentiment

## Migration Strategy

### Phase 1: Infrastructure (Sprint 1-2)
- Set up Compose infrastructure
- Implement state management
- Create analytics framework
- Build permission management system

### Phase 2: Core Screens (Sprint 3-4)
- Implement welcome and value demo screens
- Create privacy and what to track screens
- Build permission request components
- Add accessibility features

### Phase 3: Advanced Features (Sprint 5-6)
- Implement enhanced features setup
- Add background location flow
- Create success and celebration screens
- Integrate analytics tracking

### Phase 4: Testing & Optimization (Sprint 7-8)
- A/B testing implementation
- User testing and feedback integration
- Performance optimization
- Bug fixes and polish

### Phase 5: Rollout (Sprint 9-10)
- Gradual feature flag rollout
- Monitor metrics and adjust
- Documentation and training
- Full release

## Success Metrics

### Primary KPIs
- **Onboarding Completion Rate**: Target 85%+ (vs current ~60%)
- **Location Permission Grant Rate**: Target 80%+ (vs current ~55%)
- **Feature Adoption Rate**: Target 70%+ for core features
- **User Retention**: 7-day retention >75%, 30-day retention >50%

### Secondary Metrics
- **Time to Complete**: Target <5 minutes average
- **User Satisfaction**: Target 4.5+ stars app store rating
- **Support Volume**: Reduce onboarding-related tickets by 60%
- **Accessibility Compliance**: 100% WCAG 2.1 AA compliance

## Risk Mitigation

### Technical Risks
- **Compose Migration**: Gradual adoption, fallback to Views if needed
- **Performance**: Regular performance testing, optimization
- **Compatibility**: Extensive device testing matrix

### UX Risks
- **User Confusion**: Extensive user testing, iterative design
- **Permission Fatigue**: Clear rationales, just-in-time requests
- **Feature Abandonment**: Progressive disclosure, optional features

### Business Risks
- **Reduced Permissions**: Accept lower permission rates for better user experience
- **Development Time**: Phased rollout, MVP approach
- **User Backlash**: Clear communication, feedback channels

## Conclusion

This clean slate design prioritizes user understanding and voluntary engagement over maximizing permission grants. By showing value first and requesting permissions contextually, we expect to achieve higher overall user satisfaction and retention, even if some individual permission grant rates decrease.

The new system respects user agency while gently guiding them toward an optimal app experience. Through progressive disclosure and clear benefit communication, users will make informed decisions about feature enablement.

Success will be measured not just by permission grant rates, but by overall user engagement, retention, and satisfaction with the onboarding experience.

---

**Next Phase**: Create detailed wireframes and interactive prototypes for user testing and validation.

## Friction audit and flow polish (2025)

Goal: remove “nonsense” prompts and keep users in the task they initiated.

- Don’t auto-ask for notifications on launch
    - Current: `FragmentTracker.onStart()` requests POST_NOTIFICATIONS on API 33+.
    - Change: request only when the user enables alerts or starts a session that needs ongoing status. Otherwise, suppress.

- Just-in-time, single-purpose prompts
    - When user taps Start Tracking, ensure the flow checks GNSS and “anything to track” first (already done), then request only the one needed permission (foreground location) if missing. No multi-permission bundles.

- Remove Dexter and dialog-driven permission bundling
    - Migrate `TrackerTimerManager.checkTimerPermissions` off `PermissionManager` to Activity Result APIs. Show a terse bottom sheet only after a prior denial.

- Wi‑Fi scan gating and graceful fallback
    - `WifiDataProducer` should gate `startScan()` and `scanResults` behind NEARBY_WIFI_DEVICES (API 33+) or FINE (<33). If denied, skip scans silently and show a soft inline hint in Wi‑Fi UI screens only.

- Background location as a true upgrade
    - Only after user enables Auto‑tracking. Clear disclosure page → system dialog. If denied, keep auto mode in foreground-only or disable auto-starts without nagging.

- Precision choice kept simple
    - Offer Approximate vs Precise only when user is starting route recording. Default to Precise with a one‑liner; remember choice.

- Avoid repeated prompts and loops
    - Track per-permission denial count. After two denials, stop re-prompting automatically; show a small inline banner with Settings link when user re-enters that feature.

- No battery optimization or overlay prompts
    - Don’t surface battery optimization ignore or overlay permissions. If absolutely needed for a niche device, link a help article instead of prompting.

- Post-success confirmation
    - After a grant, immediately start the intended action (start tracking, enable auto, open Wi‑Fi list). Don’t force extra confirmations.

- Copy polish (1–2 lines, max 3 bullets)
    - Benefit first, then privacy assurance, then reversibility. Examples already included above; ensure wording is localized and non-technical.

- Telephony data behavior
    - `CellDataProducer` reads `allCellInfo`; keep it passive. Only request `READ_PHONE_STATE` if a dual‑SIM insight feature is explicitly enabled; otherwise degrade silently.

- Metrics
    - Track: prompt cause, surface, accept/deny, time-to-action; drop-offs within 10s after prompt; second-try grants. Use to prune any prompt that hurts success.
