# Delayed passive Location across unrelated settings

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Three narrow source/test paths; no gates, provider activation or schema change.

Complete approved independent source authorization for a fix measured before an unrelated settings revision while the same Location consent, data epoch, retention policy and physical request remain valid. The receiver precheck and mandatory WAL transaction use the existing observed-time retention lookup for the exact old grant. They independently retain current eligible Location consent, active authentic retention, identical consent/data epoch/policy ID and current data-retention floor. Off, changed consent, revoked/unknown retention and epoch/deletion fences remain. Do not compare an old observation's approval with the newer current approval merely because another source changed settings.

No new authority interface, owner, table or storage is added. LocationWalQualificationAdapter already performs the same historical exact grant lookup and remains unchanged. Two authored actual Room cases reuse the passive fixture: unrelated Wi-Fi quality rotation with an old fix admitted after the rotation, and missing old approval after receiver precheck rejected by the WAL transaction despite a newer active grant. Existing Off/new-consent and retention-revoke/epoch cases remain unchanged. All are NOT_RUN.

This slice concerns delayed pre-change fixes, not a policy redesign for fixes measured after a newer retention approval or the complete runtime transition matrix. Physical providers, full Location lifecycle and all 25 complete chains remain pending. Backup remains deferred.
