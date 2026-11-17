# Protagonist — Coding-Agent Execution Brief (Android First)

> Standalone brief for coding agents working in this repository. Implementation status against this brief is
> tracked in `docs/IMPLEMENTATION_NOTES.md`.

## Project identity and naming (binding)

* Official project and visible application name: Protagonist.
* Repository name: `protagonist`.
* Android app display name: `Protagonist` (use an Android string resource, not repeated literal labels).
* Provisional Android application ID / namespace: `com.protagonist.app`; confirm legal ownership and actual release identifier before publication. Do not assume this namespace is available or registered.
* Subsystem names: Protagonist Control (operator app), Protagonist Core (future vehicle edge service), Protagonist Simulator (deterministic development backend).
* Set project name in Gradle settings, README title, demo screen title, documentation, CI job display labels, and test descriptions. Keep protocol payloads, domain models, vendor adapters, and persistence schemas product-name neutral so rebranding or product variants do not require rewriting core software.
* Do not invent a corporate entity, trademark ownership, domain name, logo, or production signing identity. Use the working name only.

## Agent startup instruction

This file is standalone. Place it at the root of a new Android repository as `AGENTS.md` and begin implementation immediately. If the earlier design package, Kotlin stubs, or `/contracts/capture_protocol.json` is unavailable, create the required project structure and protocol types from the invariants below; do not block awaiting those files. Build an Android application, not merely a screen design or a planning document. Avoid assuming access to real sensor hardware.

## Mission

Implement Protagonist Control, an Android-native operator UI for a single-sector multispectral camera platform. The first milestone must work end-to-end in simulation with no physical cameras. Preserve clean interfaces for later downstream edge integration. Do not infer biometric identity or perform face recognition. Event-local track IDs are pseudonymous and uncertain associations must be flagged.

## UX requirements (binding)

* Landscape tablet is primary; phone portrait and landscape remain functional.
* Tabs across top: Capture, Settings, System Status. Status/settings not cluttering Capture.
* Capture: largest possible single wide observation view; single mode toggle: Visible, Low-light, Thermal, Fused. Single sector selector.
* Detection overlay: each track labeled S01, S02 ...; track IDs remain consistent across live view, gallery, selected-subject panel and queue. Include `ACTIVE`, `OCCLUDED`, `LOST`, `UNCERTAIN` states.
* Bottom horizontal candidate strip with still/preview and stable ordering. Tap subject in panorama or strip => highlight only; does NOT create a capture job.
* Upper-right on tablet: selected-subject quick controls, prominent ADD TO QUEUE action, and compact capture queue. Phone: selected card and large ADD TO QUEUE above collapsible queue; gallery horizontally scrolling below.
* Authorized capture job is bounded by a configurable burst frame limit and TTL. Show queued, acquiring, capturing, completed, failed, cancelled; no implied capture success until backend confirms it.
* Camera movement and scheduling are backend responsibilities. UI must never invent real-world range, confidence, capture result or sensor health.

## Architecture and dependencies

Kotlin; Jetpack Compose / Material 3; coroutines/StateFlow; ViewModel; repository ports; dependency injection using Hilt or simple constructor injection; Media3/WebRTC adapter behind `VideoSource` interface. Choose current stable Android toolchain at implementation time, pin versions, document them. Do not put vendor-specific SDK calls in UI modules.

Modules: `:app`, `:domain`, `:data-sim`, `:data-remote`, `:video`, `:controller-hid`, `:design-system` (can combine initially if boundaries remain explicit). Provide a single `CaptureRepository` interface whose `FakeCaptureRepository` emits deterministic tracks/queue state and whose future `RemoteCaptureRepository` subscribes to the server event stream.

## Step-by-step execution order

1. Create compilable Gradle Android app named Protagonist with project/repository convention above and module boundaries; run format/lint/build.
2. Build immutable domain models and JSON contract tests using `contracts/capture_protocol.json` if available; otherwise create that contract with eventId, trackId, jobId, idempotencyKey, burstLimit, ttlMs, seq, status, timestampMs, modality, and sensorHealth fields; reject unknown critical protocol versions, not unknown optional fields.
3. Build fake repository with scripted subject motion, occlusion, cross-mode persistence, delayed acks, disconnect/reconnect, duplicate taps and failure states.
4. Implement Compose Capture tablet layout, then responsive phone layout; implement Settings and System Status as tabs.
5. Wire `selectSubject(trackId)` to highlighting only. Wire `enqueue(trackId, idempotencyKey, burstLimit, ttlMs)` to command dispatch with optimistic pending (not queued) indication until server ack.
6. Add HID/joystick optional mapping: focus navigation; dedicated enqueue button; debounce 300 ms; state-dependent input; app remains fully usable by touch.
7. Implement remote adapter using separate live video and reliable authenticated control channel; expose mock transport for integration tests.
8. Add tests, accessibility, performance telemetry and privacy retention controls. Provide a Protagonist README with screen captures and emulator launch instructions.

## Protocol invariant and failure behavior

* All timestamps carry UTC epoch milliseconds plus monotonic sequence `seq` per stream; state snapshots reconcile gaps.
* Server assigns canonical `jobId`, retains `eventId` and stable `trackId`; `idempotencyKey` deduplicates retries.
* Selection is entirely local and never authorizes capture. Enqueue only occurs via explicit button/HID action.
* Loss of link disables new enqueue; existing authorized jobs remain under configured bounded policy; on reconnect request snapshot and reconcile. Never replay enqueue blindly.
* If a track is uncertain or lost, disable enqueue by default and show the reason. Do not silently associate a new person with an old ID.
* Default unselected high-resolution previews in RAM with TTL, no persistent archive; logs should not contain biometric templates, raw frames or personal names.

## Acceptance scenarios (must be automated)

A. Select S01 -> highlight S01 everywhere, queue unchanged. Add -> one acked job only.
B. Rapidly authorize S01,S02,S03,S04 -> exactly four distinct jobs; queue status updates asynchronously while selection continues.
C. Switch Visible->Thermal->Fused -> S01 identifier unchanged, calibrated overlay optional and clearly marked degraded if unavailable.
D. Occlude S01 -> state OCCLUDED, no new invented ID; ambiguous reacquisition -> UNCERTAIN and no automatic merge.
E. Disconnect after command -> no duplicate enqueue after reconnect; job reconciliation by idempotency key.
F. Phone viewport 360dp and 7-12-inch tablet landscape -> primary actions remain within reach, no overlapping controls.
G. 48dp minimum touch targets; screen reader labels; contrast and color-blind distinct statuses.
H. Mock sensor invalid -> explicit DEGRADED indication, no misleading live/capture indicator.

## Definition of done

Clean build from fresh checkout, instrumented Compose UI tests and unit tests passing, deterministic demo dataset, documented interfaces, no hardcoded sensor vendor paths in UI, no background persistence of unselected imagery, screen recording of tablet/phone states, and implementation notes identifying remaining real-hardware integration gates.

## First deliverable / concrete commit plan

* Commit 1: Protagonist repository/project identity, Android app label resource, Gradle wrapper, pinned AGP/Kotlin/Compose versions, manifest, root modules, CI-friendly `./gradlew test assembleDebug` workflow.
* Commit 2: Domain models, repository interface, deterministic simulator, JSON serialization and contract tests.
* Commit 3: Capture screen with labelled observation viewport (simulated scene allowed), modality selector, sector selector, stable ID overlays, selection state, visible Add to Queue button, candidate strip, and queue status.
* Commit 4: Phone layout, Settings and System Status, disconnect/degraded UI, hardware controller adapter, and automated test scenarios A-H.
* At completion, report build and test results with exact commands, screenshots of tablet and phone, and a concise hardware-integration backlog. Never claim a build or test passed unless it actually ran.

## Explicit interface boundaries

`CaptureRepository` must expose flows for session state, subject tracks, queue jobs, sensor state, and connection state. It must expose commands to select a track locally, authorize enqueue with an idempotency key, cancel authorized jobs when allowed by backend policy, change observation mode, and choose a sector. Implement a `VideoSource` abstraction for frames and calibration metadata; the UI must tolerate simulation-only images. A future production adapter supplies authenticated video and state events while keeping the Compose screens unchanged.

## Branding acceptance checks

* Home launcher displays `Protagonist` on tablet and phone.
* In-app header identifies the product as `Protagonist`, while functional navigation remains Capture / Settings / System Status.
* `settings.gradle.kts` uses `rootProject.name = "Protagonist"`.
* A clean clone's README begins with `# Protagonist` and explains the distinction between Control, Core, and Simulator.
* App labels come from configurable resources; there is no dependency on the brand name in wire protocols or capture-job identifiers.
* Document the temporary application ID and the changes needed before any Play Store or enterprise deployment.
