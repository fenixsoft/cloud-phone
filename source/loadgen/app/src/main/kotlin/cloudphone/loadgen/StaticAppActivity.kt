package cloudphone.loadgen

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 静态页（低负载对照）：静态列表（20 行稳定内容），用于"静止画面"码率/帧率基线
 * （spec：静止画面码率显著低于动态画面 → 编码器码率控制有效性判据）。
 */
class StaticAppActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "StaticApp 静态列表（低负载基线）"; textSize = 18f })
        for (i in 1..20) {
            root.addView(
                TextView(this).apply {
                    text = "条目 $i — 静态内容行（编码器应输出接近 0 增量的 P 帧）"
                    textSize = 15f
                },
            )
        }
        setContentView(root)
    }
}
