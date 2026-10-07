# Approved radio detail simplification

Phase: **IMPLEMENTATION_ONLY**. Selection: **IMPLEMENTED_UNVALIDATED**. Tests: **NOT_RUN**. Independent review: **ACCEPTED_STATIC_ONLY**, see SOURCE_REVIEW.md.

Exact base: `348cf577da1d2bfa7dfc98d07b2d91bbe158bd17`.

## Direct human approval

Question: "The radio detail screens still show internal text such as 'authenticated capture' and 'portable membership.' I recommend removing that technical card and the generic Location explanation. Keep the title, saved readings and useful coverage information, with a simple 'Imported' label when relevant."

Answer: "Use the simpler details screen (Recommended)".

## Selected source

- Wi-Fi and Cell retain their existing title, date range, coverage/status, summaries and all saved network/tower rows. Their technical authority card and generic Location-exclusion footer are removed. Imported records show just **Imported** using the existing secondary text style.
- The internal radio overview argument used only to build that removed card is deleted. Both production callers and all existing Compose call sites are updated. Data selection, opaque selectors, source readers and actions remain unchanged; typed history entries and the existing presenter still validate the exact selected data.
- The shared authority helper is preserved for Activity. Only now-unused radio origin mapping and footer code are deleted. Mixed global `strings.xml` files are not selected or modified.
- There was no concise translated Imported label in this feature or its core resource dependencies. One `radio_detail_imported` resource is added to the 40 existing isolated `radio_detail_strings.xml` resource files (default plus every existing locale). No new resource folder, dependency or copy beyond the approved label is added.
- Lazy rows, absence of a twenty-row cap, current local reading timestamps, missing-field omission, existing measurements and summaries are unchanged. No tracking, permission, persistence, map, file-format or export behavior changes.

## Authored evidence

Three existing Compose methods gain assertions for the simpler native/deleted-imported view. Three new methods retain actual imported network/tower readings alongside the concise label and protect the unchanged Activity caller. Negative radio-copy assertions scan the lazy list from its start, so a removed card/footer cannot evade the check merely by being off-screen. Existing native twenty-five-row, local-date and missing-field cases remain intact except for the removed unused call argument.

This source authorship does not claim a passing Compose check, rendered device UX, accessibility evaluation or reviewed translation quality. All test methods remain NOT_RUN. No Gradle, compilation, tests, lint, Detekt, Room/schema, diff-check, CI, Android provider/device/emulator or integration commands ran.

## Preservation

`BASE` contains exact Git bytes. `WORKING_BEFORE` contains exact shared working bytes. `SELECTED` contains the scoped source with LF endings. `FILES.json` pins each source; `DEPENDENCIES.json` pins the module, callers and data contracts. The root owns independent review, exact staging and Git. No live source, Git index, protected file or preserved draft was changed.
