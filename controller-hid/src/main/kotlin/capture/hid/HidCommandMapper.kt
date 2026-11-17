package capture.hid

import capture.domain.Clock

/** Logical controller inputs. Platform adapters translate key codes / gamepad buttons into these. */
public enum class ControllerInput {
    NAV_LEFT,
    NAV_RIGHT,
    NAV_UP,
    NAV_DOWN,

    /** Primary button (e.g. A): select the focused subject (highlight only). */
    CONFIRM,

    /** Dedicated authorize button. The only HID input that can create a capture job. */
    ENQUEUE,

    /** Back (e.g. B): clear selection, or return to the Capture tab. */
    BACK,
    MODE_NEXT,
    MODE_PREVIOUS,
    TAB_NEXT,
    TAB_PREVIOUS,
}

public enum class Screen {
    CAPTURE,
    SETTINGS,
    SYSTEM_STATUS,
}

/** Snapshot of UI state the mapper needs to decide what an input means right now. */
public data class ControllerContext(
    val screen: Screen,
    val hasSubjects: Boolean,
    val hasSelection: Boolean,
    val enqueueAllowed: Boolean,
    val enqueueBlockedReason: String? = null,
)

public sealed interface ControllerCommand {
    public data object SelectNextSubject : ControllerCommand

    public data object SelectPreviousSubject : ControllerCommand

    public data object ClearSelection : ControllerCommand

    /** Authorize the currently selected subject. Selection itself never authorizes. */
    public data object EnqueueSelected : ControllerCommand

    public data class CycleModality(
        val step: Int,
    ) : ControllerCommand

    public data class SwitchTab(
        val step: Int,
    ) : ControllerCommand

    public data object GoToCapture : ControllerCommand
}

public sealed interface HidResult {
    public data class Command(
        val command: ControllerCommand,
    ) : HidResult

    public data class Ignored(
        val reason: Reason,
        val detail: String? = null,
    ) : HidResult

    public enum class Reason {
        DEBOUNCED,
        NOT_AVAILABLE_ON_SCREEN,
        ENQUEUE_BLOCKED,
        NOTHING_TO_SELECT,
        DISABLED,
    }
}

/**
 * Maps controller inputs to commands, with per-input debounce and state-dependent behaviour.
 *
 * - Debounce: a repeat of the same input within [debounceMs] (default 300 ms) is ignored. This stops a bouncing
 *   or double-pressed ENQUEUE button from authorizing twice (the backend also deduplicates by idempotency key).
 * - ENQUEUE is honoured only on the Capture screen and only when enqueue is currently allowed.
 * - Touch remains fully functional; this mapper is optional and can be disabled.
 */
public class HidCommandMapper(
    private val clock: Clock,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    @Volatile public var enabled: Boolean = true
    private val lastAccepted = HashMap<ControllerInput, Long>()

    public fun onInput(
        input: ControllerInput,
        context: ControllerContext,
    ): HidResult {
        if (!enabled) return HidResult.Ignored(HidResult.Reason.DISABLED)
        val now = clock.nowMs()
        val last = lastAccepted[input]
        if (last != null && now - last < debounceMs) return HidResult.Ignored(HidResult.Reason.DEBOUNCED)
        val result = map(input, context)
        // Debounce window starts on every press that was not itself debounced, accepted or not.
        lastAccepted[input] = now
        return result
    }

    private fun map(
        input: ControllerInput,
        ctx: ControllerContext,
    ): HidResult {
        fun cmd(c: ControllerCommand) = HidResult.Command(c)
        return when (input) {
            ControllerInput.TAB_NEXT -> {
                cmd(ControllerCommand.SwitchTab(+1))
            }

            ControllerInput.TAB_PREVIOUS -> {
                cmd(ControllerCommand.SwitchTab(-1))
            }

            ControllerInput.BACK -> {
                when {
                    ctx.screen != Screen.CAPTURE -> cmd(ControllerCommand.GoToCapture)
                    ctx.hasSelection -> cmd(ControllerCommand.ClearSelection)
                    else -> HidResult.Ignored(HidResult.Reason.NOTHING_TO_SELECT)
                }
            }

            else -> {
                if (ctx.screen != Screen.CAPTURE) {
                    HidResult.Ignored(HidResult.Reason.NOT_AVAILABLE_ON_SCREEN)
                } else {
                    mapCapture(input, ctx)
                }
            }
        }
    }

    private fun mapCapture(
        input: ControllerInput,
        ctx: ControllerContext,
    ): HidResult =
        when (input) {
            ControllerInput.NAV_RIGHT, ControllerInput.NAV_DOWN -> {
                if (ctx.hasSubjects) {
                    HidResult.Command(ControllerCommand.SelectNextSubject)
                } else {
                    HidResult.Ignored(HidResult.Reason.NOTHING_TO_SELECT)
                }
            }

            ControllerInput.NAV_LEFT, ControllerInput.NAV_UP -> {
                if (ctx.hasSubjects) {
                    HidResult.Command(ControllerCommand.SelectPreviousSubject)
                } else {
                    HidResult.Ignored(HidResult.Reason.NOTHING_TO_SELECT)
                }
            }

            ControllerInput.CONFIRM -> {
                // Confirm focuses/highlights only. It is intentionally NOT an enqueue.
                if (ctx.hasSubjects && !ctx.hasSelection) {
                    HidResult.Command(ControllerCommand.SelectNextSubject)
                } else {
                    HidResult.Ignored(HidResult.Reason.NOTHING_TO_SELECT)
                }
            }

            ControllerInput.ENQUEUE -> {
                if (ctx.enqueueAllowed) {
                    HidResult.Command(ControllerCommand.EnqueueSelected)
                } else {
                    HidResult.Ignored(HidResult.Reason.ENQUEUE_BLOCKED, ctx.enqueueBlockedReason)
                }
            }

            ControllerInput.MODE_NEXT -> {
                HidResult.Command(ControllerCommand.CycleModality(+1))
            }

            ControllerInput.MODE_PREVIOUS -> {
                HidResult.Command(ControllerCommand.CycleModality(-1))
            }

            ControllerInput.TAB_NEXT, ControllerInput.TAB_PREVIOUS, ControllerInput.BACK -> {
                error("handled above")
            }
        }

    public companion object {
        public const val DEFAULT_DEBOUNCE_MS: Long = 300
    }
}
