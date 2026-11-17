package capture.presentation

import capture.domain.Clock
import capture.domain.TrackId
import capture.sim.FakeCaptureRepository
import capture.video.SimulatedVideoSource
import kotlinx.coroutines.test.TestScope

/**
 * Full-stack deterministic harness: state holder -> FakeCaptureRepository -> protocol client -> loopback ->
 * SimulatorServer, all on the test scheduler's virtual clock.
 */
class SimHarness(
    private val scope: TestScope,
    settings: OperatorSettings = OperatorSettings(),
) {
    val clock = Clock { EPOCH + scope.testScheduler.currentTime }
    val settings = InMemorySettingsStore(settings)
    val repo = FakeCaptureRepository.create(scope.backgroundScope, clock, rules = { this.settings.settings.value.enqueueRules() })
    val video = SimulatedVideoSource(scope.backgroundScope, clock, { repo.sensors.value.calibration })
    val holder = CaptureStateHolder(repo, video, this.settings, SequentialIdempotencyKeyFactory(), clock, scope.backgroundScope)

    val ui: CaptureUiState get() = holder.uiState.value

    fun start(): SimHarness {
        repo.start()
        video.start()
        advance(1_000)
        return this
    }

    fun advance(ms: Long) {
        scope.testScheduler.advanceTimeBy(ms)
        scope.testScheduler.runCurrent()
    }

    /** Move the virtual clock to [msAfterStart] of the scenario timeline. */
    fun advanceTo(msAfterStart: Long) {
        val delta = msAfterStart - scope.testScheduler.currentTime
        if (delta > 0) advance(delta)
    }

    fun subject(id: String): SubjectUi = ui.subjects.first { it.trackId == TrackId(id) }

    fun ids(): List<String> = ui.subjects.map { it.label }

    companion object {
        const val EPOCH = 1_763_400_000_000L
    }
}
