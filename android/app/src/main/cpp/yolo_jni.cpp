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
static int g_out_c = 22;
static int g_out_w = 8400;
static int g_out_h = 1;

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
    g_net->opt.num_threads = 1; // 降压，避免挤死天天象棋 webview
    g_net->opt.lightmode = true;
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
    const int S = 640;
    float sc = (float)S / (W > H ? W : H);
    int nw = (int)(W * sc);
    int nh = (int)(H * sc);
    if (nw < 1) nw = 1;
    if (nh < 1) nh = 1;
    int ox = (S - nw) / 2;
    int oy = (S - nh) / 2;

    // 用 ncnn 内置缩放，避免 1080×2400 逐像素循环拖死手机
    // from_pixels_resize 返回 float planar Mat（0–255）
    ncnn::Mat resized = ncnn::Mat::from_pixels_resize(
        (const unsigned char*)pixels,
        ncnn::Mat::PIXEL_RGBA2RGB,
        W, H, info.stride,
        nw, nh);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (resized.empty() || resized.c != 3) return nullptr;

    ncnn::Mat in(S, S, 3);
    in.fill(114.f / 255.f);
    {
        const float* sr = resized.channel(0);
        const float* sg = resized.channel(1);
        const float* sb = resized.channel(2);
        float* dr = in.channel(0);
        float* dg = in.channel(1);
        float* db = in.channel(2);
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                const int si = y * nw + x;
                const int di = (y + oy) * S + (x + ox);
                dr[di] = sr[si] / 255.f;
                dg[di] = sg[si] / 255.f;
                db[di] = sb[si] / 255.f;
            }
        }
    }

    ncnn::Extractor ex = g_net->create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    int ret = ex.extract("out0", out);
    if (ret != 0 || out.empty()) {
        LOGE("extract ret=%d", ret);
        return nullptr;
    }
    // out: often w=22, h=8400, c=1 (anchor-major) — log full shape
    const int Oc = out.c;
    const int Ow = out.w;
    const int Oh = out.h;
    const int Od = out.d;
    g_out_c = Oc;
    g_out_w = Ow;
    g_out_h = Oh;
    const int N = Ow * Oh * Od;
    LOGI("out w=%d h=%d c=%d d=%d total=%d", Ow, Oh, Oc, Od, Oc * N);
    jfloatArray arr = env->NewFloatArray((jsize)(Oc * N));
    if (!arr) return nullptr;
    std::vector<float> buf(Oc * N);
    for (int c = 0; c < Oc; c++) {
        const float* p = out.channel(c);
        memcpy(buf.data() + (size_t)c * N, p, sizeof(float) * N);
    }
    env->SetFloatArrayRegion(arr, 0, Oc * N, buf.data());
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xqassist_vision_YoloNcnn_nativeOutC(JNIEnv*, jobject) {
    return g_out_c;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xqassist_vision_YoloNcnn_nativeOutW(JNIEnv*, jobject) {
    return g_out_w;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xqassist_vision_YoloNcnn_nativeOutH(JNIEnv*, jobject) {
    return g_out_h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xqassist_vision_YoloNcnn_nativeRelease(JNIEnv*, jobject) {
    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
}
