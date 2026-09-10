package com.arcanebrigade.client;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 的 GIF89a 解码器。
 *
 * 存在的理由：JavaFX 的 Image 读 GIF 只会取第一帧，且不做动画。
 * 角色走动是几帧循环动画，必须自己把每一帧解出来，运行时按时间切帧播放。
 * 这里不引第三方库——项目要能一条 mvn 命令跑起来。
 *
 * 支持：全局/局部颜色表、透明索引、disposal 合成（0/1/2/3）、隔行扫描、帧延迟。
 */
public final class GifDecoder {

    /** 解码结果：按播放顺序排列的帧 + 每帧停留秒数 */
    public static final class Animation {
        public final Image[] frames;
        public final float[] delays;
        /** 循环一圈的总时长（秒）。动画时间取模它就是当前帧 */
        public final float total;

        Animation(Image[] frames, float[] delays) {
            this.frames = frames;
            this.delays = delays;
            float t = 0f;
            for (float d : delays) {
                t += d;
            }
            this.total = t;
        }

        /** 按累计时间取帧。total<=0 或只有一帧时恒返回第 0 帧 */
        public Image frameAt(float time) {
            if (frames.length == 0) {
                return null;
            }
            if (frames.length == 1 || total <= 0f) {
                return frames[0];
            }
            float t = time % total;
            if (t < 0f) {
                t += total;
            }
            for (int i = 0; i < frames.length; i++) {
                t -= delays[i];
                if (t <= 0f) {
                    return frames[i];
                }
            }
            return frames[frames.length - 1];
        }
    }

    private static final int DISPOSAL_NONE = 0;
    private static final int DISPOSAL_KEEP = 1;
    private static final int DISPOSAL_RESTORE_BG = 2;
    private static final int DISPOSAL_RESTORE_PREV = 3;

    private GifDecoder() {}

    /** 解码一个 GIF。失败返回 null，调用方据此回退到静态图或程序化绘制 */
    public static Animation decode(InputStream in) {
        if (in == null) {
            return null;
        }
        try {
            byte[] data = readAll(in);
            return decode(data);
        } catch (IOException | RuntimeException e) {
            System.err.println("[gif] 解码失败: " + e);
            return null;
        }
    }

    private static Animation decode(byte[] d) {
        if (d.length < 13 || !(d[0] == 'G' && d[1] == 'I' && d[2] == 'F')) {
            return null;
        }
        int w = u16(d, 6);
        int h = u16(d, 8);
        if (w <= 0 || h <= 0 || w * h > 16_000_000) {
            return null;
        }
        int packed = d[10] & 0xFF;
        int bgIndex = d[11] & 0xFF;
        int pos = 13;
        int[] gct = null;
        if ((packed & 0x80) != 0) {
            int n = 2 << (packed & 7);
            gct = new int[n];
            for (int i = 0; i < n; i++) {
                gct[i] = argb(d[pos] & 0xFF, d[pos + 1] & 0xFF, d[pos + 2] & 0xFF);
                pos += 3;
            }
        }

        int[] canvas = new int[w * h];             // 合成画布，ARGB，初始全透明
        int[] prevCanvas = null;                   // disposal=3 用：上一帧绘制前的快照
        int prevDisposal = DISPOSAL_KEEP;
        int prevLeft = 0, prevTop = 0, prevW = 0, prevH = 0;
        int[] prevPal = gct;                       // disposal=2 回填背景色要用上一帧的调色板
        int prevTransparent = -1;

        List<Image> frames = new ArrayList<>();
        List<Float> delays = new ArrayList<>();

        int transparentIndex = -1;
        int pendingDelay = -1;
        int pendingDisposal = DISPOSAL_KEEP;

        while (pos < d.length) {
            int descAt = pos;
            int b = d[pos++] & 0xFF;
            if (b == 0x3B) {
                break;                                  // trailer
            }
            if (b == 0x21) {                            // extension
                int label = d[pos++] & 0xFF;
                if (label == 0xF9) {                    // Graphic Control
                    int size = d[pos++] & 0xFF;
                    int gp = d[pos] & 0xFF;
                    int delayCs = u16(d, pos + 1);
                    transparentIndex = ((gp & 0x01) != 0) ? (d[pos + 3] & 0xFF) : -1;
                    pendingDisposal = (gp >> 2) & 0x07;
                    pendingDelay = delayCs;
                    pos += size;
                    pos++;                              // block terminator
                } else {
                    pos = skipSubBlocks(d, pos);        // 应用扩展 / 注释：直接跳过
                }
                continue;
            }
            if (b != 0x2C) {
                if (System.getProperty("ab.gifdebug") != null) {
                    System.out.println("  [dbg] unknown block byte " + Integer.toHexString(b)
                            + " at " + (pos - 1));
                }
                continue;                               // 未知块：跳过
            }

            // ---- Image Descriptor ----
            int left = u16(d, pos);
            int top = u16(d, pos + 2);
            int fw = u16(d, pos + 4);
            int fh = u16(d, pos + 6);
            int lpacked = d[pos + 8] & 0xFF;
            pos += 9;
            int[] pal = gct;
            if ((lpacked & 0x80) != 0) {
                int n = 2 << (lpacked & 7);
                pal = new int[n];
                for (int i = 0; i < n; i++) {
                    pal[i] = argb(d[pos] & 0xFF, d[pos + 1] & 0xFF, d[pos + 2] & 0xFF);
                    pos += 3;
                }
            }
            boolean interlaced = (lpacked & 0x40) != 0;
            int minCodeSize = d[pos++] & 0xFF;
            Ref cursor = ref(pos);                  // 必须持有同一个盒子，读完后取回新位置
            byte[] lzw = readSubBlocks(d, cursor);
            pos = cursor.value;

            // ---- 先按上一帧的 disposal 处理画布 ----
            if (prevDisposal == DISPOSAL_RESTORE_BG) {
                // 有透明索引时"背景色"就是透明——不能拿 bgIndex 去查调色板，
                // 否则背景会被填成不透明黑，精灵在场景上会顶着一个黑方块。
                int fill = 0x00000000;
                if (prevTransparent < 0 && prevPal != null
                        && bgIndex >= 0 && bgIndex < prevPal.length) {
                    fill = prevPal[bgIndex];
                }
                fillRect(canvas, w, prevLeft, prevTop, prevW, prevH, fill);
            } else if (prevDisposal == DISPOSAL_RESTORE_PREV && prevCanvas != null) {
                System.arraycopy(prevCanvas, 0, canvas, 0, canvas.length);
            }
            prevCanvas = canvas.clone();
            prevLeft = left;
            prevTop = top;
            prevW = fw;
            prevH = fh;
            prevDisposal = pendingDisposal;
            prevPal = pal;
            prevTransparent = transparentIndex;

            // ---- 解码索引并合成到画布 ----
            if (pal != null) {
                int[] idx = lzwDecode(lzw, minCodeSize, fw * fh);
                if (idx != null) {
                    int n = Math.min(idx.length, fw * fh);
                    if (interlaced) {
                        writeInterlaced(canvas, w, h, idx, n, left, top, fw, fh, pal, transparentIndex);
                    } else {
                        for (int i = 0; i < n; i++) {
                            int ci = idx[i];
                            if (ci == transparentIndex || ci >= pal.length) {
                                continue;
                            }
                            int px = left + (i % fw);
                            int py = top + (i / fw);
                            if (px >= 0 && px < w && py >= 0 && py < h) {
                                canvas[py * w + px] = pal[ci];
                            }
                        }
                    }
                }
            }

            frames.add(toImage(canvas, w, h));
            if (System.getProperty("ab.gifdebug") != null) {
                System.out.println("  [dbg] frame " + frames.size() + " desc@" + descAt
                        + " box=" + left + "," + top + " " + fw + "x" + fh
                        + " lct=" + ((lpacked & 0x80) != 0) + " interlace=" + interlaced
                        + " minCode=" + minCodeSize + " lzwBytes=" + lzw.length
                        + " nextPos=" + pos + "/" + d.length);
            }
            // 延迟单位 1/100 秒；<=1 的按 8fps 兜底，避免动画看起来是静止的
            float delay = (pendingDelay > 1) ? pendingDelay / 100f : 0.125f;
            delays.add(delay);

            pendingDelay = -1;
            pendingDisposal = DISPOSAL_KEEP;
            transparentIndex = -1;
        }

        if (frames.isEmpty()) {
            return null;
        }
        Image[] fa = frames.toArray(new Image[0]);
        float[] da = new float[delays.size()];
        for (int i = 0; i < da.length; i++) {
            da[i] = delays.get(i);
        }
        return new Animation(fa, da);
    }

    private static Image toImage(int[] pixels, int w, int h) {
        WritableImage img = new WritableImage(w, h);
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                pw.setArgb(x, y, pixels[row + x]);
            }
        }
        return img;
    }

    private static void writeInterlaced(int[] canvas, int w, int canvasH, int[] idx, int n,
                                        int left, int top, int fw, int fh,
                                        int[] pal, int transparent) {
        // 四遍隔行：起始行 0/4/2/1，步长 8/8/4/2
        int[] starts = { 0, 4, 2, 1 };
        int[] steps = { 8, 8, 4, 2 };
        int i = 0;
        for (int pass = 0; pass < 4 && i < n; pass++) {
            for (int y = starts[pass]; y < fh && i < n; y += steps[pass]) {
                for (int x = 0; x < fw && i < n; x++, i++) {
                    int ci = idx[i];
                    if (ci == transparent || ci >= pal.length) {
                        continue;
                    }
                    int px = left + x;
                    int py = top + y;
                    if (px >= 0 && px < w && py >= 0 && py < canvasH) {
                        canvas[py * w + px] = pal[ci];
                    }
                }
            }
        }
    }

    private static void fillRect(int[] canvas, int w, int left, int top, int fw, int fh, int argb) {
        for (int y = top; y < top + fh; y++) {
            if (y < 0 || y * w >= canvas.length) {
                continue;
            }
            for (int x = left; x < left + fw; x++) {
                if (x < 0 || x >= w) {
                    continue;
                }
                canvas[y * w + x] = argb;
            }
        }
    }

    // ------------------------------------------------------------------
    // LZW
    // ------------------------------------------------------------------

    /** 传入 minCodeSize 与 LZW 数据块拼接结果，返回颜色索引序列 */
    private static int[] lzwDecode(byte[] block, int minCodeSize, int pixelCount) {
        if (block == null || block.length == 0 || pixelCount <= 0) {
            return null;
        }
        int outSize = Math.max(pixelCount, 16);
        int[] out = new int[outSize];
        int outIdx = 0;

        int min = Math.max(2, Math.min(minCodeSize, 8));
        int clear = 1 << min;
        int eoi = clear + 1;
        int codeSize = min + 1;
        int next = eoi + 1;

        int[] prefix = new int[4096];
        int[] suffix = new int[4096];
        int[] stack = new int[4096];

        int bitPos = 0;
        int prev = -1;
        int totalBits = block.length * 8;

        while (bitPos + codeSize <= totalBits) {
            int code = 0;
            for (int i = 0; i < codeSize; i++) {
                int bit = (block[(bitPos + i) >>> 3] >> ((bitPos + i) & 7)) & 1;
                code |= bit << i;
            }
            bitPos += codeSize;

            if (code == clear) {
                codeSize = min + 1;
                next = eoi + 1;
                prev = -1;
                continue;
            }
            if (code == eoi) {
                break;
            }
            int cur;
            int extra = -1;                     // KwKwK 时补在末尾的那个字符
            if (code < next) {
                cur = code;
            } else if (code == next) {
                // KwKwK：这一码还没进表，它代表 string(prev) + firstChar(prev)。
                // 少了末尾这个字符，像素流就会整体少一个，画面会随帧逐渐"斜移"。
                cur = prev;
                extra = rootOf(prefix, prev, clear);
            } else {
                break;                      // 非法码：数据坏了，就此收工
            }
            if (cur < 0) {
                break;
            }

            // 把 cur 对应的串逆序展开到 stack，再正序输出
            int sp = 0;
            int c = cur;
            while (c >= clear && sp < stack.length) {
                stack[sp++] = suffix[c];
                c = prefix[c];
            }
            if (outIdx >= out.length) {
                break;
            }
            out[outIdx++] = c;
            while (sp > 0 && outIdx < out.length) {
                out[outIdx++] = stack[--sp];
            }
            if (extra >= 0 && outIdx < out.length) {
                out[outIdx++] = extra;
            }

            if (prev >= 0 && next < 4096) {
                prefix[next] = prev;
                suffix[next] = c;           // 新串的最后一个字符 = 当前串的首字符
                next++;
                if (next == (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
            }
            prev = code;
        }

        int[] res = new int[outIdx];
        System.arraycopy(out, 0, res, 0, outIdx);
        return res;
    }

    /** 顺着 prefix 链走到根节点，得到的就是这一串的首字符 */
    private static int rootOf(int[] prefix, int code, int clear) {
        int c = code;
        int guard = 0;
        while (c >= clear && guard++ < 4096) {
            c = prefix[c];
        }
        return c;
    }

    // ------------------------------------------------------------------
    // 字节流工具
    // ------------------------------------------------------------------

    /** 用来把"子块读完后的新位置"带出方法。Java 没有出参，只能借个小盒子 */
    private static final class Ref {
        int value;
        Ref(int v) { value = v; }
    }

    private static Ref ref(int v) {
        return new Ref(v);
    }

    private static byte[] readSubBlocks(byte[] d, Ref pos) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int p = pos.value;
        while (p < d.length) {
            int len = d[p++] & 0xFF;
            if (len == 0) {
                break;
            }
            if (p + len > d.length) {
                break;
            }
            buf.write(d, p, len);
            p += len;
        }
        pos.value = p;
        return buf.toByteArray();
    }

    private static int skipSubBlocks(byte[] d, int pos) {
        int p = pos;
        while (p < d.length) {
            int len = d[p++] & 0xFF;
            if (len == 0) {
                break;
            }
            p += len;
        }
        return p;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
