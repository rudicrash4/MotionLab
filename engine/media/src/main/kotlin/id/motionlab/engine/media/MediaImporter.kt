package id.motionlab.engine.media

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlResult
import id.motionlab.core.resource.ResourceId
import id.motionlab.core.resource.ResourceKind
import id.motionlab.core.resource.ResourceManager
import id.motionlab.core.resource.ResourceRef
import java.util.UUID

class ImportedMedia(val ref: ResourceRef, val metadata: MediaMetadata)

/**
 * Probes a file, classifies it, and registers it with the [ResourceManager]. Blocking: run it on a background
 * thread (e.g. through the TaskScheduler).
 */
class MediaImporter(
    private val resolver: MetadataResolver,
    private val resources: ResourceManager,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    fun import(uri: String, displayName: String? = null): MlResult<ImportedMedia> {
        val metadata = try {
            resolver.resolve(uri)
        } catch (e: MlException) {
            return MlResult.Failure(e)
        }
        val kind = when {
            metadata.isImage -> ResourceKind.IMAGE
            metadata.hasVideo -> ResourceKind.VIDEO
            metadata.hasAudio -> ResourceKind.AUDIO
            else -> return MlResult.Failure(MlException(ErrorCode.UNSUPPORTED, "File has no usable media streams"))
        }
        val ref = ResourceRef(ResourceId(idGenerator()), uri, kind, displayName)
        return when (val registered = resources.register(ref)) {
            is MlResult.Success -> MlResult.Success(ImportedMedia(ref, metadata))
            is MlResult.Failure -> registered
        }
    }
}
