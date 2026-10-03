# Radio measured interval reader prerequisite

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Eight selected source paths,
seven newly authored Room cases; no tests, compilation, schemas, provider/device
activation, lint, CI, integration or other validation ran.

The user approved one measured-date rule, keeping actual time windows and saved
reading identities. This slice selects the previously held observation-window
queries and reader dispatch, and repairs native bounds that were clipped to the
latest reading's midnight. It is separate from the six-path source-projector repair.

## Selected behavior

- One small pure helper in core/base recovers the earliest possible time from the
  original saved elapsed interval, existing coverage and uncertainty. It does not
  write an entity, checksum, ID, semantic revision or structural-day metadata.
  Fractional elapsed spans round the lower bound outward using quotient plus
  remainder, without overflowing or changing individual measurement timestamps.
  Unsupported intervals before the UTC epoch remain unavailable.
- Native observation-window discovery uses that same lower-bound calculation;
  imported discovery uses its saved portable bounds. A candidate is not a new
  reading. Existing bounded source qualification and actual child timestamps
  remain necessary before display.
- Both source repositories keep their existing ordinary read behavior and expose
  the held internal observation-window path for displayed-day History. Native API
  facts and portable conversion use recovered bounds, so earlier children fit
  the original measured span. Counts and lists are not split or invented.
- Retained native qualification checks recovered starts for the effective fact,
  aggregate owner and each correction's retention approval. A midnight clamp
  cannot expose pre-retention measurements. Epoch/erase filtering precedes interval
  recovery; an invalid row adds its own typed gap without hiding qualified siblings
  from optional context consumers. Ordinary strict source reads retain their
  existing refusal behavior. Historical registration, consent and checksum checks
  remain unchanged.

The seven new Room scenarios cover old-clamped identity/day preservation and
portable conversion, earlier-day discovery, fractional midnight rounding,
retention suppression, unsupported pre-epoch intervals and independent valid
siblings beside unsupported current/previous-epoch intervals. The two sibling
cases use the existing real Room authority and production pairing fixture. Two
existing bounds cases were adapted to distinguish fractional spans and exact
zero-span measurements. They use stored native entities/DAOs, not activated providers.
Their source does not establish full History/UI grouping,
export-file round trips or passing results.

## Remaining boundaries

Whole R11 History composition, imported overlap membership, selected-only radio
file representation, optional complete-session export and actual product callers
are excluded. Legacy summary-only data retains its actual aggregate window; this
slice does not infer individual measurements or alter delivered groups.

The source manifest separates actual byte hashes from LF-canonical selection
hashes. Four mixed files contain only the approved reader/query foundations plus
these bound repairs; all other held paths remain untouched. The immutable baseline
is `a2a5e5131162505d19ea614f95e2a55707953a41`; snapshots are local selection aids.
The manifest and patch reconstruct the intended eight-path input. All 25 assembly
chains, convergence and device evidence remain open.
