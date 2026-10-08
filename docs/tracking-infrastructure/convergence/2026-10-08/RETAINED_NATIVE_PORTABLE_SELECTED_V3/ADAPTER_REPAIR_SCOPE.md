# Follow-up saved-WAL qualification scope

Static inspection of committed 3a3476e63e5c3bc40e2697f5e503a94cb18592ed. No follow-up production/test source authored yet. IMPLEMENTATION_ONLY / NOT_RUN. Root requested this scope note before freeing the reviewer slot.

Smallest likely production scope is the three existing Activity/Wi-Fi/Cell WAL qualification adapters; reuse the committed immutable manifest/retained generation/original Stop helpers. No changes to `RoomDurableSourceIngress`, Android callbacks, broker physical ownership, new command reservation or shared current-replacement authority are proposed.

| Existing adapter | Reached production caller | Retained data defect |
| --- | --- | --- |
| ActivityCapturedWalAdmissionAdapter | ActivityCapturedFactProjectionLane uses actual adapter admission; admitHistorical also reads the same bounded saved WAL, changing only current destination-owner requirement (:123–137) | Both branches compare saved WAL with latest run/session boot/generation (:429–435), use mutable elapsed cutoff (:469–476), and strict run-boot manifest timeline (:482). |
| WifiWalQualificationAdapter | WifiCapturedFactWriter.write:89 and WifiSessionFactProjectionLane durable drain:248 | Saved original run/WAL equality :289; current mutable cutoff :287; strict run-boot timeline :300. |
| CellWalQualificationAdapter | CellCapturedFactWriter.write:111 and CellSessionFactProjectionLane durable drain:248 | Saved original run/WAL equality :397–398; mutable cutoff :396; strict run-boot timeline :405. |

Every adapter loads exact already-admitted durable WAL. These predicates are not new physical callback permission checks. Requiring current destination writer ownership during projection remains appropriate; original saved measurement identity must be checked separately. Activity's admitHistorical flag need not be repurposed as another generic mode.

Preserve exact original manifest/source/writer, registration, authorization/demand/action, event/delivery/sequence/checksum, epoch, retention and erase checks. Preserve actual current session/run pairing, current replacement bundle validation, settlement/final admission ordinal and finite source completeness. Only original elapsed boundaries with the same original boot may be compared. Missing original Stop clock must not borrow a later operation's boot or invent a finite Activity window.

Existing source fixtures for `ActivityCapturedWalAdmissionAdapterTest`/`ActivityCapturedFactProjectionLaneTest`, `WifiWalQualificationAdapterTest` and `CellWalQualificationAdapterTest`/their projection lanes should supply real delayed durable drain coverage. Reuse actual ingress where existing fixture construction supports it; clearly mark Android/provider authority and any operation transitions that remain constructed. Required cases: original accepted receipt followed by quality/End then actual writer/lane drain saves the original reading; at/after original closing boundary is omitted; changed original authority, source consent/erase/epoch remain rejected. Do not seed an already materialized fact and claim it proves delayed drain.

Before authoring, inspect each adapter's source-local classifier and finite window endpoint construction: Activity must preserve its authenticated original terminal/stopping window, and radio may contain individual timestamps/windows plus separately eligible purpose groups. If original Stop clock is unavailable, use existing typed unavailable/partial behavior rather than silently enlarging the captured window. That is the remaining concrete clock/cutoff inspection boundary for the next assigned packet.

This is a separate production-chain repair after the held native portable slice receives independent review. No callback/registration or data-store redesign is required or authorized by this note.
