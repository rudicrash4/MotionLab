package id.motionlab.core.resource

import kotlin.math.abs

/**
 * Min/max peaks of an audio file, one pair per bucket, normalised to -1..1 and stored as bytes (so a 1-hour file with
 * 2000 buckets is 4 kB). The audio is never held in memory: [Builder] consumes PCM chunks as the decoder produces them.
 */
class WaveformPeaks(val bucketCount: Int, val durationUs: Long, private val data: ByteArray) {
    init {
        require(bucketCount > 0) { "bucketCount must be > 0" }
        require(data.size == bucketCount * 2) { "data must hold min and max per bucket" }
    }

    fun min(bucket: Int): Float = data[bucket * 2] / 127f
    fun max(bucket: Int): Float = data[bucket * 2 + 1] / 127f

    /** Peak (0..1) of the time range, merging the buckets it covers; used to draw one pixel column. */
    fun peakBetween(fromUs: Long, toUs: Long): Float {
        if (durationUs <= 0 || toUs <= fromUs) return 0f
        val a = ((fromUs.coerceIn(0, durationUs)) * bucketCount / durationUs).toInt().coerceIn(0, bucketCount - 1)
        val b = ((toUs.coerceIn(0, durationUs)) * bucketCount / durationUs).toInt().coerceIn(a, bucketCount - 1)
        var peak = 0f
        for (i in a..b) peak = maxOf(peak, abs(min(i)), abs(max(i)))
        return peak
    }

    fun toBytes(): ByteArray = data.copyOf()

    class Builder(private val bucketCount: Int, private val durationUs: Long, private val sampleRate: Int, private val channels: Int) {
        private val mins = FloatArray(bucketCount) { 0f }
        private val maxs = FloatArray(bucketCount) { 0f }
        private var frame = 0L
        private val totalFrames: Long = (durationUs * sampleRate / 1_000_000L).coerceAtLeast(1)

        /** Feeds interleaved 16-bit PCM. [length] counts shorts. Cheap: no allocation. */
        fun addPcm16(samples: ShortArray, length: Int) {
            val ch = channels.coerceAtLeast(1)
            var i = 0
            while (i + ch <= length) {
                var m = 0f
                for (c in 0 until ch) {
                    val v = samples[i + c] / 32768f
                    if (abs(v) > abs(m)) m = v
                }
                val bucket = (frame * bucketCount / totalFrames).toInt().coerceIn(0, bucketCount - 1)
                if (m < mins[bucket]) mins[bucket] = m
                if (m > maxs[bucket]) maxs[bucket] = m
                frame++
                i += ch
            }
        }

        fun build(): WaveformPeaks {
            val out = ByteArray(bucketCount * 2)
            for (i in 0 until bucketCount) {
                out[i * 2] = (mins[i].coerceIn(-1f, 1f) * 127f).toInt().toByte()
                out[i * 2 + 1] = (maxs[i].coerceIn(-1f, 1f) * 127f).toInt().toByte()
            }
            return WaveformPeaks(bucketCount, durationUs, out)
        }
    }

    companion object {
        fun fromBytes(durationUs: Long, bytes: ByteArray): WaveformPeaks = WaveformPeaks(bytes.size / 2, durationUs, bytes)

        /** About 50 buckets per second, capped so memory stays tiny. */
        fun bucketsFor(durationUs: Long): Int = ((durationUs / 20_000L).toInt()).coerceIn(64, 6000)
    }
}
