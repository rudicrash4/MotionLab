package id.motionlab.editor.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Serialisasi proyek ke JSON (org.json bawaan Android). Format berversi agar mudah dimigrasi. */
object ProjectSerializer {
    private const val VERSION = 4

    fun toJson(p: Project): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("id", p.id)
        root.put("createdAt", p.createdAt)
        root.put("updatedAt", p.updatedAt)
        root.put("name", p.name)
        root.put("aspectW", p.aspectW)
        root.put("aspectH", p.aspectH)
        root.put("shortSide", p.shortSide)
        root.put("fps", p.fps)
        val tracks = JSONArray()
        for (t in p.tracks) {
            val jt = JSONObject()
            jt.put("id", t.id)
            jt.put("type", t.type.name)
            jt.put("name", t.name)
            jt.put("muted", t.muted)
            jt.put("hidden", t.hidden)
            jt.put("locked", t.locked)
            jt.put("solo", t.solo)
            val clips = JSONArray()
            for (c in t.clips) clips.put(clipToJson(c))
            jt.put("clips", clips)
            tracks.put(jt)
        }
        root.put("tracks", tracks)
        return root.toString()
    }

    private fun clipToJson(c: Clip): JSONObject {
        val o = JSONObject()
        o.put("id", c.id)
        o.put("type", c.type.name)
        o.put("name", c.name)
        o.put("uri", c.uri)
        o.put("start", c.startMs)
        o.put("dur", c.durationMs)
        o.put("srcIn", c.sourceInMs)
        o.put("srcDur", c.sourceDurationMs)
        o.put("volume", c.volume.toDouble())
        o.put("text", c.text)
        o.put("textColor", c.textColor)
        o.put("textSize", c.textSize.toDouble())
        o.put("fontWeight", c.fontWeight)
        o.put("italic", c.italic)
        o.put("fontUri", c.fontUri)
        o.put("fontName", c.fontName)
        o.put("textAlign", c.textAlign)
        o.put("letterSpacing", c.letterSpacing.toDouble())
        o.put("lineSpacing", c.lineSpacing.toDouble())
        o.put("strokeWidth", c.strokeWidth.toDouble())
        o.put("strokeColor", c.strokeColor)
        o.put("shadowSize", c.shadowSize.toDouble())
        o.put("shadowColor", c.shadowColor)
        o.put("bgColor", c.bgColor)
        o.put("fadeIn", c.fadeInMs)
        o.put("fadeOut", c.fadeOutMs)
        o.put("fxDisabled", JSONArray(c.fxDisabled.toList()))
        o.put("fxOrder", JSONArray(c.fxOrder))
        o.put("shape", c.shape.name)
        o.put("fill", c.fillColor)
        o.put("lut", c.lutUri)
        o.put("tinType", c.transitionIn.type.name)
        o.put("tinDur", c.transitionIn.durationMs)
        o.put("toutType", c.transitionOut.type.name)
        o.put("toutDur", c.transitionOut.durationMs)
        val props = JSONObject()
        for ((k, v) in c.props) props.put(k, v.toDouble())
        o.put("props", props)
        val kfs = JSONObject()
        for ((k, kt) in c.keyframes) {
            val arr = JSONArray()
            for (f in kt.frames) {
                val jf = JSONObject()
                jf.put("t", f.timeMs)
                jf.put("v", f.value.toDouble())
                jf.put("i", f.interp.name)
                jf.put("x1", f.bx1.toDouble())
                jf.put("y1", f.by1.toDouble())
                jf.put("x2", f.bx2.toDouble())
                jf.put("y2", f.by2.toDouble())
                arr.put(jf)
            }
            kfs.put(k, arr)
        }
        o.put("kf", kfs)
        return o
    }

    fun fromJson(json: String): Project {
        val root = JSONObject(json)
        val tracks = ArrayList<Track>()
        val ja = root.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until ja.length()) {
            val jt = ja.getJSONObject(i)
            val clips = ArrayList<Clip>()
            val jc = jt.optJSONArray("clips") ?: JSONArray()
            for (j in 0 until jc.length()) clips.add(clipFromJson(jc.getJSONObject(j)))
            tracks.add(
                Track(
                    id = jt.optString("id"),
                    type = enumOr(jt.optString("type"), TrackType.VIDEO),
                    name = jt.optString("name", "Track"),
                    clips = clips,
                    muted = jt.optBoolean("muted", false),
                    hidden = jt.optBoolean("hidden", false),
                    locked = jt.optBoolean("locked", false),
                    solo = jt.optBoolean("solo", false)
                )
            )
        }
        return Project(
            id = root.optString("id", "").ifEmpty { java.util.UUID.randomUUID().toString() },
            createdAt = root.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = root.optLong("updatedAt", System.currentTimeMillis()),
            name = root.optString("name", "Proyek 001"),
            aspectW = root.optInt("aspectW", 9),
            aspectH = root.optInt("aspectH", 16),
            shortSide = root.optInt("shortSide", 1080),
            fps = root.optInt("fps", 30),
            tracks = tracks
        )
    }

    private fun clipFromJson(o: JSONObject): Clip {
        val props = HashMap<String, Float>()
        val jp = o.optJSONObject("props")
        if (jp != null) {
            val it = jp.keys()
            while (it.hasNext()) {
                val k = it.next()
                props[k] = jp.optDouble(k, 0.0).toFloat()
            }
        }
        // Migrasi v3 -> v4: kecepatan konstan lama ("speed") menjadi nilai dasar properti Props.SPEED.
        val legacySpeed = o.optDouble("speed", 1.0).toFloat()
        if (!props.containsKey(Props.SPEED) && legacySpeed > 0f && Math.abs(legacySpeed - 1f) > 0.0005f) {
            props[Props.SPEED] = SpeedMap.clampSpeed(legacySpeed)
        }
        val kfs = HashMap<String, KeyframeTrack>()
        val jk = o.optJSONObject("kf")
        if (jk != null) {
            val it = jk.keys()
            while (it.hasNext()) {
                val k = it.next()
                val arr = jk.optJSONArray(k) ?: continue
                val frames = ArrayList<Keyframe>()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    frames.add(
                        Keyframe(
                            timeMs = f.optLong("t", 0L),
                            value = f.optDouble("v", 0.0).toFloat(),
                            interp = enumOr(f.optString("i"), Interp.EASE_IN_OUT),
                            bx1 = f.optDouble("x1", 0.42).toFloat(),
                            by1 = f.optDouble("y1", 0.0).toFloat(),
                            bx2 = f.optDouble("x2", 0.58).toFloat(),
                            by2 = f.optDouble("y2", 1.0).toFloat()
                        )
                    )
                }
                frames.sortBy { it.timeMs }
                kfs[k] = KeyframeTrack(frames)
            }
        }
        return Clip(
            id = o.optString("id"),
            type = enumOr(o.optString("type"), TrackType.VIDEO),
            name = o.optString("name", "Klip"),
            uri = o.optString("uri", ""),
            startMs = o.optLong("start", 0L),
            durationMs = o.optLong("dur", 1000L),
            sourceInMs = o.optLong("srcIn", 0L),
            sourceDurationMs = o.optLong("srcDur", 0L),
            volume = o.optDouble("volume", 1.0).toFloat(),
            props = props,
            keyframes = kfs,
            text = o.optString("text", ""),
            textColor = o.optInt("textColor", 0xFFFFFFFF.toInt()),
            textSize = o.optDouble("textSize", 0.07).toFloat(),
            // Migrasi: "bold" (v3) -> fontWeight 700 / 400.
            fontWeight = if (o.has("fontWeight")) o.optInt("fontWeight", 700) else if (o.optBoolean("bold", true)) 700 else 400,
            italic = o.optBoolean("italic", false),
            fontUri = o.optString("fontUri", ""),
            fontName = o.optString("fontName", ""),
            textAlign = o.optInt("textAlign", 1),
            letterSpacing = o.optDouble("letterSpacing", 0.0).toFloat(),
            lineSpacing = o.optDouble("lineSpacing", 1.0).toFloat(),
            strokeWidth = o.optDouble("strokeWidth", 0.0).toFloat(),
            strokeColor = o.optInt("strokeColor", 0xFF000000.toInt()),
            shadowSize = o.optDouble("shadowSize", 0.06).toFloat(),
            shadowColor = o.optInt("shadowColor", 0x99000000.toInt()),
            bgColor = o.optInt("bgColor", 0),
            fadeInMs = o.optLong("fadeIn", 0L),
            fadeOutMs = o.optLong("fadeOut", 0L),
            fxDisabled = readStringSet(o.optJSONArray("fxDisabled")),
            fxOrder = readStringList(o.optJSONArray("fxOrder")).ifEmpty { listOf(Props.WAVE, Props.SWIRL, Props.DISPLACE) },
            shape = enumOr(o.optString("shape"), ShapeKind.RECT),
            fillColor = o.optInt("fill", 0xFF8B7CFF.toInt()),
            transitionIn = Transition(enumOr(o.optString("tinType"), TransitionType.NONE), o.optLong("tinDur", 500L)),
            transitionOut = Transition(enumOr(o.optString("toutType"), TransitionType.NONE), o.optLong("toutDur", 500L)),
            lutUri = o.optString("lut", "")
        )
    }

    private fun readStringList(a: JSONArray?): List<String> {
        val out = ArrayList<String>()
        if (a != null) for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    private fun readStringSet(a: JSONArray?): Set<String> = readStringList(a).toSet()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E {
        if (name.isNullOrEmpty()) return fallback
        return try {
            java.lang.Enum.valueOf(E::class.java, name)
        } catch (e: Exception) {
            fallback
        }
    }
}

