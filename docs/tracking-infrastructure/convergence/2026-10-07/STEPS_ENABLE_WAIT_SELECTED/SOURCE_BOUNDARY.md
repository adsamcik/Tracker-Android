# First-enable Steps publication wait

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED; tests NOT_RUN. Authorized presented
optional two-second wait, EXECUTION_DECISIONS.md. No live source or Git changes.

After a successful current Steps save, a provider-only enable may wait up to two
seconds for the existing UI availability publication before offering its existing
permission callback. Direct sensors and existing grants do not wait. This child
belongs to ViewModel lifetime and never blocks the ordered settings-save queue.
A superseding Steps edit (including advanced frequency-Off before a slow or failed
save) cancels the wait and generation recheck rejects old intent.
No permission launch API, provider probe, durable intent, settings replay or sensor
activation was added. The existing screen still checks current selection, actual
Android grant and RESUMED lifecycle immediately before launching. Timeout retains
the existing later repair button; it does not manufacture a provider result.

Ten new ViewModel cases cover publication, bounded timeout/no resave, disable,
replacement, independent settings progress, direct-sensor behavior, launch failure
and ViewModel lifecycle cancellation. Existing
screen permission guards and save-failure tests remain unchanged. Android dialogs
and provider/device scheduling are not validated by these source fixtures.
