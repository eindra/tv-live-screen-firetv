/*
 * The NDI side of Live Screen: finds sources on the network and draws one source's video straight
 * into the activity's Surface. Audio is ignored (the room's sound system carries it).
 */
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <Processing.NDI.Lib.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "LiveScreen", __VA_ARGS__)

static NDIlib_find_instance_t finder;
static NDIlib_recv_instance_t recv;
static ANativeWindow *window;
static pthread_t thread;
static volatile int running;
static volatile int64_t frames, lastFrameMs;
static volatile int width, height, fpsN, fpsD;

static int64_t nowMs(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t) t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static void draw(const NDIlib_video_frame_v2_t *v) {
    if (v->xres != width || v->yres != height) {
        ANativeWindow_setBuffersGeometry(window, v->xres, v->yres, WINDOW_FORMAT_RGBX_8888);
        width = v->xres;
        height = v->yres;
        LOG("video %dx%d", width, height);
    }
    fpsN = v->frame_rate_N;
    fpsD = v->frame_rate_D;
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window, &buf, NULL) != 0) return;
    int rows = v->yres < buf.height ? v->yres : buf.height;
    int bytes = (v->xres < buf.width ? v->xres : buf.width) * 4;
    const uint8_t *src = v->p_data;
    uint8_t *dst = buf.bits;
    for (int y = 0; y < rows; y++) {
        memcpy(dst, src, bytes);
        src += v->line_stride_in_bytes;
        dst += buf.stride * 4;
    }
    ANativeWindow_unlockAndPost(window);
    frames++;
    lastFrameMs = nowMs();
}

static void *loop(void *arg) {
    (void) arg;
    while (running) {
        NDIlib_video_frame_v2_t v;
        if (NDIlib_recv_capture_v3(recv, &v, NULL, NULL, 500) == NDIlib_frame_type_video) {
            // RGBX_RGBA gives RGBX for opaque video, which matches the window; alpha frames (RGBA) too.
            if (v.FourCC == NDIlib_FourCC_video_type_RGBX || v.FourCC == NDIlib_FourCC_video_type_RGBA) draw(&v);
            NDIlib_recv_free_video_v2(recv, &v);
        }
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_com_moments_livescreen_Ndi_init(JNIEnv *env, jclass c) {
    if (!NDIlib_initialize()) return JNI_FALSE;
    NDIlib_find_create_t opts = { .show_local_sources = true, .p_groups = NULL, .p_extra_ips = NULL };
    finder = NDIlib_find_create_v2(&opts);
    LOG("NDI %s", NDIlib_version());
    return finder != NULL;
}

JNIEXPORT jobjectArray JNICALL Java_com_moments_livescreen_Ndi_sources(JNIEnv *env, jclass c, jint waitMs) {
    NDIlib_find_wait_for_sources(finder, (uint32_t) waitMs);
    uint32_t n = 0;
    const NDIlib_source_t *s = NDIlib_find_get_current_sources(finder, &n);
    jobjectArray out = (*env)->NewObjectArray(env, (jsize) n, (*env)->FindClass(env, "java/lang/String"), NULL);
    for (uint32_t i = 0; i < n; i++)
        (*env)->SetObjectArrayElement(env, out, (jsize) i, (*env)->NewStringUTF(env, s[i].p_ndi_name));
    return out;
}

JNIEXPORT void JNICALL Java_com_moments_livescreen_Ndi_stop(JNIEnv *env, jclass c) {
    if (running) {
        running = 0;
        pthread_join(thread, NULL);
    }
    if (recv) { NDIlib_recv_destroy(recv); recv = NULL; }
    if (window) { ANativeWindow_release(window); window = NULL; }
    width = height = 0;
}

JNIEXPORT jboolean JNICALL Java_com_moments_livescreen_Ndi_start(JNIEnv *env, jclass c,
        jstring source, jobject surface, jboolean low) {
    Java_com_moments_livescreen_Ndi_stop(env, c);
    const char *name = (*env)->GetStringUTFChars(env, source, NULL);
    NDIlib_recv_create_v3_t opts = {
        .source_to_connect_to = { .p_ndi_name = name, .p_url_address = NULL },
        .color_format = NDIlib_recv_color_format_RGBX_RGBA,
        .bandwidth = low ? NDIlib_recv_bandwidth_lowest : NDIlib_recv_bandwidth_highest,
        .allow_video_fields = false,
        .p_ndi_recv_name = "Live Screen"
    };
    recv = NDIlib_recv_create_v3(&opts);
    (*env)->ReleaseStringUTFChars(env, source, name);
    if (!recv) return JNI_FALSE;
    window = ANativeWindow_fromSurface(env, surface);
    if (!window) { Java_com_moments_livescreen_Ndi_stop(env, c); return JNI_FALSE; }
    frames = 0;
    lastFrameMs = 0;
    running = 1;
    pthread_create(&thread, NULL, loop, NULL);
    return JNI_TRUE;
}

/* frames drawn, width, height, ms since the last frame (-1 if none yet), fps x 100 */
JNIEXPORT jlongArray JNICALL Java_com_moments_livescreen_Ndi_stats(JNIEnv *env, jclass c) {
    jlong s[5] = { frames, width, height, lastFrameMs ? nowMs() - lastFrameMs : -1,
                   fpsD ? (jlong) fpsN * 100 / fpsD : 0 };
    jlongArray out = (*env)->NewLongArray(env, 5);
    (*env)->SetLongArrayRegion(env, out, 0, 5, s);
    return out;
}
