# Activity original-clock V1 source finding

IMPLEMENTATION_ONLY; all three methods NOT_RUN. No execution gates, production edits, Git
mutation, provider/device activation or recursive delegation. Exact V1 FILES
54ad15abebb151ddb023e4cbc55397287b182fa948f275631ce08430610ef0ef and patch
84e5feb14580e912b8c3770d31ee43530eef3f0ccdc2c963c63537084e92512a are preserved.

The new positive's whole Admitted comparisons at ActivityCapturedWalAdmissionAdapterTest.kt:79-81
include ActivityCaptureAcquisitionAuthority.historicalConfiguration. The actual class
ActivityHistoricalAcquisitionConfiguration (ActivityCapturedObservationAdmission.kt:43-104)
is a plain class without value equality. Each admit creates a new instance, so equal original
recording values do not make the entire Admitted data class equal. Compare the original
observation/window/delivery/capture-authority and exposed configuration values directly;
do not change production object equality to make a source fixture pass.

No additional concrete production blocker found in the bounded clock/generation change.
V1 was not accepted. V2 narrow semantic assertion repair is reviewed separately.
