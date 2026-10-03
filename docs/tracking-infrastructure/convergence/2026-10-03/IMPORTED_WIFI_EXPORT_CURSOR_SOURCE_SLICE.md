# Imported Wi-Fi export continuation

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** This is a narrow repair of
already approved complete chosen-source export, with authored unexecuted source.

The existing imported source reader orders both recent/range candidates by
`newestMemberStartTimeMs, newestMemberIdentity`. The held file exporter previously
continued using the older entry-envelope start and logical-entry identity. A
logical entry can contain a later run; after the first 100 entries, that wrong
cursor can skip valid records and still produce a successful incomplete file.

Only the last-candidate continuation in `appendImportedSessions` changes: use
the same two newest-member keys as the reader. There is no new query/API/schema,
permission or public selection policy. Date candidate parity is separate work.

`ImportedWifiExportPagingRoomTest.kt` authors one regression with 100 entries on
the first page and a 101st on the next. Their envelope starts differ from their
newest-run starts. It runs the actual exporter, Room transaction, child queries,
private spool and codec, with source-reader page evaluations supplied by an
explicit double. The resulting file must preserve all 101 original entries and
use the last member's cursor. It does not authenticate those source evaluations
through the real engine adapter or establish Hilt/provider/SAF acceptance.

The full exporter remains an unselected draft. Do not stage its other content
because this function repair is present. Independent static review and final
composition with its held definition/test are required; no gates have run.
