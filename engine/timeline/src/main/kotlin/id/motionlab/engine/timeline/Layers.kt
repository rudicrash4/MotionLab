package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.engine.animation.AnimatedProperties
import id.motionlab.engine.animation.AppliedPreset
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeTrack
import id.motionlab.engine.animation.Mat3
import id.motionlab.engine.animation.MotionPath
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.animation.PropertyRegistry
import id.motionlab.engine.animation.TransformMath
import id.motionlab.engine.animation.TransformValues

enum class LayerType(val label: String) {
    /** Transform node that owns tracks (clips are drawn under it). */
    CONTENT("Layer"),

    /** Pure container: moves/scales/fades everything beneath it. */
    GROUP("Group"),

    /** Invisible helper whose only purpose is to be a parent (a "null object"). */
    NULL("Null"),
}

/**
 * A node of the transform hierarchy. Its properties are animated in TIMELINE time (clips animate in clip-local
 * time), so a group keeps its animation when clips under it are moved. Children inherit position, rotation,
 * scale, skew and opacity through the parent chain.
 */
data class Layer(
    val id: String,
    val name: String,
    val type: LayerType = LayerType.CONTENT,
    val parentId: String? = null,
    val properties: AnimatedProperties = AnimatedProperties.NONE,
    val motionPath: MotionPath? = null,
    val presets: List<AppliedPreset> = emptyList(),
    val visible: Boolean = true,
    val locked: Boolean = false,
    val collapsed: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "layer id must not be blank" }
        require(parentId != id) { "a layer cannot be its own parent" }
    }

    val anim: AnimData get() = AnimData(properties, motionPath, presets)

    fun withAnim(a: AnimData): Layer = copy(properties = a.properties, motionPath = a.motionPath, presets = a.presets)
}

/** Identifies who owns an animation: a clip (clip-local time) or a layer (timeline time). */
sealed interface AnimationOwner {
    data class ClipOwner(val clipId: String) : AnimationOwner
    data class LayerOwner(val layerId: String) : AnimationOwner
}

/** The ONE place that turns animation data into a transform. Preview and export both go through here. */
object AnimationEvaluator {
    /**
     * Local transform of an owner at [localTimeUs] (in the owner's own time base).
     * Position = motion-path point (if any) + the animated position properties; with
     * `rotateAlongPath` the heading of the path is added to the rotation.
     */
    fun evaluate(anim: AnimData, localTimeUs: Long): TransformValues {
        val values = anim.properties.evaluate(localTimeUs, PropertyRegistry.defaults())
        var t = TransformValues.from(values)
        val path = anim.motionPath
        if (path != null) {
            val progress = values[PropertyNames.MOTION_PROGRESS] ?: 0.0
            val s = path.pointAt(progress)
            t = t.copy(
                posX = t.posX + s.x,
                posY = t.posY + s.y,
                rotationDeg = if (path.rotateAlongPath) t.rotationDeg + s.tangentDeg else t.rotationDeg,
            )
        }
        return t
    }
}

/** One thing to draw at one instant, fully resolved. */
class RenderItem(
    val trackId: String,
    val trackKind: TrackKind,
    val clip: MediaClip,
    val layerId: String?,
    val localTimeUs: Long,
    /** Clip content space (origin = clip content centre) -> canvas space (origin = canvas centre). */
    val world: Mat3,
    /** Product of the clip's and every ancestor layer's opacity. */
    val opacity: Double,
)

class SceneFrame(val timeUs: Long, val items: List<RenderItem>)

/**
 * Resolves a [Timeline] at a time into draw-ordered [RenderItem]s. Deterministic and frame-rate independent:
 * the only input is the timeline time. This is the single evaluator that preview and export must share.
 */
object SceneEvaluator {
    fun evaluate(timeline: Timeline, timeUs: Long): SceneFrame {
        val cache = HashMap<String, Pair<Mat3, Double>?>() // layer -> (world, opacity) or null if hidden
        fun layerWorld(layerId: String): Pair<Mat3, Double>? {
            if (cache.containsKey(layerId)) return cache[layerId]
            val layer = timeline.layer(layerId)
            val result: Pair<Mat3, Double>? = if (!layer.visible) {
                null
            } else {
                val local = AnimationEvaluator.evaluate(layer.anim, timeUs)
                val parent = layer.parentId?.let { layerWorld(it) ?: return@let null }
                if (layer.parentId != null && parent == null) null
                else (parent?.first ?: Mat3.IDENTITY) * local.toMatrix() to (parent?.second ?: 1.0) * local.opacity
            }
            cache[layerId] = result
            return result
        }

        val items = ArrayList<RenderItem>()
        for (track in timeline.tracks) {
            if (!track.visible || !track.kind.hasVisuals) continue
            val clip = track.clipAt(timeUs) ?: continue
            var base = Mat3.IDENTITY
            var opacity = 1.0
            val layerId = track.layerId
            if (layerId != null) {
                val lw = layerWorld(layerId) ?: continue
                base = lw.first
                opacity = lw.second
            }
            val localTime = timeUs - clip.startUs
            val local = AnimationEvaluator.evaluate(clip.anim, localTime)
            items += RenderItem(track.id, track.kind, clip, layerId, localTime, base * local.toMatrix(), opacity * local.opacity)
        }
        return SceneFrame(timeUs, items)
    }

    /** World matrix and inherited opacity of a layer at [timeUs]; null when it or an ancestor is hidden. */
    fun layerWorldAt(timeline: Timeline, layerId: String, timeUs: Long): Pair<Mat3, Double>? {
        val layer = timeline.layer(layerId)
        if (!layer.visible) return null
        val local = AnimationEvaluator.evaluate(layer.anim, timeUs)
        val parent = layer.parentId?.let { layerWorldAt(timeline, it, timeUs) ?: return null }
        return (parent?.first ?: Mat3.IDENTITY) * local.toMatrix() to (parent?.second ?: 1.0) * local.opacity
    }
}

/** Parenting operations. */
object Hierarchy {
    /** Ids of [layerId] and all its descendants. */
    fun subtree(timeline: Timeline, layerId: String): Set<String> {
        timeline.layer(layerId)
        val result = LinkedHashSet<String>()
        result += layerId
        var grew = true
        while (grew) {
            grew = false
            for (l in timeline.layers) {
                if (l.parentId in result && result.add(l.id)) grew = true
            }
        }
        return result
    }

    /** Clips on tracks attached to [layerId] or any layer below it ("select group"). */
    fun clipsInSubtree(timeline: Timeline, layerId: String): List<MediaClip> {
        val ids = subtree(timeline, layerId)
        return timeline.tracks.filter { it.layerId in ids }.flatMap { it.clips }
    }

    /**
     * Sets a new parent. With [keepVisual] the layer's local transform is recomputed so that it appears exactly where
     * it did at [timeUs] (like After Effects' "keep transform"). The recomputed values are written with
     * [setPropertyAtTime] (auto-key: an animated property gets a keyframe at [timeUs], a static one changes its
     * constant). Limits, stated honestly:
     *  - exact only at [timeUs]: a parent that is animated moves the child with it afterwards (that is parenting),
     *  - requires skewY == 0 (the 2x2 part of the matrix has no room for it) and invertible parent/child,
     *  - opacity is inherited multiplicatively and is NOT compensated.
     */
    fun reparent(timeline: Timeline, layerId: String, newParentId: String?, keepVisual: Boolean, timeUs: Long): Timeline {
        val layer = timeline.layer(layerId)
        if (newParentId != null) {
            timeline.layer(newParentId)
            if (newParentId in subtree(timeline, layerId)) {
                throw MlException(ErrorCode.INVALID_ARGUMENT, "Cannot parent layer $layerId to its own descendant $newParentId")
            }
        }
        if (layer.parentId == newParentId) return timeline
        var result = timeline.updateLayer(layerId) { it.copy(parentId = newParentId) }
        if (!keepVisual) return result

        val oldWorld = SceneEvaluator.layerWorldAt(timeline, layerId, timeUs)?.first
            ?: throw MlException(ErrorCode.INVALID_ARGUMENT, "Layer $layerId or one of its parents is hidden; cannot keep its visual position")
        val newParentWorld = newParentId?.let { SceneEvaluator.layerWorldAt(timeline, it, timeUs)?.first
            ?: throw MlException(ErrorCode.INVALID_ARGUMENT, "New parent $newParentId is hidden; cannot keep the visual position") }
            ?: Mat3.IDENTITY
        val inv = newParentWorld.invert()
            ?: throw MlException(ErrorCode.INVALID_ARGUMENT, "New parent has a singular transform (scale 0); cannot keep the visual position")
        val local = AnimationEvaluator.evaluate(layer.anim, timeUs)
        if (kotlin.math.abs(local.skewYDeg) > 1e-9) {
            throw MlException(ErrorCode.UNSUPPORTED, "Cannot keep the visual position of a layer with Skew Y; reset Skew Y first")
        }
        val d = TransformMath.decompose(inv * oldWorld, local.anchorX, local.anchorY)
            ?: throw MlException(ErrorCode.INVALID_ARGUMENT, "Layer $layerId has a singular transform; cannot keep its visual position")
        // A motion path contributes to position (and heading); the properties only have to provide the remainder.
        val sample = layer.motionPath?.pointAt(layer.properties.valueAt(PropertyNames.MOTION_PROGRESS, timeUs, 0.0))
        val pathX = sample?.x ?: 0.0
        val pathY = sample?.y ?: 0.0
        val heading = if (layer.motionPath?.rotateAlongPath == true && sample != null) sample.tangentDeg else 0.0
        val anim = layer.anim
            .let { setPropertyAtTime(it, PropertyNames.POSITION_X, timeUs, d.posX - pathX) }
            .let { setPropertyAtTime(it, PropertyNames.POSITION_Y, timeUs, d.posY - pathY) }
            .let { setPropertyAtTime(it, PropertyNames.SCALE_X, timeUs, d.scaleX) }
            .let { setPropertyAtTime(it, PropertyNames.SCALE_Y, timeUs, d.scaleY) }
            .let { setPropertyAtTime(it, PropertyNames.ROTATION, timeUs, d.rotationDeg - heading) }
            .let { setPropertyAtTime(it, PropertyNames.SKEW_X, timeUs, d.skewXDeg) }
        result = result.updateLayer(layerId) { it.withAnim(anim) }
        return result
    }
}

/** Shared by commands and hierarchy code. Top level so both can use it. */
fun setPropertyAtTime(anim: AnimData, property: String, timeUs: Long, value: Double): AnimData =
    setPropertyAtTimeImpl(anim, property, timeUs, value)

internal fun setPropertyAtTimeImpl(anim: AnimData, property: String, timeUs: Long, value: Double): AnimData {
    val def = PropertyRegistry.get(property)
    val v = def?.clamp(value) ?: value
    val track = anim.properties.track(property)
    val props = if (track.isEmpty) {
        anim.properties.withConstant(property, v)
    } else {
        val existing = track.at(timeUs)
        val updated = if (existing != null) track.replaced(existing.copy(value = v)) else track.with(Keyframe(timeUs, v))
        anim.properties.with(property, updated)
    }
    return anim.copy(properties = props)
}
