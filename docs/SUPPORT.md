# Tracker support and local diagnostics

Tracker has no support telemetry, analytics service, or remote crash-reporting endpoint. Tracebox
records bounded crash, ANR, process-exit, and structural diagnostic data inside Tracker's private
app storage. Nothing is uploaded automatically.

## Reporting a problem

1. Record the Tracker version and Android version. If native diagnostics show **degraded**, also
   record the device ABI shown by Android; managed diagnostics remain available.
2. Open **Settings → Debug → Crash diagnostics** and check readiness. You may enable the standard
   diagnostic policy or restore Tracker's defaults there. The requested policy persists across app
   restarts.
3. Reproduce the problem once if it is safe to do so.
4. Choose **Review diagnostics**. Read the disclosure before approving a package.
5. If you want to provide the package, choose **Save a copy** or **Share with another app** and pick
   the destination yourself. Tracker does not offer direct upload.
6. Open a [GitHub issue](https://github.com/adsamcik/Tracker-Android/issues) with the minimum useful
   reproduction steps. For a privacy question, contact `play@adsamcik.com` instead of attaching
   sensitive material publicly.

Do not attach a Tracker database, route export, coordinates, network names or identifiers, custom
provider URI, filename, free-form user text, or stable device/user identifier to a public issue.
Tracebox excludes those values from Tracker's diagnostic call sites, but unrelated screenshots and
manual exports can still reveal them.

## Package lifetime and deletion

Approved package bytes are bounded and short-lived. They are retired after save/share, replacement,
policy change, explicit deletion, or diagnostics-screen disposal. You cannot reuse a retired
approval.

**Delete all diagnostic data** removes Tracebox records and staging. Tracker's app-wide **Delete all
collected data** transaction first quiesces collection, removes Tracker data and export watermarks,
then requests complete Tracebox deletion. If the private handler process cannot finish immediately,
Tracker keeps a durable marker and retries on startup; it does not report the transaction complete
early.

Android cloud backup and device-to-device transfer are disabled for all Tracker app storage. A
deleted Tracebox store is therefore not restored by the platform. Copies you explicitly saved or
shared are outside Tracker's control and must be deleted at their destination.

## Release stack evidence

Release builds retain source-file and line-number attributes needed for useful managed stacks.
Tracker's release evidence binds the app source, R8 mapping, native-symbol archive, Tracebox
coordinate, and build identity. Maintainers retrace or symbolize only against an exact identity
match; mismatched symbols are left unresolved rather than guessed.

Tracebox is Apache-2.0 licensed. Notices for its pinned Crashpad, mini_chromium,
linux-syscall-support, zlib, googletest, and Chromium build-tools inputs are bundled under
**Settings → Open source licenses**.
