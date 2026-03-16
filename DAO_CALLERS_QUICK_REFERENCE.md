# QUICK REFERENCE: DAO Caller Locations

## Calling Context by DAO Method

| DAO Method | File | Function | Line | Context | Status |
|---|---|---|---|---|---|
| **ChallengeDao.getActive(now)** | ChallengeManager.kt | loadFromDb() | 60 | @WorkerThread → scope.launch | NEEDS FIX |
| **ChallengeHistoryDao.getAll()** | ChallengeManager.kt | calculateDifficulty() | 236 | private fun (non-suspend) | NEEDS FIX |
| **ChallengePersonalRecordDao.get()** | ProgressionRepository.kt | checkPersonalRecords() | 182,201 | suspend ✅ | OK |
| **PlayerProfileDao.get()** | ProgressionRepository.kt | updatePlayerProfile() | 226 | suspend ✅ | OK |
| **PlayerProfileDao.get()** | UnlockRepository.kt | isUnlockedSync() | 33 | public non-suspend | INVESTIGATE |
| **PlayerProfileDao.get()** | UnlockRepository.kt | getUnlockedFeatures() | 41 | public non-suspend | INVESTIGATE |
| **PlayerProfileDao.get()** | UnlockRepository.kt | getNextUnlock() | 57 | public non-suspend | INVESTIGATE |
| **ChallengeStreakDao.get()** | StreakManager.kt | onChallengeCompleted() | 32 | suspend ✅ | OK |
| **ChallengeStreakDao.get()** | StreakManager.kt | onChallengesExpired() | 63 | suspend ✅ | OK |
| **SessionChallengeDataDao.get()** | ChallengeWorker.kt | getSession() | 36 | suspend (CoroutineWorker) ✅ | OK |

---

## Blocking Calls Summary

### 🔴 Critical Issues (3 total)

1. **ChallengeHistoryDao.getAll()** @ ChallengeManager:236
   - Via: ChallengeManager.calculateDifficulty() (private, non-suspend)
   - Callstack: activateRandomChallenge() [suspend] → calculateDifficulty() [sync] → getAll()
   - Fix: Make calculateDifficulty() suspend, dispatch to IO

2. **ChallengeDao.getActive(now)** @ ChallengeManager:60
   - Via: ChallengeManager.loadFromDb() (@WorkerThread annotation)
   - Callstack: initialize() → scope.launch → loadFromDb() → getActive()
   - Fix: Make loadFromDb() suspend, dispatch to IO

3. **PlayerProfileDao.get()** @ UnlockRepository (3 methods)
   - Via: isUnlockedSync() (line 33), getUnlockedFeatures() (line 41), getNextUnlock() (line 57)
   - Context: Public non-suspend APIs
   - Fix: Find all callers to determine suspend variant vs blocking strategy

---

## Test Build Targets

### Run these to validate changes:
\\\ash
# Unit tests (fastest)
./gradlew :game:test

# Instrumentation tests
./gradlew :game:androidTest

# Full app integration test
./gradlew :app:connectedAndroidTest
\\\

### Key test files:
- game/src/test/java/com/adsamcik/tracker/game/challenge/DifficultyCalculationTest.kt
- game/src/test/java/com/adsamcik/tracker/game/event/ExplorationStreakTrackerTest.kt
- game/src/androidTest/java/com/adsamcik/tracker/game/challenge/database/migration/ChallengeMigrationTest.kt

