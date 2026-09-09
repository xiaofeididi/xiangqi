package com.xqassist

import android.app.Activity
import androidx.appcompat.app.AlertDialog

class DisclaimerDialog(private val activity: Activity) {
    fun show() {
        AlertDialog.Builder(activity)
            .setTitle("\u514d\u8d23\u58f0\u660e")
            .setMessage(
                "1. \u672c\u5e94\u7528\u4ec5\u4f9b\u8c61\u68cb\u5b66\u4e60\u3001\u7814\u7a76\u548c\u4e2a\u4eba\u5a31\u4e50\u4f7f\u7528\u3002\n\n" +
                    "2. \u4e0d\u4fdd\u8bc1\u8bc6\u522b\u3001\u5206\u6790\u548c\u81ea\u52a8\u529f\u80fd\u5728\u4efb\u4f55\u73af\u5883\u4e0b\u7684\u51c6\u786e\u6027\u4e0e\u53ef\u7528\u6027\u3002\n\n" +
                    "3. \u4f7f\u7528\u8005\u5e94\u9075\u5b88\u76f8\u5173\u5e73\u53f0\u3001\u8d5b\u4e8b\u548c\u670d\u52a1\u65b9\u7684\u89c4\u5219\u3002\n\n" +
                    "4. \u56e0\u4f7f\u7528\u672c\u5e94\u7528\u4ea7\u751f\u7684\u4efb\u4f55\u540e\u679c\u7531\u4f7f\u7528\u8005\u81ea\u884c\u627f\u62c5\u3002"
            )
            .setPositiveButton("\u6211\u5df2\u4e86\u89e3", null)
            .show()
    }
}
