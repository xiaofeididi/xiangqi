package vip.wqby.pro.ncnn;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

/**
 * JNI 壳：包名/类名必须与 libmyapplication.so 中
 * Java_vip_wqby_pro_ncnn_yolov13_* 符号一致，不可改名。
 */
public class yolov13 {
    static {
        System.loadLibrary("myapplication");
    }

    public native OutResult[] detect(Bitmap bitmap);

    public native boolean initModel(boolean useGpu, AssetManager assetManager);
}
