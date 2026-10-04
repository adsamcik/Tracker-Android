# Radio live freshness: F1 repaired source selection

Phase: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. All 16 authored cases are **NOT_RUN**. No compiler, Gradle, tests, lint, schema checks, devices, provider activation or Git operations were executed by this author.

## Approved behavior and finding

The existing adaptive plan requires a finite initial live request with a 60-second freshness target, independent of the broader historical age allowance. The first packet's `hasFreshLiveRadioObservation` checked capture membership and 60-second age but omitted capture's immutable maximum age. A shared callback admitted for background with a 600-second allowance could therefore suppress a session request requiring 10-second data, even when every item was rejected for that session.

The first packet and its rejected `SOURCE_REVIEW.md` are preserved unchanged. `F1_REVISION.patch` records this repair against its exact selected source; `SELECTION.patch` is the complete six-path HEAD-based selection.

## Narrow repair

- One internal read-only method in the existing `SourceRegistrationRepository` reads at most 64 exact current authorization demand IDs from the existing DAO. It uses the existing canonical payload codecs and `RadioPurposeItemQualifier` over the actual admitted candidate, exact provider acceptance, current authorization snapshot and immutable demand rows. Only that single capture demand's accepted child timestamps are returned.
- Missing or malformed rows, ambiguous capture ownership, invalid provider bounds, source/registration/boot/epoch/fingerprint mismatches and qualification failures leave the finite fresh request eligible. Cancellation propagates. The method creates no recording authority and changes no data.
- Both actors perform this read outside `callbackLock`, then retain the existing current registration, plan, queue, cutoff, retirement and Cell request-origin fences. They apply the original 60-second age at processing only to capture-qualified timestamps. A prior-owner callback cannot satisfy a successor request.
- Wi-Fi continues using the exact candidate returned after cutoff salvage. Cell's private admission method now returns its existing successfully admitted candidate instead of a boolean; no public delivery contract changes.
- All admitted historical and background children remain intact. Passive callbacks, replay identity, finite attempts and the original durable request/cooldown ledger are unchanged.
- Current native empty callbacks remain unrecorded: Wi-Fi and Cell producers already reject empty or filtered-to-empty child lists. This packet introduces no zero-coverage assumption.

## Source tests and evidence boundary

The original 11 cases remain selected. Five added cases cover real Room demand reads composed with each actual radio actor: 30-second background-only data under capture 10-second/background 600-second limits keeps the request eligible; a mixed batch retains both children but only its capture-qualified fresh child suppresses work; missing/malformed/changed demand rows and mismatched provider/registration metadata fail to satisfy freshness.

These composed cases use authentic `SourceBrokerAuthorization.rows`, actual canonical codecs, `RadioPurposeItemQualifier`, and the actual bounded repository method against Room. Provider callbacks, wakeups and the durable sink acknowledgement remain controlled fixture seams. They are not executed tests, live providers, actual WAL admission or on-device evidence. Other runtime cases explicitly mock the qualification seam to isolate existing clock/owner behavior.

## Preserved and excluded work

`WORKING_BEFORE` preserves all six current working inputs. Selected tests derive from the first packet's narrow selected test sources plus this repair; unrelated held test drafts are excluded. The repository BASE is root-exported HEAD `39718842674c35a5052c696c1a28d2c1f73c9b05`, preserving the already committed passive Location admission body exactly. The original other five BASE paths remain at `fa340240624710a5e2c77cd8883492515f4b6b39`.

No schema, public API, writer, storage, timeline, permission, budget, timer, thermal, manual/automatic, history, export or backup change is selected. The separate Location cold-transition fixture work stays paused. Root owns final exact-path composition and independent review; this packet does not declare full assembly or `dev/v10` readiness.
