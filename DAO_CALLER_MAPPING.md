# DAO Methods to Suspend - Complete Caller Location Mapping

## Executive Summary

### Blocking Calls Found (Critical)
1. **ChallengeManager.calculateDifficulty()** - Calls ChallengeHistoryDao.getAll() synchronously
2. **ChallengeManager.loadFromDb()** - Calls ChallengeDao.getActive() (marked @WorkerThread but runs in scope.launch)
3. **UnlockRepository** (3 methods) - Calls PlayerProfileDao.get() in non-suspend public APIs

### Mostly Safe (Already Suspend/Unused)
- ChallengePersonalRecordDao.get() - Already called from suspend context
- ChallengeStreakDao.get() - Already called from suspend contexts
- SessionChallengeDataDao.get() - Already called from suspend context
- Most other DAOs: Unused or flow-based only

---

## Detailed Caller Analysis by DAO

### 1. ChallengeDao.getActive(now: Long)
**File**: game/src/main/java/com/adsamcik/tracker/game/challenge/ChallengeManager.kt:60

**Caller**: ChallengeManager.loadFromDb() (line 56-66)
- Annotation: @WorkerThread
- Actual context: scope.launch { val active = loadFromDb(context) }
- Problem: Marked as worker thread but database call happens on default dispatcher
- Fix: Make loadFromDb() suspend and move to IO dispatcher

---

### 2. ChallengeHistoryDao.getAll()
**File**: game/src/main/java/com/adsamcik/tracker/game/challenge/ChallengeManager.kt:236

**Caller**: ChallengeManager.calculateDifficulty() (line 235-238)
- Context: private fun, NOT suspend
- Annotation: None
- Problem: Blocking database call in non-suspend function
- Called from: activateRandomChallenge() [suspend]
- Fix: Make calculateDifficulty() suspend, move to IO dispatcher

---

### 3. PlayerProfileDao.get()
**Multiple Callers**:

a) **ProgressionRepository.updatePlayerProfile()** (line 226)
- File: game/src/main/java/com/adsamcik/tracker/game/challenge/progression/ProgressionRepository.kt
- Context: suspend function
- Status: ✅ OK

b) **UnlockRepository.isUnlockedSync()** (line 33)
- File: game/src/main/java/com/adsamcik/tracker/game/challenge/progression/UnlockRepository.kt
- Context: PUBLIC non-suspend API
- Problem: ❌ BLOCKING - Needs investigation of all callers
- No test callers found

c) **UnlockRepository.getUnlockedFeatures()** (line 41)
- File: game/src/main/java/com/adsamcik/tracker/game/challenge/progression/UnlockRepository.kt
- Context: PUBLIC non-suspend API
- Problem: ❌ BLOCKING - Needs investigation of all callers
- No test callers found

d) **UnlockRepository.getNextUnlock()** (line 57)
- File: game/src/main/java/com/adsamcik/tracker/game/challenge/progression/UnlockRepository.kt
- Context: PUBLIC non-suspend API
- Problem: ❌ BLOCKING - Needs investigation of all callers
- No test callers found

---

### 4. ChallengePersonalRecordDao.get(type, metric)
**Caller**: ProgressionRepository.checkPersonalRecords() (lines 182, 201)
- File: game/src/main/java/com/adsamcik/tracker/game/challenge/progression/ProgressionRepository.kt
- Context: suspend function
- Status: ✅ OK

---

### 5. ChallengeStreakDao.get()
**Callers**:
- StreakManager.onChallengeCompleted() (line 32) - suspend ✅
- StreakManager.onChallengesExpired() (line 63) - suspend ✅
- File: game/src/main/java/com/adsamcik/tracker/game/challenge/progression/StreakManager.kt
- Status: ✅ OK

---

### 6. SessionChallengeDataDao.get(id)
**Caller**: ChallengeWorker.getSession() (line 36)
- File: game/src/main/java/com/adsamcik/tracker/game/challenge/worker/ChallengeWorker.kt
- Context: suspend function (CoroutineWorker.doWork())
- Status: ✅ OK

---

### Unused DAO Methods (No Production Callers)
- ChallengeDao.get(id)
- ChallengeDao.getAll()
- ChallengeEntryDao.getAll()
- ChallengeEntryDao.getActiveEntry()
- ChallengeEntryDao.get()
- ChallengeHistoryDao.get(id)
- ChallengeHistoryDao.getCompleted()
- ChallengeHistoryDao.getByMedal()
- ChallengePersonalRecordDao.getByType()
- ActiveTimeChallengeDao.get()
- ActiveTimeChallengeDao.getByEntry()
- ExplorerChallengeDao.get()
- ExplorerChallengeDao.getByEntry()
- StepChallengeDao.get()
- StepChallengeDao.getByEntry()
- WalkDistanceChallengeDao.get()
- WalkDistanceChallengeDao.getByEntry()
- XpLedgerDao.getRecent()

---

## Test Targets for Validation

### Unit Tests
1. game/src/test/java/com/adsamcik/tracker/game/challenge/DifficultyCalculationTest.kt
   - Affected by: ChallengeHistoryDao.getAll() suspension
   - Tests: ChallengeManager.difficultyFromCompletionRate()

2. game/src/test/java/com/adsamcik/tracker/game/event/ExplorationStreakTrackerTest.kt
   - Uses: Mocked ExplorationStreakDao.getByType()
   - Status: Already suspend-aware (uses coEvery)

### Android Tests
3. game/src/androidTest/java/com/adsamcik/tracker/game/challenge/database/migration/ChallengeMigrationTest.kt
   - Uses: Raw SQL queries
   - Status: Not affected by DAO suspension

### Test Gradle Targets
- :game:test
- :game:androidTest

---

## Action Items Priority

### HIGH PRIORITY (Blocking Calls)
1. Suspend ChallengeManager.calculateDifficulty()
   - Move database call to dispatcher.io
   - Update caller chain (activateRandomChallenge)

2. Suspend ChallengeManager.loadFromDb()
   - Remove @WorkerThread annotation
   - Move database call to dispatcher.io
   - Verify dispatcher usage

3. Investigate UnlockRepository callers
   - Find all callers of isUnlockedSync(), getUnlockedFeatures(), getNextUnlock()
   - Determine if suspend variants needed or if runBlocking is acceptable

### MEDIUM PRIORITY (Suspend Context Changes)
4. Suspend all ChallengeDao get* methods (when called)
   - Already in suspend contexts mostly
   - Update method signatures

5. Suspend ChallengeHistoryDao methods
   - When used for Flow observation (already handled)
   - Direct calls need suspension

### LOW PRIORITY (Already Correct)
6. No changes needed:
   - ChallengeStreakDao (all suspend)
   - ChallengePersonalRecordDao (all suspend contexts)
   - SessionChallengeDataDao (already suspend)

