package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5: unit tests for the floating chat bubble.
 *
 * [BubbleVisibility.shouldShow] is pure, so the whole truth table runs on
 * the JVM. The [OverlayPosition] tests pin the shared-position reuse: the
 * bubble reads and writes the same remembered position the capsule uses,
 * one per orientation, clamped so at least half of it stays grabbable.
 */
class BubbleVisibilityTest {

    // -- BubbleVisibility truth table -------------------------------------

    @Test
    fun `shows when enabled, granted, backgrounded, idle and no island`() {
        assertTrue(
            BubbleVisibility.shouldShow(
                bubbleEnabled = true,
                overlayPermissionGranted = true,
                appInForeground = false,
                toolRunning = false,
                dynamicIslandActive = false,
            ),
        )
    }

    @Test
    fun `truth table - only the all-clear combination shows the orb`() {
        for (enabled in booleanArrayOf(true, false)) {
            for (granted in booleanArrayOf(true, false)) {
                for (foreground in booleanArrayOf(true, false)) {
                    for (toolRunning in booleanArrayOf(true, false)) {
                        for (island in booleanArrayOf(true, false)) {
                            val expected =
                                enabled && granted && !foreground && !toolRunning && !island
                            assertEquals(
                                "enabled=$enabled granted=$granted foreground=$foreground " +
                                    "toolRunning=$toolRunning island=$island",
                                expected,
                                BubbleVisibility.shouldShow(
                                    enabled,
                                    granted,
                                    foreground,
                                    toolRunning,
                                    island,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `hidden when the bubble toggle is off`() {
        assertFalse(
            BubbleVisibility.shouldShow(
                bubbleEnabled = false,
                overlayPermissionGranted = true,
                appInForeground = false,
                toolRunning = false,
                dynamicIslandActive = false,
            ),
        )
    }

    @Test
    fun `hidden when the overlay permission is not granted`() {
        assertFalse(
            BubbleVisibility.shouldShow(
                bubbleEnabled = true,
                overlayPermissionGranted = false,
                appInForeground = false,
                toolRunning = false,
                dynamicIslandActive = false,
            ),
        )
    }

    @Test
    fun `hidden while the app is in the foreground`() {
        assertFalse(
            BubbleVisibility.shouldShow(
                bubbleEnabled = true,
                overlayPermissionGranted = true,
                appInForeground = true,
                toolRunning = false,
                dynamicIslandActive = false,
            ),
        )
    }

    @Test
    fun `hidden while a tool is running - the capsule owns that state`() {
        assertFalse(
            BubbleVisibility.shouldShow(
                bubbleEnabled = true,
                overlayPermissionGranted = true,
                appInForeground = false,
                toolRunning = true,
                dynamicIslandActive = false,
            ),
        )
    }

    @Test
    fun `hidden while a Dynamic Island is active - the island replaces the overlay`() {
        assertFalse(
            BubbleVisibility.shouldShow(
                bubbleEnabled = true,
                overlayPermissionGranted = true,
                appInForeground = false,
                toolRunning = false,
                dynamicIslandActive = true,
            ),
        )
    }

    // -- OverlayPosition reuse (the shared bubble / capsule position) -----

    private class InMemoryStore : OverlayPosition.Store {
        private val saved = mutableMapOf<OverlayPosition.Orientation, OverlayPosition.Point>()

        override fun get(orientation: OverlayPosition.Orientation): OverlayPosition.Point? =
            saved[orientation]

        override fun set(orientation: OverlayPosition.Orientation, point: OverlayPosition.Point) {
            saved[orientation] = point
        }
    }

    private val default = OverlayPosition.Point(10, 1000)
    private val screenW = 1080
    private val screenH = 1920
    private val bubbleW = 200
    private val bubbleH = 200

    @Test
    fun `orientation is landscape only when wider than tall`() {
        assertEquals(
            OverlayPosition.Orientation.LANDSCAPE,
            OverlayPosition.Orientation.of(1920, 1080),
        )
        assertEquals(
            OverlayPosition.Orientation.PORTRAIT,
            OverlayPosition.Orientation.of(1080, 1920),
        )
        assertEquals(
            OverlayPosition.Orientation.PORTRAIT,
            OverlayPosition.Orientation.of(1080, 1080),
        )
    }

    @Test
    fun `displayed returns the default when nothing was saved`() {
        val store = InMemoryStore()
        assertEquals(
            default,
            OverlayPosition.displayed(store, default, screenW, screenH, bubbleW, bubbleH),
        )
    }

    @Test
    fun `displayed returns a saved point that is on screen`() {
        val store = InMemoryStore()
        OverlayPosition.dragEnded(store, screenW, screenH, OverlayPosition.Point(100, 200))
        assertEquals(
            OverlayPosition.Point(100, 200),
            OverlayPosition.displayed(store, default, screenW, screenH, bubbleW, bubbleH),
        )
    }

    @Test
    fun `dragEnded remembers one position per orientation`() {
        val store = InMemoryStore()
        OverlayPosition.dragEnded(store, screenW, screenH, OverlayPosition.Point(50, 60))
        assertEquals(
            OverlayPosition.Point(50, 60),
            store.get(OverlayPosition.Orientation.PORTRAIT),
        )
        assertNull(store.get(OverlayPosition.Orientation.LANDSCAPE))
        OverlayPosition.dragEnded(store, screenH, screenW, OverlayPosition.Point(70, 80))
        assertEquals(
            OverlayPosition.Point(70, 80),
            store.get(OverlayPosition.Orientation.LANDSCAPE),
        )
        assertEquals(
            OverlayPosition.Point(50, 60),
            store.get(OverlayPosition.Orientation.PORTRAIT),
        )
    }

    @Test
    fun `displayed pulls a point far off the left edge back to half visible`() {
        val store = InMemoryStore()
        OverlayPosition.dragEnded(store, screenW, screenH, OverlayPosition.Point(-5000, 300))
        assertEquals(
            OverlayPosition.Point(-100, 300),
            OverlayPosition.displayed(store, default, screenW, screenH, bubbleW, bubbleH),
        )
    }

    @Test
    fun `displayed pulls a point far past the right edge back to half visible`() {
        val store = InMemoryStore()
        OverlayPosition.dragEnded(store, screenW, screenH, OverlayPosition.Point(5000, 300))
        assertEquals(
            OverlayPosition.Point(980, 300),
            OverlayPosition.displayed(store, default, screenW, screenH, bubbleW, bubbleH),
        )
    }

    @Test
    fun `displayed keeps the whole bubble below the top edge`() {
        val store = InMemoryStore()
        OverlayPosition.dragEnded(store, screenW, screenH, OverlayPosition.Point(100, -50))
        assertEquals(
            OverlayPosition.Point(100, 0),
            OverlayPosition.displayed(store, default, screenW, screenH, bubbleW, bubbleH),
        )
    }

    @Test
    fun `displayed keeps the bubble clear of a top avoid inset`() {
        val store = InMemoryStore()
        OverlayPosition.dragEnded(store, screenW, screenH, OverlayPosition.Point(100, 10))
        assertEquals(
            OverlayPosition.Point(100, 80),
            OverlayPosition.displayed(
                store,
                default,
                screenW,
                screenH,
                bubbleW,
                bubbleH,
                OverlayPosition.Avoid(top = 80),
            ),
        )
    }
}
