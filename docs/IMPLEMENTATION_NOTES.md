# Protagonist: implementation notes

## Results recorded in the authoring environment (2026-11-17)

Environment: Linux container, OpenJDK 21, Gradle 9.9.1 wrapper, Android SDK platform 37. No KVM, so no emulator.
`gcp.mirrors.maven.org` (Maven Central's mirror for large files) is blocked by the network policy.

### Ran and passed

```bash
./gradlew spotlessCheck test assembleDebug assembleDebugAndroidTest -x :app:testDebugUnitTest -x :design-system:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL`.
- spotless/ktlint passes on all 8 modules.
- `app-debug.apk` and the instrumented-test APK build. Building the test APK compiles the shared Compose UI
  test sources.
- 66 JVM tests ran, 66 passed:

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

A mutation check confirmed that the tests are not vacuous. Disabling idempotency-key reconciliation made
`reconnectReconcilesByIdempotencyKeyWithoutReplay` fail.

### Not run yet

- `:app:testDebugUnitTest`: the Robolectric UI tests `CaptureUiAcceptanceTest` (A, C, H, branding) and
  `LayoutAccessibilityTest` (F, G at 360x640, 640x360, 960x600 and 1366x1024 dp). Also `lintDebug`, which needs
  the same test classpath. Maven Central redirects `nativeruntime-dist-compat` and `android-all-instrumented`
  to `gcp.mirrors.maven.org`, which returns 403 here.
- `connectedDebugAndroidTest`, screenshots and screen recording, because no emulator is available.

The Android SDK in the authoring container was extracted from the public `cimg/android` Docker image, with layer
digests verified, because `dl.google.com` was blocked when work started.

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
