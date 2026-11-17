# Protagonist interfaces

## `CaptureRepository` (`:domain`, package `capture.domain`)

The single port between the operator UI and a backend.

| Member | Kind | Semantics |
|---|---|---|
| `session: StateFlow<SessionState>` | flow | Sectors, active sector, backend-confirmed modality, `captureAvailable` (+ reason), `CapturePolicy` (max burst, TTL bounds, operator-cancel allowed). |
| `tracks: StateFlow<List<SubjectTrack>>` | flow | Backend-reported tracks: `trackId`, `eventId`, `state` (ACTIVE/OCCLUDED/LOST/UNCERTAIN), normalized `box`, `stateReason`, `observedIn`, `preview` ref. |
| `queue: StateFlow<List<QueueEntry>>` | flow | One row per authorization, keyed by `idempotencyKey`. Client-only states: `PENDING_ACK`, `UNCONFIRMED`, `NOT_RECEIVED`. Every other status and all frame counts and reasons come from the backend. |
| `sensors: StateFlow<SensorState>` | flow | Per-modality health and cross-modality calibration, as reported by the backend. |
| `connection: StateFlow<ConnectionState>` | flow | OFFLINE / CONNECTING / SYNCING / CONNECTED. `canCommand` is true only when CONNECTED (transport up **and** a snapshot applied). |
| `diagnostics: StateFlow<LinkDiagnostics>` | flow | Counters for System Status. |
| `selectedTrackId: StateFlow<TrackId?>` | flow | Local highlight. |
| `selectTrack(trackId?)` | command | **Local only.** Never transmitted, never authorizes. |
| `authorizeEnqueue(EnqueueRequest)` | command | The only way to create capture work. It atomically re-checks `EnqueuePolicy`, inserts a `PENDING_ACK` row, then transmits. `Sent` means "handed to the transport", **not** "queued". |
| `cancelJob(jobId)` | command | Sent only if the backend marks the job `cancellable` and the policy allows it. The status changes only when the backend reports it. |
| `setObservationMode(modality)` | command | The UI shows it as pending until `session.modality` confirms it. Track IDs are unaffected. |
| `selectSector(sectorId)` | command | The backend replaces the track set. A selected track that disappears is cleared, never moved to another subject. |

Implementations: `FakeCaptureRepository` (`:data-sim`) and `RemoteCaptureRepository` (`:data-remote`). Both
delegate to `ProtocolCaptureClient`.

## `VideoSource` (`:video`, package `capture.video`)

`frames: SharedFlow<VideoFrame>`, `status: StateFlow<VideoStreamStatus>`, `bind(modality, sector)`, `start()`,
`stop()`.

`VideoFrame` carries `seq`, a source `timestampMs` (UTC), `modality`, its size, a `FrameCalibration` (status and
calibration id) and its `FrameContent`. The content is either `Synthetic(sceneTimeMs)` for the simulator or
`External(handle)` for a platform player or track.

The UI treats a frame as fresh if it arrived within 1.5 s. It never shows `LIVE` for a simulated source.

## `ControlChannel` (`:data-remote`, package `capture.remote`)

A reliable, ordered text channel: `state`, `incoming`, `connectAttempts`, `connect()`, `disconnect()`,
`send(text): Boolean`. A channel **never retries application frames**; it reconnects only the transport.

* `InMemoryControlChannel`: the mock transport for integration tests. The test plays the server.
* `LoopbackControlChannel`: connects the simulator, with fault injection.
* `WebSocketControlChannel`: OkHttp WSS, `Authorization: Bearer <token>` from a `CredentialProvider`, and
  exponential backoff. `ws://` is rejected unless explicitly allowed for loopback tests.

## Wire protocol

See `contracts/capture_protocol.json` (capture-protocol 1.0). Key rules:

* Envelope: `protocolVersion` (MAJOR.MINOR), `type`, `stream` (`state` / `control` / `heartbeat`), `seq`
  (monotonic per sender and stream), `timestampMs` (UTC epoch ms) and `payload`.
* Versioning: an unknown MAJOR version is rejected and the client blocks commands. A higher MINOR is accepted.
  Unknown optional fields are ignored. An unknown message type is ignored but still consumes its seq.
* A missing required field, or an unknown enum value in a required field, rejects the message, and the client
  resynchronizes from a snapshot.
* Gap handling: if a state seq skips, the client drops deltas, sends `snapshot_request`, and sits in SYNCING with
  commands blocked until a `snapshot` arrives.
* Reconciliation: a `snapshot` carries `lastClientSeqProcessed`. Pending authorizations are matched by
  `idempotencyKey`. One the backend never saw becomes `NOT_RECEIVED` and is **not** resent; the operator must
  re-authorize explicitly.
* The server assigns `jobId`. A duplicate `enqueue` with a known `idempotencyKey` returns the same job.

Contract conformance is enforced by `ContractTest`. It checks every message type, every enum's wire names, the
required fields of each example, round trips, minor-version tolerance, rejected examples, and the absence of the
product name in the protocol.

## `HidCommandMapper` (`:controller-hid`)

`onInput(ControllerInput, ControllerContext): HidResult`. Repeats of the same input within 300 ms are debounced.
`ENQUEUE` works only on the Capture screen and only when enqueue is allowed; otherwise the result carries the
block reason. `CONFIRM` only highlights.

## Privacy primitives (`:domain`, `capture.domain.privacy`)

* `EphemeralPreviewCache<T>`: RAM-only and size-bounded. Unselected previews expire after `unselectedTtlMs`;
  `clear()` runs on background or trim-memory.
* `OperationalLog`: an allow-listed set of fields (IDs, statuses, seq, durations). Any value that isn't a short
  identifier token is replaced with `<redacted>`.
