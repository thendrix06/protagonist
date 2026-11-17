package capture.hid

import capture.domain.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HidCommandMapperTest {
    private var now = 0L
    private val mapper = HidCommandMapper(Clock { now })
    private val capture = ControllerContext(Screen.CAPTURE, hasSubjects = true, hasSelection = true, enqueueAllowed = true)

    @Test fun enqueueDebouncedWithin300ms() {
        assertEquals(HidResult.Command(ControllerCommand.EnqueueSelected), mapper.onInput(ControllerInput.ENQUEUE, capture))
        now += 299
        assertEquals(HidResult.Ignored(HidResult.Reason.DEBOUNCED), mapper.onInput(ControllerInput.ENQUEUE, capture))
        now += 300
        assertEquals(HidResult.Command(ControllerCommand.EnqueueSelected), mapper.onInput(ControllerInput.ENQUEUE, capture))
    }

    @Test fun debounceIsPerInput() {
        mapper.onInput(ControllerInput.ENQUEUE, capture)
        now += 10
        assertIs<HidResult.Command>(mapper.onInput(ControllerInput.NAV_RIGHT, capture))
    }

    @Test fun enqueueBlockedWhenNotAllowedCarriesReason() {
        val r = mapper.onInput(ControllerInput.ENQUEUE, capture.copy(enqueueAllowed = false, enqueueBlockedReason = "S01 lost"))
        assertEquals(HidResult.Ignored(HidResult.Reason.ENQUEUE_BLOCKED, "S01 lost"), r)
    }

    @Test fun confirmNeverEnqueues() {
        val r = mapper.onInput(ControllerInput.CONFIRM, capture.copy(hasSelection = false))
        assertEquals(HidResult.Command(ControllerCommand.SelectNextSubject), r)
        now += 1_000
        assertEquals(HidResult.Ignored(HidResult.Reason.NOTHING_TO_SELECT), mapper.onInput(ControllerInput.CONFIRM, capture))
    }

    @Test fun captureOnlyInputsIgnoredOnOtherScreens() {
        val settings = capture.copy(screen = Screen.SETTINGS)
        assertEquals(HidResult.Ignored(HidResult.Reason.NOT_AVAILABLE_ON_SCREEN), mapper.onInput(ControllerInput.ENQUEUE, settings))
        assertEquals(HidResult.Command(ControllerCommand.GoToCapture), mapper.onInput(ControllerInput.BACK, settings))
        assertEquals(HidResult.Command(ControllerCommand.SwitchTab(1)), mapper.onInput(ControllerInput.TAB_NEXT, settings))
    }

    @Test fun disabledMapperIgnoresEverything() {
        mapper.enabled = false
        assertEquals(HidResult.Ignored(HidResult.Reason.DISABLED), mapper.onInput(ControllerInput.ENQUEUE, capture))
    }
}
