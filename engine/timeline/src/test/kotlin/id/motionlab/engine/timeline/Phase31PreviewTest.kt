package id.motionlab.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Phase 3.1: the 1:1 preview viewport and the touch <-> canvas mapping. Pure maths, no Android. */
class Phase31PreviewTest {
    @Test fun squareSideIsTheSmallerFreeDimension() {
        assertEquals(360.0, PreviewMath.squareSide(360.0, 500.0), 0.0)
        assertEquals(240.0, PreviewMath.squareSide(800.0, 240.0), 0.0)
        assertEquals(0.0, PreviewMath.squareSide(-5.0, 100.0), 0.0)
    }

    @Test fun landscapeCanvasIsLetterboxedInsideASquareWithoutDistortion() {
        val b = PreviewMath.letterbox(1000, 1000, 1920, 1080)
        assertEquals(1000, b[2]); assertEquals(562, b[3])
        assertEquals(0, b[0]); assertEquals(219, b[1])
        assertTrue(abs(b[2].toDouble() / b[3] - 1920.0 / 1080) < 0.01)
    }

    @Test fun portraitCanvasIsPillarboxedInsideASquare() {
        val b = PreviewMath.letterbox(1000, 1000, 1080, 1920)
        assertEquals(562, b[2]); assertEquals(1000, b[3])
        assertEquals(219, b[0]); assertEquals(0, b[1])
    }

    @Test fun squareCanvasFillsTheSquareExactly() {
        val b = PreviewMath.letterbox(800, 800, 1080, 1080)
        assertEquals(listOf(0, 0, 800, 800), b.toList())
    }

    @Test fun touchMappingRoundTripsAndHitsCanvasCentre() {
        val vm = PreviewMath.viewMap(1000, 1000, 1920, 1080)
        // The centre of the surface is the canvas origin.
        assertEquals(0.0, vm.toCanvasX(500.0), 1.0)
        assertEquals(0.0, vm.toCanvasY(500.0), 1.0)
        for ((x, y) in listOf(0.0 to 0.0, 300.0 to -120.0, -960.0 to 540.0, 959.0 to -539.0)) {
            assertEquals(x, vm.toCanvasX(vm.toScreenX(x)), 1e-9)
            assertEquals(y, vm.toCanvasY(vm.toScreenY(y)), 1e-9)
        }
    }

    @Test fun canvasCornersMapToTheLetterboxCorners() {
        val vm = PreviewMath.viewMap(1000, 1000, 1920, 1080)
        assertEquals(0.0, vm.toScreenX(-960.0), 1e-6)
        assertEquals(1000.0, vm.toScreenX(960.0), 1e-6)
        assertEquals(219.0, vm.toScreenY(-540.0), 1e-6)
        assertEquals(781.0, vm.toScreenY(540.0), 1.0)
    }

    @Test fun touchesInTheMarginAreOutsideTheCanvasButStillMapToCanvasCoordinates() {
        val vm = PreviewMath.viewMap(1000, 1000, 1920, 1080)
        assertTrue(!vm.insideCanvas(500.0, 100.0))
        assertTrue(vm.insideCanvas(500.0, 500.0))
        // Margin touches still give a (negative-out-of-range) canvas Y, so a handle dragged out of the canvas keeps working.
        assertTrue(vm.toCanvasY(100.0) < -540.0)
    }

    @Test fun dragDeltaInPixelsIsDividedByTheSameScaleOnBothAxes() {
        val vm = PreviewMath.viewMap(600, 600, 1080, 1920) // portrait canvas, k = 600/1920
        val dxCanvas = vm.toCanvasX(310.0) - vm.toCanvasX(300.0)
        val dyCanvas = vm.toCanvasY(310.0) - vm.toCanvasY(300.0)
        assertEquals("no stretching: equal pixel moves are equal canvas moves", dxCanvas, dyCanvas, 1e-9)
    }

    @Test fun mappingFollowsRotatedAndScaledItemsBecauseItOnlyConvertsSpaces() {
        // A clip placed at canvas (200, -100) must be drawn at the screen point the overlay converts back to the same value.
        val vm = PreviewMath.viewMap(720, 720, 1080, 1080)
        val sx = vm.toScreenX(200.0); val sy = vm.toScreenY(-100.0)
        assertEquals(200.0, vm.toCanvasX(sx), 1e-9)
        assertEquals(-100.0, vm.toCanvasY(sy), 1e-9)
        assertEquals(720.0 / 1080, vm.pxPerCanvasPx, 1e-9)
    }
}

/** Phase 3.1: after inserting media the timeline must scroll so the clip is visible. */
class Phase31RevealTest {
    @Test fun noHorizontalScrollWhenTheClipStartIsAlreadyVisible() {
        val v = TimelineViewport(100.0, 0)
        assertTrue(v.showsClipStart(2_000_000L, 600f))
        assertTrue(v.revealingClip(2_000_000L, 600f) === v || v.revealingClip(2_000_000L, 600f).scrollUs == v.scrollUs)
    }

    @Test fun scrollsRightSoAClipFarAwayBecomesVisible() {
        val v = TimelineViewport(100.0, 0)
        assertTrue(!v.showsClipStart(30_000_000L, 600f))
        val r = v.revealingClip(30_000_000L, 600f)
        assertTrue(r.showsClipStart(30_000_000L, 600f))
    }

    @Test fun scrollsLeftWhenTheClipStartIsBeforeTheVisibleRange() {
        val v = TimelineViewport(100.0, 20_000_000L)
        val r = v.revealingClip(5_000_000L, 600f)
        assertTrue(r.showsClipStart(5_000_000L, 600f))
    }

    @Test fun verticalRevealBringsTheRowIntoView() {
        assertEquals(0f, TimelineScroll.revealRow(0f, 60f, 0f, 120f), 0f)        // already visible
        assertEquals(60f, TimelineScroll.revealRow(180f, 240f, 0f, 180f), 0f)     // below: scroll down just enough
        assertEquals(60f, TimelineScroll.revealRow(60f, 120f, 200f, 100f), 0f)    // above: scroll up to the row top
    }
}
