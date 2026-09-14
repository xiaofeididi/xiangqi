package vip.wqby.pro.view.main.setting;

import android.content.Context;
import android.view.View;

/**
 * JNI 桩：libmyapplication.so 的 initModel 会
 * GetStaticFieldID(..., "imageSize", "I")，类名不可改。
 * 仅提供 so 需要的静态字段，不包含 Pro 校准 UI。
 */
public class CalibrationImageView extends View {
    /** NCNN 输入边长；与 Pro 运行时默认一致量级 */
    public static int imageSize = 640;

    public CalibrationImageView(Context context) {
        super(context);
    }
}
