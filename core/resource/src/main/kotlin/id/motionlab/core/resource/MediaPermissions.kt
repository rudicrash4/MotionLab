package id.motionlab.core.resource

/** Media categories the device browser can ask access for. */
enum class MediaType { IMAGES, VIDEO, AUDIO;
    val isVisual: Boolean get() = this != AUDIO
}

/** What the user's permission choices currently allow for one [MediaType]. */
enum class MediaAccessState {
    /** Everything of that type can be browsed. */
    FULL,
    /** Android 14+: the user granted only the photos/videos they selected (READ_MEDIA_VISUAL_USER_SELECTED). */
    PARTIAL,
    /** Never asked yet: the runtime dialog may be shown (after the explanation). */
    NOT_ASKED,
    /** Asked and refused, but Android will still show the dialog again (the user can be asked once more, by their own tap). */
    DENIED,
    /** Asked and refused with "don't ask again": only the system Settings can change it. */
    PERMANENTLY_DENIED,
}

/**
 * Pure rules for the runtime permissions MotionLab uses to browse device media. No Android types, so they are unit tested
 * on the JVM. The string constants are the exact Android permission names.
 *
 *  - Android 13+ (API 33): READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO.
 *  - Android 14+ (API 34): visual media additionally asks READ_MEDIA_VISUAL_USER_SELECTED so the user may share only a selection.
 *  - Android 12 and older: READ_EXTERNAL_STORAGE (the manifest limits it to maxSdkVersion 32).
 * MANAGE_EXTERNAL_STORAGE is never used. Fonts use the document picker and need no permission.
 */
object MediaPermissionPolicy {
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"

    /** Every permission this app may ever request (used by tests to prove nothing unrelated is asked). */
    val ALL: Set<String> = setOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO, READ_MEDIA_VISUAL_USER_SELECTED, READ_EXTERNAL_STORAGE)

    private fun mainPermission(sdk: Int, type: MediaType): String = when {
        sdk < 33 -> READ_EXTERNAL_STORAGE
        type == MediaType.IMAGES -> READ_MEDIA_IMAGES
        type == MediaType.VIDEO -> READ_MEDIA_VIDEO
        else -> READ_MEDIA_AUDIO
    }

    /** The permissions to put in ONE runtime request for [types] on [sdk], without duplicates, in a stable order. */
    fun permissionsFor(sdk: Int, types: Set<MediaType>): List<String> {
        val out = LinkedHashSet<String>()
        for (t in MediaType.values()) if (t in types) out += mainPermission(sdk, t)
        // Android 14: the visual-selected permission must be requested together with the full ones, or the system shows no partial option.
        if (sdk >= 34 && types.any { it.isVisual }) out += READ_MEDIA_VISUAL_USER_SELECTED
        return out.toList()
    }

    /** Current access for one [type]. [granted] is the set of permissions the system reports as granted right now. */
    fun accessFor(
        sdk: Int,
        type: MediaType,
        granted: Set<String>,
        askedBefore: Boolean,
        canShowRationale: Boolean,
    ): MediaAccessState {
        if (mainPermission(sdk, type) in granted) return MediaAccessState.FULL
        if (sdk >= 34 && type.isVisual && READ_MEDIA_VISUAL_USER_SELECTED in granted) return MediaAccessState.PARTIAL
        return when {
            !askedBefore -> MediaAccessState.NOT_ASKED
            canShowRationale -> MediaAccessState.DENIED
            else -> MediaAccessState.PERMANENTLY_DENIED
        }
    }

    /** True when the browser can list something (all or a user-selected subset). */
    fun canBrowse(access: MediaAccessState): Boolean = access == MediaAccessState.FULL || access == MediaAccessState.PARTIAL

    /** True when asking again with the system dialog makes sense; false once only Settings can help (no repeated prompts). */
    fun mayRequest(access: MediaAccessState): Boolean = access == MediaAccessState.NOT_ASKED || access == MediaAccessState.DENIED || access == MediaAccessState.PARTIAL

    /** One short sentence explaining why the access is needed; shown BEFORE the system dialog. */
    fun rationale(type: MediaType): String = when (type) {
        MediaType.IMAGES -> "MotionLab needs access to your photos so you can browse them and add them to the timeline."
        MediaType.VIDEO -> "MotionLab needs access to your videos so you can browse them and add them to the timeline."
        MediaType.AUDIO -> "MotionLab needs access to your audio files so you can browse them and add them to the timeline."
    }

    /** Shown when [MediaAccessState.PERMANENTLY_DENIED]: says what happened, how to change it and which alternative still works. */
    fun settingsExplanation(type: MediaType): String {
        val what = when (type) { MediaType.IMAGES -> "photos"; MediaType.VIDEO -> "videos"; MediaType.AUDIO -> "audio files" }
        return "Access to $what was turned off. To browse them here, open the app's system Settings, choose Permissions and allow access. " +
            "You can still pick files with the system picker without any permission."
    }

    /** Shown for [MediaAccessState.PARTIAL]. */
    fun partialExplanation(): String =
        "You shared only some photos and videos with MotionLab. Only those are listed. You can pick more, or allow access to all."
}

/** Which content URIs are direct MediaStore references (usable while the media permission lasts) and which are not. */
object MediaUriPolicy {
    /**
     * Gallery-style MediaStore URIs look like content://media/external/images/media/42. Photo Picker URIs also use the
     * "media" authority but a "picker" path and are only valid temporarily, so they must be copied instead.
     */
    fun isMediaStoreItem(scheme: String?, authority: String?, firstPathSegment: String?): Boolean =
        scheme == "content" && authority == "media" && (firstPathSegment == "external" || firstPathSegment == "external_primary")

    /**
     * True when a device item and a project asset are the same media, so the browser never lists a file twice and a second
     * "add" reuses the existing asset. Same URI, or (for copies made by the picker import) the same file name AND size.
     */
    fun sameMedia(aUri: String, aName: String?, aSize: Long, bUri: String, bName: String?, bSize: Long): Boolean =
        aUri == bUri || (aSize > 0 && aSize == bSize && !aName.isNullOrEmpty() && aName == bName)
}
