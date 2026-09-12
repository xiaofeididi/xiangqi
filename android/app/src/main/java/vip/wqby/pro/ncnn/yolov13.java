package vip.wqby.pro.ncnn;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

/**
 * JNI 壳：包名/类名必须与 libmyapplication.so 中
 * Java_vip_wqby_pro_ncnn_yolov13_* 符号一致，不可改名。
 *
 * 不在 static 里 loadLibrary：该 so 可能在加载时原生崩溃，
 * 必须由调用方在可控时机显式 load，且失败可回退。
 */
public class yolov13 {
    public native OutResult[] detect(Bitmap bitmap);

    public native boolean initModel(boolean useGpu, AssetManager assetManager);
}
