# Five-level adaptive collection: product proposal

Date: 2026-09-26. PLANNING_ONLY within IMPLEMENTATION_ONLY. This report makes recommendations for the latest five-step balance request; it does not approve new defaults, hardware demand, data contracts or precise thresholds. No production/test edits, commits, validation, activation or integration. The future independent assembled-code review remains separate.

## 1. What is settled, and what changes

The user now requests one adaptive system informed by multiple sources, device/settings context and whether a live session is active; five balance steps; live sessions favor quality. Earlier direction also settles one source configuration, live sessions as higher-fidelity organization, and retrospective session creation. Use the current centralized source policy/resolver and owners. A second source list or a separate collection engine is not the target.

The five levels describe **what to prioritize**, not five guaranteed sample rates, provider modes or measured battery-consumption bands. Source selection, permission/consent and hard user limits remain independent. A session requests more detail from participating authorized sources; it does not turn on unselected sources, add automatic Activity consent, or erase baseline collection from nonparticipating sources. Retrospective organization changes no current collection.

Active background escalation beyond previously consented passive/opportunistic baseline remains an unresolved explicit scope choice. The new request establishes adaptation as the target, but does not choose its precise background authorization envelope. CONTROL retention, organization overlap/removal, map limits and the earlier unrelated D/P proposals are not approved by this report.

## 2. Current-code evidence

Read `TASK_CONTEXT.md`, `ADAPTIVE_COLLECTION_AND_SESSIONS.md` in full, prior whole-product proposals and current code below. MAIN remains clean `dev/v10` at `13f1a99871b59d29b3d059f374fc415490da6943`; planning checkout HEAD was `3f3f5c7a2bae6d1d56cd283b658cf1784cf1c5a5` when inspected. Earlier candidate implementation/drafts remain unassembled/unvalidated. All code anchors here refer to MAIN.

| Exact source | Current behavior / implication |
| --- | --- |
| `data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/SourceCollectionSettings.kt:3–7,16–23` | **Four** enum entries: OFF and three enabled frequencies. OFF must not become one of the requested five balance levels. Numeric codes are compatibility data, not safe UI ordinal indexes. |
| `data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/SourcePolicy.kt:10–17,53–109,150–170` | Policy separates purpose consent and persistence; capture enabled/QoS invariants and revisioned updates already exist. A cosmetic unified row cannot flatten or recreate consent. |
| `app/src/main/java/com/adsamcik/tracker/app/settings/TrackingSettingsViewModel.kt:351–396` | Applying presets currently replaces all six source bits and frequency values. The new balance selector must stop doing this; changing balance cannot add Pressure or drop Steps/radios. |
| `app/src/main/java/com/adsamcik/tracker/app/settings/data/TrackingPolicyPreset.kt:28–97` versus `data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/TrackingPreset.kt:16–51` | Setup and settings preset definitions differ, e.g. Balanced Location 20m/10s/50m versus10m/2s/50m. Migration cannot infer the actual old plan from display name alone. |
| `app/src/main/java/com/adsamcik/tracker/app/onboarding/ui/SetupViewModel.kt:268–310` | Setup filters intent by availability and enables preset Pressure without a visible Pressure choice. Existing P1 repair is necessary; no hidden source from new balance defaults. |
| `app/src/main/java/com/adsamcik/tracker/app/settings/ui/TrackingPresetSelector.kt:49–108,135–157` | Existing compact selected-card/Compare options and accessible radio group can host five choices without a new screen or dense slider. |
| `app/src/main/java/com/adsamcik/tracker/app/settings/tracking/TrackingSettingsScreen.kt:367–376,387–425,444–481,487–524,648–718` | Existing balance, simple/advanced source controls, detailed filters and technical status shells can be reused. Replace duplicate session/ambient lists with one source-row family; preserve advanced limits and status. |
| `app/src/main/java/com/adsamcik/tracker/app/settings/tracking/SourcePurposeSettings.kt:96–202` | Separate ambient switch intent and operational readiness are already distinguished; preserve their consent semantics in the combined row. |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/control/CollectionMotionController.kt:36–85,105–182,214–249` | Current motion fusion is tied to active logical/service session and accepts Activity, Steps, Location, Cell and Pressure candidates. No Wi-Fi handler; retrospective/baseline input semantics cannot be claimed implemented. |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/control/CollectionMotionPolicy.kt:4–21,51–69` | Real motion states are distinct from balance. Compatibility `unrestricted()` uses MOVING/100 with NO_ACTIVE_SESSION; UI must not expose that as an observed movement/confidence claim. |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/SourcePlanResolver.kt:53–158,171–220` | Central constraints, direct demand floors, policy ceilings, thermal/power handling and motion adaptation exist. Motion reduction is currently reported as POWER_SAVER at145–151; user-facing reasons must distinguish it from Android Battery Saver. |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/policy/BatteryAwarePolicy.kt:53–73` | Another battery-tier path exists. Runtime must reconcile decisions centrally; UI must not show one proposed plan while another path overrides it. |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/battery/BatteryImpactEstimate.kt:3–45`; `app/src/main/res/values/strings.xml:279–287,353–364` | Estimated qualitative impact and measured device drain are already differentiated. Existing preset copy promises all/reduced sensors and route detail; replace it. Five priority levels must not be presented as calibrated battery percentages. |
| `data/preferences/src/test/java/com/adsamcik/tracker/shared/preferences/tracking/RoomSourcePolicyRepositoryTest.kt:65,107,161,451` | Existing authored migration, purpose-consent and QoS-without-consent-rotation cases are relevant test-extension homes; inspected, not executed. |

## 3. Recommended five levels

Settings heading: **Power and detail**. Helper: **“Choose the usual balance. During a live session, Tracker favors detail within your source limits.”** Compact selected card plus the existing Compare options disclosure; expanded choices are accessible radios ordered1→5. No sixth Custom balance step. Custom per-source settings appear as subordinate “Source overrides”/“Custom limits” status.

| Step | Proposed label | One-line description | Intended adaptation preference |
| --- | --- | --- | --- |
| **1** | **Power first** | “Favor lower power use; observations may be less frequent or arrive later.” | Most conservative useful authorized collection; favor supported batching/opportunistic delivery and reduce redundant work; never equate silence with stillness or turn selected sources off merely as a preset. |
| **2** | **Favor power** | “Save power while responding sooner when useful changes are detected.” | Quicker useful-change response than1, with restrained update/refresh effort; missing hints remain unknown. |
| **3** | **Balanced** | “Balance useful detail, timely updates and power use.” | Middle priority for normal collection; proposed new-user default, pending approval. |
| **4** | **Favor detail** | “Favor timely, detailed observations while reducing redundant work.” | Quality-oriented collection and prompt recovery within allowed mechanisms; recommended minimum live-session target. |
| **5** | **Detail first** | “Prioritize the most useful detail available, with greater potential power use.” | Strongest authorized quality preference; still adapt redundant work and respect physical/OS/privacy limits. Never imply continuous GPS, fastest radio scans or perfect accuracy. |

These symmetric labels describe priority instead of promising maximum savings or achieved accuracy. Do not invent battery-per-hour or samples-per-second claims to make the intermediate levels look different. The proposed labels are a single recommendation, not an additional undecided menu of copy options.

Runtime supplies the concrete initial source-profile table and named timing constants for the same consolidated approval proposal. Some adjacent levels may resolve to the same physical mechanism for a source because Android/provider capabilities are discrete. The overall effort envelope can differ in latency, batching, retry/refresh budget or quality strategy without inventing five separate radio APIs. Approve that bounded behavior envelope once together with this product design; routine implementation of its specified constants does not require another permission loop. Measured tuning outside the approved envelope remains a new decision. This product report does not invent a second competing numeric table.

| Source | Honest meaning of moving toward savings | Honest meaning of moving toward detail |
| --- | --- | --- |
| Location | Lower authorized acquisition effort and more tolerated batching; sparse passive baseline remains sparse until expansion is separately approved | More timely/useful fixes and bounded attempts to meet authorized quality; does not grant precise access, produce GPS indoors, or create a route from absent points |
| Steps | Favor supported continuity and batching rather than rapid display refresh | Faster permitted reporting, possibly the already-supported live counter alongside continuity; never multiply real steps or require Location |
| Wi-Fi | Favor permitted broadcasts/opportunistic observations and lower refresh effort | More timely permitted finite refresh attempts where legal/useful; no promised scan cadence and no inferred distinct AP count from aggregate facts |
| Cell | Favor change callbacks and lower refresh effort | More timely permitted observation/refresh where supported; no guaranteed modem update rate or movement proof from a handover |
| Captured Activity | Favor supported transitions/batching when its existing capture contract allows | More timely permitted classifications; no conflation with separately consented automatic CONTROL or user activity labels |
| Pressure | Favor permitted batching/lower useful continuous effort | More timely retained pressure detail within capability/thermal bounds; still hPa, not inferred altitude or a newly enabled sensor |

## 4. Baseline, live focus and explicit limits

Recommended rule, agreed with Runtime for the proposal:

1. Compute each source's baseline from the overall selected level, or its explicit per-source baseline override.
2. While a real live session includes that source, request `max(baseline, 4)` as its temporary **desired** detail target. A selected5 stays5. A baseline-only source continues its baseline even while other sources participate in a live session.
3. Resolve that target through the actual authorized envelope, direct useful-data requirements, existing hard user caps, permissions, platform/legal host constraints and device safety. The target is not a claim about applied acquisition or successful saved observations.
4. Reduce wasted work using fresh compatible hints while preserving a usable wake/recovery path and the session's useful quality floor. A stationary live session can batch safe unchanged data, but must not silently miss departure because the UI still says it is favoring detail. Unknown/conflicting/stale motion is not stationary.
5. Ending the last applicable live demand returns to the authorized baseline; if baseline is disabled, collection stops for that source. Existing pause/automatic start controls remain meaningful and must be reconciled, not removed. Adding/editing past organization produces no live focus or permission request.

The session quality target is **soft**: if level4 cannot be achieved, preserve the useful authorized working plan and show the specific limitation. Do not translate the preference into an impossible hard floor that disables a healthy baseline source. Hard functional demands and legal/privacy limits remain real, but inability to improve detail is not equivalent to inability to collect. This needs a precise runtime distinction because SourcePlanResolver:90–92 currently disables a plan on an unsatisfied direct floor.

Battery changes should lower effort within the source's useful floor before removing it. The legacy battery-tier path can cap collection to AMBIENT at low battery (`BatteryAwarePolicy.kt:71–72`); its resulting GPS behavior must be reconciled with the central source resolver. No unexplained disappearance of the sole selected Location source during a live session. If a genuine safety/platform constraint prevents usable collection, show “Location paused: …” with the actual reason and preserve the session's honest gap; a quality preference is not a guarantee that hardware can continue. Charging may relax an approved power constraint but never expands consent or a hard user ceiling.

`max(level,4)` is a concrete recommendation requiring approval. Alternatives: +1 level respects the low-power preference more closely but leaves level1 sessions below the user's stated quality-first intention; a second always-visible session slider adds configuration overhead. Recommend one understandable temporary behavior and advanced source limits, not a second top-level slider.

**Advanced per-source control:** reuse each source's Details: “Usual balance: Use overall setting” or one of the same five choices. This changes that source's baseline priority, not its membership or consent. Existing explicit numeric filters/quality ceilings stay separate under “Limits” and remain enforced. A low baseline override is not secretly a hard maximum; the live-session helper must make that distinction clear. Preserve migrated settings as actual limits where current policy treated them as ceilings. Add new numeric ceiling controls only if the concrete source table needs them and they are approved; no matrix of five new sliders per source.

Changing the balance during a live session changes the saved baseline and recomputes desired focus under the same rule. The row can say “Balanced normally · Favoring detail for this session.” Do not imply changing baseline1 immediately defeats the documented live focus; if the user needs a strict limit, expose the existing Limits affordance and explain its effect. The consolidated initial source table and migration specify those bounds as part of the same approval.

## 5. One source list that preserves freedom

Recommend one row per source with its name, concise selection summary and relevant actual status. Tapping expands existing-style options:

- **Use in live sessions**: source participation in live capture/fidelity demand; preserves existing capture eligibility.
- **Collect in the background**: separately consented baseline, including while another live session is active; its helper explicitly says it continues until disabled. It does not mean “outside sessions only.” Hide unsupported-purpose switches with a short explanation in Details rather than invent support.
- **Usual balance** and existing **Limits**: advanced disclosure, not repeated primary controls.

Row summaries can be **“Off”**, **“Live sessions”**, **“Background”**, or **“Background and live sessions”**. These are summaries of two independent choices, not four new collection engines or unconditional readiness claims. For a background-only source, starting a session does not increase its effort or convert its records to captured-session authority; its qualified observations may still appear in the organizational interval with original provenance.

Do not introduce a master source switch that silently sets both consents. Both choices off leaves historical data accessible according to retained authority. Changing balance never changes either choice. New-user source defaults and default-off background permission remain separately specified; selecting level3 does not grant them. Existing Activity/Pressure remain in the existing source settings paths; captured Activity is distinct from automatic control evidence, and Pressure does not become altitude.

An automatic start creates the same temporary live fidelity demand only after authorized trigger/host checks. “Automatic start” remains a separate behavior control with its actual prerequisites, not a seventh source or a hidden Activity switch. A foreground feature such as My location or a game can request its own explicitly permitted transient demand, without enrolling that source in tracked history.

## 6. Inputs the user should be able to trust

Runtime owns the exact arbitration/hysteresis/freshness implementation. This table specifies product meaning and limits, including all relevant input families instead of exposing an internal “brain” dashboard.

| Input family | What it may influence | Truth/privacy limit and normal feedback |
| --- | --- | --- |
| Explicit settings | Source/purpose selections, overall/per-source balance, precise/approximate permission, hard cadence/quality filters, automatic intent | Highest user intent; never silently enable another source. Show the selected preference independently from capability. |
| Live session / explicit feature need | Temporary useful-data/latency preference, organization interval; real game/current-position demand | A past label or open History screen is not a live demand. Feature demand has its own lifetime and persistence boundary. |
| Activity recognition/transitions | Fresh positive movement/stillness hints under existing authorized capture/control | Do not enable Activity merely to optimize another source; no current-life inference from a retrospective/imported activity label. |
| Steps | Recent positive deltas support movement; source continuity and batch age qualify them | Zero/missing/batched Steps alone do not prove stationary; delayed history is not fresh wake evidence. |
| Location | Qualified speed/displacement, accuracy/fix age and acquisition yield | Missing fixes are not stationary. Bad accuracy can justify bounded recovery/backoff, not endless high-power retry. No Location demand from a map-enrichment query. |
| Cell observations | Fresh changes may corroborate ongoing movement and provider availability | Handover can happen without movement; static/missing radio is not stillness. No extra retained subscriber/tower identity for adaptation. |
| Wi-Fi observations | Potential weak environment-change hint from already permitted fresh observations | Not implemented in current motion handler; proposal must fit identity-free facts or approved transient privacy contract. No extra scan just to manufacture hints, no movement assertion from scan count alone. |
| Pressure | Fresh change can corroborate other motion/context | Weather/elevator/noise ambiguity; not reliable stationary/movement/elevation proof alone. No hidden Pressure enablement. |
| Hint quality/time | Observation age, confidence/uncertainty, conflict, source origin, boot/clock validity, authorized scope | Stale/conflicting evidence yields unknown, not “You are stationary”; precise confidence numbers are not user-facing proof. |
| Battery and charging | Approved power preference adjustments, finite effort/retry budgets | Charging need not mean stationary; low battery need not mean source off. No silent data-source changes. Show a limitation only if it changes the requested result. |
| Thermal / system power restrictions | Mandatory protection, legal request modes and delayed deliveries | Live quality preference cannot override safety/OS limits. “Limited by device temperature” is different from “Lower detail while stationary.” |
| Permissions / hardware / services | Sensor/SIM/radio/provider availability, foreground/background host legality, permission precision | Missing one source leaves eligible siblings working. Offer only effective repairs; contained/unimplemented capability is not a permission problem. |
| App/device lifecycle | Foreground visibility, screen state, Doze, process/reboot/resume, user stop | Not movement proof. Screen-off does not cancel user live-session intent; legal execution and continuity still constrain it. |
| Pipeline health / delivered value | Freshness, accepted/rejected observations, gap/continuity state, queue/storage pressure, repeated provider failures, useful-data yield | No “Recording” merely from a registration; no unlimited retry or fabricated healthy zero. Preserve durable error/gap meaning. |
| Existing cost estimates | Qualitative relative effort and validated evidence where available | Generic estimate is not measured Tracker battery drain; no new telemetry/learning infrastructure or personalized battery promise. |

Use cheap compatible signals already available. Positive movement can justify prompt response; confident stationary evidence can reduce redundant work with stable transitions. A missing source reduces available hints, not the independence of every other source. No setting requires users to consent to all sensors for the adaptive system to work. A narrow hint may be useful without being strong enough for a persisted detected activity or reward.

## 7. Earned feedback and consistent meaning

Display three concepts separately only when they help: **saved preference**, **current desired focus**, and **actual source state**. Default screen needs selected balance and actual source cards, not three numerical levels on every row.

| Surface | Recommended copy/layout | Must not imply |
| --- | --- | --- |
| Settings collapsed | “Balanced” / source participation summaries / existing Compare options | That Balanced changes selected sensors or is measured battery use |
| Live header | “Session active · Prioritizing detail” plus elapsed time and existing Stop | That every source achieves the requested precision/rate |
| Meaningful exception | “Location limited by approximate access” + actual repair; “Wi-Fi: waiting for a new observation”; “Updates delayed by Android” if known | Missing callbacks prove no networks, a device limit is user error, or a saved switch means recording |
| Temporary motion adaptation | Details: “Reducing redundant updates while stationary” only with qualified current evidence and a preserved recovery path | App knows stationary because no fix/scan arrived; “Android Battery Saver” when actual cause was motion |
| Source status Details | Usual balance, live request if relevant, actual mechanism/limitation, last observation and last saved time separately | Preview equals applied provider state; last write time is measurement freshness |
| Stop / background | Session ends; compact confirmation/status says background collection continues only when actually authorized | Stop revokes unrelated baseline or stops every separately requested feature |
| History / organized session | Actual date/window, observed facts and coverage; “Added later” when relevant | Editing a group upgrades original fidelity, consent, detected Activity or native authority |
| Export/share | Actual selection and format includes/omits; original source purpose and quality preserved | Current balance describes old data; a requested high-detail session achieved it; organization silently widens file scope |
| Widget / notification | Active session and useful saved source fact/limitation; preserve quick Stop and existing preferences | Baseline collection is a live session, or a native radio-only session has distance0 because Location absent |

Data peer specifies three independent truths behind this compact UI: desired policy, acknowledged applied plan, and fresh qualified persisted evidence. “Ready” requires eligible applied capability; “Waiting for data” does not imply a saved observation; “Recording” must be backed by appropriate current saved evidence and its observation time. A successful request or callback alone is not history. Preserve source-specific continuity/coverage rather than inventing a universal heartbeat.

No permanent motion badge, confidence score or repeated technical warning is proposed. Exception feedback belongs next to the affected result/action. Explain how collection adapted only on meaningful change or in Details; do not announce every callback or poll for cosmetic status. History/export need actual quality/coverage and original provenance, not a new verbose dump of every ephemeral hint. Data owns the minimal durable decision trace necessary to reconstruct significant acquisition changes.

Retrospective session creation and edits can change the visible grouping and range selection, not collection history. Counts and rewards deduplicate underlying evidence across overlaps; whole-entry export enlargement remains explicit. The previous Between sessions-only product card is superseded as the main model: one collection summary can show continuing baseline plus a distinct live session, without maintaining two competing source lists or implying baseline cannot exist inside a session.

## 8. Migration and failure behavior

1. Read authoritative stored source policy, exact numeric settings, purpose consent and revisions. Never call existing `applyPreset` to migrate. Never infer source selection from a preset title or its defaults.
2. Map old enabled frequency labels1/2/3 to descriptive five-level anchors1/3/5 only where the preserved physical profile actually matches. This mapping is not permission to apply newly tuned profiles. Preserve all explicit per-source settings and hard limits; a nonmatching legacy configuration remains “Custom source settings” beneath the five-step control until the user chooses a new envelope.
3. Preserve baseline/capture consent pairs and epochs exactly; baseline-only remains active during live sessions as before. QoS changes use existing policy revision semantics without rotating or inventing source consent. Invalid/missing policy remains unavailable and recoverable, not default-on migration.
4. Fresh setup proposes level3 only; unchosen sources including Pressure remain off, separately consented background remains off. Existing users do not gain background collection, active baseline escalation or a larger ceiling by upgrading.
5. Source baseline overrides remain when the global level changes. Provide a targeted “Use overall setting” in source Details; no bulk reset of source masks/consent. A future “Reset overrides” shortcut is optional and not necessary here.
6. Apply committed preference changes through one revisioned path and show pending/saved/failure honestly. Keep user edits on retry and reconcile concurrent updates. A balance preview cannot be shown as effective until acknowledged by actual planning/application status.

There is a concrete migration tension: existing SourceQos is a ceiling while the new five-level concept includes a baseline plus temporary live request. Conservatively preserve existing physical limits until the user adopts the approved adaptive envelope. Runtime/Data's consolidated design defines new-user ceilings and conversion together; silent reinterpretation of a low stored ceiling as a raiseable baseline is unacceptable. Data recommends separate balance/profile-version metadata rather than reinterpreting old QoS stable codes; carry that exact compatibility choice in the same approval.

## 9. Decisions to batch for approval

| Decision | Recommendation | Meaningful alternative |
| --- | --- | --- |
| Five labels/layout/default | Table3 symmetric priority labels, selected card + Compare options, new-user Balanced3 | A five-stop slider is compact but less readable/accessibly descriptive; keep the existing radio-card pattern. No numeric battery claims. |
| Live-session preference | Desired `max(source baseline,4)` within explicit ceilings/safety and revert on end | +1 level; smaller power increase but does not make low-level sessions quality-first. |
| Source override semantics | Baseline override under source Details; existing hard limits separate and preserved | Ceiling-only overrides are simpler but cannot prioritize one source above the overall baseline without raising the others. |
| One source row | Live participation + separately consented background options within that row, summary outside | An explicit four-choice mode menu encodes the same pair but must explain consent; do not use misleading outside-sessions-only mode. |
| Legacy migration | Preserve exact behavior/caps/consent and show custom settings until new envelope is chosen | Force approximate remapping changes behavior and is not recommended. |
| Background escalation | Keep previous passive/opportunistic authorization unless explicit bounded active adaptation is approved | Approve source-specific active baseline envelopes with clear device/permission impact; five levels alone do not resolve this choice. |

Bundle Runtime's concrete initial source table, named numeric constraints and hint freshness/hysteresis with Data's migration/status contract into this one approvable five-level design. Do not postpone those essentials into unspecified further permission rounds. Organization storage/actions/export and expanded background acquisition are distinct previously unresolved behavior choices and must be made explicit in the consolidated proposal. The user has already chosen five steps and the live-quality preference: do not ask whether to have three steps or make sessions mere labels again.

## 10. Bounded acceptance and next slices

After approval, fold into existing S0/S3/S6/S8/S9; do not start another implementation program. Runtime owns five-level profiles and central arbitration; Data owns revision/provenance/migration; Product owns existing setup/settings/status/organization consumers. One owner edits shared preference schemas/resources at a time.

Author focused cases, execute only after complete assembly freeze:

- All five levels × every supported source subset and empty: masks/consents unchanged, siblings independent, meaningful source-specific plan or limitation. Adjacent same-mechanism outcomes are valid and honestly described.
- Each baseline1–5 with live start/end: target rule, level5 preserved, explicit hard cap/thermal/permission honored, background-only source continues without a boost, stop releases only the live demand. Retrospective session creation/edit has no runtime call.
- Motion unknown/conflicting/stale/batched/boot mismatch: no false stationary/recording; positive movement recovers timely under approved limits; disabled Activity/Location does not block Steps/radio independence.
- Changing balance/override while live: saved preference and current demand visible accurately, exact authority revision, no source enablement/consent mutation; failure/retry preserves edits and data.
- Legacy PowerSave/Balanced/HighAccuracy/custom and differing setup profiles: exact masks/numeric caps/consent retained, no hidden Pressure or baseline enrollment, no new live-ceiling escalation without approved migration.
- History/export/widget agrees with actual data rather than selected level: sparse/no/partial context stays sparse/no/partial, newly added organization cannot create movement quality or duplicate rewards, file scope/metadata remain exact.
- Five-choice accessibility: one radio action per choice, logical order/selected state, localized descriptions/units, large-font wrapping; relative power labels do not claim calibrated drain. Check rendering and TalkBack only in the eventual authorized batch.

Static recommendations do not prove five measurable power bands, usable platform rates, battery savings, continuous background execution or on-device polish. Existing authored tests and candidate slices remain IMPLEMENTED_UNVALIDATED. Final source table/platform/device evidence and the requested fresh assembled-code review remain required.
