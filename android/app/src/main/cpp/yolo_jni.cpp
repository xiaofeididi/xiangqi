#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <ncnn/net.h>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cstdarg>

#define LOG_TAG "YoloNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// libncnn.a 用新版 libc++ 编译，NDK r25 缺这个符号
namespace std {
inline namespace __ndk1 {
void __libcpp_verbose_abort(const char* format, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, format);
    vsnprintf(buf, sizeof(buf), format, ap);
    va_end(ap);
    LOGE("libc++ abort: %s", buf);
    abort();
}
}
}

static ncnn::Net* g_net = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xqassist_vision_YoloNcnn_nativeInit(JNIEnv* env, jobject, jstring jparam, jstring jbin) {
    const char* param = env->GetStringUTFChars(jparam, nullptr);
    const char* bin = env->GetStringUTFChars(jbin, nullptr);
    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
    g_net = new ncnn::Net();
    g_net->opt.use_vulkan_compute = false;
    g_net->opt.num_threads = 2;
    int r1 = g_net->load_param(param);
    int r2 = g_net->load_model(bin);
    env->ReleaseStringUTFChars(jparam, param);
    env->ReleaseStringUTFChars(jbin, bin);
    LOGI("load_param=%d load_model=%d", r1, r2);
    if (r1 != 0 || r2 != 0) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// returns float[8400*22] flattened, or null
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xqassist_vision_YoloNcnn_nativeDetect(JNIEnv* env, jobject, jobject bitmap) {
    if (!g_net) return nullptr;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bitmap format %d", info.format);
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) return nullptr;

    const int W = info.width;
    const int H = info.height;
    // letterbox to 640x640
    const int S = 640;
    float sc = (float)S / (W > H ? W : H);
    int nw = (int)(W * sc);
    int nh = (int)(H * sc);
    int ox = (S - nw) / 2;
    int oy = (S - nh) / 2;

    ncnn::Mat in(S, S, 3);
    in.fill(114);
    const uint8_t* src = (const uint8_t*)pixels;
    for (int y = 0; y < nh; y++) {
        int sy = (int)(y / sc);
        if (sy >= H) sy = H - 1;
        for (int x = 0; x < nw; x++) {
            int sx = (int)(x / sc);
            if (sx >= W) sx = W - 1;
            const uint8_t* p = src + sy * info.stride + sx * 4;
            float* r = (float*)in.channel(0);
            float* g = (float*)in.channel(1);
            float* b = (float*)in.channel(2);
            r[(y + oy) * S + (x + ox)] = p[0] / 255.f;
            g[(y + oy) * S + (x + ox)] = p[1] / 255.f;
            b[(y + oy) * S + (x + ox)] = p[2] / 255.f;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    int ret = ex.extract("out0", out);
    if (ret != 0 || out.empty()) {
        LOGE("extract ret=%d", ret);
        return nullptr;
    }
    // out: c=22, w=8400, h=1  (or similar)
    const int C = out.c;
    const int N = out.w * out.h * out.d;
    jfloatArray arr = env->NewFloatArray((jsize)(C * N));
    if (!arr) return nullptr;
    std::vector<float> buf(C * N);
    for (int c = 0; c < C; c++) {
        const float* p = out.channel(c);
        memcpy(buf.data() + (size_t)c * N, p, sizeof(float) * N);
    }
    env->SetFloatArrayRegion(arr, 0, C * N, buf.data());
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xqassist_vision_YoloNcnn_nativeOutC(JNIEnv*, jobject) {
    return 22;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xqassist_vision_YoloNcnn_nativeRelease(JNIEnv*, jobject) {
    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
}
