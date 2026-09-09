package com.xqassist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val dp by lazy { resources.displayMetrics.density.toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFF5F1E8.toInt())
            setPadding(dp * 24, dp * 24, dp * 24, dp * 24)
        }

        val icon = TextView(this).apply {
            text = "\u5e05"
            textSize = 68f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFB02020.toInt())
        }

        val title = TextView(this).apply {
            text = "\u8c61\u68cb\u52a9\u624b"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFF17202A.toInt())
            setPadding(0, dp * 12, 0, dp * 4)
        }

        val subtitle = TextView(this).apply {
            text = "\u76ae\u5361\u9c7c\u5f15\u64ce \u00b7 \u4e91\u5e93 \u00b7 \u60ac\u6d6e\u7a97"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF6B7280.toInt())
        }

        val legal = TextView(this).apply {
            text = "\u672c\u5e94\u7528\u4ec5\u4f9b\u8c61\u68cb\u5b66\u4e60\u548c\u7814\u7a76\uff0c\u8bf7\u9075\u5b88\u76ee\u6807\u5e73\u53f0\u89c4\u5219\uff0c\u4f7f\u7528\u8005\u627f\u62c5\u76f8\u5173\u4f7f\u7528\u8d23\u4efb\u3002"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF8A8F98.toInt())
            setPadding(dp * 8, dp * 24, dp * 8, 0)
        }

        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp * 14, 0, 0)
        }
        links.addView(linkButton("GitHub") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xiaofeididi/xiangqi")))
        })
        links.addView(linkButton("\u514d\u8d23\u58f0\u660e") {
            DisclaimerDialog(this@SplashActivity).show()
        })

        root.addView(icon)
        root.addView(title)
        root.addView(subtitle)
        root.addView(legal)
        root.addView(links)
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 3000L)
    }

    private fun linkButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp / 2
            marginEnd = dp / 2
        }
    }
}
