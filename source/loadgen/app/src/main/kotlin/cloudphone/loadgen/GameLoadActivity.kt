package cloudphone.loadgen

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 恒定负载页（gaming-uplink-1080p SPEC）：
 * - 默认（无 `entropy` extra）：Choreographer 驱动 60fps 简单方块动画（M0 验收矩阵的低熵 game 负载，
 *   语义与历史数据可比性不变）；
 * - `--es entropy low|mid|high`：高熵 GPU 场景——全屏 GLSurfaceView + 片元着色器
 *   value-noise 3 octave × 双层滚动 + 高对比粒子层 + 每 100ms 色相漂移。
 *   渲染循环由 GLSurfaceView 独立渲染线程驱动（不依赖串流会话），帧计数/fps overlay 同口径。
 *
 * 档位 = 粒子密度/噪声频率缩放（[EntropyTier]）；标定纪律见 docs/uplink-report.md 附录：
 * 档位定义不看代码参数看编码器实际输出。标定微调可用 extras 覆盖：
 * `--ef noiseFreq <f>` `--ef particleDensity <f>`（不传用档位默认）。
 *
 * 可点击按钮：点击白闪 + 计数（触摸注入通路验证 + 白闪延迟测量标记），两模式均保留。
 * 高熵场景本身为纯展示、不可交互（SPEC uplink-stress-loadgen：高熵负载场景）。
 */
class GameLoadActivity : Activity() {

    companion object {
        /** intent extra：高熵档位（low/mid/high）；缺省 = 传统简单动画（M0 矩阵兼容） */
        const val EXTRA_ENTROPY = "entropy"
        /** 标定微调 extras（可选，float） */
        const val EXTRA_NOISE_FREQ = "noiseFreq"
        const val EXTRA_PARTICLE_DENSITY = "particleDensity"

        private const val TAG = "cloudphone-loadgen"
    }

    private lateinit var counterText: TextView
    private lateinit var fpsText: TextView
    private lateinit var tapFlash: View
    private lateinit var root: FrameLayout

    private val frames = java.util.concurrent.atomic.AtomicLong()
    private val taps = java.util.concurrent.atomic.AtomicLong()
    private var lastFpsAtMs = 0L
    private var lastFpsFrames = 0L
    private var animRunning = false

    // 60fps 动画：方块往返移动（传统低熵负载）
    private var boxX = 0f
    private var boxDir = 1f
    private lateinit var box: View

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animRunning || !::box.isInitialized) return // entropy 模式无 box（GLSurfaceView 独立渲染）
            frames.incrementAndGet()
            val w = root.width.coerceAtLeast(1)
            boxX += boxDir * (w / 120f) // ~2s 单程
            if (boxX > w - 120) boxDir = -1f
            if (boxX < 0) boxDir = 1f
            box.translationX = boxX
            val now = SystemClock.elapsedRealtime()
            if (now - lastFpsAtMs >= 500) {
                val fps = (frames.get() - lastFpsFrames) * 1000 / (now - lastFpsAtMs).coerceAtLeast(1)
                fpsText.text = "fps=$fps frames=${frames.get()}"
                lastFpsAtMs = now
                lastFpsFrames = frames.get()
            }
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val density = resources.displayMetrics.density
        root = FrameLayout(this).apply { setBackgroundColor(0xFF20306A.toInt()) }

        counterText = TextView(this).apply {
            text = "GameLoad 60fps 压测页"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
        }
        fpsText = TextView(this).apply {
            setTextColor(0xFF88FF88.toInt())
            textSize = 14f
        }

        val tierName = intent?.getStringExtra(EXTRA_ENTROPY)?.lowercase()
        val tier = tierName?.let { runCatching { EntropyTier.valueOf(it.uppercase()) }.getOrNull() }
        if (tier != null) {
            setupEntropyScene(tier, density)
        } else {
            setupLegacyScene(density)
        }

        // 可点击按钮：点击 → 白闪 120ms + 计数（触摸通路验证 + 延迟测量标记），两模式共用
        val tapButton = Button(this).apply {
            text = "点我（白闪标记）"
            setOnClickListener {
                taps.incrementAndGet()
                counterText.text = "taps=${taps.get()}"
                tapFlash.visibility = View.VISIBLE
                window.decorView.postDelayed({ tapFlash.visibility = View.GONE }, 120)
                Toast.makeText(this@GameLoadActivity, "tap #${taps.get()}", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(
            tapButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = (48 * density).toInt() },
        )

        // 白闪覆盖层（延迟测量：注入点击 → 白帧出现 → 客户端解码可见）
        tapFlash = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
        }
        root.addView(
            tapFlash,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        setContentView(root)
    }

    /** 传统低熵负载：Choreographer 方块动画（M0 矩阵 game 负载语义不变） */
    private fun setupLegacyScene(density: Float) {
        box = View(this).apply { setBackgroundColor(0xFFFF5533.toInt()) }
        root.addView(
            box,
            FrameLayout.LayoutParams((120 * density).toInt(), (120 * density).toInt(), Gravity.TOP or Gravity.START),
        )
        root.addView(
            counterText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        root.addView(
            fpsText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = (28 * density).toInt() },
        )
    }

    /** 高熵 GPU 场景：全屏 GLSurfaceView（独立渲染线程），overlay 计数与 fps 打点同口径 */
    private fun setupEntropyScene(tier: EntropyTier, density: Float) {
        // 标定微调 extras：不传用档位默认（标定过程见 docs/uplink-report.md 附录 A）
        val noiseFreq = intent?.getFloatExtra(EXTRA_NOISE_FREQ, tier.noiseFreq) ?: tier.noiseFreq
        val particleDensity = intent?.getFloatExtra(EXTRA_PARTICLE_DENSITY, tier.particleDensity) ?: tier.particleDensity

        counterText.text = "高熵负载 ${tier.name.lowercase()}（noise=$noiseFreq particle=$particleDensity）"
        root.addView(
            counterText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        root.addView(
            fpsText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = (28 * density).toInt() },
        )

        val glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            // 必须先 setRenderer 再 setRenderMode（GLThread 由 setRenderer 创建，顺序反了 NPE）
            setRenderer(
                EntropyRenderer(
                    tier = tier,
                    noiseFreq = noiseFreq,
                    particleDensity = particleDensity,
                    fpsListener = { fps, total ->
                        runOnUiThread {
                            fpsText.text = "fps=$fps frames=$total"
                        }
                    },
                ),
            )
            // RENDERMODE_CONTINUOUSLY：GLSurfaceView 渲染线程独立驱动（不依赖 Choreographer/串流会话）
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        // 半分辨率渲染（布局 1/2 + 2× 缩放铺满）：swiftshader 软件光栅整屏只能 ~48fps，
        // 噪声场按 uv 归一化（屏幕空间频率与缓冲分辨率无关），半分辨率不改变图案结构，
        // 仅省 4× 光栅量 → 稳 60fps（SPEC 1.1 硬指标）
        root.addView(
            glView,
            FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START),
        )
        root.post {
            val hw = (root.width / 2).coerceAtLeast(1)
            val hh = (root.height / 2).coerceAtLeast(1)
            glView.layoutParams = FrameLayout.LayoutParams(hw, hh, Gravity.TOP or Gravity.START)
            glView.pivotX = 0f
            glView.pivotY = 0f
            glView.scaleX = 2f
            glView.scaleY = 2f
            glView.requestLayout()
        }
        // overlay 置顶（GLSurfaceView 之后 addView 的默认顶层顺序）
        counterText.bringToFront()
        fpsText.bringToFront()
        glView.tag = "entropy-gl"
    }

    override fun onResume() {
        super.onResume()
        animRunning = true
        lastFpsAtMs = SystemClock.elapsedRealtime()
        (root.findViewWithTag<GLSurfaceView>("entropy-gl"))?.onResume()
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        animRunning = false
        (root.findViewWithTag<GLSurfaceView>("entropy-gl"))?.onPause()
        super.onPause()
    }
}

/** 高熵档位：粒子密度/噪声频率缩放（档位验收以编码器实际输出为准，参数见 uplink-report 附录 A） */
enum class EntropyTier(val noiseFreq: Float, val particleDensity: Float) {
    LOW(noiseFreq = 3.0f, particleDensity = 9f),
    MID(noiseFreq = 11.0f, particleDensity = 30f),
    HIGH(noiseFreq = 24.0f, particleDensity = 64f),
}

/**
 * 高熵片元着色器渲染器（GLSL ES 1.00）：
 * - value-noise 3 octave × 双层反向滚动（空域高频细节 + 全局时域运动，每帧大面积像素变化）；
 * - 高对比粒子层：网格哈希定位 + 往复运动 + 高频闪烁（密度按档位缩放）；
 * - 每 100ms 色相漂移（uTime 量化 10Hz，黄金比例步进保证相邻色相差异最大化）；
 * - fps 打点：500ms 聚合回传 UI + 1s 聚合 logcat（`cloudphone-loadgen entropy fps=` 校验钩子）。
 */
class EntropyRenderer(
    private val tier: EntropyTier,
    private val noiseFreq: Float,
    private val particleDensity: Float,
    private val fpsListener: (fps: Int, totalFrames: Long) -> Unit,
) : GLSurfaceView.Renderer {

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            uniform vec2 uRes;
            uniform float uTime;
            uniform float uNoiseFreq;
            uniform float uParticle;

            // Hoskins 式 fract 哈希（无 sin）：软件 GL 下 ~2.5× 于 sin-hash（熵数学等价，60fps 达标关键）
            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float vnoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
                    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
                    u.y);
            }

            // value-noise 3 octave，各层独立时域滚动（空域熵 + 时域熵）
            float fbm3(vec2 p, float t) {
                float v = 0.5333 * vnoise(p + vec2(t * 1.7, -t * 1.3));
                v += 0.2667 * vnoise(p * 2.03 + vec2(-t * 2.9, t * 2.2) + 17.17);
                v += 0.1333 * vnoise(p * 4.09 + vec2(t * 4.1, -t * 3.3) + 47.3);
                return v;
            }

            // 单 octave 滚动噪声（第二层：全局运动为主，软件 GL 下省 2 次 sin 哈希保 60fps）
            float layer2(vec2 p, float t) {
                return vnoise(p + vec2(-t * 2.9, t * 2.2) + 17.17);
            }

            vec3 hsl2rgb(float h, float s, float l) {
                vec3 rgb = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
                return l + s * (rgb - 0.5) * (1.0 - abs(2.0 * l - 1.0));
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / uRes;
                vec2 p = uv * vec2(uRes.x / uRes.y, 1.0);
                float t = uTime;

                // 双层噪声场（不同频率/方向）：主层 3 octave + 副层 1 octave
                float field = fbm3(p * uNoiseFreq, t) * 0.68 + layer2(p * uNoiseFreq * 2.7, -t * 0.7) * 0.32;

                // 每 100ms 色相漂移：量化 10Hz + 黄金比例步进
                float hue = fract(floor(t * 10.0) * 0.61803398875);
                vec3 base = hsl2rgb(hue, 0.85, 0.25 + 0.5 * field);

                // 高对比粒子层：网格哈希定位 + 往复运动 + 高频闪烁
                vec2 g = p * uParticle;
                vec2 cell = floor(g);
                vec2 f = fract(g) - 0.5;
                float rnd = hash(cell);
                float rnd2 = hash(cell + 91.7);
                vec2 vel = vec2(
                    sin(t * (1.0 + rnd * 3.0) + rnd2 * 6.2831),
                    cos(t * (1.0 + rnd2 * 3.0) + rnd * 6.2831)) * 0.34;
                float d = length(f - vel);
                float spark = step(d, 0.055 + 0.05 * rnd) * (0.65 + 0.35 * sin(t * 42.0 + rnd * 6.2831));
                vec3 col = mix(base, vec3(1.0), spark * step(0.35, rnd));
                col = mix(col, vec3(0.02), spark * (1.0 - step(0.35, rnd)));
                gl_FragColor = vec4(col, 1.0);
            }
        """
    }

    private var program = 0
    private var uResLoc = 0
    private var uTimeLoc = 0
    private var uNoiseFreqLoc = 0
    private var uParticleLoc = 0
    private var startNanos = 0L
    private val frameCount = java.util.concurrent.atomic.AtomicLong()
    private var lastReportAtMs = 0L
    private var lastLogAtMs = 0L
    private var lastLogFrames = 0L

    private fun compileShader(type: Int, src: String): Int {
        val shader = android.opengl.GLES20.glCreateShader(type)
        android.opengl.GLES20.glShaderSource(shader, src)
        android.opengl.GLES20.glCompileShader(shader)
        val status = IntArray(1)
        android.opengl.GLES20.glGetShaderiv(shader, android.opengl.GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = android.opengl.GLES20.glGetShaderInfoLog(shader)
            throw IllegalStateException("shader 编译失败: $log")
        }
        return shader
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vs = compileShader(android.opengl.GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(android.opengl.GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = android.opengl.GLES20.glCreateProgram().also {
            android.opengl.GLES20.glAttachShader(it, vs)
            android.opengl.GLES20.glAttachShader(it, fs)
            android.opengl.GLES20.glLinkProgram(it)
            val status = IntArray(1)
            android.opengl.GLES20.glGetProgramiv(it, android.opengl.GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "program 链接失败: ${android.opengl.GLES20.glGetProgramInfoLog(it)}" }
        }
        android.opengl.GLES20.glUseProgram(program)
        uResLoc = android.opengl.GLES20.glGetUniformLocation(program, "uRes")
        uTimeLoc = android.opengl.GLES20.glGetUniformLocation(program, "uTime")
        uNoiseFreqLoc = android.opengl.GLES20.glGetUniformLocation(program, "uNoiseFreq")
        uParticleLoc = android.opengl.GLES20.glGetUniformLocation(program, "uParticle")

        // 全屏四边形（TRIANGLE_STRIP，clip space）
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val vbo = IntArray(1)
        android.opengl.GLES20.glGenBuffers(1, vbo, 0)
        android.opengl.GLES20.glBindBuffer(android.opengl.GLES20.GL_ARRAY_BUFFER, vbo[0])
        val bb = java.nio.ByteBuffer.allocateDirect(quad.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(quad)
        android.opengl.GLES20.glBufferData(
            android.opengl.GLES20.GL_ARRAY_BUFFER, quad.size * 4, bb.asFloatBuffer(),
            android.opengl.GLES20.GL_STATIC_DRAW,
        )
        val aPos = android.opengl.GLES20.glGetAttribLocation(program, "aPos")
        android.opengl.GLES20.glEnableVertexAttribArray(aPos)
        android.opengl.GLES20.glVertexAttribPointer(aPos, 2, android.opengl.GLES20.GL_FLOAT, false, 0, 0)
        android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
        startNanos = SystemClock.elapsedRealtimeNanos()
        lastReportAtMs = 0L
        lastLogAtMs = 0L
        Log.i("cloudphone-loadgen", "entropy renderer ready tier=${tier.name.lowercase()} " +
            "noise=$noiseFreq particle=$particleDensity")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        android.opengl.GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val t = (nowNanos - startNanos) / 1_000_000_000.0f
        val total = frameCount.incrementAndGet()
        val nowMs = SystemClock.elapsedRealtime()
        if (lastReportAtMs == 0L) lastReportAtMs = nowMs

        android.opengl.GLES20.glUniform2f(uResLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        android.opengl.GLES20.glUniform1f(uTimeLoc, t)
        android.opengl.GLES20.glUniform1f(uNoiseFreqLoc, noiseFreq)
        android.opengl.GLES20.glUniform1f(uParticleLoc, particleDensity)
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 500ms → UI overlay；1s → logcat 校验钩子（先本地跑稳 60fps 再接入矩阵的验收探针）
        if (nowMs - lastReportAtMs >= 500) {
            val fps = ((total - (lastReportFrames)) * 1000 / (nowMs - lastReportAtMs).coerceAtLeast(1)).toInt()
            fpsListener(fps, total)
            lastReportAtMs = nowMs
            lastReportFrames = total
        }
        if (lastLogAtMs != 0L && nowMs - lastLogAtMs >= 1000) {
            val fps = (total - lastLogFrames) * 1000 / (nowMs - lastLogAtMs).coerceAtLeast(1)
            Log.i("cloudphone-loadgen", "entropy fps=$fps tier=${tier.name.lowercase()} frames=$total")
            lastLogAtMs = nowMs
            lastLogFrames = total
        } else if (lastLogAtMs == 0L) {
            lastLogAtMs = nowMs
            lastLogFrames = total
        }
    }

    private var lastReportFrames = 0L
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    override fun toString(): String = "EntropyRenderer(tier=$tier)"
}
