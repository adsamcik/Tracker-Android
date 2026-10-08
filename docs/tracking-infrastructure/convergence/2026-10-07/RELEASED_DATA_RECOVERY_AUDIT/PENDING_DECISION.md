# Approved older-data preservation decision

Status: APPROVED / IMPLEMENTATION_PENDING, 2026-10-08.

The human directly answered Yes to copying original saved details into the current
database with extra fields and small older-record tables, reusing existing readings
and session labels. Missing values stay empty; incomplete old sessions remain older
records. The independently reviewed exact V2 storage proposal is
PRESERVATION_PROPOSAL.md.

Additional human direction: "migrations should leverage import export workflow so
that we can do it more cleanly and reliably." The existing one-shot database import
already uses import-job receipts. The smallest shared conversion/import seam is
being traced before implementation; no mandatory temporary export file or lossy
portable round trip is inferred. Preserve all released main12 data and unknown
developmental databases.

The earlier ASKED / UNANSWERED state is historical, retained in Git. No production
fields/backfill are declared complete by this documentation.

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Tests NOT_RUN; no validation or
provider activation/integration authorization is implied.
