# Google Play release checklist

Use this checklist for the exact AAB candidate identified by the SHA-256 and `releaseId` in
`build/release-evidence/release-manifest.json`. Repository evidence and Play Console state are
different claims: never mark an external item complete from source inspection alone.

## Repository-proven candidate evidence

- [ ] `./gradlew releaseValidation --no-configuration-cache` passes from a clean commit.
- [ ] `build/release-evidence/release-manifest.json` records the non-empty AAB hash, application
  ID/version, target SDK, ABI inventory, every packaged native library, ELF alignment results, APK
  ZIP alignment results, requested sensitive permissions, foreground-service types, and the
  `specialUse` subtype.
- [ ] The structured manifest evidence still includes background location only for the documented
  user-visible route-recording behavior, and the in-app flow requests foreground location before
  background location.
- [ ] The merged manifest's foreground-service declarations match runtime use. In particular,
  every service using `specialUse` has a specific subtype and a user-visible, defensible purpose.
- [ ] The bundled privacy policy and in-app disclosure match the candidate's permission use,
  local-first storage behavior, optional export/share flows, and user-enabled MapLibre network
  access through `NetworkGateway`.

## Externally verified for this Play candidate

Record the verifier, date, and Play Console or retained evidence link for every checked item.
These facts are not repository-proven.

- [ ] **Background location:** the Play declaration, review status, requested demonstration, and
  store disclosure are complete for the uploaded candidate.
- [ ] **Foreground services:** the Play Console declarations cover every merged-manifest type.
  The `specialUse` justification accurately describes the recorded subtype and why no standard
  foreground-service type covers that user-visible operation. For the current
  `local_device_signal_collection` subtype, verify that the console copy describes a genuine
  signal-only tracking session; location and activity/step sessions use their standard `location`
  and `health` types instead.
- [ ] **Data safety:** the published form matches actual production behavior, including on-device
  tracked data, explicit export/share actions, and request data sent to user-enabled map hosts. Do
  not infer the form answers solely from permission names or the repository's local-first design.
- [ ] **Privacy policy:** the Play listing URL is public, current, and textually consistent with the
  policy bundled in this AAB.
- [ ] **Pre-launch evidence:** the report for this uploaded AAB has been reviewed for crashes,
  ANRs, permission-flow failures, accessibility findings, and native compatibility; each accepted
  exception has retained rationale.

## Release record

Capture the source commit, `releaseId`, AAB SHA-256, Play track/version code, external verifier,
verification date, and links to the declarations and pre-launch report. Uploading, signing, and
promotion remain manual and are intentionally outside repository validation.
