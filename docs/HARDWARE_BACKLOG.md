# Hardware-integration backlog (Protagonist Control → Protagonist Core)

Everything below is a gate that needs real hardware, a real Core endpoint, or decisions from those owners. The
simulator path is complete, and the UI and domain code are not expected to change for these items.

| # | Gate | Where | Notes |
|---|---|---|---|
| 1 | **Production `VideoSource`** (Media3 RTSP/HLS or WebRTC) | new `:video-media3` / `:video-webrtc` module implementing `capture.video.VideoSource` | Emit `FrameContent.External` plus calibration metadata per frame. Measure glass-to-glass latency. Vendor SDKs stay inside this module. |
| 2 | Overlay ↔ video time alignment | `ObservationView` + VideoSource | Boxes are drawn at the latest track state. With real video, align by `timestampMs` (frame vs. track update) and expose the skew in System Status. |
| 3 | **Credential provisioning** for the WSS control channel | `CredentialProvider` in `AppContainer` | Short-lived bearer tokens, device attestation, rotation. Today it returns `null`. |
| 4 | TLS trust policy (pinning or private CA) | `WebSocketControlChannel` `OkHttpClient` | Decide pinning versus enterprise CA. |
| 5 | Core protocol conformance | `contracts/capture_protocol.json` | Core must pass the same `ContractTest` vectors. Agree on heartbeat interval, ack timeout (client default 5 s) and snapshot size limits. |
| 6 | Backend capture policy | `CapturePolicy` | Real max burst, TTL bounds and cancel rules come from Core. The client already clamps to them. |
| 7 | Sensor health semantics | `SensorHealth`, `CalibrationStatus` | Map vendor diagnostics to OK/DEGRADED/INVALID/OFFLINE **in Core**, never in the app. |
| 8 | Real previews | `PreviewRef` + `EphemeralPreviewCache` | Fetch thumbnails over an authenticated channel into RAM only. Verify nothing reaches disk (StrictMode disk checks and a forensics pass). |
| 9 | Field HID hardware | `AndroidHidAdapter.DEFAULT_BINDINGS` | Validate the button map on the chosen joystick or gamepad, then tune debounce and repeat. |
| 10 | Performance on target tablets | telemetry in System Status | Budget: UI state compute < 2 ms average, < 5% janky frames at 15 fps overlay updates with 20+ tracks. |
| 11 | Multi-sector / PTZ feedback | `SessionState`, protocol | Out of scope for the single-sector milestone. |
| 12 | Clock sync | `LinkDiagnostics.lastApparentLatencyMs` | Includes clock skew today. Use NTP/PTP status from Core for true latency. |
| 13 | Device management | release identity | Managed config for the endpoint, kiosk mode, OS update policy. |
