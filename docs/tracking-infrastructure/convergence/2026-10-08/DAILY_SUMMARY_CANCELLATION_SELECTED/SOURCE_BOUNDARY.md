# Existing daily summary cancellation

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. One focused host method NOT_RUN.

The existing getBetween reader catches every Exception and converts coroutine cancellation into a database error. Preserve cancellation explicitly before its unchanged existing storage-error handling, as required by repository structured concurrency. No API/model/storage/UI/quality/source admission semantics change. Existing real storage-failure and successful-read tests remain unchanged. The new MockK fixture checks the exact original cancellation propagates; it is not Room/device evidence. Complete all-day numerical delivery remains open.
