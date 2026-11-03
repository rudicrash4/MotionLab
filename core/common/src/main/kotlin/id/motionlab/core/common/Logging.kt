package id.motionlab.core.common

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** Destination for log records. The Android app installs a sink that forwards to android.util.Log. */
fun interface LogSink {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/** Platform-independent logger so pure-Kotlin modules can log and be unit-tested on the JVM. */
object MlLog {
    @Volatile
    private var sinks: List<LogSink> = emptyList()

    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    @Synchronized
    fun addSink(sink: LogSink) {
        sinks = sinks + sink
    }

    @Synchronized
    fun clearSinks() {
        sinks = emptyList()
    }

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        if (level.ordinal < minLevel.ordinal) return
        for (sink in sinks) {
            // A broken sink must never take the app down.
            try {
                sink.log(level, tag, message, throwable)
            } catch (_: Exception) {
            }
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) = log(LogLevel.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, message, t)
}
