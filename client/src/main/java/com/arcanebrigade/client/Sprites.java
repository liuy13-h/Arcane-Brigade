package com.arcanebrigade.client;

import com.arcanebrigade.core.Element;
import com.arcanebrigade.core.HeroClass;
import javafx.scene.Group;
import javafx.scene.shape.ArcType;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.io.File;
import java.util.function.Consumer;

/**
 * 程序化生成的精灵图。零美术资源依赖，全部代码画出来。
 *
 * 所有 Image 在启动时一次性烘焙好，运行时只做 drawImage，绝不临时生成。
 */
public final class Sprites {

    /**
     * 按索引的形象。
     * 0 = 国王（大厅初始操控对象）；1/2/3 = 巫师/战士/弓箭手（HeroClass）；
     * 4 = 召唤师（LobbyClass.SUMMONER，仅大厅展示，无战斗逻辑）。
     */
    public static Image[] heroes = new Image[5];
    /** 王座大厅背景（启动后的准备大厅整屏底图） */
    public static Image lobbyBg;
    /**
     * 各职业的细节立绘（选人大厅右侧滑出的大图）。
     * 下标 = 职业 id（1..4 = 巫师/战士/弓箭手/召唤师）。
     * 这是美术给的大尺寸全身立绘，与上方 32px 行走小立绘 heroes[] 相互独立。
     */
    public static Image[] heroPortraits = new Image[5];
    public static Image[] enemies = new Image[3];
    /** 按元素索引的弹体颜色，见 Element。运行时只查表，不做任何变换 */
    public static Image[] bolts = new Image[Element.COUNT];
    public static Image gem;

    /** 快照需要节点挂在 Scene 下才可靠，用一个离屏容器兜着 */
    private static final Group OFFSCREEN = new Group();
    private static final Scene OFFSCREEN_SCENE = new Scene(OFFSCREEN, 1, 1);

    private Sprites() {}

    public static void load() {
        // 国王（大厅初始操控对象）：美术 32×32 小立绘，裁透明边再 ×2 最近邻放大。
        // 缺图时退回程序生成的中性旅行者，保证仍能跑。
        heroes[0] = pixelScale(trimOpaque(loadArt("Sprite-00017.png")), 2);
        if (heroes[0] == null) {
            heroes[0] = bake(44, 44, Sprites::paintAdventurer);
        }
        // 职业立绘与王座背景：从仓库根 image/ 读现成美术
        // 32x32 原图内容居中、四周留白，先裁掉透明边，再按整数倍(×2)最近邻放大。
        // 这样渲染时 1:1 绘制即是清晰像素风；直接放大到非整数尺寸会让 JavaFX
        // 用平滑插值把像素抹糊（用户反馈"有点糊"的根源）。
        heroes[HeroClass.ARCHER]  = pixelScale(trimOpaque(loadArt("Sprite-0001.png")), 2);   // 0001 射手
        heroes[HeroClass.WIZARD]  = pixelScale(trimOpaque(loadArt("Sprite-0002.png")), 2);   // 0002 法师
        heroes[HeroClass.WARRIOR] = pixelScale(trimOpaque(loadArt("Sprite-0003.png")), 2);   // 0003 战士
        heroes[LobbyClass.SUMMONER] = pixelScale(trimOpaque(loadArt("图片10.png")), 2);      // 召唤师
        lobbyBg = loadArt("皇宫王座大厅背景.jpg");
        // 细节立绘（右侧角色卡大图），按下标对齐职业。
        // 美术给的多是带纯色底（黑/白）的整幅图，叠到王座厅上会出现一块黑底/白底，
        // 这里把环绕角色、与图边相连的背景色抠成透明（见 knockoutBackground）。
        heroPortraits[HeroClass.WARRIOR] = knockoutBackground(loadArt("Edit_this_pixel_art_character__2026-09-09T01-59-50.png")); // 战士
        heroPortraits[HeroClass.WIZARD]  = knockoutBackground(loadArt("Edit_this_pixel_art_character__2026-09-09T02-00-45.png")); // 法师/巫师
        heroPortraits[HeroClass.ARCHER]  = knockoutBackground(loadArt("弓箭手角色-尖角额甲版.jpg"));                                // 射手
        heroPortraits[LobbyClass.SUMMONER] = knockoutBackground(loadArt("summoner_transparent.png"));                               // 召唤师
        enemies[0] = bake(32, 32, g -> paintSlime(g, Color.rgb(96, 200, 120), Color.rgb(40, 120, 70)));
        enemies[1] = bake(32, 32, g -> paintBat(g, Color.rgb(178, 130, 235), Color.rgb(96, 62, 150)));
        enemies[2] = bake(32, 32, g -> paintBrute(g, Color.rgb(240, 150, 80), Color.rgb(150, 74, 30)));
        bolts[Element.NONE]   = bake(22, 22, g -> paintBolt(g,
                Color.rgb(255, 250, 225), Color.rgb(255, 215, 120), Color.rgb(230, 180, 90)));
        bolts[Element.FIRE]   = bake(22, 22, g -> paintBolt(g,
                Color.rgb(255, 245, 210), Color.rgb(255, 150, 50), Color.rgb(220, 60, 20)));
        bolts[Element.FROST]  = bake(22, 22, g -> paintBolt(g,
                Color.rgb(240, 252, 255), Color.rgb(130, 205, 255), Color.rgb(40, 110, 220)));
        bolts[Element.SHOCK]  = bake(22, 22, g -> paintBolt(g,
                Color.rgb(255, 255, 215), Color.rgb(195, 235, 120), Color.rgb(125, 90, 240)));
        bolts[Element.ARCANE] = bake(22, 22, g -> paintBolt(g,
                Color.rgb(250, 235, 255), Color.rgb(205, 145, 255), Color.rgb(120, 60, 220)));
        gem = bake(14, 14, Sprites::paintGem);
    }

    /**
     * 裁掉四周全透明的边，只留不透明内容。32×32 精灵内容通常居中、四周留白，
     * 不裁掉的话按"底边贴地"缩放会整张图悬空。返回内容紧贴边框的新图（原图不动）。
     */
    private static Image trimOpaque(Image src) {
        if (src == null) {
            return null;
        }
        PixelReader pr = src.getPixelReader();
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((pr.getArgb(x, y) >>> 24) & 0xFF) != 0) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0 || y1 < 0) {
            return src;                        // 整张全透明：原样返回
        }
        final int fx0 = x0, fy0 = y0, fw = x1 - x0 + 1, fh = y1 - y0 + 1;
        return bake(fw, fh, g -> g.drawImage(src, fx0, fy0, fw, fh, 0, 0, fw, fh));
    }

    /**
     * 最近邻整数倍放大（关闭平滑插值），得到清晰的像素立绘。
     * k 必须是非负整数：k=2 即每个源像素占 2×2 目标像素，边角保持锐利。
     */
    private static Image pixelScale(Image src, int k) {
        if (src == null || k <= 1) {
            return src;
        }
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        return bake(w * k, h * k, g -> {
            g.setImageSmoothing(false);
            g.drawImage(src, 0, 0, w * k, h * k);
        });
    }

    /**
     * 抠掉环绕角色的背景：只把「与图片边缘四连通的近似纯色背景」变透明，
     * 角色主体内部的同色细节（描边、高光、瞳孔等）因不与边相连而完整保留。
     * 黑底、白底、已透明的边都会被清除，四种立绘统一得到干净透明背景。
     */
    private static Image knockoutBackground(Image src) {
        if (src == null) {
            return null;
        }
        PixelReader pr = src.getPixelReader();
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        int[] argb = new int[w * h];
        pr.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        boolean[] key = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            int a = (argb[i] >>> 24) & 0xFF;
            if (a < 40) {
                key[i] = true;                       // 原本透明：放行洪泛
                continue;
            }
            int r = (argb[i] >>> 16) & 0xFF;
            int g = (argb[i] >>> 8) & 0xFF;
            int b = argb[i] & 0xFF;
            key[i] = Math.max(r, Math.max(g, b)) < 48      // 近黑底
                    || Math.min(r, Math.min(g, b)) > 207;   // 近白底
        }
        boolean[] rm = new boolean[w * h];
        int[] stack = new int[w * h];
        int sp = 0;
        // 种子：顶/底/左/右四条边上的背景像素
        for (int x = 0; x < w; x++) {
            if (key[x] && !rm[x]) { rm[x] = true; stack[sp++] = x; }
            int b = (h - 1) * w + x;
            if (key[b] && !rm[b]) { rm[b] = true; stack[sp++] = b; }
        }
        for (int y = 0; y < h; y++) {
            int l = y * w;
            if (key[l] && !rm[l]) { rm[l] = true; stack[sp++] = l; }
            int rr = y * w + (w - 1);
            if (key[rr] && !rm[rr]) { rm[rr] = true; stack[sp++] = rr; }
        }
        while (sp > 0) {
            int idx = stack[--sp];
            if (idx % w > 0) {
                int n = idx - 1;
                if (key[n] && !rm[n]) { rm[n] = true; stack[sp++] = n; }
            }
            if (idx % w < w - 1) {
                int n = idx + 1;
                if (key[n] && !rm[n]) { rm[n] = true; stack[sp++] = n; }
            }
            int u = idx - w;
            if (u >= 0 && key[u] && !rm[u]) { rm[u] = true; stack[sp++] = u; }
            int d = idx + w;
            if (d < w * h && key[d] && !rm[d]) { rm[d] = true; stack[sp++] = d; }
        }
        int removed = 0;
        for (int i = 0; i < w * h; i++) {
            if (rm[i]) {
                argb[i] &= 0x00FFFFFF;               // 清掉 alpha
                removed++;
            }
        }
        if (removed == 0) {
            return src;                              // 本来就没有背景块
        }
        WritableImage out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return out;
    }

    private static Image bake(int w, int h, Consumer<GraphicsContext> painter) {
        Canvas c = new Canvas(w, h);
        OFFSCREEN.getChildren().add(c);
        try {
            painter.accept(c.getGraphicsContext2D());
            SnapshotParameters sp = new SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            return c.snapshot(sp, null);
        } finally {
            OFFSCREEN.getChildren().remove(c);
        }
    }

    /**
     * 从仓库根目录 image/ 载入现成美术。从当前工作目录往上逐级找 image 目录，
     * 兼容 run.bat（项目根）与 IntelliJ（可能是模块目录）两种工作目录。
     * 找不到时打印警告并返回 null，调用方需容忍缺图。
     */
    private static Image loadArt(String fileName) {
        File dir = null;
        for (File d = new File(System.getProperty("user.dir")); d != null; d = d.getParentFile()) {
            File cand = new File(d, "image");
            if (cand.isDirectory()) {
                dir = cand;
                break;
            }
        }
        File f = (dir != null) ? new File(dir, fileName) : new File(fileName);
        if (!f.isFile()) {
            System.err.println("[Sprites] 缺少美术资源: " + f.getAbsolutePath());
            return null;
        }
        return new Image(f.toURI().toString(), false);
    }

    /**
     * 预烘焙旋转帧。
     * JavaFX 运行时旋转 drawImage 会走软件变换路径，慢一个量级；
     * 方向性投射物（冰锥、飞刀）必须提前把 N 个角度烤成独立 Image。
     */
    public static Image[] bakeRotations(Image src, int angles) {
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        int size = (int) Math.ceil(Math.sqrt(w * w + h * h));
        Image[] out = new Image[angles];
        for (int i = 0; i < angles; i++) {
            double a = i * 2 * Math.PI / angles;
            out[i] = bake(size, size, g -> {
                g.translate(size / 2.0, size / 2.0);
                g.rotate(Math.toDegrees(a));
                g.drawImage(src, -w / 2.0, -h / 2.0);
            });
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 画法
    // ------------------------------------------------------------------

    /**
     * 中性「旅行者」剪影：准备大厅里还没选职业时的化身。
     * 刻意用灰色系，与三个职业的彩色光晕区分开。
     */
    private static void paintAdventurer(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下灰雾
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(210, 214, 224, 0.40)),
                new Stop(1, Color.rgb(210, 214, 224, 0))));
        g.fillOval(2, 4, 40, 40);

        // 灰斗篷
        g.setFill(Color.rgb(104, 108, 122));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(78, 82, 96));
        g.fillOval(cx - 10, cy + 8, 20, 10);

        // 兜帽(比斗篷浅一点,翻起的帽沿)
        g.setFill(Color.rgb(122, 126, 140));
        g.fillArc(cx - 10, cy - 15, 20, 19, 0, 180, ArcType.ROUND);
        g.setFill(Color.rgb(90, 93, 108));
        g.fillRect(cx - 12, cy - 6, 24, 3);

        // 脸与眼睛
        g.setFill(Color.rgb(226, 222, 214));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(30, 32, 48));
        g.fillOval(cx - 3.5, cy + 0.5, 2.4, 2.4);
        g.fillOval(cx + 1.1, cy + 0.5, 2.4, 2.4);
    }

    private static void paintSlime(GraphicsContext g, Color body, Color dark) {
        g.setFill(dark);
        g.fillOval(4, 8, 24, 22);
        g.setFill(body);
        g.fillOval(4, 6, 24, 20);
        g.setFill(Color.rgb(255, 255, 255, 0.55));
        g.fillOval(9, 9, 7, 5);
        g.setFill(Color.rgb(24, 32, 28));
        g.fillOval(11, 16, 3.4, 3.4);
        g.fillOval(18, 16, 3.4, 3.4);
    }

    private static void paintBat(GraphicsContext g, Color body, Color dark) {
        g.setFill(dark);
        g.beginPath();
        g.moveTo(16, 10);
        g.lineTo(30, 4);
        g.lineTo(28, 18);
        g.closePath();
        g.fill();
        g.beginPath();
        g.moveTo(16, 10);
        g.lineTo(2, 4);
        g.lineTo(4, 18);
        g.closePath();
        g.fill();
        g.setFill(body);
        g.fillOval(10, 8, 12, 16);
        g.setFill(Color.rgb(255, 240, 200));
        g.fillOval(12.5, 13, 2.8, 2.8);
        g.fillOval(16.7, 13, 2.8, 2.8);
    }

    private static void paintBrute(GraphicsContext g, Color body, Color dark) {
        g.setFill(dark);
        g.fillRect(4, 6, 24, 22);
        g.setFill(body);
        g.fillOval(6, 6, 20, 16);
        g.setFill(Color.rgb(255, 220, 190));
        g.fillOval(11, 20, 10, 6);
        g.setFill(Color.rgb(50, 26, 16));
        g.fillRect(9.5, 11, 4, 4);
        g.fillRect(18.5, 11, 4, 4);
    }

    private static void paintBolt(GraphicsContext g, Color core, Color mid, Color outer) {
        g.setFill(new RadialGradient(0, 0, 11, 11, 11, false, CycleMethod.NO_CYCLE,
                new Stop(0, core),
                new Stop(0.35, Color.color(mid.getRed(), mid.getGreen(), mid.getBlue(), 0.95)),
                new Stop(0.7, Color.color(outer.getRed(), outer.getGreen(), outer.getBlue(), 0.55)),
                new Stop(1, Color.color(outer.getRed(), outer.getGreen(), outer.getBlue(), 0))));
        g.fillOval(0, 0, 22, 22);
    }

    private static void paintGem(GraphicsContext g) {
        g.setFill(new RadialGradient(0, 0, 7, 7, 7, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(150, 255, 230, 0.9)),
                new Stop(1, Color.rgb(40, 190, 170, 0))));
        g.fillOval(0, 0, 14, 14);
        g.setFill(Color.rgb(120, 245, 215));
        g.beginPath();
        g.moveTo(7, 1);
        g.lineTo(13, 7);
        g.lineTo(7, 13);
        g.lineTo(1, 7);
        g.closePath();
        g.fill();
    }
}
