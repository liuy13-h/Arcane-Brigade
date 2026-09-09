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
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.io.InputStream;
import java.util.function.Consumer;

/**
 * 程序化生成的精灵图。零美术资源依赖，全部代码画出来。
 *
 * 所有 Image 在启动时一次性烘焙好，运行时只做 drawImage，绝不临时生成。
 */
public final class Sprites {

    /** 按职业索引的英雄静态形象（索引见 HeroClass：1 巫师 / 2 战士 / 3 弓箭手 / 4 召唤师） */
    public static Image[] heroes = new Image[5];
    /** 按职业索引的走动动画帧（GIF 解码结果）。null 表示没有动画，退化为静态形象 */
    public static GifDecoder.Animation[] heroWalk = new GifDecoder.Animation[5];
    /** 按 Boss 档位索引的 Boss 形象（BOSS_NAMES 的顺序） */
    public static Image[] bosses = new Image[4];
    /** 召唤师的宠物形象（程序化：没给美术素材，自己画一只秘能仆从） */
    public static Image minion;
    public static Image[] enemies = new Image[3];
    /** 按元素索引的弹体颜色，见 Element。运行时只查表，不做任何变换 */
    public static Image[] bolts = new Image[Element.COUNT];
    /** 敌方弹幕（统一的"敌意红"），与玩家元素弹做明显区分 */
    public static Image enemyBolt;
    public static Image gem;

    /** 快照需要节点挂在 Scene 下才可靠，用一个离屏容器兜着 */
    private static final Group OFFSCREEN = new Group();
    private static final Scene OFFSCREEN_SCENE = new Scene(OFFSCREEN, 1, 1);

    private Sprites() {}

    public static void load() {
        // 职业形象：优先读 resources/sprites 下的真实素材，读不到才回退到程序化绘制。
        // 回退很关键——build.bat 的 javac 兜底路径不会复制 resources，
        // 没有兜底就是一片空白。
        loadHero(HeroClass.WIZARD, "wizard", Sprites::paintWizard);
        loadHero(HeroClass.WARRIOR, "warrior", Sprites::paintWarrior);
        loadHero(HeroClass.ARCHER, "archer", Sprites::paintArcher);
        loadHero(HeroClass.SUMMONER, "summoner", Sprites::paintSummoner);
        for (int t = 0; t < bosses.length; t++) {
            bosses[t] = loadBoss(t);
        }
        minion = bake(28, 28, Sprites::paintMinion);
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
        // 敌方弹幕统一用"敌意红"：亮心 + 血红中圈 + 近黑外圈，一眼就能和玩家元素弹区分。
        enemyBolt = bake(22, 22, g -> paintBolt(g,
                Color.rgb(255, 225, 225), Color.rgb(255, 80, 70), Color.rgb(140, 8, 18)));
        gem = bake(14, 14, Sprites::paintGem);
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

    /** 职业形象 + 走动动画。任一缺失都用程序化绘制兜底 */
    private static void loadHero(int classKind, String base, Consumer<GraphicsContext> fallback) {
        Image idle = loadImage(base + ".png");
        heroes[classKind] = (idle != null) ? idle : bake(44, 44, fallback);
        try (InputStream in = res(base + "_walk.gif")) {
            heroWalk[classKind] = GifDecoder.decode(in);
        } catch (Exception e) {
            heroWalk[classKind] = null;
        }
    }

    /**
     * Boss 形象。原图是上千像素的 jpg，直接按原尺寸加载既占内存又慢，
     * 这里请求 192×192 的缩略图——显示尺寸只有 ~110px，缩放后看不出差别。
     */
    private static Image loadBoss(int tier) {
        String url = Sprites.class.getResource("/sprites/boss_" + tier + ".jpg") != null
                ? Sprites.class.getResource("/sprites/boss_" + tier + ".jpg").toExternalForm()
                : null;
        if (url == null) {
            return null;
        }
        try {
            return new Image(url, 192, 192, true, true);
        } catch (Exception e) {
            return null;
        }
    }

    private static Image loadImage(String name) {
        String url = Sprites.class.getResource("/sprites/" + name) != null
                ? Sprites.class.getResource("/sprites/" + name).toExternalForm()
                : null;
        if (url == null) {
            return null;
        }
        try {
            return new Image(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream res(String name) {
        return Sprites.class.getResourceAsStream("/sprites/" + name);
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

    private static void paintWizard(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下光晕
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(140, 190, 255, 0.45)),
                new Stop(1, Color.rgb(140, 190, 255, 0))));
        g.fillOval(2, 4, 40, 40);

        // 袍子
        g.setFill(Color.rgb(62, 84, 168));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(42, 58, 128));
        g.fillOval(cx - 10, cy + 8, 20, 10);

        // 帽子
        g.setFill(Color.rgb(46, 64, 140));
        g.beginPath();
        g.moveTo(cx, cy - 26);
        g.lineTo(cx + 12, cy - 4);
        g.lineTo(cx - 12, cy - 4);
        g.closePath();
        g.fill();
        g.setFill(Color.rgb(70, 96, 200));
        g.fillRect(cx - 14, cy - 6, 28, 4);

        // 法杖（右侧，顶部发光宝珠）
        g.setStroke(Color.rgb(128, 90, 48));
        g.setLineWidth(3);
        g.strokeLine(cx + 11, cy + 9, cx + 16, cy - 11);
        g.setFill(new RadialGradient(0, 0, cx + 16, cy - 13, 5, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(190, 235, 255)),
                new Stop(1, Color.rgb(120, 90, 230, 0))));
        g.fillOval(cx + 12, cy - 17, 8, 8);

        // 脸与眼睛
        g.setFill(Color.rgb(238, 220, 190));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(30, 32, 48));
        g.fillOval(cx - 3.5, cy + 0.5, 2.4, 2.4);
        g.fillOval(cx + 1.1, cy + 0.5, 2.4, 2.4);
    }

    private static void paintWarrior(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下光晕（暖色）
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 170, 90, 0.45)),
                new Stop(1, Color.rgb(255, 170, 90, 0))));
        g.fillOval(2, 4, 40, 40);

        // 剑（右侧，剑身朝上）
        g.setFill(Color.rgb(214, 224, 238));
        g.fillRect(cx + 9, cy - 13, 3, 15);
        g.setFill(Color.rgb(170, 120, 50));
        g.fillRect(cx + 7, cy + 2, 7, 2);
        g.setFill(Color.rgb(110, 74, 36));
        g.fillRect(cx + 10, cy + 4, 2, 6);

        // 盾（左侧圆形）
        g.setFill(Color.rgb(150, 70, 60));
        g.fillOval(cx - 18, cy - 4, 13, 15);
        g.setStroke(Color.rgb(235, 205, 140));
        g.setLineWidth(1.5);
        g.strokeOval(cx - 18, cy - 4, 13, 15);

        // 身体铠甲（铁灰 + 红带）
        g.setFill(Color.rgb(105, 110, 125));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(78, 82, 96));
        g.fillOval(cx - 10, cy + 8, 20, 10);
        g.setFill(Color.rgb(190, 60, 50));
        g.fillRect(cx - 10, cy - 1, 20, 3);

        // 头盔（带红缨）
        g.setFill(Color.rgb(122, 128, 146));
        g.fillArc(cx - 9, cy - 13, 18, 15, 0, 180, ArcType.ROUND);
        g.setFill(Color.rgb(205, 70, 60));
        g.beginPath();
        g.moveTo(cx, cy - 14);
        g.lineTo(cx - 3, cy - 21);
        g.lineTo(cx + 3, cy - 14);
        g.closePath();
        g.fill();

        // 脸与眼睛
        g.setFill(Color.rgb(238, 220, 190));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(30, 32, 48));
        g.fillOval(cx - 3.5, cy + 0.5, 2.4, 2.4);
        g.fillOval(cx + 1.1, cy + 0.5, 2.4, 2.4);
    }

    private static void paintArcher(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下光晕（绿色）
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(120, 220, 150, 0.45)),
                new Stop(1, Color.rgb(120, 220, 150, 0))));
        g.fillOval(2, 4, 40, 40);

        // 弓（横在身前，弧形弓臂朝左 + 弓弦）
        g.setStroke(Color.rgb(122, 82, 42));
        g.setLineWidth(3);
        g.strokeArc(cx - 17, cy - 7, 24, 20, 90, 180, ArcType.OPEN);
        g.setStroke(Color.rgb(235, 235, 235));
        g.setLineWidth(1);
        g.strokeLine(cx - 17, cy + 3, cx + 7, cy - 3);

        // 身体斗篷（绿色）
        g.setFill(Color.rgb(66, 138, 88));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(46, 104, 66));
        g.fillOval(cx - 10, cy + 8, 20, 10);

        // 兜帽
        g.setFill(Color.rgb(52, 120, 74));
        g.fillArc(cx - 10, cy - 14, 20, 18, 0, 180, ArcType.ROUND);
        g.setFill(Color.rgb(40, 96, 58));
        g.fillRect(cx - 12, cy - 6, 24, 3);

        // 脸与眼睛
        g.setFill(Color.rgb(238, 220, 190));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(30, 32, 48));
        g.fillOval(cx - 3.5, cy + 0.5, 2.4, 2.4);
        g.fillOval(cx + 1.1, cy + 0.5, 2.4, 2.4);
    }

    /** 召唤师兜底形象：紫金法袍 + 悬浮的召唤法阵 */
    private static void paintSummoner(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下召唤法阵（紫色）
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(200, 140, 255, 0.55)),
                new Stop(1, Color.rgb(120, 60, 200, 0))));
        g.fillOval(2, 4, 40, 40);
        g.setStroke(Color.rgb(215, 170, 255, 0.8));
        g.setLineWidth(1.2);
        g.strokeOval(cx - 14, cy + 12, 28, 9);

        // 身体（紫袍 + 金边）
        g.setFill(Color.rgb(112, 74, 168));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(86, 54, 136));
        g.fillOval(cx - 10, cy + 8, 20, 10);
        g.setFill(Color.rgb(240, 210, 120));
        g.fillRect(cx - 10, cy - 1, 20, 3);

        // 兜帽
        g.setFill(Color.rgb(96, 62, 150));
        g.fillArc(cx - 10, cy - 14, 20, 18, 0, 180, ArcType.ROUND);

        // 脸与眼睛（秘能色）
        g.setFill(Color.rgb(238, 220, 190));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(180, 120, 240));
        g.fillOval(cx - 3.5, cy + 0.5, 2.6, 2.6);
        g.fillOval(cx + 0.9, cy + 0.5, 2.6, 2.6);

        // 悬浮的召唤宝珠
        g.setFill(new RadialGradient(0, 0, cx - 14, cy - 12, 6, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(235, 210, 255)),
                new Stop(1, Color.rgb(150, 90, 240, 0))));
        g.fillOval(cx - 19, cy - 17, 10, 10);
    }

    /** 召唤物（宠物）：一只小型秘能仆从。没给美术素材，程序化画一个 */
    private static void paintMinion(GraphicsContext g) {
        double cx = 14, cy = 15;
        // 光晕
        g.setFill(new RadialGradient(0, 0, cx, cy, 13, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(190, 140, 255, 0.5)),
                new Stop(1, Color.rgb(120, 70, 210, 0))));
        g.fillOval(1, 2, 26, 26);

        // 身体（水滴形）
        g.setFill(Color.rgb(138, 92, 208));
        g.fillOval(cx - 8, cy - 7, 16, 17);
        g.setFill(Color.rgb(108, 68, 176));
        g.fillOval(cx - 8, cy + 4, 16, 8);

        // 尖耳（左右各一只）
        g.setFill(Color.rgb(112, 72, 182));
        g.beginPath();
        g.moveTo(cx - 7, cy - 5);
        g.lineTo(cx - 12, cy - 12);
        g.lineTo(cx - 3, cy - 8);
        g.closePath();
        g.fill();
        g.beginPath();
        g.moveTo(cx + 7, cy - 5);
        g.lineTo(cx + 12, cy - 12);
        g.lineTo(cx + 3, cy - 8);
        g.closePath();
        g.fill();

        // 眼睛（发光）
        g.setFill(Color.rgb(245, 235, 255));
        g.fillOval(cx - 4.5, cy - 2, 3.4, 3.4);
        g.fillOval(cx + 1.1, cy - 2, 3.4, 3.4);
        g.setFill(Color.rgb(60, 40, 90));
        g.fillOval(cx - 3.6, cy - 1.4, 1.8, 1.8);
        g.fillOval(cx + 2.0, cy - 1.4, 1.8, 1.8);
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
