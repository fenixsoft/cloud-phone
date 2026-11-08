package cloudphone.shell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.Display;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * cloudphone shell 助手（adb root + app_process 启动，scrcpy 同款范式）。
 *
 * 端口：22340 = 行式控制命令；22341 = 帧流（每连接首行 displayId，
 * 之后 [u8 0x02][视频载荷] 记录流，载荷布局同 docs/protocol.md §2.2）。
 *
 * 命令：
 *   PING                        → PONG 1
 *   CREATE <w> <h> <dpi>        → OK <displayId> | ERR <reason>
 *   START <displayId> <pkg>     → OK <component> | ERR <reason>
 *   STOP  <displayId>           → OK | ERR
 *   INJECT <displayId> <act0..3> <x> <y> → OK | ERR   (归一化 0..65535；3=远端 back)
 *   IDR <displayId>             → OK | ERR
 *   CAPS                        → OK <描述>
 *
 * 虚拟屏采集编码：shell/root uid 经 DisplayManager.createVirtualDisplay(PUBLIC) 建屏，
 * MediaCodec AVC 软/硬编输出 H.264，帧内嵌单调时钟 PTS，首帧前置 SPS/PPS（csd）。
 */
public final class ShellHelper {

    private static final int CONTROL_PORT = 22340;
    private static final int FRAME_PORT = 22341;
    private static final int HELPER_VERSION = 1;

    private static Context context;
    private static DisplayManager displayManager;
    private static Object inputManager; // android.hardware.input.InputManager（反射注入）
    private static Method injectMethod;
    private static Method setDisplayIdMethod;

    /** 每个虚拟屏一个会话 */
    private static final class VdSession {
        final int displayId;
        final int width;
        final int height;
        final android.hardware.display.VirtualDisplay display;
        final android.media.MediaCodec codec;
        final Surface inputSurface;
        final LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>(180);
        final AtomicLong sequence = new AtomicLong(1);
        volatile byte[] csd = null;
        volatile boolean csdSent = false;
        volatile boolean running = true;
        long downTimeMs = 0;

        VdSession(int displayId, int width, int height,
                  android.hardware.display.VirtualDisplay display,
                  android.media.MediaCodec codec, Surface inputSurface) {
            this.displayId = displayId;
            this.width = width;
            this.height = height;
            this.display = display;
            this.codec = codec;
            this.inputSurface = inputSurface;
        }
    }

    private static final Map<Integer, VdSession> sessions = new ConcurrentHashMap<>();

    /** 主屏 DOWN 时间戳（display 0 无 VdSession 时的触摸起点） */
    private static long mainDownTimeMs = 0;

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepare(); // system context 创建需要主线程 Looper
        // system context（scrcpy 同款反射路径；多路径降级）
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object thread = null;
            try {
                thread = atClass.getMethod("currentActivityThread").invoke(null);
            } catch (Throwable ignored) {
            }
            if (thread == null) {
                thread = atClass.getMethod("systemMain").invoke(null);
            }
            context = (Context) atClass.getMethod("getSystemContext").invoke(thread);
            System.out.println("helper: system context ok");
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null) cause = cause.getCause();
            System.out.println("helper: system context FAILED: " + cause);
        }
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        try {
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            inputManager = imClass.getMethod("getInstance").invoke(null);
            injectMethod = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (ReflectiveOperationException e) {
            System.err.println("inject unavailable: " + e);
        }

        ServerSocket control = new ServerSocket(CONTROL_PORT, 8, java.net.InetAddress.getByName("127.0.0.1"));
        ServerSocket frameSrv = new ServerSocket(FRAME_PORT, 8, java.net.InetAddress.getByName("127.0.0.1"));
        System.out.println("cloudphone shell helper v" + HELPER_VERSION + " ready (uid=" + android.os.Process.myUid() + ")");

        Thread frameThread = new Thread(() -> {
            while (true) {
                try {
                    Socket s = frameSrv.accept();
                    serveFrames(s);
                } catch (IOException e) {
                    return;
                }
            }
        }, "frame-accept");
        frameThread.setDaemon(true);
        frameThread.start();

        while (true) {
            Socket s = control.accept();
            Thread t = new Thread(() -> serveControl(s), "control-" + s.getPort());
            t.setDaemon(true);
            t.start();
        }
    }

    // ── 控制命令 ──

    private static void serveControl(Socket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                String resp = handle(line.trim());
                w.write(resp + "\n");
                w.flush();
                if ("PING".equals(line.trim())) break; // 探活即断，避免探测连接堆积
            }
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static String handle(String cmd) {
        String[] p = cmd.split("\\s+");
        try {
            switch (p[0]) {
                case "PING":
                    return "PONG " + HELPER_VERSION;
                case "CAPS":
                    return "OK {\"helper\":" + HELPER_VERSION + ",\"uid\":" + android.os.Process.myUid()
                        + ",\"inject\":" + (injectMethod != null) + ",\"displays\":" + sessions.size() + "}";
                case "CREATE": {
                    int w = Integer.parseInt(p[1]);
                    int h = Integer.parseInt(p[2]);
                    int dpi = Integer.parseInt(p[3]);
                    return createDisplay(w, h, dpi);
                }
                case "START": {
                    int displayId = Integer.parseInt(p[1]);
                    String pkg = p[2];
                    return startApp(displayId, pkg);
                }
                case "STOP": {
                    int displayId = Integer.parseInt(p[1]);
                    VdSession s = sessions.remove(displayId);
                    if (s == null) return "ERR no such display " + displayId;
                    s.running = false;
                    try { s.codec.stop(); } catch (Exception ignored) {}
                    try { s.codec.release(); } catch (Exception ignored) {}
                    s.inputSurface.release();
                    s.display.release();
                    return "OK";
                }
                case "IDR": {
                    int displayId = Integer.parseInt(p[1]);
                    VdSession s = sessions.get(displayId);
                    if (s == null) return "ERR no such display";
                    android.os.Bundle params = new android.os.Bundle();
                    params.putInt(android.media.MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    s.codec.setParameters(params);
                    return "OK";
                }
                case "INJECT": {
                    int displayId = Integer.parseInt(p[1]);
                    int action = Integer.parseInt(p[2]);
                    int nx = Integer.parseInt(p[3]);
                    int ny = Integer.parseInt(p[4]);
                    return inject(displayId, action, nx, ny);
                }
                default:
                    return "ERR unknown command " + p[0];
            }
        } catch (Exception e) {
            return "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String createDisplay(int w, int h, int dpi) throws IOException {
        String name = "cloudphone-vd-" + w + "x" + h;
        String mimeType = android.media.MediaFormat.MIMETYPE_VIDEO_AVC;
        android.media.MediaCodecInfo encInfo = pickEncoder(mimeType);
        if (encInfo == null) return "ERR no AVC encoder";
        android.media.MediaCodec codec = android.media.MediaCodec.createByCodecName(encInfo.getName());
        android.media.MediaFormat format = android.media.MediaFormat.createVideoFormat(mimeType, w, h);
        format.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 8000 * 1000);
        format.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, Integer.MAX_VALUE);
        codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = codec.createInputSurface();
        codec.start();

        int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        android.hardware.display.VirtualDisplay vd;
        try {
            vd = displayManager.createVirtualDisplay(name, w, h, dpi, surface, flags);
        } catch (SecurityException e) {
            // 退回私有屏（own content；shell uid 通常可 PUBLIC）
            vd = displayManager.createVirtualDisplay(name, w, h, dpi, surface, 0);
        }
        if (vd == null) {
            try { codec.stop(); codec.release(); } catch (Exception ignored) {}
            surface.release();
            return "ERR createVirtualDisplay returned null";
        }
        int displayId = vd.getDisplay().getDisplayId();
        VdSession s = new VdSession(displayId, w, h, vd, codec, surface);
        sessions.put(displayId, s);
        drainEncoder(s);
        return "OK " + displayId;
    }

    private static void drainEncoder(final VdSession s) {
        Thread t = new Thread(() -> {
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            while (s.running) {
                int idx;
                try {
                    idx = s.codec.dequeueOutputBuffer(info, 50_000);
                } catch (Exception e) {
                    return;
                }
                if (idx < 0) continue;
                if (idx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (info.size > 0) {
                        java.nio.ByteBuffer b = s.codec.getOutputBuffer(idx);
                        if (b != null) {
                            byte[] out = new byte[info.size];
                            b.position(info.offset);
                            b.get(out);
                            s.csd = out;
                        }
                    }
                    s.codec.releaseOutputBuffer(idx, false);
                    continue;
                }
                if (info.size > 0) {
                    java.nio.ByteBuffer b = s.codec.getOutputBuffer(idx);
                    if (b != null) {
                        byte[] out = new byte[info.size];
                        b.position(info.offset);
                        b.get(out);
                        s.codec.releaseOutputBuffer(idx, false);
                        boolean keyframe = (info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        byte[] payload = out;
                        if (keyframe && !s.csdSent && s.csd != null) {
                            byte[] csd = s.csd;
                            payload = new byte[csd.length + out.length];
                            System.arraycopy(csd, 0, payload, 0, csd.length);
                            System.arraycopy(out, 0, payload, csd.length, out.length);
                            s.csdSent = true;
                        }
                        long ptsUs = android.os.SystemClock.elapsedRealtimeNanos() / 1000;
                        // 封 [u8 0x02][视频载荷] 记录，入队供帧流连接读取（布局 docs/protocol.md §2.2）
                        s.frames.offer(encodeVideoRecord(s.sequence.getAndIncrement(), ptsUs, keyframe, payload));
                    }
                } else {
                    s.codec.releaseOutputBuffer(idx, false);
                }
            }
        }, "vd-enc-" + s.displayId);
        t.setDaemon(true);
        t.start();
    }

    /** [u8 0x02][u32 seq][u64 pts][u8 flags][u16 fragIdx][u16 fragCnt][u32 total][data...] */
    private static byte[] encodeVideoRecord(long seq, long ptsUs, boolean keyframe, byte[] data) {
        byte[] rec = new byte[1 + 21 + data.length];
        rec[0] = 0x02;
        ByteBuffer b = ByteBuffer.wrap(rec, 1, 21 + data.length);
        b.putInt((int) seq);
        b.putLong(ptsUs);
        b.put((byte) (keyframe ? 0x01 : 0x00));
        b.putShort((short) 0);
        b.putShort((short) 1);
        b.putInt(data.length);
        b.put(data);
        return rec;
    }

    private static void serveFrames(Socket s) {
        int displayId = -1;
        OutputStream out = null;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String head = r.readLine();
            displayId = Integer.parseInt(head.trim());
            VdSession session = sessions.get(displayId);
            out = s.getOutputStream();
            if (session == null) {
                out.write("ERR no such display\n".getBytes());
                return;
            }
            out.write("OK\n".getBytes());
            out.flush();
            while (session.running) {
                byte[] rec = session.frames.poll(1, TimeUnit.SECONDS);
                if (rec != null) {
                    out.write(rec);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static String startApp(int displayId, String pkg) throws IOException {
        // 解析 launcher activity（shell cmd）
        android.util.Pair<String, String> comp = resolveLauncher(pkg);
        if (comp == null) return "ERR cannot resolve launcher activity of " + pkg;
        ProcessBuilder pb = new ProcessBuilder(
            "am", "start", "--display", String.valueOf(displayId), "-n",
            comp.first + "/" + comp.second);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = readAll(proc.getInputStream());
        try { proc.waitFor(); } catch (InterruptedException ignored) {}
        return out.contains("Error") || out.contains("Exception") ? "ERR " + firstLine(out) : "OK " + comp.second;
    }

    private static android.util.Pair<String, String> resolveLauncher(String pkg) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("cmd", "package", "resolve-activity", "--brief", pkg);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = readAll(proc.getInputStream());
        try { proc.waitFor(); } catch (InterruptedException ignored) {}
        String[] lines = out.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.contains("/") && l.startsWith(pkg)) {
                int slash = l.indexOf('/');
                return android.util.Pair.create(l.substring(0, slash), l.substring(slash + 1));
            }
        }
        return null;
    }

    private static String inject(int displayId, int action, int nx, int ny) {
        if (injectMethod == null || setDisplayIdMethod == null) {
            return "ERR injection unavailable on this image";
        }
        VdSession s = sessions.get(displayId);
        int w, h;
        if (s != null) {
            w = s.width;
            h = s.height;
        } else if (displayId == android.view.Display.DEFAULT_DISPLAY) {
            // 全屏主屏：无 VdSession（单应用 CREATE 才注册），实时取真实分辨率；
            // 旋转/分辨率变化自动跟随，无需重建会话
            android.view.Display d = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d == null) return "ERR no such display";
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            d.getRealMetrics(dm);
            w = dm.widthPixels;
            h = dm.heightPixels;
            if (w <= 0 || h <= 0) return "ERR no such display";
        } else {
            return "ERR no such display";
        }
        try {
            float x = nx * w / 65535f;
            float y = ny * h / 65535f;
            long now = android.os.SystemClock.uptimeMillis();
            InputEvent ev;
            if (action == 3) { // 远端 back 键
                ev = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
                setDisplayIdMethod.invoke(ev, displayId);
                injectMethod.invoke(inputManager, ev, 0);
                KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
                setDisplayIdMethod.invoke(up, displayId);
                injectMethod.invoke(inputManager, up, 0);
                return "OK";
            }
            if (action == 0) mainDownTimeMs = now; // DOWN 起始新触摸
            MotionEvent ev2 = MotionEvent.obtain(
                displayId == android.view.Display.DEFAULT_DISPLAY ? mainDownTimeMs : s.downTimeMs,
                now, action, x, y, 0);
            setDisplayIdMethod.invoke(ev2, displayId);
            injectMethod.invoke(inputManager, ev2, 0);
            ev2.recycle();
            return "OK";
        } catch (Exception e) {
            return "ERR inject: " + e.getMessage();
        }
    }

    private static android.media.MediaCodecInfo pickEncoder(String mime) {
        for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) {
                if (t.equalsIgnoreCase(mime)) return info;
            }
        }
        return null;
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }
}
