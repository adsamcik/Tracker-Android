# Live radio freshness repair

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** This packet selects five source/test paths against `fa340240624710a5e2c77cd8883492515f4b6b39`. No tests, compilation, schema gates, device/provider activation, battery run or integration has occurred.

## Approved behavior and narrow repair

`FIVE_LEVEL_ADAPTIVE_POLICY.md:96` already requires the single **60-second live desired freshness** target, separate from the **10-minute useful-history admission** cap. The original user approved one durable active request and carried cooldowns through restart/quality changes. The existing finite initial-live request flow remains: this repair adds no periodic expiry cadence, second allowance or replenishment. The complete original task/corrections remain in the parent conversation and the frozen task-context dependency.

- Wi-Fi and Cell keep the same historical observation qualifiers, payloads, immutable captured callback contexts, admission transactions, replay identities and passive callbacks.
- Only an admitted provider measurement that is at most60 seconds old **when the actor finishes admission**, in the current exact owner/plan and same-boot capture window, can satisfy the initial live freshness target. Receipt time cannot make an old measurement fresh; new capture authority cannot lend backward.
- Wi-Fi's private admission result now returns its already prepared delivery instead of only its identity. The actor uses precisely the successfully admitted candidate, including an existing cutoff salvage result. Stable replay identity and all failure/duplicate outcomes keep their meaning.
- Cell's private actor-local recording method returns its existing qualified snapshot instead of a boolean. A response carrying an earlier request context cannot satisfy a successor owner's freshness target, even when the historical response is otherwise admitted.
- A Wi-Fi wakeup queued before fresh evidence cancelled it is checked before reserving the durable request allowance. It cannot spend an unneeded request merely because it survived cancellation.
- Existing provider operation mutexes, callback admission locks, finite attempts, owner fences, outstanding Cell request completion, timeout and cancellation propagation remain in use. No new physical source, datastore, proof framework, public API, schema or product surface is added.

## Authored source coverage

Eleven new source cases, all **NOT_RUN**, use existing real runtime actors/registration fixtures, mocked providers/atomic sinks, the existing preference-allowance test double and Robolectric's clock. They are host fixtures, not on-device evidence.

- Exact60-second provider-age boundary, invalid/future/zero clocks and receipt-to-processing delay.
- Current same-boot capture authority; ambient-only/earlier-admission/foreign-boot readings cannot suppress a live owner's request.
- Older valid Wi-Fi and Cell records remain admitted while each pending active request still executes.
- Fresh Wi-Fi and Cell records suppress redundant work without reserving the durable allowance, including a manually fired already-cancelled wakeup.
-61-second admission delay preserves original readings and leaves the initial fresh request eligible.
- Queued earlier-owner Wi-Fi/Cell admissions retain their original context and cannot suppress/spend a successor request; the physical provider remains shared.
- A late Cell refresh response retains its ordinary admission and settles only its own outstanding response, while the next live owner remains eligible.

The two authorization fixtures gain optional logical-owner and age values with their original defaults preserved. The older-history cases explicitly grant the historical age they exercise. Held test drafts are preserved in `WORKING_BEFORE` and excluded from `SELECTED`. The exported sixth support-test BASE is not selected; the existing Wi-Fi authorization fixture supports the two shared clock/authority cases without another harness.

`FILES.json` pins original BASE, working-before, selected and actual-after bytes. `SELECTION.patch` is the normalized source delta, `TEST_SOURCE_INVENTORY.json` records only authored cases, and `DEPENDENCY_PATHS.txt` requests immutable existing HEAD dependencies for independent review. The separate thermal propagation slice and held manual/automatic changes are excluded. Full assembly and convergence readiness remain pending.
