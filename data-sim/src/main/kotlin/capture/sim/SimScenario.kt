package capture.sim

import capture.domain.CalibrationStatus
import capture.domain.CapturePolicy
import capture.domain.Modality
import capture.domain.NormalizedBox
import capture.domain.Sector
import capture.domain.SectorId
import capture.domain.TrackState
import kotlin.math.PI
import kotlin.math.sin

/** Smooth, deterministic motion through the panorama (normalized coordinates). */
public data class SimPath(
    val centerX: Float,
    val centerY: Float,
    val amplitudeX: Float,
    val amplitudeY: Float = 0.02f,
    val periodMs: Long = 24_000,
    val phase: Float = 0f,
    val boxWidth: Float = 0.045f,
    val boxHeight: Float = 0.17f,
) {
    public fun boxAt(elapsedMs: Long): NormalizedBox {
        val angle = 2.0 * PI * (elapsedMs.toDouble() / periodMs) + phase
        val cx = centerX + amplitudeX * sin(angle).toFloat()
        val cy = centerY + amplitudeY * sin(angle * 0.5).toFloat()
        val left = (cx - boxWidth / 2f).coerceIn(0f, 1f - boxWidth)
        val top = (cy - boxHeight / 2f).coerceIn(0f, 1f - boxHeight)
        return NormalizedBox(left.quantize(), top.quantize(), boxWidth, boxHeight)
    }

    private fun Float.quantize(): Float = (this * 1000f).toInt() / 1000f
}

/** A backend-side state change at [atMs] after session start. */
public data class SimStateEvent(
    val atMs: Long,
    val state: TrackState,
    val reason: String? = null,
)

public data class SimTrackScript(
    val trackId: String,
    val eventId: String,
    val sectorId: SectorId,
    val path: SimPath,
    val appearAtMs: Long = 0,
    val stateEvents: List<SimStateEvent> = emptyList(),
    val observedIn: Set<Modality> = setOf(Modality.VISIBLE, Modality.LOW_LIGHT, Modality.THERMAL, Modality.FUSED),
) {
    public fun stateAt(elapsedMs: Long): SimStateEvent =
        stateEvents.filter { it.atMs <= elapsedMs }.maxByOrNull { it.atMs } ?: SimStateEvent(appearAtMs, TrackState.ACTIVE)
}

public data class SimTiming(
    val tickMs: Long = 100,
    val ackDelayMs: Long = 300,
    val acquireMs: Long = 900,
    val frameIntervalMs: Long = 150,
)

/** Deterministic demo dataset. Same inputs at the same virtual times always produce the same messages. */
public data class SimScenario(
    val sessionId: String,
    val sectors: List<Sector>,
    val initialSector: SectorId,
    val tracks: List<SimTrackScript>,
    val policy: CapturePolicy = CapturePolicy.Conservative,
    val initialCalibration: CalibrationStatus = CalibrationStatus.CALIBRATED,
    val timing: SimTiming = SimTiming(),
) {
    public companion object {
        public val NORTH: SectorId = SectorId("N")
        public val EAST: SectorId = SectorId("E")

        /**
         * Demo timeline (ms after session start), sector N:
         *  - S01..S04 active from t=0.
         *  - S01 OCCLUDED 20s–26s, then ACTIVE again with the same ID.
         *  - S03 LOST at 35s; at 40s an ambiguous reacquisition is reported as UNCERTAIN (never auto-merged).
         *  - S05, a different person, appears at 45s with a NEW ID.
         * Sector E holds S06 and S07.
         */
        public fun demo(timing: SimTiming = SimTiming()): SimScenario =
            SimScenario(
                sessionId = "sim-session-0001",
                sectors = listOf(Sector(NORTH, "North arc"), Sector(EAST, "East arc")),
                initialSector = NORTH,
                timing = timing,
                tracks =
                    listOf(
                        SimTrackScript(
                            trackId = "S01",
                            eventId = "EV-N1",
                            sectorId = NORTH,
                            path = SimPath(0.22f, 0.58f, 0.10f, phase = 0.0f),
                            stateEvents =
                                listOf(
                                    SimStateEvent(20_000, TrackState.OCCLUDED, "Occluded by structure"),
                                    SimStateEvent(26_000, TrackState.ACTIVE),
                                ),
                        ),
                        SimTrackScript("S02", "EV-N1", NORTH, SimPath(0.62f, 0.55f, 0.08f, periodMs = 30_000, phase = 1.3f)),
                        SimTrackScript(
                            trackId = "S03",
                            eventId = "EV-N1",
                            sectorId = NORTH,
                            path = SimPath(0.42f, 0.62f, 0.06f, periodMs = 20_000, phase = 2.1f),
                            stateEvents =
                                listOf(
                                    SimStateEvent(35_000, TrackState.LOST, "Left field of view"),
                                    SimStateEvent(
                                        40_000,
                                        TrackState.UNCERTAIN,
                                        "Ambiguous reacquisition near last position; association not confirmed",
                                    ),
                                ),
                        ),
                        SimTrackScript("S04", "EV-N1", NORTH, SimPath(0.84f, 0.52f, 0.05f, periodMs = 18_000, phase = 0.7f)),
                        SimTrackScript(
                            "S05",
                            "EV-N1",
                            NORTH,
                            SimPath(0.50f, 0.50f, 0.12f, periodMs = 26_000, phase = 0.2f),
                            appearAtMs = 45_000,
                        ),
                        SimTrackScript("S06", "EV-E1", EAST, SimPath(0.35f, 0.57f, 0.10f, periodMs = 22_000)),
                        SimTrackScript("S07", "EV-E1", EAST, SimPath(0.70f, 0.60f, 0.07f, periodMs = 28_000, phase = 1.0f)),
                    ),
            )
    }
}
