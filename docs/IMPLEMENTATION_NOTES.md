# Protagonist: implementation notes

## Results recorded in the authoring environment (2026-11-17)

Environment: Linux container, OpenJDK 21, Gradle 9.9.1, AGP 9.5.1, Android SDK platform 37. There was no KVM,
so no emulator was available.

### Ran and passed

```bash
./gradlew --no-build-cache clean spotlessCheck test lintDebug assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL`, with 228 tasks. 74 tests passed and 0 failed. The 8 skipped tests are the opt-in
`ScreenshotTest`, which was run separately and also passed.

| Module | Suite | Tests |
|---|---|---|
| app | CaptureUiAcceptanceTest (Robolectric Compose: A, C, H, branding) | 4 |
| app | LayoutAccessibilityTest (Robolectric Compose: F + G at 360×640, 640×360, 960×600, 1366×1024 dp) | 4 |
| controller-hid | HidCommandMapperTest | 6 |
| data-remote | ContractTest (against `contracts/capture_protocol.json`) | 9 |
| data-remote | ProtocolCaptureClientTest (mock transport integration) | 14 |
| data-sim | SimulatorServerTest (determinism, seq, scripted states) | 8 |
| domain | EnqueuePolicyTest | 7 |
| domain | PrivacyTest | 4 |
| presentation | AcceptanceScenariosTest (A, B, C, D, E, H, HID, ordering, sector; full simulator stack) | 12 |
| presentation | AccessibilityTokensTest (WCAG contrast, CVD separation, glyph/label uniqueness) | 6 |

Android lint reports 0 issues in `:app`. `:design-system` has one warning, `ObsoleteLintCustomCheck`, which
concerns lint checks bundled in a third-party AAR, not our code.

Mutation checks confirmed that the tests are not vacuous:
- Disabling idempotency-key reconciliation fails the reconnect test.
- A 40dp queue toggle fails the 48dp touch-target check.

### Bugs found by running the Android layer for real

These were all fixed:
- A frame-telemetry loop kept Compose permanently busy. It is now switchable, and UI tests turn it off.
- Overriding `dispatchKeyEvent` used a restricted AndroidX API. HID input now goes through `onKeyDown`.
- Plain text rendered black on the dark theme. The theme now provides the content color.
- "System Status" was truncated on phones.
- Mode labels were truncated in phone landscape. The compact layout now uses a 2×2 mode grid.
- The "already has a acquiring job" grammar.

Label truncation is checked by screenshot review, not by an automated assertion. Robolectric's
`GetTextLayoutResult` for Material segmented buttons reported an 8px label width even where the label
visibly fits, so an assertion based on it would only produce false failures.

### CI on GitHub (`thendrix06/protagonist`)

[Run 44168197096](https://github.com/thendrix06/protagonist/actions/runs/44168197096) at commit `93f65fc`
passed all three jobs:
- **Core modules (JVM):** `spotlessCheck test`.
- **Android:** `spotlessCheck test lintDebug assembleDebug`, which includes the Robolectric UI tests.
- **Instrumented Compose tests:** the shared UI tests ran on an API 35 Pixel Tablet emulator through
  `connectedDebugAndroidTest`. The same job failed on commit `9dd3365`, before the busy-Compose fix, which
  confirms the tests really execute on the device.

### Screen recordings

- [Run 44175153127](https://github.com/thendrix06/protagonist/actions/runs/44175153127) at commit `023862e`
  passed all 5 jobs. The on-device UI tests passed on both a Pixel Tablet and a Pixel 7 emulator (API 35).
- Both jobs uploaded emulator screen recordings of `DeviceDemoTest`: `protagonist-screen-recording-tablet`
  and `protagonist-screen-recording-phone`.
- On a device, fixed test viewports are used only when they fit the screen. Robolectric verifies every size.

The Android SDK in the authoring container was extracted from the public `cimg/android` Docker image, with layer
digests verified, because `dl.google.com` was initially blocked.

### Production transport over a real socket (`:core-reference`)

`RemoteOverWebSocketTest` runs the production `RemoteCaptureRepository` and `WebSocketControlChannel` (OkHttp)
against the reference server on localhost. Its 3 tests passed, and stayed stable over 5 repeated runs:
- connect, sync and complete a job
- a server-side link drop, followed by reconnect and reconciliation with no resend
- a wrong token is rejected

These tests found a real bug. The channel did not handle OkHttp's `onClosing`, so after a **server-initiated**
close it never noticed the link was down and never reconnected. The mock transports could not catch this. It is
now fixed: the channel completes the close handshake, marks the link down and reconnects.

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

- After the four planned commits, follow-up commits moved to the current toolchain and fixed the issues the first
  real Android runs exposed.
- The four commits follow the plan's themes, but the phone layout ships in the same `CaptureScreen.kt` as the
  tablet layout (commit 3), because the responsive switch lives in one place. Commit 4 adds the HID tests,
  acceptance tests, UI tests and these notes.
- An extra platform-neutral module, `:presentation`, holds the state holder and reducer, so the acceptance
  scenarios run on the JVM. `:app` keeps only a thin `ViewModel`.
- Hilt was not used. Constructor injection goes through `AppContainer`.

## Remaining gates

See `docs/HARDWARE_BACKLOG.md`. The most important are the production `VideoSource` (Media3/WebRTC), credential
provisioning for the WSS control channel, and Core conformance to `contracts/capture_protocol.json`.
