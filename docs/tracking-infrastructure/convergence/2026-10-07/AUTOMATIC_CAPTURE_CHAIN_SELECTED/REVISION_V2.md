# V2: bound the real apply await

The independent V1 review found that production apply can retry an invalid intent indefinitely.
The composed test now wraps only that real await in the existing five-second timeout utility.
The production call, assertions, and existing cleanup are unchanged. V1 selected source, manifest,
patch and review inputs remain under REVIEW_V1.

Static source change only. Every case remains NOT_RUN; no validation gate was executed.
