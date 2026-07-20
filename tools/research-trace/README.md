# Research trace offline format

Debug research exports are AES-256-GCM envelopes containing UTF-8 NDJSON. No Android dependency is
needed to decode them. With JDK 17 or newer:

```bash
java tools/research-trace/ResearchTraceDecoder.java capture.trackertrace > capture.ndjson
```

The decoder prompts for the passphrase. For non-interactive use, set
`TRACKER_TRACE_PASSPHRASE`; avoid putting a passphrase in command-line arguments or shell history.
It streams to standard output and creates no plaintext temporary file. Treat the output as valid
only when the decoder exits successfully, because the GCM authentication tag is verified at EOF.

The envelope starts with `TRKTRACE`, then one-byte version/KDF/cipher identifiers, one-byte salt and
nonce lengths, a four-byte big-endian PBKDF2 iteration count, salt, nonce, and AES-GCM ciphertext
with its 128-bit tag. Version 1 uses PBKDF2-HMAC-SHA256, a 256-bit key, 16-byte salt, and 12-byte
nonce. The entire header is GCM additional authenticated data.

The first plaintext line is a versioned `manifest`. Subsequent `recordType` values are
`trace_marker`, `location_observation`, `tracker_run`, `presence_interval`,
`accepted_location_sample`, and finally `end`. The final record contains counts and
`"complete":true`. Trace markers carry both `wallTimeMs` (Unix epoch milliseconds) and
`elapsedRealtimeNanos` (Android monotonic time since boot) for offline RTK/video/gate alignment.
Coordinates use signed E7 integers. Binary posterior payloads use Base64.

Run the dependency-free decoder contract test without Gradle:

```bash
mkdir -p /tmp/tracker-trace-decoder-test
javac -d /tmp/tracker-trace-decoder-test \
  tools/research-trace/ResearchTraceDecoder.java \
  tools/research-trace/ResearchTraceDecoderTest.java
java -cp /tmp/tracker-trace-decoder-test ResearchTraceDecoderTest
```
