# Collection control decisions

2026-09-27. IMPLEMENTATION_ONLY. Original user text is in TASK_CONTEXT section 35.

## Settled semantics

- Start uses shared source readiness, Room preparation and Android enqueue. One runnable requested
  source suffices; missing siblings never add demand or block valid work.
- End releases live capture/focus only, preserving background and independently controlled features.
- Automatic End offers Stop now, pause automatic starts for 30 minutes, and until charging.
- Turn off ends capture, disables background/automatic intent, preserves choices/quality/history.
  Game/My-location retain their own controls. Success follows durable off/fence acknowledgment.
- Save intent before prompting. Permission callbacks refresh actual capability and never restore
  participation. Only explicit Start/resume or background re-enable acknowledges observed user-stop.

## Pending confirmation, asked through question tool

1. Shared control API with separate Turn off actions in app/notifications/widgets/shortcuts, or
   external surfaces opening the in-app action. Placement and acknowledgment/retry wiring pending.
2. Permission repair continues only its initiating source-policy revision and durable lifecycle
   generation, or requires another explicit Start. End/Off/edit/user-stop invalidate continuation.

No answer received or inferred. AGENTS.md requires confirmation of genuinely new design contracts.
Dependent implementation remains unapplied. Bounded intent/capability and exact-current resume
corrections implement settled requirements and do not complete the controls task.
