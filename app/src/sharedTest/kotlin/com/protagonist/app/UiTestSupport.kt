package com.protagonist.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import capture.designsystem.ControlTheme
import capture.sim.FakeCaptureRepository
import com.protagonist.app.di.AppContainer
import com.protagonist.app.ui.AppTab
import com.protagonist.app.ui.ProtagonistRoot

/** Boots the real app shell against a fresh simulator container inside a fixed-size viewport. */
class UiHarness(
    private val rule: ComposeContentTestRule,
) {
    val container = AppContainer(ApplicationProvider.getApplicationContext(), controlUrl = "")
    val vm = CaptureViewModel(container.stateHolder, container.simulatorControls)
    val sim: FakeCaptureRepository get() = container.repository as FakeCaptureRepository

    /**
     * Launches the app shell. A fixed [width] x [height] viewport is used only when it fits the device window;
     * otherwise the app fills the real window (on a phone, a 1280dp viewport would put controls off-screen where
     * they cannot be tapped). Robolectric's large virtual window always fits.
     */
    fun launch(
        width: Dp? = null,
        height: Dp? = null,
    ): UiHarness {
        val fixed = width != null && height != null && fitsWindow(width, height)
        rule.setContent {
            ControlTheme {
                var tab by remember { mutableStateOf(AppTab.CAPTURE) }
                val sized = if (fixed) Modifier.requiredSize(width, height) else Modifier
                Box(sized.testTag(VIEWPORT)) {
                    ProtagonistRoot(vm, tab) { tab = it }
                }
            }
        }
        waitForTag("track_box_S01")
        return this
    }

    fun waitForTag(
        tag: String,
        timeoutMs: Long = 15_000,
    ) = rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    fun close() = container.shutdown()

    companion object {
        const val VIEWPORT = "test_viewport"

        /** True if a [width] x [height] dp viewport fits inside the device's current window. */
        fun fitsWindow(
            width: Dp,
            height: Dp,
        ): Boolean {
            val config = ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration
            return width.value <= config.screenWidthDp && height.value <= config.screenHeightDp
        }
    }
}

fun hasTestTagPrefix(prefix: String) =
    SemanticsMatcher("testTag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

val hasClickAction: SemanticsMatcher = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)

fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)
