# Main-branch baseline databases

This folder ships **immutable binary fixtures** representing each Room database
in the state it was released on the `main` branch. They are consumed by
[`MainBaselineMigrationTest`](../../../java/com/adsamcik/tracker/shared/base/database/MainBaselineMigrationTest.kt)
to prove that the migrations on `dev/v10` correctly carry production data
forward.

| File                       | Database         | Module     | Schema version | Identity hash |
|---------------------------|------------------|-----------|----------------|---------------|
| `main_database.db`        | AppDatabase      | sbase     | 12             | `603376ce119f1b4f1d3e4866aec15b4e` |
| `debug_database_sbase.db` | DebugDatabase    | sbase     | 1              | `3413cc2a29d2275b27a7c2331a5caece` |
| `preference_database.db`  | PreferenceDB     | sbase     | 1              | `0608179a3962e9cd5340a093ba0378d5` |
| `debug_database_logger.db`| LogDatabase      | logger    | 2              | `b97eff39573b7d2217bdfb0fad947274` |
| `points_database.db`      | PointsDatabase   | points    | 1              | `67361523b8053dd6727d713508ad45ab` |
| `stats_database.db`       | StatsDatabase    | statistics| 1              | `9bcc267288495935f80c47544aaf0bbf` |
| `challenge_database.db`   | ChallengeDatabase| game      | 2              | `fc01d6d4df1d92f270a03f050e98bde0` |

`ChallengeDatabase` was deleted on `dev/v10`. The fixture is still shipped so
the test can prove the leftover SQLite file remains harmless (no class on
`dev/v10` opens it, the file is still valid bytes, nothing crashes).

## How to regenerate

The fixtures were produced once from a worktree of the `main` branch by a
Python seeder that reads each module's exported Room schema JSON directly:

```bash
git worktree add .worktrees/main-baseline main
cd .worktrees/main-baseline/baseline-export
python build_baselines.py
# baselines are written next to the script
cp *.db <repo>/sbase/src/test/resources/baseline/main/
```

The seeder lives at `.worktrees/main-baseline/baseline-export/build_baselines.py`
on the main worktree. It is intentionally side-effect-free, deterministic, and
emulator-free so it can be reproduced in CI.

**Do not edit these files by hand.** If a schema on `main` is corrected (very
unlikely — `main` is the released branch), re-run the seeder and overwrite the
binaries. If you need to extend the seed data, edit `build_baselines.py` on
the main worktree first so the seed and its provenance stay in lockstep.
