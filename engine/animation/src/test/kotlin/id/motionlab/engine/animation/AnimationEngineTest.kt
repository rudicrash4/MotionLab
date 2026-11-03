package id.motionlab.engine.animation

import id.motionlab.core.common.MlException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InterpolationKindTest {
    private val eps = 1e-6

    private fun track(kind: InterpolationKind) =
        KeyframeTrack.of(listOf(Keyframe(0, 0.0, kind.create()), Keyframe(1_000_000, 100.0)))

    @Test
    fun everyKindRoundTripsThroughCreateAndKind() {
        for (k in InterpolationKind.entries) assertEquals(k, k.create().kind())
        assertEquals(InterpolationKind.BEZIER, Interpolation.Bezier(0.1, 0.2, 0.3, 0.4).kind())
    }

    @Test
    fun allSixModesEvaluateDifferentlyAtTheMidpointExceptSymmetricOnes() {
        val mid = 500_000L
        assertEquals(0.0, track(InterpolationKind.HOLD).evaluate(mid), eps)
        assertEquals(50.0, track(InterpolationKind.LINEAR).evaluate(mid), eps)
        assertTrue(track(InterpolationKind.EASE_IN).evaluate(mid) < 50.0)
        assertTrue(track(InterpolationKind.EASE_OUT).evaluate(mid) > 50.0)
        assertEquals(50.0, track(InterpolationKind.EASE_IN_OUT).evaluate(mid), 1e-4) // symmetric curve
        assertNotEquals(50.0, track(InterpolationKind.BEZIER).evaluate(mid), 1.0)
        for (k in InterpolationKind.entries) {
            assertEquals(0.0, track(k).evaluate(0), eps)
            assertEquals(100.0, track(k).evaluate(1_000_000), eps)
        }
    }

    @Test
    fun easeInOutIsMonotonicAndBezierMayOvershoot() {
        val t = track(InterpolationKind.EASE_IN_OUT)
        var prev = -1.0
        for (i in 0..100) {
            val v = t.evaluate(i * 10_000L)
            assertTrue(v >= prev)
            prev = v
        }
        val overshoot = KeyframeTrack.of(listOf(Keyframe(0, 0.0, Interpolation.Bezier(0.34, 1.56, 0.64, 1.0)), Keyframe(1_000_000, 100.0)))
        assertTrue((0..100).maxOf { overshoot.evaluate(it * 10_000L) } > 100.0)
    }
}

class KeyframeEditingTest {
    @Test
    fun keyframesHaveStableUniqueIdsThatSurviveMoveAndEdit() {
        val a = Keyframe(0, 1.0)
        val b = Keyframe(1_000, 2.0)
        assertNotEquals(a.id, b.id)
        val t = KeyframeTrack.of(listOf(a, b))
        val moved = t.replaced(a.copy(timeUs = 2_000, value = 5.0))
        assertEquals(listOf(b.id, a.id), moved.keyframes.map { it.id })
        assertEquals(5.0, moved.byId(a.id)!!.value, 0.0)
        assertEquals(2_000L, moved.byId(a.id)!!.timeUs)
    }

    @Test
    fun movingOntoAnotherKeyframeReplacesIt() {
        val a = Keyframe(0, 1.0)
        val b = Keyframe(1_000, 2.0)
        val t = KeyframeTrack.of(listOf(a, b)).replaced(a.copy(timeUs = 1_000))
        assertEquals(1, t.keyframes.size)
        assertEquals(a.id, t.keyframes[0].id)
    }

    @Test
    fun replacingUnknownKeyframeFails() {
        try {
            KeyframeTrack.EMPTY.replaced(Keyframe(0, 0.0))
            fail()
        } catch (e: MlException) {
        }
    }

    @Test
    fun navigationFindsNeighboursStrictly() {
        val t = KeyframeTrack.of(listOf(Keyframe(100, 0.0), Keyframe(200, 1.0), Keyframe(300, 2.0)))
        assertEquals(100L, t.previousBefore(200)!!.timeUs)
        assertEquals(300L, t.nextAfter(200)!!.timeUs)
        assertNull(t.previousBefore(100))
        assertNull(t.nextAfter(300))
        assertEquals(200L, t.at(200)!!.timeUs)
        assertNull(t.at(201))
    }

    @Test
    fun speedGraphIsTheDerivativeOfTheValueCurve() {
        val t = KeyframeTrack.of(listOf(Keyframe(0, 0.0), Keyframe(2_000_000, 100.0)))
        assertEquals(50.0, t.speedPerSecond(1_000_000), 1e-3) // 100 units over 2 s
        val ease = KeyframeTrack.of(listOf(Keyframe(0, 0.0, Easing.EASE_IN_OUT), Keyframe(1_000_000, 100.0)))
        assertTrue(ease.speedPerSecond(500_000) > ease.speedPerSecond(50_000)) // fastest in the middle
        assertEquals(0.0, KeyframeTrack.EMPTY.speedPerSecond(0), 0.0)
    }

    @Test
    fun withoutIdsRemovesOnlyThoseKeyframes() {
        val a = Keyframe(0, 0.0)
        val b = Keyframe(10, 1.0)
        val t = KeyframeTrack.of(listOf(a, b)).withoutIds(setOf(a.id))
        assertEquals(listOf(b.id), t.keyframes.map { it.id })
    }

    @Test
    fun propertyStateAnimatedBeatsConstantBeatsDefault() {
        val p = AnimatedProperties.NONE
        assertEquals(1.0, p.valueAt(PropertyNames.OPACITY, 0, 1.0), 0.0)
        val c = p.withConstant(PropertyNames.OPACITY, 0.4)
        assertEquals(0.4, c.valueAt(PropertyNames.OPACITY, 123, 1.0), 0.0)
        assertFalse(c.isAnimated(PropertyNames.OPACITY))
        val a = c.with(PropertyNames.OPACITY, KeyframeTrack.of(listOf(Keyframe(0, 0.0), Keyframe(100, 1.0))))
        assertTrue(a.isAnimated(PropertyNames.OPACITY))
        assertEquals(0.5, a.valueAt(PropertyNames.OPACITY, 50, 1.0), 1e-9)
        assertEquals(0.5, a.evaluate(50, mapOf(PropertyNames.OPACITY to 1.0)).getValue(PropertyNames.OPACITY), 1e-9)
        // an emptied track removes the animation
        assertFalse(a.with(PropertyNames.OPACITY, KeyframeTrack.EMPTY).isAnimated(PropertyNames.OPACITY))
    }

    @Test
    fun evaluationUsesTimeNotCallCount() {
        val t = KeyframeTrack.of(listOf(Keyframe(0, 0.0), Keyframe(1_000_000, 10.0)))
        repeat(1000) { t.evaluate(250_000) } // no hidden state: repeated calls cannot advance the animation
        assertEquals(2.5, t.evaluate(250_000), 1e-9)
        assertEquals(t.evaluate(250_000), t.evaluate(250_000), 0.0)
    }
}

class PropertyRegistryTest {
    @Test
    fun standardTransformPropertiesAreRegisteredWithDefaults() {
        for (name in PropertyRegistry.TRANSFORM) assertNotNull(name, PropertyRegistry.get(name))
        assertEquals(1.0, PropertyRegistry.defaultOf(PropertyNames.SCALE_X), 0.0)
        assertEquals(1.0, PropertyRegistry.defaultOf(PropertyNames.OPACITY), 0.0)
        assertEquals(0.0, PropertyRegistry.defaultOf(PropertyNames.SKEW_Y), 0.0)
    }

    @Test
    fun newPropertiesCanBeRegisteredAndClamped() {
        PropertyRegistry.register(PropertyDef("fx.blur", "Blur", 0.0, "Effects", "px", min = 0.0, max = 100.0))
        assertEquals(100.0, PropertyRegistry.get("fx.blur")!!.clamp(500.0), 0.0)
        assertEquals(0.0, PropertyRegistry.get("fx.blur")!!.clamp(-5.0), 0.0)
        assertTrue(PropertyRegistry.all().any { it.name == "fx.blur" })
        assertEquals(0.0, PropertyRegistry.defaults().getValue("fx.blur"), 0.0)
    }
}

class TransformTest {
    private val eps = 1e-9

    @Test
    fun identityValuesGiveIdentityMatrix() {
        val m = TransformValues().toMatrix()
        assertEquals(5.0, m.mapX(5.0, 7.0), eps)
        assertEquals(7.0, m.mapY(5.0, 7.0), eps)
    }

    @Test
    fun rotationIsClockwiseOnAYDownScreen() {
        val m = TransformValues(rotationDeg = 90.0).toMatrix()
        // the point to the right of the origin ends up BELOW it (y grows downwards)
        assertEquals(0.0, m.mapX(1.0, 0.0), eps)
        assertEquals(1.0, m.mapY(1.0, 0.0), eps)
    }

    @Test
    fun anchorPointIsTheFixedPointOfRotationAndScale() {
        val v = TransformValues(posX = 100.0, posY = 50.0, rotationDeg = 37.0, scaleX = 2.0, scaleY = 3.0, anchorX = 10.0, anchorY = -20.0)
        val m = v.toMatrix()
        // the anchor is mapped to the position
        assertEquals(100.0, m.mapX(10.0, -20.0), eps)
        assertEquals(50.0, m.mapY(10.0, -20.0), eps)
    }

    @Test
    fun scaleThenTranslateOrderIsCorrect() {
        val m = TransformValues(posX = 10.0, scaleX = 2.0, scaleY = 2.0).toMatrix()
        assertEquals(10.0 + 2.0 * 3.0, m.mapX(3.0, 0.0), eps)
    }

    @Test
    fun skewShearsAlongTheAxis() {
        val m = TransformValues(skewXDeg = 45.0).toMatrix()
        assertEquals(1.0 + 1.0 * 2.0, m.mapX(1.0, 2.0), 1e-9) // x' = x + tan(45)*y
        assertEquals(2.0, m.mapY(1.0, 2.0), 1e-9)
    }

    @Test
    fun inverseUndoesTheMatrixAndSingularHasNoInverse() {
        val m = TransformValues(posX = 3.0, posY = -4.0, rotationDeg = 20.0, scaleX = 1.5, scaleY = 0.5, skewXDeg = 10.0).toMatrix()
        val i = m.invert()!!
        val p = m * i
        assertEquals(1.0, p.a, 1e-9); assertEquals(0.0, p.b, 1e-9); assertEquals(0.0, p.c, 1e-9)
        assertEquals(1.0, p.d, 1e-9); assertEquals(0.0, p.e, 1e-9); assertEquals(0.0, p.f, 1e-9)
        assertNull(TransformValues(scaleX = 0.0).toMatrix().invert())
    }

    @Test
    fun decomposeReproducesTheMatrixForManyTransforms() {
        val cases = listOf(
            TransformValues(posX = 12.0, posY = -7.0, rotationDeg = 33.0, scaleX = 2.0, scaleY = 0.5, skewXDeg = 15.0, anchorX = 4.0, anchorY = 9.0),
            TransformValues(rotationDeg = -120.0, scaleX = 1.0, scaleY = 1.0),
            TransformValues(rotationDeg = 200.0, scaleX = -1.0, scaleY = 2.0, skewXDeg = -30.0),
            TransformValues(posX = 500.0, scaleX = 0.01, scaleY = 100.0, skewXDeg = 60.0, anchorX = -50.0),
        )
        for (v in cases) {
            val m = v.toMatrix()
            val d = TransformMath.decompose(m, v.anchorX, v.anchorY)!!
            val rebuilt = TransformValues(d.posX, d.posY, d.scaleX, d.scaleY, d.rotationDeg, v.anchorX, v.anchorY, d.skewXDeg, 0.0).toMatrix()
            for (pt in listOf(0.0 to 0.0, 10.0 to 0.0, 0.0 to 10.0, -33.0 to 21.0)) {
                assertEquals(m.mapX(pt.first, pt.second), rebuilt.mapX(pt.first, pt.second), 1e-6)
                assertEquals(m.mapY(pt.first, pt.second), rebuilt.mapY(pt.first, pt.second), 1e-6)
            }
        }
        assertNull(TransformMath.decompose(Mat3(0.0, 0.0, 0.0, 0.0), 0.0, 0.0))
    }

    @Test
    fun fromMapUsesRegistryDefaultsAndClampsOpacity() {
        val v = TransformValues.from(mapOf(PropertyNames.POSITION_X to 5.0, PropertyNames.OPACITY to 7.0))
        assertEquals(5.0, v.posX, 0.0)
        assertEquals(1.0, v.scaleX, 0.0)
        assertEquals(1.0, v.opacity, 0.0)
    }
}

class MotionPathTest {
    private val straight = MotionPath(listOf(PathPoint("a", 0.0, 0.0), PathPoint("b", 100.0, 0.0)))

    @Test
    fun needsTwoPointsAndUniqueIds() {
        try { MotionPath(listOf(PathPoint("a", 0.0, 0.0))); fail() } catch (_: MlException) {}
        try { MotionPath(listOf(PathPoint("a", 0.0, 0.0), PathPoint("a", 1.0, 1.0))); fail() } catch (_: MlException) {}
    }

    @Test
    fun straightPathIsArcLengthParameterised() {
        assertEquals(100.0, straight.lengthPx, 1e-6)
        val mid = straight.pointAt(0.5)
        assertEquals(50.0, mid.x, 1e-6)
        assertEquals(0.0, mid.y, 1e-6)
        assertEquals(0.0, mid.tangentDeg, 1e-6)
        assertEquals(0.0, straight.pointAt(0.0).x, 1e-9)
        assertEquals(100.0, straight.pointAt(1.0).x, 1e-9)
        assertEquals(100.0, straight.pointAt(7.0).x, 1e-9) // clamped
    }

    @Test
    fun handlesBendTheCurveAndEndpointsStayExact() {
        val curved = MotionPath(
            listOf(
                PathPoint("a", 0.0, 0.0, outDx = 0.0, outDy = 100.0),
                PathPoint("b", 100.0, 0.0, inDx = 0.0, inDy = 100.0),
            ),
        )
        assertTrue(curved.lengthPx > 100.0)
        val mid = curved.pointAt(0.5)
        assertEquals(50.0, mid.x, 1e-6)
        assertTrue(mid.y > 50.0) // bulges down (y grows downwards)
        assertEquals(0.0, curved.pointAt(0.0).x, 1e-9)
        assertEquals(100.0, curved.pointAt(1.0).x, 1e-9)
        assertEquals(90.0, curved.pointAt(0.0).tangentDeg, 1e-6) // leaves straight down
    }

    @Test
    fun speedIsConstantAlongTheArcRegardlessOfHandles() {
        val p = MotionPath(
            listOf(
                // Strong handles but no cusp: an S-curve. (A handle that folds the curve back on itself would make
                // the chord between samples shorter than the arc and say nothing about the parameterisation.)
                PathPoint("a", 0.0, 0.0, outDx = 60.0, outDy = 90.0),
                PathPoint("b", 100.0, 100.0, inDx = -60.0, inDy = -90.0),
            ),
        )
        var prev = p.pointAt(0.0)
        val steps = 100
        val dists = (1..steps).map { i ->
            val s = p.pointAt(i / steps.toDouble())
            val d = Math.hypot(s.x - prev.x, s.y - prev.y)
            prev = s
            d
        }
        val avg = dists.average()
        for (d in dists) assertEquals(avg, d, avg * 0.03)
    }

    @Test
    fun multiplePointsAreVisitedInOrder() {
        val p = MotionPath.smoothThrough(listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0))
        assertEquals(3, p.points.size)
        val end = p.pointAt(1.0)
        assertEquals(100.0, end.x, 1e-6)
        assertEquals(100.0, end.y, 1e-6)
        // the middle point lies on the path
        val onPath = p.flatten(48).minOf { Math.hypot(it.first - 100.0, it.second - 0.0) }
        assertTrue(onPath < 1.0)
    }

    @Test
    fun editingReturnsNewPathsAndKeepsRotationFlag() {
        val moved = straight.updatePoint(PathPoint("b", 200.0, 0.0))
        assertEquals(100.0, straight.lengthPx, 1e-6)
        assertEquals(200.0, moved.lengthPx, 1e-6)
        val extended = moved.addPoint(PathPoint("c", 200.0, 100.0))
        assertEquals(3, extended.points.size)
        assertEquals(300.0, extended.lengthPx, 1e-6)
        assertEquals(2, extended.removePoint("c").points.size)
        try { extended.removePoint("c").removePoint("b"); fail() } catch (_: MlException) {}
        assertTrue(straight.withRotateAlongPath(true).rotateAlongPath)
        assertTrue(straight.withRotateAlongPath(true).updatePoint(PathPoint("a", 1.0, 1.0)).rotateAlongPath)
        try { straight.updatePoint(PathPoint("zz", 0.0, 0.0)); fail() } catch (_: MlException) {}
    }

    @Test
    fun tangentFollowsTheDirectionOfTravel() {
        val diag = MotionPath(listOf(PathPoint("a", 0.0, 0.0), PathPoint("b", 100.0, 100.0)))
        assertEquals(45.0, diag.pointAt(0.3).tangentDeg, 1e-6)
        val up = MotionPath(listOf(PathPoint("a", 0.0, 0.0), PathPoint("b", 0.0, -100.0)))
        assertEquals(-90.0, up.pointAt(0.5).tangentDeg, 1e-6)
    }
}

class PresetTest {
    private fun build(id: String, clip: Long = 3_000_000, w: Int = 1920, h: Int = 1080) =
        PresetLibrary.get(id)!!.build(clip, w, h)

    @Test
    fun allCategoriesHavePresets() {
        for (c in PresetCategory.entries) assertTrue(c.name, PresetLibrary.byCategory(c).isNotEmpty())
    }

    @Test
    fun everyPresetProducesValidKeyframesInsideTheClip() {
        for (p in PresetLibrary.all()) {
            for (clip in listOf(200_000L, 3_000_000L)) {
                val r = p.build(clip, 1920, 1080)
                assertTrue(p.id, r.keyframes.isNotEmpty())
                for ((prop, kfs) in r.keyframes) {
                    assertNotNull(prop, PropertyRegistry.get(prop))
                    val track = KeyframeTrack.of(kfs) // throws unless strictly increasing
                    assertTrue(p.id, track.keyframes.first().timeUs >= 0)
                    assertTrue(p.id, track.keyframes.last().timeUs <= clip)
                    assertEquals(kfs.size, kfs.map { it.id }.toSet().size)
                }
            }
        }
    }

    @Test
    fun fadeInStartsTransparentAndEndsOpaqueAtThePresetDuration() {
        val r = build("fade_in")
        val t = KeyframeTrack.of(r.keyframes.getValue(PropertyNames.OPACITY))
        assertEquals(0.0, t.evaluate(0), 1e-9)
        assertEquals(1.0, t.evaluate(r.applied.durationUs), 1e-9)
        assertEquals(1.0, t.evaluate(3_000_000), 1e-9)
        assertEquals(0L, r.applied.startUs)
    }

    @Test
    fun outPresetsAreAnchoredToTheEndOfTheClip() {
        val r = build("fade_out", clip = 3_000_000)
        assertEquals(3_000_000L, r.applied.startUs + r.applied.durationUs)
        val t = KeyframeTrack.of(r.keyframes.getValue(PropertyNames.OPACITY))
        assertEquals(1.0, t.evaluate(r.applied.startUs), 1e-9)
        assertEquals(0.0, t.evaluate(3_000_000), 1e-9)
    }

    @Test
    fun presetDurationIsCappedToAShortClip() {
        val r = build("slide_in_left", clip = 200_000)
        assertEquals(200_000L, r.applied.durationUs)
    }

    @Test
    fun slideDistanceFollowsTheCanvas() {
        val narrow = build("slide_in_left", w = 500).keyframes.getValue(PropertyNames.POSITION_X).first().value
        val wide = build("slide_in_left", w = 2000).keyframes.getValue(PropertyNames.POSITION_X).first().value
        assertEquals(-500.0, narrow, 0.0)
        assertEquals(-2000.0, wide, 0.0)
    }

    @Test
    fun bounceLandsAtRestAndTouchesTheFloorRepeatedly() {
        val t = KeyframeTrack.of(build("bounce_in").keyframes.getValue(PropertyNames.POSITION_Y))
        assertEquals(0.0, t.keyframes.last().value, 0.0)
        assertTrue(t.keyframes.count { it.value == 0.0 } >= 3)
        assertTrue(t.keyframes.first().value < 0.0)
    }

    @Test
    fun wholeClipPresetsSpanTheClip() {
        val r = build("slow_zoom", clip = 4_000_000)
        assertEquals(0L, r.applied.startUs)
        assertEquals(4_000_000L, r.applied.durationUs)
        assertEquals(1.15, KeyframeTrack.of(r.keyframes.getValue(PropertyNames.SCALE_X)).evaluate(4_000_000), 1e-9)
    }

    @Test
    fun customPresetsCanBeRegisteredAndUseTheSameKeyframes() {
        PresetLibrary.register(
            AnimationPreset("test_nudge", "Nudge", PresetCategory.SMOOTH_MOVEMENT, PresetAnchor.IN, 100_000) { s, d, _, _ ->
                mapOf(PropertyNames.POSITION_X to listOf(Keyframe(s, 0.0), Keyframe(s + d, 10.0)))
            },
        )
        assertEquals(10.0, KeyframeTrack.of(build("test_nudge").keyframes.getValue(PropertyNames.POSITION_X)).evaluate(100_000), 0.0)
    }
}
