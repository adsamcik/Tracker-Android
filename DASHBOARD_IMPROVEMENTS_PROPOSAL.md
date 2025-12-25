# Dashboard Improvements Proposal

## 1. Coordinate Format Toggle

**Current State:** Coordinates are displayed in DMS (Degrees, Minutes, Seconds) format.
**Proposal:** Allow users to tap the coordinate text to toggle between DMS and Decimal Degrees (DD).
**Benefit:** Cater to different user preferences (e.g., geocaching vs. technical logging) without cluttering settings.

## 2. Enhanced Visual Feedback for Copy

**Current State:** Long-press triggers a Snackbar and haptic feedback.
**Proposal:**

- Animate the copy icon (change to a checkmark temporarily).
- Add a subtle background flash or ripple on the specific metric being copied.

**Benefit:** Immediate, localized confirmation of the action.

## 3. Animated Metric Updates

**Current State:** Values update instantly when data changes.
**Proposal:** Use `AnimatedContent` or number rolling animations for changing values like Speed, Altitude, and Accuracy.
**Benefit:** Makes the dashboard feel more "live" and responsive to sensor data.

## 4. Session Summary "Share" Action

**Current State:** Long-press copies a text summary to the clipboard.
**Proposal:** Add a standard "Share" button/icon to the Session Card that opens the system Share Sheet.
**Benefit:** Allows sending directly to other apps (Notes, Messaging) without the intermediate copy-paste step.

## 5. Lightweight Map Preview (Static)

**Current State:** No map visualization on the dashboard.
**Proposal:** Show a static, non-interactive path preview of the current session (vector path on blank background) in the "Session Overview" card.
**Benefit:** Provides spatial context without the heavy overhead of a full Google Maps instance.

## 6. Empty State Illustration

**Current State:** Simple text card.
**Proposal:** Add a vector illustration (using Compose `Canvas` or vector asset) representing "Ready to Track".
**Benefit:** Improves the aesthetic appeal of the "zero state".
