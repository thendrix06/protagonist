package com.protagonist.app.hid

import android.view.KeyEvent
import capture.hid.ControllerInput

/**
 * Maps Android key codes (gamepads, joysticks with HID buttons, keyboards) to logical controller inputs.
 * The mapping is data, so a field kit with different hardware can supply its own table without code changes.
 * Debounce and state-dependent behaviour are handled by the platform-neutral HidCommandMapper.
 */
class AndroidHidAdapter(
    private val bindings: Map<Int, ControllerInput> = DEFAULT_BINDINGS,
) {
    /** Returns the logical input for a key-down event, or null if the key is not bound. Repeats are ignored. */
    fun map(event: KeyEvent): ControllerInput? {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return null
        return bindings[event.keyCode]
    }

    companion object {
        val DEFAULT_BINDINGS: Map<Int, ControllerInput> =
            mapOf(
                KeyEvent.KEYCODE_DPAD_LEFT to ControllerInput.NAV_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT to ControllerInput.NAV_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP to ControllerInput.NAV_UP,
                KeyEvent.KEYCODE_DPAD_DOWN to ControllerInput.NAV_DOWN,
                KeyEvent.KEYCODE_BUTTON_A to ControllerInput.CONFIRM,
                KeyEvent.KEYCODE_DPAD_CENTER to ControllerInput.CONFIRM,
                // Dedicated authorize button. Deliberately not the primary/confirm button.
                KeyEvent.KEYCODE_BUTTON_X to ControllerInput.ENQUEUE,
                KeyEvent.KEYCODE_BUTTON_B to ControllerInput.BACK,
                KeyEvent.KEYCODE_BUTTON_R1 to ControllerInput.MODE_NEXT,
                KeyEvent.KEYCODE_BUTTON_L1 to ControllerInput.MODE_PREVIOUS,
                KeyEvent.KEYCODE_BUTTON_R2 to ControllerInput.TAB_NEXT,
                KeyEvent.KEYCODE_BUTTON_L2 to ControllerInput.TAB_PREVIOUS,
            )
    }
}
