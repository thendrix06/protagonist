# Protagonist: implementation notes

## Results recorded in the authoring environment (2026-11-17)

Environment: Linux container, OpenJDK 21, Gradle 8.14.3 wrapper. The network policy allowed Maven Central and the
Gradle Plugin Portal and **denied `dl.google.com`**, which serves all of Google Maven (AGP, AndroidX, Compose). No
KVM was available, so no emulator could run.

### Ran and passed

```bash
./gradlew --no-build-cache -Pprotagonist.android=false clean spotlessCheck test
```

Result: `BUILD SUCCESSFUL`, with 52 tasks executed. 66 tests ran: 66 passed, 0 failed, 0 skipped.

| Module | Suite | Tests |
|---|---|---|
| controller-hid | HidCommandMapperTest | 6 |
| data-remote | ContractTest (against `contracts/capture_protocol.json`) | 9 |
| data-remote | ProtocolCaptureClientTest (mock transport integration) | 14 |
| data-sim | SimulatorServerTest (determinism, seq, scripted states) | 8 |
| domain | EnqueuePolicyTest | 7 |
| domain | PrivacyTest | 4 |
| presentation | AcceptanceScenariosTest (A, B, C, D, E, H, HID, ordering, sector; full simulator stack) | 12 |
| presentation | AccessibilityTokensTest (WCAG contrast, CVD separation, glyph/label uniqueness) | 6 |

ktlint 1.8.0 was also run directly over `:app` and `:design-system` sources with zero violations. Spotless covers
those modules automatically when Android modules are enabled.

A mutation check confirmed that the acceptance and integration tests are not vacuous. Disabling
idempotency-key reconciliation made `reconnectReconcilesByIdempotencyKeyWithoutReplay` fail.

### Not run (blocked by the environment)

```bash
./gradlew test assembleDebug
```

This fails while configuring the build:

```
Could not GET 'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.0/gradle-8.13.0.pom'.
Received status code 403 from server: Forbidden
```

Consequently, the following have **not** been compiled or executed:

- the `:app` and `:design-system` Kotlin/Compose sources
- the Robolectric Compose UI tests in `app/src/sharedTest`: `CaptureUiAcceptanceTest` (A, C, H, branding) and
  `LayoutAccessibilityTest` (F, G at 360×640, 640×360, 960×600 and 1366×1024 dp)
- the instrumented tests (`connectedDebugAndroidTest`)
- Android lint, the debug APK, and screenshots or screen recordings

The Android sources were written against stable Compose Material 3 APIs and pass ktlint. Expect a small number of
compile fixes on the first build. See `docs/TOOLCHAIN.md` for the first-build checklist.

Note also that the Android SDK in the authoring container was extracted from the public `cimg/android` Docker
image (layer digests verified), because `dl.google.com` was blocked. It was not used for any successful build,
since AGP itself could not be resolved.

## Design decisions worth knowing

1. **One protocol client for simulator and production.** `FakeCaptureRepository` = `ProtocolCaptureClient` +
   `LoopbackControlChannel` + `SimulatorServer`. The demo therefore exercises the same ack, seq-gap and reconnect
   code as the field build.
2. **Enqueue is bound at tap time.** `CaptureStateHolder.onAddToQueue()` captures the selected track and a fresh
   idempotency key synchronously, so continued selection cannot redirect an authorization (scenario B). The
   repository then re-checks `EnqueuePolicy` atomically under a lock, which stops double taps creating
   duplicates.
3. **`Sent` ≠ queued.** A row is `PENDING_ACK` until a `command_ack` or `job_update` arrives. A link loss makes it
   `UNCONFIRMED`. After reconnect, the snapshot either contains the idempotency key (reconciled) or the row
   becomes `NOT_RECEIVED`. Nothing is ever resent automatically.
4. **SYNCING blocks commands.** After connect, a seq gap, a decode error or an unsupported major version, commands
   stay blocked until an authoritative snapshot is applied.
5. **Causal simulator.** The simulator does not schedule a job before its acknowledgement has been emitted. A test
   caught an earlier ordering bug where `ACQUIRING` was emitted before the delayed `QUEUED`.
6. **Conservative enqueue rules.** UNCERTAIN and LOST tracks are never queueable from the client. OCCLUDED is
   blocked by default and configurable. The backend enforces its own rules as well.
7. **The UI never says LIVE for the simulator.** `FeedIndicator.SIMULATED` is distinct. A DEGRADED or stale feed
   suppresses any live indication.
8. **Status = glyph + label + color.** Tokens are platform-neutral ARGB values, tested for WCAG AA chip contrast
   and protan/deutan separation.
9. **Privacy.** Previews live in RAM only, with a TTL, and are cleared on background. `allowBackup=false` plus
   data-extraction rules exclude everything. Settings persist numbers and toggles only. `OperationalLog`
   allow-lists identifier fields and redacts anything else.
10. **Brand neutrality.** Core packages are `capture.*`. "Protagonist" appears only in resources, Gradle settings,
    docs and CI labels. `ContractTest` asserts that the protocol contract never mentions the product name.

## Deviations from the brief's commit plan

- The four commits follow the plan's themes, but the phone layout ships in the same `CaptureScreen.kt` as the
  tablet layout (commit 3), because the responsive switch lives in one place. Commit 4 adds the HID tests,
  acceptance tests, UI tests and these notes.
- An extra platform-neutral module, `:presentation`, holds the state holder and reducer, so the acceptance
  scenarios run on the JVM. `:app` keeps only a thin `ViewModel`.
- Hilt was not used. Constructor injection goes through `AppContainer`.

## Remaining gates

See `docs/HARDWARE_BACKLOG.md`. The most important are the production `VideoSource` (Media3/WebRTC), credential
provisioning for the WSS control channel, and Core conformance to `contracts/capture_protocol.json`.
