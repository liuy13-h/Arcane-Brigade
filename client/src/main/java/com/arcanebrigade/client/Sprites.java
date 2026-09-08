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

import java.util.function.Consumer;

/**
 * 程序化生成的精灵图。零美术资源依赖，全部代码画出来。
 *
 * 所有 Image 在启动时一次性烘焙好，运行时只做 drawImage，绝不临时生成。
 */
public final class Sprites {

    /** 按职业索引的英雄形象（索引 1=巫师 / 2=战士 / 3=弓箭手，见 HeroClass） */
    public static Image[] heroes = new Image[4];
    public static Image[] enemies = new Image[3];
    /** 按元素索引的弹体颜色，见 Element。运行时只查表，不做任何变换 */
    public static Image[] bolts = new Image[Element.COUNT];
    public static Image gem;

    /** 快照需要节点挂在 Scene 下才可靠，用一个离屏容器兜着 */
    private static final Group OFFSCREEN = new Group();
    private static final Scene OFFSCREEN_SCENE = new Scene(OFFSCREEN, 1, 1);

    private Sprites() {}

    public static void load() {
        heroes[HeroClass.WIZARD]  = bake(44, 44, Sprites::paintWizard);
        heroes[HeroClass.WARRIOR] = bake(44, 44, Sprites::paintWarrior);
        heroes[HeroClass.ARCHER]  = bake(44, 44, Sprites::paintArcher);
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
