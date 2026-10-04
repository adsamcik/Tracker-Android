# Independent review — delayed passive Location policy rotation

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Review only; no source edits, Git mutation, compilation, test execution, schema checks, provider/device activation or recursive delegation. Original user requirements and settled source independence, observation-time authority, historical sibling preservation, Off/consent/retention/erase decisions were considered. This review is separate from root authorship.

## Exact input

Base: `fa340240624710a5e2c77cd8883492515f4b6b39`.

- `FILES.json`: `e31e9bf1b98ec894d796af2dcdce9f454699b3f350970bd62e0f41fee0d412fb`
- `SELECTION.patch`: `74e325915ae698556f4b985c7e177a76a088ce3d1053f2f4062e3444f61a82e4`
- `SOURCE_BOUNDARY.md`: `39dd8ccd63722c52a6a430cb66833c23c99f9fdf9d0f0934a1519a7f8a47b555`

All three supplied BASE and SELECTED source hashes match their manifest pins. The patch exactly describes the supplied BASE-to-SELECTED delta. Review used selected `DurableSourceIngress`, not its mixed working file. Its unrelated held admission changes are excluded.

## Result

**No concrete blocking finding in this exact slice.** The narrow source repair is consistent with approved behavior and introduces no owner, table, interface, consent grant or replacement authorization policy.

`SourceRegistrationRepository.passiveLocationAdmissionAt` (selected lines 295–400) still authenticates the original observed-time provider registration, bounded authorization members, exact persisted demands and sealed passive plan. New lines 334–346 use the existing `LocationPurposeDao.retentionAuthorityAt` to check the original retention grant at the fix's boot/elapsed time. Matching source-policy revision, consent, dataset epoch, retention policy ID and approval revision are required. A newer grant cannot fill a missing original grant: the DAO selects only the latest row effective at or before that observed time, including revoked rows.

The separate live fence (347–357) still requires current eligible Location ambient consent, unchanged consent epoch, authentic active retention, the original retention policy ID, current dataset epoch and the current saved retention floor. Removing latest-approval equality allows an unrelated global settings revision without granting new authority. Captured Location remains separate; only the exact ambient system-rearmable scope qualifies.

`DurableSourceIngress.hasLivePassiveLocationAuthority` (selected 533–575) repeats historical and current checks inside the mandatory WAL transaction, called with each unit's actual observed elapsed time (865–868). Existing startup/resume fencing, current DB/lifecycle epoch and floor parity, active retention-settlement rejection and point-before-floor rejection remain before insertion (592–683, 740–749). Thus a successful receiver precheck cannot bypass a subsequent Off/new-consent, retention revocation, full-clear or retention-floor change. Exact duplicate replay remains existing retained-data behavior, not new acquisition permission.

The unchanged `LocationWalQualificationAdapter` already selects the same original observed-time grant (245–255) before materialization. No future grant is borrowed and no current grant is substituted as provenance for an older fix.

## Authored cases and limits

Two new Room cases at selected `SourceRegistrationRepositoryTest` 1832–1910 reuse real registration/plan/broker/ingress storage. The quality rotation helper copies policies to revision 7, changes only Wi-Fi quality, preserves Location consent 8, and installs a later authentic approval effective at 150. The positive fix was measured at 130 and delivered at 170, so its original approval 1 is selected while latest active approval 2 remains valid. The negative builds that same pending delivery, removes approval 1 after precheck and confirms receiver and WAL reject it despite approval 2. The new ingress fixture follows the existing startup/catalog fixture and invokes the actual transactional admission method. Existing Off-after-precheck, revoked-retention, new-consent and epoch cases remain unchanged. **All cases are unexecuted.**

This accepts only delayed fixes measured before the unrelated revision. It does not establish how an old physical request handles fixes measured after the new approval, passive-to-active replacement, a complete Location/automatic source chain, provider reliability, battery behavior, dev/v10 integration or all 25 assembly prerequisites.

Metadata note: this packet's existing `FILES.json.phase` says `IMPLEMENTED_UNVALIDATED`; the source boundary correctly states implementation only. Root should separate `phase=IMPLEMENTATION_ONLY` and `status=IMPLEMENTED_UNVALIDATED` before staging; a metadata-only repin does not change this source conclusion.

## Metadata-only repin by coordinator

The reviewed three source snapshots and selection patch are unchanged. FILES.json now separates IMPLEMENTATION_ONLY phase from IMPLEMENTED_UNVALIDATED status. Original manifest: `e31e9bf1b98ec894d796af2dcdce9f454699b3f350970bd62e0f41fee0d412fb`; updated manifest: `ba2daded3629db55f12ff0351a13a563bbe1ab14c60b3d1b0e050234a1c81ef6`. This changes no reviewed source conclusion.
