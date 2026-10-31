#include <jni.h>

#include <sstream>
#include <string>

#include "jni_util.h"

#ifdef MOTIONLAB_HAS_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}
#endif

// Output format (parsed by FfmpegProbeParser in Kotlin):
//   format=<demuxer names>
//   duration_us=<int64 or -1>
//   bit_rate=<int64 or 0>
//   stream=<type>|<codec>|<width>|<height>|<fps>|<sample_rate>|<channels>|<bit_rate>
extern "C" {

JNIEXPORT jboolean JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeFfmpegAvailable(JNIEnv*, jclass) {
#ifdef MOTIONLAB_HAS_FFMPEG
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeFfmpegVersion(JNIEnv* env, jclass) {
#ifdef MOTIONLAB_HAS_FFMPEG
    return env->NewStringUTF(av_version_info());
#else
    return env->NewStringUTF("");
#endif
}

JNIEXPORT jstring JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeFfmpegProbe(JNIEnv* env, jclass, jstring jpath) {
#ifdef MOTIONLAB_HAS_FFMPEG
    if (jpath == nullptr) {
        motionlab::ThrowNative(env, "ffmpegProbe: path is null");
        return nullptr;
    }
    const char* chars = env->GetStringUTFChars(jpath, nullptr);
    if (chars == nullptr) return nullptr;  // OutOfMemoryError pending
    std::string path(chars);
    env->ReleaseStringUTFChars(jpath, chars);

    AVFormatContext* ctx = nullptr;
    int rc = avformat_open_input(&ctx, path.c_str(), nullptr, nullptr);
    if (rc < 0) {
        char err[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(rc, err, sizeof(err));
        motionlab::ThrowNative(env, std::string("avformat_open_input failed: ") + err);
        return nullptr;
    }
    rc = avformat_find_stream_info(ctx, nullptr);
    if (rc < 0) {
        char err[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(rc, err, sizeof(err));
        avformat_close_input(&ctx);
        motionlab::ThrowNative(env, std::string("avformat_find_stream_info failed: ") + err);
        return nullptr;
    }

    std::ostringstream out;
    out << "format=" << (ctx->iformat != nullptr ? ctx->iformat->name : "unknown") << "\n";
    out << "duration_us=" << (ctx->duration != AV_NOPTS_VALUE ? static_cast<long long>(ctx->duration) : -1LL) << "\n";
    out << "bit_rate=" << static_cast<long long>(ctx->bit_rate) << "\n";
    for (unsigned i = 0; i < ctx->nb_streams; ++i) {
        const AVStream* st = ctx->streams[i];
        const AVCodecParameters* par = st->codecpar;
        const char* type = "other";
        if (par->codec_type == AVMEDIA_TYPE_VIDEO) type = "video";
        else if (par->codec_type == AVMEDIA_TYPE_AUDIO) type = "audio";
        else if (par->codec_type == AVMEDIA_TYPE_SUBTITLE) type = "subtitle";
        double fps = 0.0;
        if (st->avg_frame_rate.den != 0 && st->avg_frame_rate.num != 0) fps = av_q2d(st->avg_frame_rate);
        out << "stream=" << type << "|" << avcodec_get_name(par->codec_id) << "|" << par->width << "|" << par->height
            << "|" << fps << "|" << par->sample_rate << "|" << par->ch_layout.nb_channels << "|"
            << static_cast<long long>(par->bit_rate) << "\n";
    }
    avformat_close_input(&ctx);
    return env->NewStringUTF(out.str().c_str());
#else
    (void)jpath;
    motionlab::ThrowNative(env, "FFmpeg is not available in this build");
    return nullptr;
#endif
}

}  // extern "C"
