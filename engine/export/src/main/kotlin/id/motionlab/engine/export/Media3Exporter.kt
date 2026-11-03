package id.motionlab.engine.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.engine.effects.EffectInstance
import id.motionlab.engine.effects.Media3EffectFactory
import id.motionlab.engine.timeline.TrackKind
import java.io.File

/**
 * Exports an [ExportPlan] with Media3 Transformer (MediaCodec H.264/AAC into an MP4).
 * Must be created and used on a thread with a Looper (normally the main thread); the callbacks arrive there too.
 */
class Media3Exporter(
    private val context: Context,
    private val effectFactory: Media3EffectFactory,
    private val clipEffects: (clipId: String) -> List<EffectInstance> = { emptyList() },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var progressPoller: Runnable? = null

    val isRunning: Boolean get() = transformer != null

    fun start(plan: ExportPlan, config: ExportConfig, onState: (ExportState) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3Exporter must be used on the main thread" }
        if (transformer != null) throw MlException(ErrorCode.INVALID_ARGUMENT, "An export is already running")
        val composition = try {
            config.validate()
            File(config.outputPath).parentFile?.mkdirs()
            buildComposition(plan, config)
        } catch (e: MlException) {
            onState(ExportState.Failed(e))
            return
        }

        val startedAt = System.currentTimeMillis()
        val t = Transformer.Builder(context)
            .setVideoMimeType(config.videoMimeType)
            .setAudioMimeType(config.audioMimeType)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    onState(
                        ExportState.Succeeded(
                            config.outputPath,
                            exportResult.fileSizeBytes,
                            System.currentTimeMillis() - startedAt,
                        ),
                    )
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish()
                    MlLog.e("Media3Exporter", "export failed: ${exportException.errorCodeName}", exportException)
                    onState(
                        ExportState.Failed(
                            MlException(ErrorCode.ENCODE, "Export failed: ${exportException.errorCodeName}", exportException),
                        ),
                    )
                }
            })
            .build()
        transformer = t
        try {
            t.start(composition, config.outputPath)
        } catch (e: RuntimeException) {
            finish()
            onState(ExportState.Failed(MlException(ErrorCode.ENCODE, "Could not start export: ${e.message}", e)))
            return
        }
        onState(ExportState.Running(0))
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onState(ExportState.Running(holder.progress))
                }
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
        progressPoller = poll
        handler.postDelayed(poll, PROGRESS_INTERVAL_MS)
    }

    /** Cancels a running export and deletes the partial output file. */
    fun cancel(config: ExportConfig, onState: (ExportState) -> Unit) {
        val t = transformer ?: return
        t.cancel()
        finish()
        File(config.outputPath).delete()
        onState(ExportState.Cancelled)
    }

    private fun finish() {
        progressPoller?.let { handler.removeCallbacks(it) }
        progressPoller = null
        transformer = null
    }

    private fun buildComposition(plan: ExportPlan, config: ExportConfig): Composition {
        val sequences = plan.sequences.map { seq ->
            val items = seq.segments
            val builder = EditedMediaItemSequence.Builder(
                if (seq.kind == TrackKind.AUDIO) setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                else setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO, androidx.media3.common.C.TRACK_TYPE_AUDIO),
            )
            for (segment in items) {
                if (segment.isGap) builder.addGap(segment.durationUs) else builder.addItem(editedItem(segment, seq.kind, config))
            }
            builder.build()
        }
        return Composition.Builder(sequences).build()
    }

    private fun editedItem(segment: PlanSegment, kind: TrackKind, config: ExportConfig): EditedMediaItem {
        val mediaItem = if (segment.isImage) {
            MediaItem.Builder().setUri(segment.uri!!).setImageDurationMs(segment.durationUs / 1000).build()
        } else {
            MediaItem.Builder()
                .setUri(segment.uri!!)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(segment.sourceStartUs / 1000)
                        .setEndPositionMs((segment.sourceStartUs + segment.durationUs) / 1000)
                        .build(),
                )
                .build()
        }
        val videoEffects = ArrayList<Effect>()
        config.outputHeight?.let { videoEffects += Presentation.createForHeight(it) }
        segment.clipId?.let { videoEffects += effectFactory.createAll(clipEffects(it)) }
        val builder = EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), videoEffects))
        if (kind == TrackKind.AUDIO) builder.setRemoveVideo(true)
        if (segment.isImage) builder.setDurationUs(segment.durationUs).setFrameRate(config.frameRate)
        return builder.build()
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}
