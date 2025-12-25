# TrackingPolicyManager Implementation Analysis

## Key Implementation Details

### Threshold Logic
```kotlin
STEP_RATE_MOVEMENT_SUSPECTED = 10f  // steps/min
STEP_RATE_ACTIVE_MODERATE = 40f      // steps/min  
STEP_RATE_ACTIVE_ELEVATED = 80f      // steps/min
```

**Critical:** Thresholds use `>` (greater than), not `>=`:
```kotlin
if (stepsPerMinute > STEP_RATE_MOVEMENT_SUSPECTED) { ... }
```

So exactly 10.0 steps/min does NOT trigger escalation - needs to be > 10.

### Escalation Rules

**Step-based escalation is ONE LEVEL at a time:**
- PASSIVE_LOW can only escalate to MOVEMENT_SUSPECTED (if > 10 steps/min)
- MOVEMENT_SUSPECTED can only escalate to ACTIVE_MODERATE (if > 40 steps/min)
- ACTIVE_MODERATE can only escalate to ACTIVE_ELEVATED (if > 80 steps/min)

**You cannot skip levels in a single update.** Even if step rate is 200 steps/min, it will:
1. First call: PASSIVE_LOW → MOVEMENT_SUSPECTED
2. Next call needs NEW delta from new baseline
3. Cannot reach ACTIVE_ELEVATED in one jump

### De-escalation Rules

**Cooldown-based only:**
```kotlin
COOLDOWN_DURATION_MS = 5 * 60 * 1000L  // 5 minutes (300 seconds)
```

De-escalation requires:
1. 5 minutes to pass since last `transitionTo()` call
2. `checkCooldown()` is called on EVERY `onStepUpdate()`  
3. If cooldown period elapsed, downgrades ONE LEVEL:
   - ACTIVE_ELEVATED → ACTIVE_MODERATE
   - ACTIVE_MODERATE → MOVEMENT_SUSPECTED
   - MOVEMENT_SUSPECTED → PASSIVE_LOW

**Activity transitions DO NOT cause de-escalation:**
- `onActivityTransition()` ONLY escalates (when moving from STILL to movement)
- Detecting STILL activity does nothing
- Only cooldown can downgrade

**Location changes DO NOT cause de-escalation:**
- `onLocationChange()` ONLY escalates (when displacement > 50m)
- Low displacement does nothing
- No downgrade logic

### Step Delta Calculation

```kotlin
val deltaSteps = if (lastStepCount > 0) stepCount - lastStepCount else 0
val deltaTime = if (lastStepTime > 0) timeMs - lastStepTime else 0L

if (deltaTime > 0 && deltaSteps > 0) {
    val stepsPerMinute = (deltaSteps.toFloat() / deltaTime) * 60_000f
    // ... check thresholds
}
```

**Critical behaviors:**
1. First call sets baseline, no escalation (lastStepCount = 0)
2. Second call uses delta from first call
3. Negative deltas (counter reset) are ignored (deltaSteps <= 0)
4. Zero time delta ignored (prevents divide by zero)

### Activity Transition Logic

```kotlin
val isMoving = activityType in listOf(0, 1, 2, 7, 8)  
// 0=IN_VEHICLE, 1=ON_BICYCLE, 2=ON_FOOT, 7=WALKING, 8=RUNNING

val wasStill = previousActivity == 3  // 3 = STILL

if (wasStill && isMoving && confidence > 50) {
    // Escalate from PASSIVE_LOW or MOVEMENT_SUSPECTED
}
```

**Only escalates when:**
1. Previous activity was STILL (3)
2. New activity is movement (0, 1, 2, 7, or 8)
3. Confidence > 50

### Location Change Logic

```kotlin
DISPLACEMENT_THRESHOLD_METERS = 50f

if (displacementMeters > 50f) {
    // Escalate
}
```

**Only escalates when displacement > 50 meters.**

Parameters are `(displacementMeters, timeMs)`, NOT `(accuracy, speed)`.

### User-Initiated Mode

```kotlin
if (isUserInitiated) return@withLock  // Early return in all update methods
```

**All adaptation disabled:**
- Step updates ignored
- Activity transitions ignored
- Location changes ignored
- Policy stays at USER_INITIATED forever

---

## Why Tests Are Failing

### 1. `exact threshold boundaries produce correct policy transitions`

**Problem:** Uses exact threshold values (10, 40, 80 steps/min)

**Implementation:** Thresholds are `>` not `>=`

**Fix:** Use > threshold values (11, 41, 81 steps/min) OR change from one-level-at-a-time escalation

**Actual:** 
- 10 steps/min does NOT trigger (needs > 10)
- 40 steps/min does NOT trigger (needs > 40)
- 80 steps/min does NOT trigger (needs > 80)

---

### 2. `step count decreasing (device reboot) resets baseline gracefully`

**Problem:** Expects escalation after counter reset

**Implementation:** Negative deltas are ignored (`if deltaSteps > 0`)

**Test flow:**
1. stepCount = 100 → baseline set
2. stepCount = 140 (delta = 40, triggers escalation)
3. stepCount = 10 (delta = -130, IGNORED)
4. stepCount = 50 (delta = 40, triggers escalation)

**Actual:** Step 3 is ignored, so step 4 delta is from baseline 140, not 10.

**Fix:** After counter reset, next update will be ignored. Need 2 updates after reset to get new delta.

---

### 3. `policy downgrades after period of inactivity`

**Problem:** Expects immediate downgrade after low activity (5 steps/min)

**Implementation:** Requires 5 minute (300 second) cooldown period

**Test timing:**
- baseTime + 0: stepCount = 10
- baseTime + 60s: stepCount = 100 (escalates to MOVEMENT_SUSPECTED due to one-level limit)
- baseTime + 120s: stepCount = 105 (5 steps/min, but only 120s elapsed, cooldown needs 300s)

**Actual:** 
1. First update doesn't reach ACTIVE_ELEVATED (one level at a time)
2. Cooldown doesn't trigger (only 120s, needs 300s)

**Fix:** Wait 300+ seconds AND feed multiple step updates to climb through all levels

---

### 4. `activity transition to STILL downgrades policy`

**Problem:** Expects downgrade when STILL activity detected

**Implementation:** Activity transitions ONLY escalate, never de-escalate

```kotlin
if (wasStill && isMoving && confidence > 50) {
    // ONLY escalation logic here
}
// No de-escalation logic for moving → STILL
```

**Actual:** STILL activity is completely ignored

**Fix:** Remove test OR wait for 5-minute cooldown

---

### 5. `interleaved step and activity events produce consistent policy`

**Problem:** Expects ACTIVE_MODERATE after 25 steps/min + WALKING

**Implementation:** 
- 25 steps/min escalates PASSIVE_LOW → MOVEMENT_SUSPECTED
- WALKING activity only escalates if `wasStill && isMoving`
- Test doesn't set initial activity to STILL

**Actual:**
- Steps escalate to MOVEMENT_SUSPECTED
- WALKING activity does nothing (previous activity was -1, not 3)
- Final policy is MOVEMENT_SUSPECTED, not ACTIVE_MODERATE

**Fix:** Set initial activity to STILL before WALKING transition

---

### 6. `poor location accuracy during high step rate maintains elevated policy`

**Problem:** Test title mentions "accuracy" but implementation doesn't use accuracy

**Implementation:** `onLocationChange(displacementMeters, timeMs)` only checks displacement

**Test:** Calls `onLocationChange(5f, ...)` with 5 meters displacement

**Actual:**
- Reaches ACTIVE_ELEVATED via steps (works)
- Location with 5m displacement < 50m threshold → no action
- No de-escalation logic in onLocationChange → policy stays elevated (CORRECT!)

**Why it fails:** Test probably doesn't reach ACTIVE_ELEVATED in first place (one-level-at-a-time issue)

---

### 7. `large step count values do not cause overflow`

**Problem:** Uses Int.MAX_VALUE - 1000 for step counts

**Test:**
```kotlin
stepCount = Int.MAX_VALUE - 1000  // baseline
stepCount = Int.MAX_VALUE - 900   // delta = 100 steps over 60s = 100 steps/min
```

**Expected:** 100 steps/min > 80 → ACTIVE_ELEVATED

**Actual:** 100 steps/min > 10 → MOVEMENT_SUSPECTED (one level only!)

**Fix:** Need multiple updates to climb through levels, or start from higher policy

---

### 8-11. Integration Test Failures

Similar issues:
- One-level-at-a-time escalation
- 5-minute cooldown requirement
- Activity requires previous STILL state
- Displacement vs accuracy confusion

---

## Corrected Understanding

### To reach ACTIVE_ELEVATED from PASSIVE_LOW:

**Minimum 3 step updates required:**

```kotlin
manager.onStepUpdate(10, baseTime)                    // Baseline
manager.onStepUpdate(21, baseTime + 60_000)          // 11 steps/min → MOVEMENT_SUSPECTED
manager.onStepUpdate(62, baseTime + 120_000)         // 41 steps/min → ACTIVE_MODERATE
manager.onStepUpdate(143, baseTime + 180_000)        // 81 steps/min → ACTIVE_ELEVATED
```

### To trigger de-escalation:

**Must wait 5 minutes (300 seconds) since last transition:**

```kotlin
manager.onStepUpdate(10, baseTime)
manager.onStepUpdate(100, baseTime + 60_000)         // Escalate (lastTransitionTime set)
manager.onStepUpdate(100, baseTime + 400_000)        // 340s later, cooldown triggers → downgrade
```

### Activity-driven escalation:

**Requires previous activity = STILL:**

```kotlin
manager.onActivityTransition(3, 80, baseTime)        // Set to STILL first
manager.onActivityTransition(7, 80, baseTime + 1000) // WALKING escalates
```

---

## Recommended Test Fixes

1. **Use > thresholds:** 11, 41, 81 steps/min instead of 10, 40, 80
2. **Multi-step escalation:** 3-4 updates to climb through all levels
3. **Cooldown timing:** Use baseTime + 400_000 or similar for downgrade tests
4. **Activity setup:** Always call with STILL first before movement
5. **Remove impossible tests:** Can't test instant de-escalation on STILL or low displacement

See next document for specific test corrections.
