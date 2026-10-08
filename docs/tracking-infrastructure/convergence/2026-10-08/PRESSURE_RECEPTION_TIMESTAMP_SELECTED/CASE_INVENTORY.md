# Focused source cases

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Both new methods are NOT_RUN.

1. `delayed Pressure callback keeps its original reception wall time through later close`: real runtime callback receives a six-second-old sensor measurement; after the elapsed clock advances three seconds, the runtime closes and sends the original wall reception and existing measured-time mapping to its sink. Original delivery identity remains checked.
2. `target closure retains the completed windows last reception rather than the triggering callback`: a later callback closes a previous window; the candidate keeps that completed window's last original reception, not the later triggering callback/checkpoint time. Sample count and original observed end remain unchanged.

Existing test methods are byte-preserved. Both use the existing recording sink and mocked Android SensorManager/registration fixture. They reach real callback reception, window accumulation, candidate creation, actor and atomic sink submission; the sink returns fixture admission. They do not execute Room admission, projection, retained reader or an actual pressure sensor. The unchanged production caller trace is pinned separately. No claim of full provider-to-product proof is made.
