package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.project.ProjectDocument
import id.motionlab.engine.animation.Easing
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeTrack
import id.motionlab.engine.animation.MotionPath
import id.motionlab.engine.animation.PathPoint
import id.motionlab.engine.animation.PropertyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun expectFail(block: () -> Unit): MlException {
    try {
        block()
    } catch (e: MlException) {
        return e
    }
    throw AssertionError("expected MlException")
}

private fun clip(id: String, start: Long, dur: Long, src: Long = 0, srcDur: Long? = null) =
    MediaClip(id, start, dur, assetId = "asset-$id", sourceStartUs = src, sourceDurationUs = srcDur)

private fun session(vararg tracks: Track, layers: List<Layer> = emptyList()) =
    EditorSession(Timeline(tracks.toList(), layers), ids = IdSource.sequential())

private fun vtrack(id: String, vararg clips: MediaClip, kind: TrackKind = TrackKind.VIDEO) = Track(id, id, kind, clips.toList())

class ClipOperationTest {
    @Test
    fun trimStartKeepsTheEndAndAdvancesTheSourceOffset() {
        val c = clip("a", 1_000_000, 4_000_000, src = 500_000)
        val t = c.trimmedStart(2_000_000, limitToSource = true)
        assertEquals(2_000_000L, t.startUs)
        assertEquals(c.endUs, t.endUs)
        assertEquals(1_500_000L, t.sourceStartUs)
        assertEquals("asset-a", t.assetId) // media reference untouched
    }

    @Test
    fun trimStartCannotGoBeforeTheStartOfTheSourceOrBeyondTheEnd() {
        val c = clip("a", 1_000_000, 4_000_000, src = 500_000)
        c.trimmedStart(500_000, true) // exactly to source start: allowed
        expectFail { c.trimmedStart(499_999, true) }
        expectFail { c.trimmedStart(c.endUs, true) }
        c.trimmedStart(0, limitToSource = false) // generated content may extend freely
    }

    @Test
    fun trimEndRespectsSourceLength() {
        val c = clip("a", 0, 2_000_000, src = 1_000_000, srcDur = 5_000_000)
        assertEquals(4_000_000L, c.maxEndUs(true))
        c.trimmedEnd(4_000_000, true)
        expectFail { c.trimmedEnd(4_000_001, true) }
        c.trimmedEnd(9_000_000, limitToSource = false)
        expectFail { c.trimmedEnd(5_000, true) } // shorter than the minimum
    }

    @Test
    fun trimStartRebasesKeyframesSoAnimationDoesNotSlide() {
        val anim = id.motionlab.engine.animation.AnimatedProperties.NONE.with(
            PropertyNames.OPACITY, KeyframeTrack.of(listOf(Keyframe(0, 0.0), Keyframe(2_000_000, 1.0))),
        )
        val c = clip("a", 0, 4_000_000).copy(properties = anim)
        val absoluteBefore = c.startUs + 1_000_000
        val before = c.properties.valueAt(PropertyNames.OPACITY, 1_000_000, 1.0)
        val t = c.trimmedStart(500_000, false)
        val after = t.properties.valueAt(PropertyNames.OPACITY, absoluteBefore - t.startUs, 1.0)
        assertEquals(before, after, 1e-9)
    }

    @Test
    fun splitKeepsBothHalvesContiguousAndGivesTheRightHalfNewKeyframeIds() {
        val anim = id.motionlab.engine.animation.AnimatedProperties.NONE.with(
            PropertyNames.POSITION_X, KeyframeTrack.of(listOf(Keyframe(0, 0.0), Keyframe(4_000_000, 400.0))),
        )
        val c = clip("a", 1_000_000, 4_000_000, src = 100).copy(properties = anim)
        val (l, r) = c.splitAt(3_000_000, "b")
        assertEquals(l.endUs, r.startUs)
        assertEquals(c.endUs, r.endUs)
        assertEquals(100L + 2_000_000, r.sourceStartUs)
        val leftIds = l.properties.track(PropertyNames.POSITION_X).keyframes.map { it.id }.toSet()
        val rightIds = r.properties.track(PropertyNames.POSITION_X).keyframes.map { it.id }.toSet()
        assertTrue(leftIds.intersect(rightIds).isEmpty())
        // the animation value at a given absolute time is the same before and after the split
        assertEquals(
            c.properties.valueAt(PropertyNames.POSITION_X, 3_500_000 - c.startUs, 0.0),
            r.properties.valueAt(PropertyNames.POSITION_X, 3_500_000 - r.startUs, 0.0), 1e-9,
        )
    }

    @Test
    fun duplicateGetsFreshKeyframeIdsAndSameReference() {
        val c = clip("a", 0, 1_000_000).copy(
            properties = id.motionlab.engine.animation.AnimatedProperties.NONE.with(
                PropertyNames.OPACITY, KeyframeTrack.of(listOf(Keyframe(0, 0.0))),
            ),
        )
        val d = c.duplicatedAs("b", 2_000_000)
        assertEquals("asset-a", d.assetId)
        assertNotEquals(c.properties.track(PropertyNames.OPACITY).keyframes[0].id, d.properties.track(PropertyNames.OPACITY).keyframes[0].id)
    }

    @Test
    fun trackFreeSpaceSearch() {
        val t = vtrack("v", clip("a", 1_000, 1_000), clip("b", 2_500, 500))
        assertTrue(t.isFree(0, 1_000))
        assertFalse(t.isFree(0, 1_001))
        assertEquals(0L, t.firstFreeStart(0, 1_000))
        assertEquals(3_000L, t.firstFreeStart(1_500, 1_000))
        assertEquals(2_000L, t.firstFreeStart(1_500, 500))
    }
}

class SnapAndMoveTest {
    private val tl = Timeline(listOf(vtrack("v", clip("a", 1_000_000, 1_000_000), clip("b", 5_000_000, 1_000_000))))

    @Test
    fun snapsToClipEdgesPlayheadAndZero() {
        val targets = SnapEngine.targets(tl, emptySet(), playheadUs = 3_500_000)
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L, 3_500_000L, 5_000_000L, 6_000_000L), targets.toList())
        val r = SnapEngine.snap(listOf(2_030_000L), targets, 50_000)
        assertEquals(-30_000L, r.deltaUs)
        assertEquals(2_000_000L, r.snappedTo)
        assertNull(SnapEngine.snap(listOf(2_500_000L), targets, 50_000).snappedTo)
        assertEquals(0L, SnapEngine.snap(listOf(2_500_000L), targets, 50_000).deltaUs)
    }

    @Test
    fun snapPicksTheClosestOfStartAndEnd() {
        val targets = SnapEngine.targets(tl, setOf("b"), playheadUs = 0)
        // moving clip [3_000_000, 4_000_000]: its start is 1_000 from nothing, its end 4_000_000 -> no target; use playhead
        val t2 = SnapEngine.targets(tl, setOf("b"), playheadUs = 4_010_000)
        val r = SnapEngine.snap(listOf(3_000_000L, 4_000_000L), t2, 50_000)
        assertEquals(10_000L, r.deltaUs)
        assertEquals(4_010_000L, r.snappedTo)
        assertTrue(targets.isNotEmpty())
    }

    @Test
    fun excludedClipsAreNotSnapTargets() {
        val targets = SnapEngine.targets(tl, setOf("a"), 0)
        assertFalse(targets.contains(1_000_000L))
    }

    @Test
    fun dragIsClampedAgainstNeighboursAndTimeZero() {
        assertEquals(-1_000_000L, MoveSolver.clampDelta(tl, setOf("a"), -5_000_000)) // stops at 0
        assertEquals(3_000_000L, MoveSolver.clampDelta(tl, setOf("a"), 9_000_000)) // a ends at 2s, b starts at 5s
        assertEquals(500_000L, MoveSolver.clampDelta(tl, setOf("a"), 500_000))
        // both together keep their spacing and may move as a block
        assertEquals(3_000_000L, MoveSolver.clampDelta(tl, setOf("a", "b"), 3_000_000))
        assertEquals(-1_000_000L, MoveSolver.clampDelta(tl, setOf("a", "b"), -3_000_000))
    }
}

class CommandTest {
    private fun base() = session(
        vtrack("v1", clip("a", 0, 2_000_000), clip("b", 3_000_000, 1_000_000)),
        vtrack("v2"),
        Track("au", "Audio", TrackKind.AUDIO, listOf(clip("m", 0, 1_000_000))),
    )

    @Test
    fun moveAndUndoRestoreExactState() {
        val s = base()
        val before = s.timeline
        s.execute(MoveClips(listOf("a"), 500_000))
        assertEquals(500_000L, s.timeline.requireClip("a").second.startUs)
        s.undo()
        assertEquals(before.requireClip("a").second, s.timeline.requireClip("a").second)
        s.redo()
        assertEquals(500_000L, s.timeline.requireClip("a").second.startUs)
    }

    @Test
    fun overlappingMoveFailsAndLeavesStateAndHistoryUntouched() {
        val s = base()
        val before = s.timeline
        expectFail { s.execute(MoveClips(listOf("a"), 1_500_000)) } // would overlap b
        assertSame(before, s.timeline)
        assertFalse(s.canUndo)
        expectFail { s.execute(MoveClips(listOf("a"), -1)) }
    }

    private fun assertSame(a: Any, b: Any) = assertTrue(a === b)

    @Test
    fun multiMoveKeepsSpacing() {
        val s = base()
        s.execute(MoveClips(listOf("a", "b"), 1_000_000))
        assertEquals(1_000_000L, s.timeline.requireClip("a").second.startUs)
        assertEquals(4_000_000L, s.timeline.requireClip("b").second.startUs)
    }

    @Test
    fun dragGestureIsOneUndoStep() {
        val s = base()
        for (d in listOf(100_000L, 200_000L, 300_000L, 400_000L)) s.execute(MoveClips(listOf("a"), d - 0))
        // each MoveClips is a delta from the CURRENT position: total 1_000_000
        assertEquals(1_000_000L, s.timeline.requireClip("a").second.startUs)
        s.undo()
        assertEquals(0L, s.timeline.requireClip("a").second.startUs)
        assertFalse(s.canUndo)
    }

    @Test
    fun endGestureStartsANewUndoStep() {
        val s = base()
        s.execute(MoveClips(listOf("a"), 100_000))
        s.endGesture()
        s.execute(MoveClips(listOf("a"), 100_000))
        s.undo()
        assertEquals(100_000L, s.timeline.requireClip("a").second.startUs)
    }

    @Test
    fun moveToOtherTrackRespectsKinds() {
        val s = base()
        s.execute(MoveClipToTrack("a", "v2", 1_000_000))
        assertNull(s.timeline.track("v1").clip("a"))
        assertEquals(1_000_000L, s.timeline.track("v2").clip("a")!!.startUs)
        assertEquals(ErrorCode.INVALID_ARGUMENT, expectFail { s.execute(MoveClipToTrack("a", "au", 0)) }.code)
        s.undo()
        assertNotNull(s.timeline.track("v1").clip("a"))
    }

    @Test
    fun trimCommandsAreUndoableAndCheckNeighbours() {
        val s = base()
        s.execute(TrimClipEnd("a", 2_900_000))
        assertEquals(2_900_000L, s.timeline.requireClip("a").second.endUs)
        expectFail { s.execute(TrimClipEnd("a", 3_500_000)) } // runs into b
        s.undo()
        assertEquals(2_000_000L, s.timeline.requireClip("a").second.endUs)
        s.execute(TrimClipStart("b", 3_500_000))
        assertEquals(500_000L, s.timeline.requireClip("b").second.sourceStartUs) // video track: source advances
    }

    @Test
    fun generatedClipsExtendFreelyAtTheStart() {
        val s = session(vtrack("t", clip("x", 2_000_000, 1_000_000), kind = TrackKind.TEXT))
        s.execute(TrimClipStart("x", 0))
        assertEquals(0L, s.timeline.requireClip("x").second.startUs)
        assertEquals(0L, s.timeline.requireClip("x").second.sourceStartUs)
    }

    @Test
    fun setDurationChangesEndOnly() {
        val s = base()
        s.execute(SetClipDuration("a", 1_500_000))
        assertEquals(0L, s.timeline.requireClip("a").second.startUs)
        assertEquals(1_500_000L, s.timeline.requireClip("a").second.durationUs)
        expectFail { s.execute(SetClipDuration("a", 0)) }
    }

    @Test
    fun splitAtPlayheadSplitsSelectionOrClipUnderPlayhead() {
        val s = base()
        s.seekTo(1_000_000)
        assertTrue(s.splitAtPlayhead())
        assertEquals(1_000_000L, s.timeline.requireClip("a").second.durationUs)
        assertEquals(2, s.timeline.track("v1").clips.count { it.endUs <= 3_000_000 })
        s.undo()
        assertEquals(2_000_000L, s.timeline.requireClip("a").second.durationUs)
        s.seekTo(2_500_000) // gap: nothing to split
        assertFalse(s.splitAtPlayhead())
    }

    @Test
    fun duplicatePlacesCopyAfterOriginalAtFreeSpot() {
        val s = base()
        s.select(Selection(clipIds = setOf("a")))
        assertTrue(s.duplicateSelection())
        val copies = s.selection.clipIds
        assertEquals(1, copies.size)
        val copy = s.timeline.requireClip(copies.first()).second
        assertEquals("asset-a", copy.assetId)
        assertEquals(4_000_000L, copy.startUs) // 2s..3s is only 1s wide, so it goes after b
        s.undo()
        assertEquals(2, s.timeline.track("v1").clips.size)
    }

    @Test
    fun deleteAndUndo() {
        val s = base()
        s.select(Selection(clipIds = setOf("a", "b")))
        assertTrue(s.deleteSelection())
        assertTrue(s.timeline.track("v1").clips.isEmpty())
        assertTrue(s.selection.isEmpty)
        s.undo()
        assertEquals(2, s.timeline.track("v1").clips.size)
    }

    @Test
    fun copyPasteKeepsRelativeTimingAndAvoidsOverlap() {
        val s = base()
        s.select(Selection(clipIds = setOf("a", "b")))
        assertTrue(s.copySelection())
        s.seekTo(0)
        assertTrue(s.paste(targetTrackId = "v2"))
        val pasted = s.timeline.track("v2").clips
        assertEquals(2, pasted.size)
        assertEquals(3_000_000L, pasted[1].startUs - pasted[0].startUs) // relative timing kept
        // pasting again at the same place must slide the block, not overlap
        assertTrue(s.paste(targetTrackId = "v2"))
        assertEquals(4, s.timeline.track("v2").clips.size)
    }

    @Test
    fun pasteRefusesIncompatibleOrLockedTracks() {
        val s = base()
        s.select(Selection(clipIds = setOf("a")))
        s.copySelection()
        expectFail { s.paste(targetTrackId = "au") }
        s.execute(SetTrackFlags("v2", locked = true))
        expectFail { s.paste(targetTrackId = "v2") }
    }

    @Test
    fun lockedTrackBlocksEditsButNotUnlocking() {
        val s = base()
        s.execute(SetTrackFlags("v1", locked = true))
        expectFail { s.execute(MoveClips(listOf("a"), 1)) }
        expectFail { s.execute(DeleteClips(listOf("a"))) }
        expectFail { s.execute(SetKeyframe(AnimationOwner.ClipOwner("a"), PropertyNames.OPACITY, Keyframe(0, 0.5))) }
        s.execute(SetTrackFlags("v1", locked = false))
        s.execute(MoveClips(listOf("a"), 1))
    }

    @Test
    fun trackAddRemoveReorderFlagsAreUndoable() {
        val s = base()
        val id = s.addTrack(TrackKind.TEXT)
        assertEquals("Text 1", s.timeline.track(id).name)
        s.execute(MoveTrack(id, 0))
        assertEquals(id, s.timeline.tracks[0].id)
        s.execute(SetTrackFlags("au", muted = true))
        assertTrue(s.timeline.track("au").muted)
        s.execute(SetTrackFlags("v2", visible = false, collapsed = true))
        assertFalse(s.timeline.track("v2").visible)
        s.execute(RemoveTrack("v2"))
        assertEquals(3, s.timeline.tracks.size)
        repeat(5) { s.undo() }
        assertEquals(listOf("v1", "v2", "au"), s.timeline.tracks.map { it.id })
        assertFalse(s.timeline.track("au").muted)
        assertFalse(s.canUndo)
    }

    @Test
    fun trackOrderingDefinesDrawOrder() {
        val s = session(
            vtrack("bottom", clip("a", 0, 1_000_000)),
            vtrack("top", clip("b", 0, 1_000_000)),
        )
        assertEquals(listOf("a", "b"), s.frameAt(0).items.map { it.clip.id })
        s.execute(MoveTrack("top", 0))
        assertEquals(listOf("b", "a"), s.frameAt(0).items.map { it.clip.id })
    }

    @Test
    fun hiddenTracksAndAudioTracksAreNotDrawn() {
        val s = base()
        s.execute(SetTrackFlags("v1", visible = false))
        assertTrue(s.frameAt(0).items.isEmpty()) // audio is never drawn
    }

    @Test
    fun failedCompositeDoesNotApplyPartially() {
        val s = base()
        val before = s.timeline
        expectFail { s.execute(CompositeCommand("x", listOf(MoveClips(listOf("a"), 100_000), MoveClips(listOf("zzz"), 1)))) }
        assertTrue(before === s.timeline)
    }

    @Test
    fun undoRedoOnEmptyHistoryFailsCleanly() {
        val s = base()
        expectFail { s.undo() }
        expectFail { s.redo() }
    }

    @Test
    fun newEditClearsRedo() {
        val s = base()
        s.execute(MoveClips(listOf("a"), 100_000))
        s.undo()
        assertTrue(s.canRedo)
        s.execute(MoveClips(listOf("a"), 200_000))
        assertFalse(s.canRedo)
    }

    @Test
    fun undoHistoryIsBounded() {
        val h = History(EditorState(Timeline(listOf(vtrack("v", clip("a", 0, 1_000))))), limit = 5)
        repeat(20) { h.execute(SetClipPayload("a", mapOf("n" to "$it")), allowMerge = false) }
        assertEquals(5, h.undoDepth)
    }

    @Test
    fun selectionIsPrunedWhenThingsDisappear() {
        val s = base()
        s.select(Selection(clipIds = setOf("a")))
        s.execute(DeleteClips(listOf("a")))
        assertTrue(s.selection.clipIds.isEmpty())
    }
}

class LayerTest {
    private fun sessionWithLayers(): EditorSession {
        val p = Layer("p", "Parent", LayerType.NULL)
        val c = Layer("c", "Child", parentId = "p")
        return session(Track("v", "v", TrackKind.VIDEO, listOf(clip("a", 0, 4_000_000)), layerId = "c"), layers = listOf(p, c))
    }

    @Test
    fun layersAndTracksAreSeparateConcepts() {
        val s = sessionWithLayers()
        assertEquals("c", s.timeline.track("v").layerId)
        assertEquals(2, s.timeline.layers.size)
        s.execute(LinkTrackToLayer("v", null))
        assertNull(s.timeline.track("v").layerId)
        assertEquals(2, s.timeline.layers.size) // detaching a track keeps the layer
    }

    @Test
    fun invalidHierarchyIsRejected() {
        try { Timeline(layers = listOf(Layer("a", "a", parentId = "b"), Layer("b", "b", parentId = "a"))); fail() } catch (_: IllegalArgumentException) {}
        try { Timeline(layers = listOf(Layer("a", "a", parentId = "zz"))); fail() } catch (_: IllegalArgumentException) {}
        try { Timeline(tracks = listOf(Track("t", "t", TrackKind.VIDEO, layerId = "none"))); fail() } catch (_: IllegalArgumentException) {}
        try { Layer("a", "a", parentId = "a"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun childInheritsParentTransformAndOpacity() {
        val s = sessionWithLayers()
        val p = AnimationOwner.LayerOwner("p")
        val c = AnimationOwner.LayerOwner("c")
        s.execute(SetPropertyValue(p, PropertyNames.POSITION_X, 0, 100.0))
        s.execute(SetPropertyValue(p, PropertyNames.OPACITY, 0, 0.5))
        s.execute(SetPropertyValue(c, PropertyNames.POSITION_X, 0, 30.0))
        s.execute(SetPropertyValue(c, PropertyNames.OPACITY, 0, 0.5))
        val item = s.frameAt(0).items.single()
        assertEquals(130.0, item.world.e, 1e-9)
        assertEquals(0.25, item.opacity, 1e-9)
    }

    @Test
    fun parentRotationAndScaleAffectChildPositionLikeAGroup() {
        val s = sessionWithLayers()
        s.execute(SetPropertyValue(AnimationOwner.LayerOwner("p"), PropertyNames.ROTATION, 0, 90.0))
        s.execute(SetPropertyValue(AnimationOwner.LayerOwner("p"), PropertyNames.SCALE_X, 0, 2.0))
        s.execute(SetPropertyValue(AnimationOwner.LayerOwner("p"), PropertyNames.SCALE_Y, 0, 2.0))
        s.execute(SetPropertyValue(AnimationOwner.LayerOwner("c"), PropertyNames.POSITION_X, 0, 10.0))
        val w = s.frameAt(0).items.single().world
        // child at (10,0) in parent space: scaled to (20,0), rotated 90deg clockwise on a y-down screen -> (0,20)
        assertEquals(0.0, w.mapX(0.0, 0.0), 1e-9)
        assertEquals(20.0, w.mapY(0.0, 0.0), 1e-9)
    }

    @Test
    fun hiddenLayerHidesItsSubtree() {
        val s = sessionWithLayers()
        s.execute(SetLayerFlags("p", visible = false))
        assertTrue(s.frameAt(0).items.isEmpty())
        s.undo()
        assertEquals(1, s.frameAt(0).items.size)
    }

    @Test
    fun layerAnimationUsesTimelineTimeNotClipTime() {
        val s = session(
            Track("v", "v", TrackKind.VIDEO, listOf(clip("a", 2_000_000, 2_000_000)), layerId = "g"),
            layers = listOf(Layer("g", "g", LayerType.GROUP)),
        )
        s.execute(SetKeyframe(AnimationOwner.LayerOwner("g"), PropertyNames.POSITION_X, Keyframe(0, 0.0)))
        s.execute(SetKeyframe(AnimationOwner.LayerOwner("g"), PropertyNames.POSITION_X, Keyframe(4_000_000, 400.0)))
        assertEquals(300.0, s.frameAt(3_000_000).items.single().world.e, 1e-9)
        s.execute(MoveClips(listOf("a"), 1_000_000)) // moving the clip does not move the group's animation
        assertEquals(300.0, s.frameAt(3_000_000).items.single().world.e, 1e-9)
    }

    @Test
    fun reparentKeepingVisualPositionDoesNotMoveTheObject() {
        val s = session(
            Track("v", "v", TrackKind.VIDEO, listOf(clip("a", 0, 4_000_000)), layerId = "c"),
            layers = listOf(Layer("p", "p", LayerType.NULL), Layer("c", "c")),
        )
        val p = AnimationOwner.LayerOwner("p")
        val c = AnimationOwner.LayerOwner("c")
        s.execute(SetPropertyValue(p, PropertyNames.POSITION_X, 0, 200.0))
        s.execute(SetPropertyValue(p, PropertyNames.POSITION_Y, 0, -50.0))
        s.execute(SetPropertyValue(p, PropertyNames.ROTATION, 0, 30.0))
        s.execute(SetPropertyValue(p, PropertyNames.SCALE_X, 0, 1.5))
        s.execute(SetPropertyValue(p, PropertyNames.SCALE_Y, 0, 0.75))
        s.execute(SetPropertyValue(c, PropertyNames.POSITION_X, 0, 40.0))
        s.execute(SetPropertyValue(c, PropertyNames.ROTATION, 0, 10.0))
        s.execute(SetPropertyValue(c, PropertyNames.ANCHOR_X, 0, 5.0))
        val before = s.frameAt(0).items.single().world
        s.execute(SetLayerParent("c", "p", keepVisual = true, atUs = 0))
        val after = s.frameAt(0).items.single().world
        for (pt in listOf(0.0 to 0.0, 50.0 to 0.0, 0.0 to 50.0, -20.0 to 30.0)) {
            assertEquals(before.mapX(pt.first, pt.second), after.mapX(pt.first, pt.second), 1e-6)
            assertEquals(before.mapY(pt.first, pt.second), after.mapY(pt.first, pt.second), 1e-6)
        }
        assertEquals("p", s.timeline.layer("c").parentId)
        // and back to the root
        s.execute(SetLayerParent("c", null, keepVisual = true, atUs = 0))
        val back = s.frameAt(0).items.single().world
        assertEquals(before.mapX(7.0, 9.0), back.mapX(7.0, 9.0), 1e-6)
        assertEquals(before.mapY(7.0, 9.0), back.mapY(7.0, 9.0), 1e-6)
    }

    @Test
    fun reparentWithoutKeepVisualMovesTheObjectWithTheNewParent() {
        val s = session(
            Track("v", "v", TrackKind.VIDEO, listOf(clip("a", 0, 4_000_000)), layerId = "c"),
            layers = listOf(Layer("p", "p", LayerType.NULL), Layer("c", "c")),
        )
        s.execute(SetPropertyValue(AnimationOwner.LayerOwner("p"), PropertyNames.POSITION_X, 0, 200.0))
        s.execute(SetLayerParent("c", "p", keepVisual = false))
        assertEquals(200.0, s.frameAt(0).items.single().world.e, 1e-9)
    }

    @Test
    fun reparentRefusesCyclesSingularParentsAndSkewY() {
        val s = session(layers = listOf(Layer("a", "a"), Layer("b", "b", parentId = "a")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, expectFail { s.execute(SetLayerParent("a", "b", false)) }.code)
        val s2 = session(layers = listOf(Layer("p", "p", LayerType.NULL), Layer("c", "c")))
        s2.execute(SetPropertyValue(AnimationOwner.LayerOwner("p"), PropertyNames.SCALE_X, 0, 0.0))
        assertEquals(ErrorCode.INVALID_ARGUMENT, expectFail { s2.execute(SetLayerParent("c", "p", true)) }.code)
        val s3 = session(layers = listOf(Layer("p", "p", LayerType.NULL), Layer("c", "c")))
        s3.execute(SetPropertyValue(AnimationOwner.LayerOwner("c"), PropertyNames.SKEW_Y, 0, 10.0))
        assertEquals(ErrorCode.UNSUPPORTED, expectFail { s3.execute(SetLayerParent("c", "p", true)) }.code)
        // the failed commands changed nothing
        assertNull(s3.timeline.layer("c").parentId)
    }

    @Test
    fun groupingKeepsChildrenInPlaceAndUngroupRestoresThem() {
        val s = session(
            Track("v1", "v1", TrackKind.VIDEO, listOf(clip("a", 0, 4_000_000)), layerId = "l1"),
            Track("v2", "v2", TrackKind.VIDEO, listOf(clip("b", 0, 4_000_000)), layerId = "l2"),
            layers = listOf(Layer("l1", "l1"), Layer("l2", "l2")),
        )
        s.execute(SetPropertyValue(AnimationOwner.LayerOwner("l1"), PropertyNames.POSITION_X, 0, 10.0))
        val before = s.frameAt(0).items.map { it.world }
        s.execute(GroupLayers(listOf("l1", "l2"), Layer("g", "Group", LayerType.GROUP)))
        assertEquals("g", s.timeline.layer("l1").parentId)
        assertEquals(before, s.frameAt(0).items.map { it.world })
        // moving the group moves both
        s.execute(SetPropertyValue(AnimationOwner.LayerOwner("g"), PropertyNames.POSITION_Y, 0, 100.0))
        assertEquals(listOf(100.0, 100.0), s.frameAt(0).items.map { it.world.f })
        // selecting the group selects every clip beneath it
        val sel = Selection().withLayerGroup(s.timeline, "g")
        assertEquals(setOf("a", "b"), sel.clipIds)
        s.execute(UngroupLayer("g", atUs = 0))
        assertNull(s.timeline.layer("l1").parentId)
        assertNull(s.timeline.findLayer("g"))
        assertEquals(100.0, s.frameAt(0).items.first().world.f, 1e-6) // visual position kept
    }

    @Test
    fun groupingRequiresSharedParent() {
        val s = session(layers = listOf(Layer("p", "p", LayerType.GROUP), Layer("a", "a", parentId = "p"), Layer("b", "b")))
        expectFail { s.execute(GroupLayers(listOf("a", "b"), Layer("g", "g", LayerType.GROUP))) }
        expectFail { s.execute(GroupLayers(listOf("a"), Layer("g", "g", LayerType.CONTENT))) }
    }

    @Test
    fun removeLayerNeedsCascadeWhenInUse() {
        val s = sessionWithLayers()
        expectFail { s.execute(RemoveLayer("p")) }
        s.execute(RemoveLayer("p", cascade = true))
        assertTrue(s.timeline.layers.isEmpty())
        assertNull(s.timeline.track("v").layerId) // clip survives, detached
        assertEquals(1, s.timeline.track("v").clips.size)
        s.undo()
        assertEquals(2, s.timeline.layers.size)
    }
}

class AnimationIntegrationTest {
    private val owner = AnimationOwner.ClipOwner("a")
    private fun s() = session(vtrack("v", clip("a", 2_000_000, 4_000_000)))

    @Test
    fun playheadDecidesTheEvaluatedValueUsingClipLocalTime() {
        val s = s()
        s.seekTo(2_000_000)
        s.addKeyframeAtPlayhead(owner, PropertyNames.OPACITY)           // local 0, value 1.0
        s.seekTo(4_000_000)
        s.setValueAtPlayhead(owner, PropertyNames.OPACITY, 0.0)           // animated now -> auto-keyframe at local 2s
        assertEquals(2, s.timeline.requireClip("a").second.properties.track(PropertyNames.OPACITY).keyframes.size)
        assertEquals(0.5, s.frameAt(3_000_000).items.single().opacity, 1e-9)
        assertEquals(1.0, s.frameAt(2_000_000).items.single().opacity, 1e-9)
        assertEquals(0.0, s.frameAt(5_000_000).items.single().opacity, 1e-9) // held after the last keyframe
    }

    @Test
    fun staticValueChangeDoesNotCreateKeyframes() {
        val s = s()
        s.seekTo(3_000_000)
        s.setValueAtPlayhead(owner, PropertyNames.POSITION_X, 80.0)
        val props = s.timeline.requireClip("a").second.properties
        assertTrue(props.track(PropertyNames.POSITION_X).isEmpty)
        assertEquals(80.0, s.frameAt(2_000_000).items.single().world.e, 1e-9) // the same at every time
        assertEquals(80.0, s.frameAt(5_000_000).items.single().world.e, 1e-9)
    }

    @Test
    fun valueAtPlayheadFeedsTheStopwatchKeyframe() {
        val s = s()
        s.seekTo(3_000_000)
        s.setValueAtPlayhead(owner, PropertyNames.POSITION_X, 80.0)
        s.addKeyframeAtPlayhead(owner, PropertyNames.POSITION_X)
        val k = s.timeline.requireClip("a").second.properties.track(PropertyNames.POSITION_X).keyframes.single()
        assertEquals(1_000_000L, k.timeUs)
        assertEquals(80.0, k.value, 0.0)
    }

    @Test
    fun moveKeyframesKeepsIdsAndRelativeTiming() {
        val s = s()
        val ids = listOf(0L, 1_000_000L, 2_000_000L).map { t ->
            val kf = Keyframe(t, t / 10_000.0)
            s.execute(SetKeyframe(owner, PropertyNames.POSITION_X, kf)); kf.id
        }
        val refs = ids.map { KeyframeRef(owner, PropertyNames.POSITION_X, it) }
        s.execute(MoveKeyframes(refs.take(2), 500_000))
        val t = s.timeline.requireClip("a").second.properties.track(PropertyNames.POSITION_X)
        assertEquals(listOf(500_000L, 1_500_000L, 2_000_000L), t.keyframes.map { it.timeUs })
        assertEquals(ids[0], t.keyframes[0].id)
        s.undo()
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L), s.timeline.requireClip("a").second.properties.track(PropertyNames.POSITION_X).keyframes.map { it.timeUs })
    }

    @Test
    fun movingAKeyframeOntoAnotherReplacesItAndUndoBringsItBack() {
        val s = s()
        val a = Keyframe(0, 0.0); val b = Keyframe(1_000_000, 1.0)
        s.execute(SetKeyframe(owner, PropertyNames.OPACITY, a)); s.execute(SetKeyframe(owner, PropertyNames.OPACITY, b))
        s.execute(MoveKeyframes(listOf(KeyframeRef(owner, PropertyNames.OPACITY, a.id)), 1_000_000))
        assertEquals(1, s.timeline.requireClip("a").second.properties.track(PropertyNames.OPACITY).keyframes.size)
        s.undo()
        assertEquals(2, s.timeline.requireClip("a").second.properties.track(PropertyNames.OPACITY).keyframes.size)
    }

    @Test
    fun valueAndInterpolationEditsAreUndoable() {
        val s = s()
        val a = Keyframe(0, 0.0); val b = Keyframe(1_000_000, 100.0)
        s.execute(SetKeyframe(owner, PropertyNames.POSITION_X, a)); s.execute(SetKeyframe(owner, PropertyNames.POSITION_X, b))
        val ra = KeyframeRef(owner, PropertyNames.POSITION_X, a.id)
        s.execute(SetKeyframeInterpolation(listOf(ra), Easing.EASE_IN))
        assertTrue(s.frameAt(2_500_000).items.single().world.e < 50.0)
        s.execute(SetKeyframeValue(KeyframeRef(owner, PropertyNames.POSITION_X, b.id), 200.0))
        assertEquals(200.0, s.timeline.requireClip("a").second.properties.track(PropertyNames.POSITION_X).byId(b.id)!!.value, 0.0)
        s.undo(); s.undo()
        assertEquals(Interpolation.Linear, s.timeline.requireClip("a").second.properties.track(PropertyNames.POSITION_X).byId(a.id)!!.interpolation)
    }

    @Test
    fun deleteAndDuplicateKeyframes() {
        val s = s()
        val a = Keyframe(0, 0.0); val b = Keyframe(1_000_000, 1.0)
        s.execute(SetKeyframe(owner, PropertyNames.OPACITY, a)); s.execute(SetKeyframe(owner, PropertyNames.OPACITY, b))
        s.select(Selection(keyframes = setOf(KeyframeRef(owner, PropertyNames.OPACITY, a.id))))
        assertTrue(s.duplicateSelection())
        val t = s.timeline.requireClip("a").second.properties.track(PropertyNames.OPACITY)
        assertEquals(3, t.keyframes.size)
        assertEquals(500_000L, t.keyframes[1].timeUs)
        assertEquals(1, s.selection.keyframes.size)
        assertTrue(s.deleteSelection())
        assertEquals(2, s.timeline.requireClip("a").second.properties.track(PropertyNames.OPACITY).keyframes.size)
    }

    @Test
    fun copyPasteKeyframesKeepsPropertyAndTimingAcrossClips() {
        val s = session(vtrack("v", clip("a", 0, 4_000_000), clip("b", 5_000_000, 4_000_000)))
        val a = AnimationOwner.ClipOwner("a")
        val k1 = Keyframe(500_000, 10.0); val k2 = Keyframe(1_500_000, 50.0); val k3 = Keyframe(1_000_000, 0.3)
        s.execute(SetKeyframe(a, PropertyNames.POSITION_X, k1)); s.execute(SetKeyframe(a, PropertyNames.POSITION_X, k2))
        s.execute(SetKeyframe(a, PropertyNames.OPACITY, k3))
        s.select(Selection(keyframes = setOf(
            KeyframeRef(a, PropertyNames.POSITION_X, k1.id), KeyframeRef(a, PropertyNames.POSITION_X, k2.id), KeyframeRef(a, PropertyNames.OPACITY, k3.id),
        )))
        assertTrue(s.copySelection())
        s.seekTo(6_000_000) // 1s into clip b
        assertTrue(s.paste(keyframeOwner = AnimationOwner.ClipOwner("b")))
        val b = s.timeline.requireClip("b").second.properties
        assertEquals(listOf(1_000_000L, 2_000_000L), b.track(PropertyNames.POSITION_X).keyframes.map { it.timeUs })
        assertEquals(listOf(10.0, 50.0), b.track(PropertyNames.POSITION_X).keyframes.map { it.value })
        assertEquals(1_500_000L, b.track(PropertyNames.OPACITY).keyframes.single().timeUs) // 0.5s after the earliest + 1s
        assertTrue(b.track(PropertyNames.POSITION_X).keyframes.map { it.id }.intersect(s.timeline.requireClip("a").second.properties.track(PropertyNames.POSITION_X).keyframes.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun clearAnimationBakesTheCurrentValue() {
        val s = s()
        s.execute(SetKeyframe(owner, PropertyNames.POSITION_X, Keyframe(0, 0.0)))
        s.execute(SetKeyframe(owner, PropertyNames.POSITION_X, Keyframe(2_000_000, 100.0)))
        s.execute(ClearPropertyAnimation(owner, PropertyNames.POSITION_X, 1_000_000))
        val p = s.timeline.requireClip("a").second.properties
        assertFalse(p.isAnimated(PropertyNames.POSITION_X))
        assertEquals(50.0, p.valueAt(PropertyNames.POSITION_X, 0, 0.0), 1e-9)
    }

    @Test
    fun keyframeNavigationFindsNeighboursAcrossProperties() {
        val s = s()
        s.execute(SetKeyframe(owner, PropertyNames.OPACITY, Keyframe(500_000, 1.0)))
        s.execute(SetKeyframe(owner, PropertyNames.POSITION_X, Keyframe(1_500_000, 1.0)))
        s.seekTo(3_000_000) // local 1s
        assertEquals(2_500_000L, s.previousKeyframeTimeUs(owner))
        assertEquals(3_500_000L, s.nextKeyframeTimeUs(owner))
        s.seekTo(2_000_000)
        assertNull(s.previousKeyframeTimeUs(owner))
    }

    @Test
    fun presetBecomesOrdinaryEditableKeyframesAndIsUndoable() {
        val s = s()
        s.applyPreset(owner, "fade_in")
        val clipNow = s.timeline.requireClip("a").second
        assertTrue(clipNow.properties.isAnimated(PropertyNames.OPACITY))
        assertEquals("fade_in", clipNow.presets.single().presetId)
        assertEquals(0.0, s.frameAt(2_000_000).items.single().opacity, 1e-9)
        assertEquals(1.0, s.frameAt(2_000_000 + clipNow.presets.single().durationUs).items.single().opacity, 1e-9)
        // the keyframes are selected and can be edited like any others
        val ref = s.selection.keyframes.first()
        s.execute(SetKeyframeValue(ref, 0.3))
        s.undo()
        s.undo()
        assertFalse(s.timeline.requireClip("a").second.properties.isAnimated(PropertyNames.OPACITY))
        assertTrue(s.timeline.requireClip("a").second.presets.isEmpty())
        expectFail { s.execute(ApplyPreset(owner, "nope", 1920, 1080)) }
    }

    @Test
    fun outPresetsLandAtTheEndOfTheClip() {
        val s = s()
        s.applyPreset(owner, "slide_out_right")
        val c = s.timeline.requireClip("a").second
        val local = c.durationUs
        assertEquals(1920.0, c.properties.valueAt(PropertyNames.POSITION_X, local, 0.0), 1e-9)
        assertEquals(0.0, c.properties.valueAt(PropertyNames.POSITION_X, 0, 0.0), 1e-9)
    }

    @Test
    fun motionPathMovesTheObjectAndAddsTheOffset() {
        val s = s()
        val path = MotionPath(listOf(PathPoint("p0", -100.0, 0.0), PathPoint("p1", 100.0, 0.0)))
        s.execute(SetMotionPath(owner, path))
        s.execute(SetKeyframe(owner, PropertyNames.MOTION_PROGRESS, Keyframe(0, 0.0)))
        s.execute(SetKeyframe(owner, PropertyNames.MOTION_PROGRESS, Keyframe(4_000_000, 1.0)))
        assertEquals(-100.0, s.frameAt(2_000_000).items.single().world.e, 1e-6)
        assertEquals(0.0, s.frameAt(4_000_000).items.single().world.e, 1e-6)
        assertEquals(100.0, s.frameAt(5_999_999).items.single().world.e, 1.0)
        s.execute(SetPropertyValue(owner, PropertyNames.POSITION_Y, 0, 25.0))
        assertEquals(25.0, s.frameAt(4_000_000).items.single().world.f, 1e-6)
        s.execute(SetMotionPath(owner, null))
        assertEquals(0.0, s.frameAt(4_000_000).items.single().world.e, 1e-6)
    }

    @Test
    fun rotationAlongThePathFollowsItsHeading() {
        val s = s()
        s.execute(SetMotionPath(owner, MotionPath(listOf(PathPoint("a", 0.0, 0.0), PathPoint("b", 0.0, 100.0)), rotateAlongPath = true)))
        val w = s.frameAt(2_000_000).items.single().world
        // heading straight down = +90 degrees: x axis of the object points down the screen
        assertEquals(0.0, w.a, 1e-9)
        assertEquals(1.0, w.b, 1e-9)
    }

    @Test
    fun animationIsFrameRateIndependentAndDeterministic() {
        val s = s()
        s.execute(SetKeyframe(owner, PropertyNames.POSITION_X, Keyframe(0, 0.0)))
        s.execute(SetKeyframe(owner, PropertyNames.POSITION_X, Keyframe(4_000_000, 400.0)))
        val at = s.frameAt(4_000_000).items.single().world.e
        // sampling at 24 fps, 30 fps and 144 fps all agree on the value at the same instant
        for (fps in listOf(24.0, 30.0, 144.0)) {
            val frames = (2.0 * fps).toInt()
            val t = (frames / fps * 1_000_000).toLong() + 2_000_000
            assertEquals((t - 2_000_000) / 10_000.0, s.frameAt(t).items.single().world.e, 0.01)
        }
        assertEquals(at, s.frameAt(4_000_000).items.single().world.e, 0.0)
    }

    @Test
    fun clockDrivesThePlayheadAndStopsAtTheEnd() {
        var now = 0L
        val clock = TimelineClock { now }
        val s = EditorSession(Timeline(listOf(vtrack("v", clip("a", 0, 2_000_000)))), clock = clock)
        s.play()
        now = 1_000_000_000L // 1 s
        assertEquals(1_000_000L, s.currentTimeUs)
        assertEquals(1, s.frame().items.size)
        now = 5_000_000_000L
        val f = s.frame()
        assertEquals(2_000_000L, f.timeUs)
        assertFalse(s.isPlaying)
        s.seekTo(500_000)
        assertEquals(500_000L, s.currentTimeUs)
    }
}

class ViewportTest {
    @Test
    fun timeAndPixelsRoundTrip() {
        val v = TimelineViewport(200.0, 1_000_000)
        assertEquals(0f, v.timeToPx(1_000_000), 0.01f)
        assertEquals(200f, v.timeToPx(2_000_000), 0.01f)
        assertEquals(3_000_000L, v.pxToTime(400f))
        assertEquals(1_000_000L, v.pxToDuration(200f))
    }

    @Test
    fun zoomKeepsTheTimeUnderTheFingerFixed() {
        val v = TimelineViewport(100.0, 2_000_000)
        val focus = 300f
        val t = v.pxToTime(focus)
        val z = v.zoomed(2.0, focus)
        assertEquals(200.0, z.pxPerSecond, 1e-9)
        assertTrue(kotlin.math.abs(t - z.pxToTime(focus)) <= 2)
        val out = z.zoomed(0.001, focus)
        assertEquals(TimelineViewport.MIN_PX_PER_SECOND, out.pxPerSecond, 1e-9) // clamped
        assertTrue(out.scrollUs >= 0)
    }

    @Test
    fun scrollNeverGoesNegativeAndRevealFollowsThePlayhead() {
        val v = TimelineViewport(100.0, 0)
        assertEquals(0L, v.scrolledByPx(-500f).scrollUs)
        assertEquals(1_000_000L, v.scrolledByPx(100f).scrollUs)
        val r = v.revealing(20_000_000, 400f)
        assertTrue(r.timeToPx(20_000_000) in 0f..400f)
        assertTrue(v.revealing(1_000_000, 400f) === v)
    }

    @Test
    fun rulerStepGrowsWhenZoomedOut() {
        assertTrue(TimelineViewport(1000.0).rulerStepUs() < TimelineViewport(10.0).rulerStepUs())
        assertTrue(TimelineViewport(10.0).durationToPx(TimelineViewport(10.0).rulerStepUs()) >= 64f)
    }

    @Test
    fun visibleRangeCoversOnlyWhatIsOnScreen() {
        val r = TimelineViewport(100.0, 5_000_000).visibleRange(300f)
        assertEquals(5_000_000L, r.first)
        assertEquals(8_000_000L, r.last)
    }

    @Test
    fun timecodeFormatsFramesAndHours() {
        assertEquals("00:00:00", Timecode.format(0, 30.0))
        assertEquals("00:01:15", Timecode.format(1_500_000, 30.0))
        assertEquals("01:05:00", Timecode.format(65_000_000, 30.0))
        assertEquals("1:01:01:00", Timecode.format(3_661_000_000, 30.0))
        assertEquals(33_333L, Timecode.snapToFrame(35_000, 30.0))
    }
}

class PersistenceTest {
    private fun doc() = ProjectDocument(id = "p", name = "n", createdAtMs = 1, modifiedAtMs = 1)

    @Test
    fun everythingSurvivesSaveAndLoad() {
        val s = EditorSession(
            Timeline(
                listOf(
                    Track("v", "v", TrackKind.TEXT, listOf(clip("a", 1_000_000, 4_000_000)), layerId = "c"),
                    Track("w", "w", TrackKind.VIDEO, emptyList(), muted = true, locked = true, visible = false, collapsed = true, layerId = "p"),
                ),
                listOf(Layer("p", "p", LayerType.GROUP), Layer("c", "c", parentId = "p")),
            ),
            ids = IdSource.sequential(),
        )
        val a = AnimationOwner.ClipOwner("a")
        s.execute(SetKeyframe(a, PropertyNames.POSITION_X, Keyframe(0, 5.0, Easing.EASE_IN_OUT)))
        s.execute(SetKeyframe(a, PropertyNames.POSITION_X, Keyframe(1_000_000, 50.0, Interpolation.Hold)))
        s.execute(SetKeyframe(a, PropertyNames.OPACITY, Keyframe(0, 0.2, Interpolation.Bezier(0.1, 0.2, 0.3, 1.4))))
        s.execute(SetPropertyValue(a, PropertyNames.SCALE_X, 0, 1.5))
        s.execute(SetMotionPath(a, MotionPath.smoothThrough(listOf(0.0 to 0.0, 50.0 to 80.0, 120.0 to 0.0)).withRotateAlongPath(true)))
        s.execute(SetClipPayload("a", mapOf("text" to "Halo", "color" to "#FF8800")))
        s.execute(SetKeyframe(AnimationOwner.LayerOwner("p"), PropertyNames.ROTATION, Keyframe(0, 10.0)))
        s.applyPreset(a, "bounce_in")
        val saved = s.toDocument(doc())
        val loaded = EditorSession.fromDocument(saved, IdSource.sequential())
        assertEquals(s.timeline.tracks.map { it.id }, loaded.timeline.tracks.map { it.id })
        val t0 = s.timeline.track("w"); val t1 = loaded.timeline.track("w")
        assertEquals(listOf(t0.muted, t0.locked, t0.visible, t0.collapsed, t0.layerId, t0.kind), listOf(t1.muted, t1.locked, t1.visible, t1.collapsed, t1.layerId, t1.kind))
        assertEquals(s.timeline.requireClip("a").second, loaded.timeline.requireClip("a").second) // incl. keyframe ids, paths, presets, payload
        assertEquals(s.timeline.layers, loaded.timeline.layers)
        for (t in listOf(0L, 700_000L, 2_500_000L, 3_999_999L)) {
            assertEquals(s.frameAt(1_000_000 + t).items.single().world, loaded.frameAt(1_000_000 + t).items.single().world)
        }
        assertEquals(saved.config.durationUs, 5_000_000L)
    }

    @Test
    fun schemaOneDocumentsLoadWithDefaults() {
        val old = doc().copy(
            schemaVersion = 1,
            tracks = listOf(
                id.motionlab.core.project.TrackDto(
                    "v", "v", "VIDEO",
                    clips = listOf(
                        id.motionlab.core.project.ClipDto(
                            "a", "asset", 0, 1_000_000,
                            properties = mapOf("opacity" to listOf(id.motionlab.core.project.KeyframeDto(0, 0.0), id.motionlab.core.project.KeyframeDto(500_000, 1.0))),
                        ),
                    ),
                ),
            ),
        )
        val s = EditorSession.fromDocument(old)
        val t = s.timeline.track("v")
        assertTrue(t.visible && !t.collapsed && t.layerId == null)
        assertTrue(s.timeline.layers.isEmpty())
        val kfs = t.clip("a")!!.properties.track("opacity").keyframes
        assertEquals(2, kfs.size)
        assertTrue(kfs.all { it.id.startsWith("kf-") })
        assertEquals(0.5, s.frameAt(250_000).items.single().opacity, 1e-9)
    }

    @Test
    fun unknownKindsAndCorruptPathsAreReportedNotCrashed() {
        val bad = doc().copy(tracks = listOf(id.motionlab.core.project.TrackDto("v", "v", "HOLOGRAM")))
        assertEquals(ErrorCode.UNSUPPORTED, expectFail { EditorSession.fromDocument(bad) }.code)
        val badPath = doc().copy(
            tracks = listOf(
                id.motionlab.core.project.TrackDto(
                    "v", "v", "VIDEO",
                    clips = listOf(id.motionlab.core.project.ClipDto("a", null, 0, 1_000, motionPath = id.motionlab.core.project.MotionPathDto(emptyList()))),
                ),
            ),
        )
        assertEquals(ErrorCode.CORRUPT_DATA, expectFail { EditorSession.fromDocument(badPath) }.code)
        val cycle = doc().copy(
            layers = listOf(
                id.motionlab.core.project.LayerDto("a", "a", parentId = "b"),
                id.motionlab.core.project.LayerDto("b", "b", parentId = "a"),
            ),
        )
        assertEquals(ErrorCode.CORRUPT_DATA, expectFail { EditorSession.fromDocument(cycle) }.code)
    }

    @Test
    fun loadResetsHistoryAndPlayhead() {
        val s = session(vtrack("v", clip("a", 0, 1_000_000)))
        s.execute(MoveClips(listOf("a"), 10))
        s.seekTo(500_000)
        s.load(doc())
        assertFalse(s.canUndo)
        assertEquals(0L, s.currentTimeUs)
        assertTrue(s.timeline.tracks.isEmpty())
    }

    @Test
    fun listenersAreNotifiedOnEveryChange() {
        val s = session(vtrack("v", clip("a", 0, 1_000_000)))
        var n = 0
        s.addListener { n++ }
        s.execute(MoveClips(listOf("a"), 10))
        s.undo()
        s.redo()
        s.select(Selection(clipIds = setOf("a")))
        assertEquals(4, n)
    }
}

class PreviewMathTest {
    private fun project(m: id.motionlab.engine.animation.Mat3, w: Double, h: Double, px: Double, py: Double): Pair<Double, Double> {
        val q = PreviewMath.quadMatrix(m, w, h, 1000, 500)
        // vertex (px,py) of the unit quad -> clip space
        return (q[0] * px + q[4] * py + q[12]).toDouble() to (q[1] * px + q[5] * py + q[13]).toDouble()
    }

    @Test
    fun identityWorldCentresTheContentBoxOnTheCanvas() {
        val id = id.motionlab.engine.animation.Mat3.IDENTITY
        // a 500x250 box on a 1000x500 canvas covers the middle quarter: its top-right corner (quad +1,+1) lands at (+0.5, +0.5)
        val (x, y) = project(id, 500.0, 250.0, 1.0, 1.0)
        assertEquals(0.5, x, 1e-6)
        assertEquals(0.5, y, 1e-6) // quad top = screen top = clip +y
    }

    @Test
    fun positiveWorldYMovesDownTheScreen() {
        val moved = id.motionlab.engine.animation.Mat3.translate(0.0, 100.0)
        val (_, y) = project(moved, 100.0, 100.0, 0.0, 0.0)
        assertEquals(-100.0 / 250.0, y, 1e-6) // y-down canvas -> negative clip y
    }

    @Test
    fun letterboxFitsAndCentres() {
        val r = PreviewMath.letterbox(1000, 1000, 1920, 1080)
        assertEquals(1000, r[2]); assertEquals(562, r[3]); assertEquals(0, r[0]); assertEquals(219, r[1])
        val wide = PreviewMath.letterbox(2000, 500, 1000, 1000)
        assertEquals(500, wide[2]); assertEquals(500, wide[3]); assertEquals(750, wide[0])
    }

    @Test
    fun colourParsingAndDefaults() {
        assertEquals(0xFFFF8800.toInt(), PreviewMath.parseColor("#FF8800", 0))
        assertEquals(0x80112233.toInt(), PreviewMath.parseColor("#80112233", 0))
        assertEquals(7, PreviewMath.parseColor("red", 7))
        assertEquals(7, PreviewMath.parseColor(null, 7))
        assertEquals(7, PreviewMath.parseColor("#12", 7))
    }

    @Test
    fun contentSizeUsesPayloadOrADefaultTile() {
        val c = clip("a", 0, 1000).copy(payload = mapOf("w" to "300", "h" to "100"))
        assertEquals(300.0 to 100.0, PreviewMath.contentSize(c, 1920))
        val d = PreviewMath.contentSize(clip("b", 0, 1000), 1000)
        assertEquals(400.0, d.first, 1e-9); assertEquals(225.0, d.second, 1e-9)
    }
}
