# Preserve repository source line endings

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED; tests NOT_RUN.

Existing `.gitattributes` requires automatic text LF normalization. Exact-byte
index staging bypassed Git's normal clean filter for selected CRLF artifacts.
This source-only selection restores LF for the recent reviewed task files that
contain CRLF. Every byte other than CRLF-to-LF is unchanged; authored behavior,
comments and tests remain identical. Unrelated files/drafts are excluded.

Raw reviewed artifacts and their original hashes remain available. This receipt
records their source-equivalent committed realization; no schema, build, test,
whitespace gate, provider activation, integration or runtime validation is run.
