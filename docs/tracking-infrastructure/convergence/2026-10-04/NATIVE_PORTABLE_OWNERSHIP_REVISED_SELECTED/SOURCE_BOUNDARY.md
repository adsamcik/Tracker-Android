# Revised ownership lookup selection

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

The original rejected freeze remains unchanged. This revision changes only the selected
SourceSessionDaoTest hunk: it excludes the unrelated raw-lifecycle caller authority case.
All 13 production paths and the 15 ownership cases remain exactly the original frozen selection.
Other actual working files may contain separately owned later companions; their selection
comes from the original frozen patch and pins, not broad current working-file staging.

N1 is selected explicitly in RAW_LIFECYCLE_CALLER_PROJECTION_SELECTED with its existing
21-field production projection and that one Room test. N2 exact Activity comparison and
the actual migration fixture addition have separate coherent packets. Do not claim
the revised lookup alone closes those dependent review findings.

No source changed while authoring this selection; no test, build, schema/gate, device/provider
execution or Git mutation occurred. Test cases remain authored and unexecuted.
