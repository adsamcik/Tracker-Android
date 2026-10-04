# Existing radio request limits: source trace

Phase: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. No tests or providers ran.
Input: `1056464a1376e3574f3a744a6eaf5e3402accaf7`; exact source hashes are in
`RADIO_REQUEST_LIMIT_SOURCE_TRACE_INPUT.json`. The four inspected working files
match their committed input.

## Preserve the existing implementation

`a2a4cddc91` already added `RadioActiveRequestAllowance`: one synchronous local
preference commit spends a session's optional Wi-Fi or Cell request before the
Android call. Recreating the object or changing that session's configuration
does not refund its request. The sources have separate keys. Passive callbacks
do not consume this allowance. Ordinary recorded-data clearing deletes source
runtime database rows but does not delete these operational preferences.

Both runtime actors inspect the saved allowance when scheduling, reserve it
under their existing provider-operation mutex immediately before the Android
call, and recheck the exact registration and plan after the suspended reservation.
Cell also retains exact request/SIM/timeout ownership. These are source traces,
not proof of real Android scheduling or battery behavior.

The September 27 proposed Room checkpoint is not required merely to add durable
request limits: this later preference implementation already exists. Do not add
a second ledger or move the state without a demonstrated need and user approval.
The October 4 optional storage question was asked before this existing committed
implementation was rediscovered; it is not permission to overwrite it wholesale.

## Concrete gap found in this input

The saved keys contain the session, boot, elapsed time and wall time of the last
request, but not its original next-allowed time or interval. `decision` computes
the remaining cooldown from the **new caller's** `minimumIntervalMs`. For example,
a 300-second Cell request followed one second later by another session at a
120-second setting gets a 120-second cooldown, shortening the original limit.
The same recomputation applies across reboot. The approved carried cooldown is
therefore not fully implemented.

Keep the existing ledger and inspect the smallest repair that saves the original
limit with the reservation. It must preserve one request per actual live session,
source independence, cancellation and storage-failure behavior, and truthful
recovery when saved values are incomplete. Do not invent another consent or
observation identity for this operational limit.

## Focused authorship still required

The original cooldown and incomplete-state repair is now selected and independently
source-reviewed in `RADIO_ORIGINAL_COOLDOWN_V2_SELECTED`. Eight additional ledger
cases are authored, NOT_RUN. Full runtime/provider evidence remains open; the
original input and the first selection are preserved. The list below records
the contract traced for that repair and the still-required composed runtime work.

- Earlier cooldown cannot be shortened by a later session's quality or mode.
- Reconfiguration, physical replacement and reboot preserve the spent session.
- Incomplete or wrongly typed stored values cannot grant a fresh request.
- Storage failure grants no Android request while passive delivery continues.
- Verify actual runtime reservation and callback ownership, including a newer
  registration arriving while persistence suspends.

The three existing preference test methods are authored source only. They cover
same-session recreation, a subsequent session's unchanged interval and reboot;
they do not establish the differing-interval contract above. The complete
source-power prerequisite, independent review and device evidence remain open.
