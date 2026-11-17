package com.protagonist.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Scenario F (reachable primary actions, no overlapping controls) and G (48dp touch targets, labels) at
 * 360dp phone portrait, phone landscape, 7" and 12" tablet landscape.
 */
@RunWith(AndroidJUnit4::class)
class LayoutAccessibilityTest {
    @get:Rule val rule = createComposeRule()
    private var h: UiHarness? = null

    @After fun tearDown() {
        h?.close()
    }

    @Test fun phonePortrait360() = check(360.dp, 640.dp, "layout_phone")

    @Test fun phoneLandscape() = check(640.dp, 360.dp, "layout_compact_landscape")

    @Test fun tablet7InchLandscape() = check(960.dp, 600.dp, "layout_tablet")

    @Test fun tablet12InchLandscape() = check(1366.dp, 1024.dp, "layout_tablet")

    private fun check(
        width: Dp,
        height: Dp,
        expectedLayout: String,
    ) {
        // Only viewports that fit the device can be verified on it; Robolectric's large window covers all sizes.
        assumeTrue("${width}x$height viewport does not fit this device", UiHarness.fitsWindow(width, height))
        h = UiHarness(rule).launch(width, height)
        rule.onNodeWithTag(expectedLayout).assertExists()
        val density = rule.density
        val viewport = rule.onNodeWithTag(UiHarness.VIEWPORT).fetchSemanticsNode().boundsInRoot

        fun rect(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

        // F: ADD TO QUEUE is fully visible on the first screen, without scrolling.
        val add = rect("add_to_queue")
        assertTrue(add.left >= viewport.left && add.right <= viewport.right, "ADD TO QUEUE horizontally inside viewport: $add vs $viewport")
        assertTrue(add.top >= viewport.top && add.bottom <= viewport.bottom, "ADD TO QUEUE reachable without scrolling: $add vs $viewport")

        // F: primary controls do not overlap one another.
        val tags = listOf("observation_view", "add_to_queue", "selected_panel", "mode_toggle", "sector_selector")
        val rects = tags.associateWith(::rect)
        for (i in tags.indices) {
            for (j in i + 1 until tags.size) {
                val a = rects.getValue(tags[i])
                val b = rects.getValue(tags[j])
                assertTrue(!a.overlaps(b), "${tags[i]} overlaps ${tags[j]} at ${width}x$height: $a / $b")
            }
        }

        // G: every clickable element on screen is >= 48x48dp and has an accessible label.
        val minPx = with(density) { 48.dp.toPx() } - 0.5f
        val clickables = rule.onAllNodes(hasClickAction, useUnmergedTree = false).fetchSemanticsNodes()
        assertTrue(clickables.isNotEmpty())
        for (node in clickables) {
            val b = node.boundsInRoot
            if (!b.overlaps(viewport)) continue // scrolled out of view
            if (b.width <= 0f || b.height <= 0f) continue
            // Measure the node's full layout size: boundsInRoot is clipped for partially scrolled-in nodes.
            val w = node.size.width
            val ht = node.size.height
            assertTrue(w >= minPx && ht >= minPx, "Touch target too small (${node.tag()}): ${w}x${ht}px")
            val label =
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: node.config
                    .getOrNull(SemanticsProperties.Text)
                    ?.joinToString()
            assertTrue(!label.isNullOrBlank(), "Clickable without accessible label: ${node.tag()}")
        }
    }
}
