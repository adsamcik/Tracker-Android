# Source selection boundary

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Source authored against the exact
committed input in FILES.json; original working bytes preserved in WORKING_BEFORE.
No live source, protected paths or Git changed by authoring this packet. No tests,
compilation, schema, device/provider, battery or CI gates run. Independent review
is pending. This is a bounded slice, not whole-chain acceptance.

Five new layer cases: firstload dependency limit withvalidlegacy, thrown context read clearsolddeviceoverlay, validcontext/failedlegacy, cancellationnofallback, bothfailedremainfailure. Existingcase nowretainslegacy fortypedlimit/storagefail. Tests NOT_RUN.
