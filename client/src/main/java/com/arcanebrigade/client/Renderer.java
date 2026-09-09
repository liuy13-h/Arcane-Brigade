package com.arcanebrigade.client;

import com.arcanebrigade.core.Balance;
import com.arcanebrigade.core.Element;
import com.arcanebrigade.core.HeroClass;
import com.arcanebrigade.core.Loadout;
import com.arcanebrigade.core.PassiveDef;
import com.arcanebrigade.core.Passives;
import com.arcanebrigade.core.SpellDef;
import com.arcanebrigade.core.Spells;
import com.arcanebrigade.core.Upgrades;
import com.arcanebrigade.core.World;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Canvas 立即模式渲染。整个画面在一张 Canvas 上画完，不往 Scene Graph 里塞任何节点。
 *
 * 特效全部是程序化几何（圆环 / 线段 / 扇形），没有一张贴图——
 * 这样爆炸和闪电链不管叠多少都不吃额外内存。
 */
public final class Renderer {

    private static final double TILE = 72.0;

    /** 复用同一个 Text 量宽度，避免每帧新建节点 */
    private static final Text measurer = new Text();

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Font hudFont = Font.font("Consolas", 14);
    private final Font spellFont = Font.font("Microsoft YaHei", 13);

    private final Color hpBack = Color.rgb(70, 30, 40);
    private final Color hpFill = Color.rgb(225, 65, 85);

    private float camX;
    private float camY;
    private boolean camReady;
    private double fps;

    public Renderer(Canvas canvas) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
    }

    public void setFps(double v) {
        this.fps = v;
    }

    public double getCanvasWidth() {
        return canvas.getWidth();
    }

    public double getCanvasHeight() {
        return canvas.getHeight();
    }

    public void draw(World w, float alpha) {
        double vw = canvas.getWidth();
        double vh = canvas.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        updateCamera(w, alpha);

        // 背景与地砖随场景主题切换（森林 / 雪原 / 熔岩 / 终焉）
        Color[] pal = stagePalette(w.stage());
        gc.setFill(pal[0]);
        gc.fillRect(0, 0, vw, vh);
        drawTiles(vw, vh, pal[1], pal[2]);
        drawObstacles(w, alpha, vw, vh);
        drawEntities(w, alpha, vw, vh);
        drawHud(w, vw, vh);
        drawBossBar(w, vw);
    }

    /** 4 个阶段的配色：[背景, 地砖A, 地砖B] */
    private static Color[] stagePalette(int stage) {
        return switch (stage) {
            case 0 -> new Color[] { Color.rgb(18, 22, 16), Color.rgb(26, 34, 24), Color.rgb(31, 40, 28) }; // 森林
            case 1 -> new Color[] { Color.rgb(20, 24, 32), Color.rgb(28, 34, 44), Color.rgb(33, 40, 52) }; // 雪原
            case 2 -> new Color[] { Color.rgb(28, 16, 14), Color.rgb(38, 22, 18), Color.rgb(44, 26, 20) }; // 熔岩
            default -> new Color[] { Color.rgb(18, 14, 26), Color.rgb(26, 20, 38), Color.rgb(31, 24, 45) }; // 终焉
        };
    }

    private void updateCamera(World w, float alpha) {
        if (w.wizardCount() == 0) {
            return;
        }
        int id = w.wizard(0);
        float rx = w.px[id] + (w.x[id] - w.px[id]) * alpha;
        float ry = w.py[id] + (w.y[id] - w.py[id]) * alpha;
        if (!camReady) {
            camX = rx;
            camY = ry;
            camReady = true;
        } else {
            camX += (rx - camX) * 0.14f;
            camY += (ry - camY) * 0.14f;
        }
    }

    private void drawTiles(double vw, double vh, Color tileA, Color tileB) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        int c0 = (int) Math.floor(left / TILE);
        int c1 = (int) Math.floor((left + vw) / TILE);
        int r0 = (int) Math.floor(top / TILE);
        int r1 = (int) Math.floor((top + vh) / TILE);
        for (int c = c0; c <= c1; c++) {
            for (int r = r0; r <= r1; r++) {
                gc.setFill(((c + r) & 1) == 0 ? tileA : tileB);
                gc.fillRect(c * TILE - left, r * TILE - top, TILE, TILE);
            }
        }
    }

    /** 障碍物：静态碰撞体，绘制在实体下层，颜色随场景主题与类型变化 */
    private void drawObstacles(World w, float alpha, double vw, double vh) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        double right = left + vw;
        double bottom = top + vh;
        int stage = w.stage();
        for (int i = 0; i < w.highWater(); i++) {
            if (!w.alive[i] || w.kind[i] != World.KIND_OBSTACLE) {
                continue;
            }
            float rx = w.x[i];
            float ry = w.y[i];
            float rr = w.r[i];
            if (rx + rr < left || rx - rr > right || ry + rr < top || ry - rr > bottom) {
                continue;
            }
            double sx = rx - left;
            double sy = ry - top;
            gc.setFill(obstacleFill(stage, w.meta[i]));
            gc.fillOval(sx - rr, sy - rr, rr * 2, rr * 2);
            gc.setStroke(obstacleStroke(stage));
            gc.setLineWidth(2);
            gc.strokeOval(sx - rr, sy - rr, rr * 2, rr * 2);
        }
    }

    private static Color obstacleFill(int stage, int type) {
        return switch (stage) {
            case 0 -> switch (type) { // 森林：树 / 巨石 / 灌木
                case 0 -> Color.rgb(46, 92, 46);
                case 1 -> Color.rgb(110, 110, 120);
                default -> Color.rgb(60, 80, 50);
            };
            case 1 -> switch (type) { // 雪原：冰晶 / 雪堆
                case 0 -> Color.rgb(150, 200, 230);
                case 1 -> Color.rgb(205, 225, 240);
                default -> Color.rgb(170, 190, 210);
            };
            case 2 -> Color.rgb(120, 50, 40);  // 熔岩：熔岩石（统一偏红）
            default -> switch (type) {          // 终焉：虚空尖塔
                case 0 -> Color.rgb(90, 60, 130);
                case 1 -> Color.rgb(70, 50, 100);
                default -> Color.rgb(110, 70, 150);
            };
        };
    }

    private static Color obstacleStroke(int stage) {
        return switch (stage) {
            case 0 -> Color.rgb(20, 40, 20);
            case 1 -> Color.rgb(120, 160, 190);
            case 2 -> Color.rgb(200, 90, 60);
            default -> Color.rgb(60, 40, 90);
        };
    }

    private void drawEntities(World w, float alpha, double vw, double vh) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        double right = left + vw;
        double bottom = top + vh;

        for (int i = 0; i < w.highWater(); i++) {
            if (!w.alive[i]) {
                continue;
            }
            float rx = w.px[i] + (w.x[i] - w.px[i]) * alpha;
            float ry = w.py[i] + (w.y[i] - w.py[i]) * alpha;
            float rr = w.r[i];
            // 视口裁剪：屏幕外一个都不画
            if (rx + rr < left || rx - rr > right || ry + rr < top || ry - rr > bottom) {
                continue;
            }
            double sx = rx - left;
            double sy = ry - top;

            switch (w.kind[i]) {
                case World.KIND_WIZARD -> {
                    Loadout wlo = w.loadout(i);
                    int ck = (wlo != null) ? wlo.classKind : HeroClass.WIZARD;
                    Image hero = Sprites.heroes[ck];
                    if (hero != null) {
                        // 立绘已 ×2 预放大，按自然尺寸 1:1 绘制（清晰像素），底边贴角色位置
                        double hw = hero.getWidth();
                        double hh = hero.getHeight();
                        gc.drawImage(hero, Math.round(sx - hw / 2), Math.round(sy + 4 - hh));
                    }
                }
                case World.KIND_ENEMY -> {
                    gc.drawImage(Sprites.enemies[w.meta[i] % Sprites.enemies.length], sx - 16, sy - 16);
                    drawEnemyStatus(w, i, sx, sy);
                    if (w.hp[i] < w.maxHp[i]) {
                        float f = Math.max(0f, w.hp[i] / w.maxHp[i]);
                        gc.setFill(Color.rgb(30, 12, 16));
                        gc.fillRect(sx - 13, sy - rr - 10, 26, 4);
                        gc.setFill(Color.rgb(235, 70, 90));
                        gc.fillRect(sx - 12, sy - rr - 9, 24 * f, 2);
                    }
                }
                case World.KIND_PROJECTILE -> {
                    SpellDef def = Spells.get(w.meta[i]);
                    Image img = Sprites.bolts[def != null ? def.element : Element.NONE];
                    gc.drawImage(img, sx - img.getWidth() / 2, sy - img.getHeight() / 2);
                }
                case World.KIND_PICKUP -> {
                    if (w.meta[i] == 1) {
                        // 宝箱
                        gc.setFill(Color.rgb(255, 215, 80, 0.95));
                        gc.fillOval(sx - 8, sy - 8, 16, 16);
                        gc.setStroke(Color.rgb(180, 130, 30));
                        gc.setLineWidth(2);
                        gc.strokeOval(sx - 8, sy - 8, 16, 16);
                    } else {
                        gc.drawImage(Sprites.gem, sx - 7, sy - 7);
                    }
                }
                case World.KIND_FX -> drawFx(w, i, left, top);
                case World.KIND_ZONE -> drawZone(w, i, sx, sy);
                default -> { }
            }
        }
    }

    /** 敌人身上的元素状态：一圈元素色的环；被眩晕时环变成断续的白色 */
    private void drawEnemyStatus(World w, int i, double sx, double sy) {
        if (w.elem[i] == Element.NONE) {
            return;
        }
        Color c = elementColor(w.elem[i]);
        double rad = w.r[i] + 4;
        if (w.stunT[i] > 0f) {
            gc.setStroke(Color.rgb(255, 255, 255, 0.85));
        } else {
            gc.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.9));
        }
        gc.setLineWidth(2);
        gc.strokeOval(sx - rad, sy - rad, rad * 2, rad * 2);
    }

    private void drawFx(World w, int i, double left, double top) {
        // t 从 1 淡到 0
        float t = w.speed[i] > 0f ? Math.max(0f, Math.min(1f, w.life[i] / w.speed[i])) : 0f;
        Color c = elementColor(w.elem[i]);

        switch (w.meta[i]) {
            case World.FX_BLAST -> {
                double rad = w.r[i] * (1.2 - 0.2 * t);
                gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.20 * t));
                gc.fillOval(sx(w, i, left) - rad, sy(w, i, top) - rad, rad * 2, rad * 2);
                gc.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.9 * t));
                gc.setLineWidth(3);
                gc.strokeOval(sx(w, i, left) - rad, sy(w, i, top) - rad, rad * 2, rad * 2);
            }
            case World.FX_BEAM -> {
                double x1 = w.x[i] - left;
                double y1 = w.y[i] - top;
                double x2 = w.vx[i] - left;
                double y2 = w.vy[i] - top;
                gc.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.95 * t));
                gc.setLineWidth(4);
                gc.strokeLine(x1, y1, x2, y2);
                gc.setStroke(Color.rgb(255, 255, 255, 0.8 * t));
                gc.setLineWidth(1.5);
                gc.strokeLine(x1, y1, x2, y2);
            }
            case World.FX_ARC -> {
                double cx = w.x[i] - left;
                double cy = w.y[i] - top;
                double rad = w.r[i];
                double half = Math.toDegrees(w.vx[i]);
                double start = -Math.toDegrees(w.dmg[i]) - half;
                gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.28 * t));
                gc.fillArc(cx - rad, cy - rad, rad * 2, rad * 2,
                        start, half * 2, javafx.scene.shape.ArcType.ROUND);
            }
            default -> { }
        }
    }

    private static double sx(World w, int i, double left) {
        return w.x[i] - left;
    }

    private static double sy(World w, int i, double top) {
        return w.y[i] - top;
    }

    private void drawHud(World w, double vw, double vh) {
        gc.setFont(hudFont);
        gc.setFill(Color.rgb(228, 228, 240));
        int reactions = w.reactionCount(Element.R_STEAM)
                + w.reactionCount(Element.R_SUPERCONDUCT)
                + w.reactionCount(Element.R_OVERLOAD)
                + w.reactionCount(Element.R_DETONATE);
        Loadout lo = (w.wizardCount() > 0) ? w.loadout(w.wizard(0)) : null;
        StringBuilder hud = new StringBuilder();
        hud.append(String.format("FPS %3.0f   实体 %4d   敌人 %4d   击杀 %5d   反应 %4d   %.1fs",
                fps, w.liveCount(), w.enemyCount(), w.killCount(), reactions, w.time()));
        if (lo != null) {
            hud.append(String.format("  [%s]   Lv %d   伤害 x%.2f   暴击 %.0f%%",
                    HeroClass.name(lo.classKind), lo.level,
                    lo.stats.dmgMul, lo.stats.critChance * 100));
        }
        gc.fillText(hud.toString(), 16, 26);
        if (lo != null && lo.stats.loneWolfActive) {
            gc.setFill(Color.rgb(255, 200, 100));
            gc.fillText("⚡ 孤注一掷", 16, 46);
        }

        if (lo == null) {
            return;
        }

        // 经验条
        double xpW = 240, xpH = 6;
        double xpX = 16, xpY = 48;
        gc.setFill(Color.rgb(12, 10, 18, 0.7));
        gc.fillRect(xpX - 2, xpY - 2, xpW + 4, xpH + 4);
        gc.setFill(Color.rgb(20, 18, 36));
        gc.fillRect(xpX, xpY, xpW, xpH);
        gc.setFill(Color.rgb(110, 200, 255));
        gc.fillRect(xpX, xpY, xpW * lo.xpRatio(), xpH);

        // 血条 + 护盾
        int id = w.wizard(0);
        double bw = 340;
        double bh = 16;
        double bx = (vw - bw) / 2;
        double by = vh - 42;
        float f = Math.max(0f, w.hp[id] / w.maxHp[id]);
        gc.setFill(Color.rgb(12, 10, 18, 0.85));
        gc.fillRect(bx - 3, by - 3, bw + 6, bh + 6);
        gc.setFill(hpBack);
        gc.fillRect(bx, by, bw, bh);
        gc.setFill(hpFill);
        gc.fillRect(bx, by, bw * f, bh);
        // 护盾条
        if (lo.shield > 0f) {
            float sf = Math.min(1f, lo.shield / w.maxHp[id]);
            gc.setFill(Color.rgb(140, 200, 255, 0.75));
            gc.fillRect(bx, by, bw * sf, bh);
        }

        drawSpellBar(w, vw, vh);
        drawPassiveBar(w, vw, vh);
    }

    /** Boss 血条：顶部居中，显示血量 + 护盾 */
    /** 量文字宽度，用于右对齐。Canvas 的 GraphicsContext 没有直接可用的度量接口 */
    private static double measureWidth(Font font, String text) {
        measurer.setFont(font);
        measurer.setText(text);
        return measurer.getLayoutBounds().getWidth();
    }

    /**
     * 水平居中的文字，可带一层柔和暗影（制造"浮于画面"的层次感）。
     * 一次调用负责设字体；调用方无需再 setFont。
     */
    private static void drawTextSoft(GraphicsContext gc, Font f, double cx, double baseY,
            String text, Color fill, Color shadow) {
        gc.setFont(f);
        double x0 = cx - measureWidth(f, text) / 2;
        if (shadow != null) {
            gc.setFill(shadow);
            gc.fillText(text, x0 + 1, baseY + 1.5);
        }
        gc.setFill(fill);
        gc.fillText(text, x0, baseY);
    }

    /**
     * 圆角小牌（状态徽章）。返回文本串内文字起点 x，便于接着追加角标文字。
     * textFont/textFill 画主文字；suffixFont/suffixFill 可选画右侧小字（如"未开放"）。
     * 主文字以 ctrX 水平居中；若带后缀，则整体视觉居中。
     */
    private static void drawPlate(GraphicsContext gc, double ctrX, double topY, double height,
            String main, Font mainFont, Color mainFill,
            String suffix, Font suffixFont, Color suffixFill,
            Color bg, Color border) {
        double mainW = measureWidth(mainFont, main);
        double suffixW = (suffix == null || suffix.isEmpty()) ? 0 : 8 + measureWidth(suffixFont, suffix);
        double total = mainW + suffixW;
        double x0 = ctrX - total / 2;
        gc.setFill(bg);
        gc.fillRoundRect(x0 - 10, topY, total + 20, height, 9, 9);
        if (border != null) {
            gc.setStroke(border);
            gc.setLineWidth(1.5);
            gc.strokeRoundRect(x0 - 10, topY, total + 20, height, 9, 9);
        }
        double base = topY + height / 2 + mainFont.getSize() * 0.36;
        gc.setFont(mainFont);
        gc.setFill(mainFill);
        gc.fillText(main, x0, base);
        if (suffixW > 0) {
            gc.setFont(suffixFont);
            gc.setFill(suffixFill);
            gc.fillText(suffix, x0 + mainW + 8, topY + height / 2 + suffixFont.getSize() * 0.36);
        }
    }

    private void drawBossBar(World w, double vw) {
        int bid = w.bossId();
        if (bid < 0 || !w.alive[bid]) {
            return;
        }
        double bw = Math.min(700, vw - 80);
        double bh = 14;
        double bx = (vw - bw) / 2;
        double by = 16;
        float f = Math.max(0f, w.hp[bid] / w.maxHp[bid]);
        gc.setFill(Color.rgb(8, 6, 12, 0.8));
        gc.fillRect(bx - 3, by - 3, bw + 6, bh + 6);
        gc.setFill(Color.rgb(60, 20, 30));
        gc.fillRect(bx, by, bw, bh);
        gc.setFill(Color.rgb(220, 60, 80));
        gc.fillRect(bx, by, bw * f, bh);
        if (w.enemyShield[bid] > 0f) {
            float sf = Math.min(1f, w.enemyShield[bid] / w.maxHp[bid]);
            gc.setFill(Color.rgb(150, 200, 255, 0.7));
            gc.fillRect(bx, by, bw * sf, bh);
        }
        gc.setFont(hudFont);
        gc.setFill(Color.rgb(255, 190, 200));
        String title = "BOSS  " + w.bossName();
        int tier = w.bossTier();
        if (tier >= 0) {
            title += "  (" + (tier + 1) + "/" + Balance.BOSS_NAMES.length + ")";
        }
        gc.fillText(title, bx, by - 6);
        // 右侧显示剩余血量数字，玩家能判断还要打多久
        gc.setFill(Color.rgb(240, 220, 230));
        String hpText = String.format("%.0f / %.0f", w.hp[bid], w.maxHp[bid]);
        gc.fillText(hpText, bx + bw - measureWidth(hudFont, hpText), by - 6);
    }

    /** 主动技能条：名字 + 冷却遮罩。3 个槽，见 Loadout.SLOTS */
    private void drawSpellBar(World w, double vw, double vh) {
        Loadout lo = w.loadout(w.wizard(0));
        if (lo == null) {
            return;
        }
        double sw = 104;
        double sh = 34;
        double gap = 8;
        double total = Loadout.SLOTS * sw + (Loadout.SLOTS - 1) * gap;
        double x0 = (vw - total) / 2;
        double y0 = vh - 84;

        gc.setFont(spellFont);
        for (int i = 0; i < Loadout.SLOTS; i++) {
            double bx = x0 + i * (sw + gap);
            SpellDef def = Spells.get(lo.resolvedSpell(i));
            boolean evolved = lo.spells[i] != Spells.NONE
                    && lo.resolvedSpell(i) != lo.spells[i];

            gc.setFill(Color.rgb(12, 10, 18, 0.82));
            gc.fillRect(bx, y0, sw, sh);

            if (def != null && lo.cd[i] > 0f) {
                float f = Math.min(1f, lo.cd[i] / def.cooldown);
                gc.setFill(Color.rgb(80, 100, 175, 0.55));
                gc.fillRect(bx, y0 + sh * (1 - f), sw, sh * f);
            }

            // 进化形态用金色边框
            Color border = evolved ? Color.rgb(240, 180, 80)
                    : (def != null ? elementColor(def.element) : Color.rgb(70, 70, 90));
            gc.setStroke(border);
            gc.setLineWidth(evolved ? 2.5 : 2);
            gc.strokeRect(bx, y0, sw, sh);

            gc.setFill(def != null ? Color.rgb(232, 232, 244) : Color.rgb(110, 110, 130));
            gc.fillText(def != null ? def.name : "空槽", bx + 10, y0 + 22);
        }
    }

    /** 8 个被动槽：每个格子用被动元素的代表色 + 名称 + 层数 */
    private void drawPassiveBar(World w, double vw, double vh) {
        Loadout lo = w.loadout(w.wizard(0));
        if (lo == null) {
            return;
        }
        double pw = 92, ph = 38, gap = 6;
        double total = Loadout.PASSIVE_SLOTS * pw + (Loadout.PASSIVE_SLOTS - 1) * gap;
        double x0 = (vw - total) / 2;
        double y0 = vh - 128;

        gc.setFont(Font.font("Microsoft YaHei", 11));
        for (int i = 0; i < Loadout.PASSIVE_SLOTS; i++) {
            double bx = x0 + i * (pw + gap);
            int pid = lo.passives[i];
            if (pid == Passives.NONE) {
                gc.setFill(Color.rgb(18, 16, 28, 0.65));
                gc.fillRect(bx, y0, pw, ph);
                gc.setStroke(Color.rgb(60, 60, 80));
                gc.setLineWidth(1);
                gc.strokeRect(bx, y0, pw, ph);
                continue;
            }
            PassiveDef d = Passives.get(pid);
            Color rarity = switch (d.rarity) {
                case PassiveDef.RARE -> Color.rgb(80, 160, 240);
                case PassiveDef.EPIC -> Color.rgb(240, 160, 60);
                default -> Color.rgb(110, 110, 130);
            };
            gc.setFill(Color.rgb(24, 20, 38, 0.9));
            gc.fillRect(bx, y0, pw, ph);
            gc.setStroke(rarity);
            gc.setLineWidth(d.kind == PassiveDef.Kind.MUTATION ? 2.5 : 1);
            gc.strokeRect(bx, y0, pw, ph);

            // 名称
            gc.setFill(Color.rgb(232, 232, 244));
            gc.fillText(d.name, bx + 6, y0 + 16);
            // 层数
            if (lo.pstacks[i] > 1) {
                gc.setFill(rarity);
                gc.setFont(Font.font("Consolas", 12));
                gc.fillText("x" + lo.pstacks[i], bx + pw - 24, y0 + 16);
                gc.setFont(Font.font("Microsoft YaHei", 11));
            }
            // 类别标签
            gc.setFill(Color.rgb(150, 150, 170));
            gc.fillText(kindLabel(d.kind), bx + 6, y0 + 32);
        }
    }

    private static String kindLabel(PassiveDef.Kind k) {
        return switch (k) {
            case OUTPUT -> "输出";
            case SURVIVAL -> "生存";
            case RESOURCE -> "资源";
            case ELEMENT -> "元素";
            case MUTATION -> "质变";
            case CONVERTED -> "改造";
        };
    }

    private static Color elementColor(int element) {
        return switch (element) {
            case Element.FIRE -> Color.rgb(255, 140, 50);
            case Element.FROST -> Color.rgb(110, 200, 255);
            case Element.SHOCK -> Color.rgb(190, 230, 120);
            case Element.ARCANE -> Color.rgb(200, 140, 255);
            case Element.POISON -> Color.rgb(120, 220, 90);
            default -> Color.rgb(230, 230, 240);
        };
    }

    private void drawZone(World w, int i, double sx, double sy) {
        // t 从 1 淡到 0
        float t = w.speed[i] > 0f ? Math.max(0f, Math.min(1f, w.life[i] / w.speed[i])) : 0f;
        int sub = w.meta[i];
        if (sub == World.ZONE_WARNING) {
            // Boss 预警圈：红圈 + 半透明填充，给玩家明确的躲避提示
            gc.setStroke(Color.rgb(255, 80, 80, 0.9));
            gc.setLineWidth(3);
            gc.strokeOval(sx - w.r[i], sy - w.r[i], w.r[i] * 2, w.r[i] * 2);
            gc.setFill(Color.rgb(255, 80, 80, 0.18 * t));
            gc.fillOval(sx - w.r[i], sy - w.r[i], w.r[i] * 2, w.r[i] * 2);
            return;
        }
        if (sub == World.ZONE_TRAP) {
            // 钉刺陷阱：紫色尖刺
            gc.setFill(Color.rgb(180, 80, 200, 0.55 * t));
            gc.fillOval(sx - w.r[i], sy - w.r[i], w.r[i] * 2, w.r[i] * 2);
            gc.setStroke(Color.rgb(220, 100, 240, 0.9 * t));
            gc.setLineWidth(1.5);
            gc.strokeOval(sx - w.r[i], sy - w.r[i], w.r[i] * 2, w.r[i] * 2);
            return;
        }
        // 其他区域：元素色的填充
        Color c = elementColor(w.elem[i]);
        double a = (sub == World.ZONE_CHAIN) ? 0.20 : 0.28;
        gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), a * t));
        gc.fillOval(sx - w.r[i], sy - w.r[i], w.r[i] * 2, w.r[i] * 2);
        gc.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.6 * t));
        gc.setLineWidth(1.5);
        gc.strokeOval(sx - w.r[i], sy - w.r[i], w.r[i] * 2, w.r[i] * 2);
    }

    // ------------------------------------------------------------------
    // 准备大厅（LOBBY）
    //
    // 大厅是纯客户端轻量状态：正式开战前玩家在一个小房间里用 WASD 走动，
    // 走上职业祭坛即选中职业，走进「出征」光门按 E 才真正 spawnWizard 开怪。
    // 这里没有 core World，因此也绝不会有刷怪/模拟，渲染与移动判定都只依赖
    // 下方 geom() 产出的一套只读几何（单一事实来源，GameApp 与 Renderer 共用）。
    // ------------------------------------------------------------------

    /** 大厅几何（屏幕像素坐标）。全部字段只读。 */
    public record LobbyGeom(
            double rx0, double ry0, double rx1, double ry1,    // 走动能到达的整块地面范围
            double minX, double minY, double maxX, double maxY, // 化身圆心可走边界
            double[][] altarC, double altarR,                  // 四个角色站位圆心（脚底）+ 选中判定半径
            double gx, double gy, double gR,                   // 出征光门圆心 + 半径
            double avatarR) {                                  // 化身半径（判定用）

        /** 国王起步点：站在王座台阶正前方、四人一字排开的纵深上方 */
        public double[] kingSpawn() {
            double sx = (minX + maxX) / 2;
            double sy = minY + (altarC[0][1] - minY) * 0.50;
            return new double[] { sx, sy };
        }
    }

    /** 大厅四人「脚底」占屏高的比例（0=顶，1=底）。整体上下微调时改这里。 */
    private static final double LOBBY_FEET_FRAC = 0.56;
    /**
     * 大厅立绘已由 Sprites 按整数倍预放大（内容 1:1 绘制，像素清晰）。
     * 各人显示高度 = 其放大后的自然高度（约 42~50px）。此值仅在美术资源缺失时
     * 当作排版占位高度。
     */
    private static final double LOBBY_HERO_FALLBACK_H = 44.0;
    /** 走近站位即选中的判定半径（化身圆心到角色脚底的距离阈值） */
    private static final double LOBBY_STATION_RADIUS = 46.0;

    // ------------------------------------------------------------------
    // 右侧角色细节卡：文案与数值。下标=职业 id（1..4 = 巫师/战士/弓箭手/召唤师）。
    // ------------------------------------------------------------------
    private static final String[] CARD_NAME = { "", "巫师", "战士", "弓箭手", "召唤师" };
    private static final String[] CARD_EN = { "", "WIZARD", "VANGUARD", "ARCHER", "SUMMONER" };
    private static final String[] CARD_ROLE = {
            "", "远程 · 法系爆发", "近战 · 范围挥砍", "远程 · 穿透点射", "辅助 · 召唤协战" };
    private static final String[][] CARD_FEATS = {
            {},
            { "法术伤害 +10%", "每 30 秒免费重抽", "远程弹幕 · 拉扯走位" },
            { "生命 140 · 能扛能打", "受伤减免 15% · 击杀回血", "近战弧形 · 贴身压制" },
            { "移速最快 · 游走风筝", "暴击 +10% · 箭箭穿心", "身板最脆 · 注意走位" },
            { "召唤伙伴分担火力", "增益 / 控场 · 计划中", "暂未开放 · 敬请期待" } };
    /** 数值条：0..1 的归一值（召唤师未开放，展示占位） */
    private static final double[] CARD_LIFE = { 0, 100 / 150.0, 140 / 150.0, 85 / 150.0, 0.55 };
    private static final double[] CARD_SPEED = { 0, 195 / 235.0, 180 / 235.0, 205 / 235.0, 0.55 };

    /**
     * 计算大厅布局：随窗口尺寸缩放。GameApp 移动/判定与绘制必须共用它。
     * 四位角色均匀一字排开站在王座前（四人中间不留特殊大空位），化身可以满场走动，
     * 出征光门嵌在画面下缘正中。altarC 存的是每人「脚底」坐标，视觉上立绘就立在那里。
     */
    public static LobbyGeom geom(double vw, double vh) {
        double avatarR = 15;
        double minX = 30, maxX = vw - 30;
        double minY = 44, maxY = vh - 18;
        double rx0 = minX, ry0 = minY, rx1 = maxX, ry1 = maxY;

        // 四人均匀一字排开：占位等分整排，正中不预留大空位
        int n = LobbyClass.CLASSES.length;                     // 4
        double altarR = LOBBY_STATION_RADIUS;                  // 靠近脚底即选中
        double feetY = vh * LOBBY_FEET_FRAC;
        double[][] altarC = new double[n][2];
        for (int i = 0; i < n; i++) {
            double f = (i + 0.5) / n;                          // 12.5% / 37.5% / 62.5% / 87.5%
            altarC[i][0] = minX + (maxX - minX) * f;
            altarC[i][1] = feetY;
        }

        // 出征光门：画面下缘正中的一道传送门
        double gR = 52;
        double gx = (minX + maxX) / 2;
        double gy = vh - 84;

        return new LobbyGeom(rx0, ry0, rx1, ry1,
                minX, minY, maxX, maxY, altarC, altarR, gx, gy, gR, avatarR);
    }

    /** 职业代表色：巫师 紫 / 战士 橙红 / 弓箭手 绿 / 召唤师 冰蓝 */
    private static Color classAccent(int cls) {
        return switch (cls) {
            case HeroClass.WARRIOR   -> Color.rgb(255, 140, 90);
            case HeroClass.ARCHER    -> Color.rgb(140, 230, 150);
            case LobbyClass.SUMMONER -> Color.rgb(150, 235, 255);
            default                  -> Color.rgb(200, 140, 255);   // 巫师
        };
    }

    /**
     * 画准备大厅（王座厅）。chosen=0 表示还未选职业，化身用中性剪影。
     * 底图是美术提供的皇宫王座大厅背景（整屏铺满裁切）；四个角色站在同一
     * 条脚底线上均匀一字排开。px/py 是化身圆心，与 geom 坐标同参考系。
     */
    public void drawLobby(LobbyGeom g, double px, double py, int chosen, double t,
            int cardClass, double reveal) {
        double vw = canvas.getWidth();
        double vh = canvas.getHeight();
        double pulse = 0.5 + 0.5 * Math.sin(t * 2.4);

        // ---- 背景：王座大厅整图，等比放大铺满（裁掉多出来的边） ----
        Image bg = Sprites.lobbyBg;
        if (bg != null) {
            double s = Math.max(vw / bg.getWidth(), vh / bg.getHeight());
            double dw = bg.getWidth() * s;
            double dh = bg.getHeight() * s;
            gc.drawImage(bg, (vw - dw) / 2, (vh - dh) / 2, dw, dh);
        } else {
            // 缺图兜底：仍用深紫渐变，保证可跑
            gc.setFill(new LinearGradient(0, 0, 0, vh, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.rgb(12, 10, 22)),
                    new Stop(0.5, Color.rgb(24, 21, 38)),
                    new Stop(1, Color.rgb(42, 37, 62))));
            gc.fillRect(0, 0, vw, vh);
        }
        // 底部压一层暗色，让名字与光门更清晰，不遮王座主体
        gc.setFill(new LinearGradient(0, vh * 0.62, 0, vh, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(0, 0, 0, 0)),
                new Stop(1, Color.rgb(0, 0, 0, 0.45))));
        gc.fillRect(0, 0, vw, vh);

        // ---- 顶部标题区：柔和渐变幕布 + 「大标题 → 引导小字」两层主次 ----
        gc.setFill(new LinearGradient(0, 0, 0, 96, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(5, 4, 13, 0.62)),
                new Stop(1, Color.rgb(5, 4, 13, 0.0))));
        gc.fillRect(0, 0, vw, 96);
        Font titleFont = Font.font("Microsoft YaHei", FontWeight.BOLD, 27);
        drawTextSoft(gc, titleFont, vw / 2, 38, "奥 术 旅 团",
                Color.rgb(255, 227, 168), Color.rgb(18, 10, 4));
        Font hf = Font.font("Microsoft YaHei", 13.5);
        String hint = "WASD 移动 · 靠近勇者空格确认同行 · 走进光门按 E 出征";
        double hw = measureWidth(hf, hint);
        gc.setFont(hf);
        gc.setFill(Color.rgb(228, 225, 240, 0.92));
        gc.fillText(hint, (vw - hw) / 2, 63);
        gc.setFill(Color.rgb(255, 227, 168, 0.65));
        gc.fillRoundRect(vw / 2 - 24, 73, 48, 2, 1, 1);

        // ---- 四个角色：均匀一字排开，站在同一脚底线上 ----
        // 立绘已由 Sprites 按整数倍预放大(×2, 最近邻)，这里按自然尺寸 1:1 绘制、
        // 坐标取整到像素，JavaFX 不会做任何缩放 => 像素风清晰不发糊。
        int[] clsArr = LobbyClass.CLASSES;
        for (int i = 0; i < clsArr.length; i++) {
            int ck = clsArr[i];
            boolean sel = chosen == ck;
            double cx = g.altarC()[i][0];
            double feetY = g.altarC()[i][1];
            Color ac = classAccent(ck);
            Image art = Sprites.heroes[ck];
            double h = (art != null) ? art.getHeight() : LOBBY_HERO_FALLBACK_H;
            boolean joined = chosen != 0 && chosen == ck;   // 已空格确认、随国王同行的勇者

            // 只给"已随行"者头顶打一道聚光
            if (sel) {
                drawSpotlight(gc, cx, feetY, h * 0.42, 0.9 + 0.1 * pulse);
            }

            if (joined) {
                // 已随行：该位置不再是 NPC，只留发光基座（控制对象此刻正跟在国王处）
                gc.setStroke(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.55 + 0.2 * pulse));
                gc.setLineWidth(2);
                gc.strokeOval(cx - h * 0.30, feetY - h * 0.03, h * 0.60, h * 0.11);
                gc.setFill(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.18 + 0.12 * pulse));
                gc.fillOval(cx - h * 0.36, feetY - h * 0.05, h * 0.72, h * 0.14);
            } else if (art != null) {
                // 待招募的 NPC 勇者：脚底阴影 + 1:1 立绘贴脚底线
                gc.setFill(Color.rgb(0, 0, 0, 0.30));
                gc.fillOval(cx - h * 0.24, feetY - 4, h * 0.48, 8);
                double w = art.getWidth();
                gc.drawImage(art, Math.round(cx - w / 2), Math.round(feetY - h));
            }

            // ---- 名牌：按状态分层（未选暗底 / 选中职业色描边+✓ / 召唤师琥珀"未开放"） ----
            boolean locked = ck == LobbyClass.SUMMONER;
            String nm = LobbyClass.name(ck);
            String plateMain = (sel && !locked) ? "✓ " + nm : nm;
            String plateSuffix = locked ? "未开放" : "";
            Font pfM = Font.font("Microsoft YaHei", sel ? FontWeight.BOLD : FontWeight.NORMAL,
                    sel ? 15 : 14);
            Font pfS = Font.font("Microsoft YaHei", 11);
            double plateH = sel ? 28 : 24;
            double plateTop = feetY + 8;
            Color plateBg;
            Color plateEdge = null;
            Color plateMainCol;
            if (sel && !locked) {
                plateBg = Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.26);
                plateEdge = Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.95);
                plateMainCol = Color.WHITE;
            } else if (sel) {
                plateBg = Color.rgb(96, 60, 20, 0.75);
                plateEdge = Color.rgb(255, 198, 116, 0.95);
                plateMainCol = Color.rgb(255, 240, 210);
            } else if (locked) {
                plateBg = Color.rgb(18, 12, 5, 0.55);
                plateMainCol = Color.rgb(235, 195, 130);
            } else {
                plateBg = Color.rgb(6, 5, 14, 0.55);
                plateMainCol = Color.rgb(204, 200, 218);
            }
            drawPlate(gc, cx, plateTop, plateH, plateMain, pfM, plateMainCol,
                    plateSuffix, pfS, Color.rgb(255, 200, 122), plateBg, plateEdge);
        }

        // ---- 出征光门：画面下缘正中的传送门 ----
        boolean inside = (px - g.gx()) * (px - g.gx()) + (py - g.gy()) * (py - g.gy())
                <= (g.gR() + g.avatarR()) * (g.gR() + g.avatarR());
        double gr = g.gR();
        // 站在门内的玩家，才能看到出发提示；未选/选了召唤师都被挡住
        boolean canGo = chosen != 0 && chosen != LobbyClass.SUMMONER;
        gc.setFill(Color.color(0.22, 0.70, 0.85,
                inside ? (canGo ? 0.30 + 0.12 * pulse : 0.18 + 0.06 * pulse) : 0.06 + 0.05 * pulse));
        gc.fillOval(g.gx() - gr - 18, g.gy() - gr - 18, (gr + 18) * 2, (gr + 18) * 2);
        gc.setFill(Color.rgb(10, 24, 36));
        gc.fillOval(g.gx() - gr, g.gy() - gr, gr * 2, gr * 2);
        gc.setStroke(canGo ? Color.rgb(120, 235, 255) : Color.rgb(160, 160, 172));
        gc.setLineWidth(inside ? 5 : 3);
        gc.strokeOval(g.gx() - gr, g.gy() - gr, gr * 2, gr * 2);
        gc.setStroke(Color.color(0.7, 0.92, 1.0, 0.4));
        gc.setLineWidth(1);
        gc.strokeOval(g.gx() - gr + 10, g.gy() - gr + 10, (gr - 10) * 2, (gr - 10) * 2);
        Font gf = Font.font("Microsoft YaHei", FontWeight.BOLD, 20);
        String go = "出 征";
        double goW = measureWidth(gf, go);
        gc.setFont(gf);
        gc.setFill(Color.rgb(6, 8, 16, 0.65));
        gc.fillText(go, g.gx() - goW / 2 + 1, g.gy() + 9);
        gc.setFill(canGo ? Color.rgb(236, 253, 255) : Color.rgb(210, 210, 220));
        gc.fillText(go, g.gx() - goW / 2, g.gy() + 8);
        // 站在光门内时的提示（画在光门上方，带底卡，主次分明）
        if (inside) {
            Font pf = Font.font("Microsoft YaHei", FontWeight.BOLD, 15);
            String msg;
            Color mc;
            if (chosen == 0) {
                msg = "请先靠近一位勇者，空格确认同行";
                mc = Color.rgb(255, 182, 110);
            } else if (chosen == LobbyClass.SUMMONER) {
                msg = "召唤师尚未开放 · 请另选一位";
                mc = Color.rgb(255, 150, 120);
            } else {
                msg = "按 E（或空格）出发";
                mc = Color.rgb(120, 240, 200);
            }
            double pw = measureWidth(pf, msg);
            double pTop = g.gy() - gr - 50;
            double pH = 30;
            gc.setFill(Color.rgb(6, 6, 16, 0.78));
            gc.fillRoundRect(g.gx() - pw / 2 - 15, pTop, pw + 30, pH, 9, 9);
            gc.setStroke(Color.color(mc.getRed(), mc.getGreen(), mc.getBlue(), 0.8));
            gc.setLineWidth(1.5);
            gc.strokeRoundRect(g.gx() - pw / 2 - 15, pTop, pw + 30, pH, 9, 9);
            gc.setFont(pf);
            gc.setFill(mc);
            gc.fillText(msg, g.gx() - pw / 2, pTop + pH / 2 + pf.getSize() * 0.36);
        }

        // ---- 化身：初始为国王(heroes[0])，空格确认后替换为随行的勇者精灵 ----
        // 统一按自然尺寸 1:1、脚底贴化身中心下缘绘制
        gc.setFill(Color.rgb(0, 0, 0, 0.35));
        gc.fillOval(px - 20, py - 3, 40, 13);
        Image av = Sprites.heroes[chosen];
        if (av != null) {
            double aw = av.getWidth();
            double ah = av.getHeight();
            gc.drawImage(av, Math.round(px - aw / 2), Math.round(py - 4 - ah));
        }
        if (chosen != 0) {
            // 化身头顶的已选状态小卡（和名牌同一套"徽章"语言）
            Color lac = classAccent(chosen);
            boolean locked2 = chosen == LobbyClass.SUMMONER;
            String label = locked2 ? "已选 · 召唤师" : "已选 · " + LobbyClass.name(chosen);
            Font cf = Font.font("Microsoft YaHei", FontWeight.BOLD, 13);
            double cw = measureWidth(cf, label);
            double cTop = py - 60;
            double cH = 24;
            gc.setFill(locked2 ? Color.rgb(30, 20, 8, 0.8) : Color.rgb(6, 6, 16, 0.75));
            gc.fillRoundRect(px - cw / 2 - 10, cTop, cw + 20, cH, 8, 8);
            gc.setStroke(Color.color(lac.getRed(), lac.getGreen(), lac.getBlue(),
                    locked2 ? 0.75 : 0.9));
            gc.setLineWidth(1.5);
            gc.strokeRoundRect(px - cw / 2 - 10, cTop, cw + 20, cH, 8, 8);
            gc.setFont(cf);
            gc.setFill(locked2 ? Color.rgb(255, 214, 150) : lac);
            gc.fillText(label, px - cw / 2, cTop + cH / 2 + cf.getSize() * 0.36);
        }

        // ---- 右侧：站在角色面前时的细节立绘（透明，离开对应区域即滑出缩回） ----
        if (cardClass != 0 && reveal > 0.001) {
            drawClassCard(vw, vh, cardClass, reveal);
        }
        // ---- 屏幕下方的招募确认对话条：与右侧卡同步出现，已随行的不重复提问 ----
        if (cardClass != 0 && cardClass != chosen && reveal > 0.03) {
            drawRecruitBar(vw, vh, cardClass, chosen, reveal);
        }
    }

    /**
     * 屏幕下方的招募确认文字栏。target = 正在面对、待招募的勇者；chosen = 当前随行者
     * （0 表示还控制国王）。锁定勇者只显示"尚未准备"，可空格改选/确认的才给提示。
     * reveal 复用右侧立绘卡的进度，进出同步淡入淡出。
     */
    private void drawRecruitBar(double vw, double vh, int target, int chosen, double reveal) {
        double r = Math.max(0, Math.min(1, reveal));
        if (r < 0.03) {
            return;
        }
        double fade = Math.pow(r, 1.6);
        Color ac = classAccent(target);
        boolean locked = target == LobbyClass.SUMMONER;

        // 条占屏幕左下部，右侧避开立绘文字面板
        double barH = 92;
        double cardW = Math.min(430, vw * 0.34);
        double x0 = 18;
        double right = vw - cardW - 36;
        double barW = Math.max(240, right - x0);
        double y = vh - barH - 16;

        gc.setFill(Color.rgb(8, 7, 18, 0.82 * fade));
        gc.fillRoundRect(x0, y, barW, barH, 14, 14);
        gc.setStroke(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.55 * fade));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(x0, y, barW, barH, 14, 14);
        gc.setFill(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.95 * fade));
        gc.fillRoundRect(x0 + 10, y + 14, 5, barH - 28, 2, 2);

        Font qF = Font.font("Microsoft YaHei", FontWeight.BOLD, 16);
        double baseY = y + barH / 2 + qF.getSize() * 0.36 - 1;
        double tx = x0 + 28;
        String prefix = locked ? "" : "「" + LobbyClass.name(target) + "」";
        String q = locked ? "这位勇者尚未做好准备，无法随行…"
                : (chosen != 0 ? "确定改选为这位勇者出征吗？" : "确定选择这位勇者出征吗？");
        gc.setFont(qF);
        if (!prefix.isEmpty()) {
            gc.setFill(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.98 * fade));
            gc.fillText(prefix, tx, baseY);
            tx += measureWidth(qF, prefix) + 4;
        }
        if (locked) {
            gc.setFill(Color.rgb(255, 205, 140, 0.97 * fade));
        } else {
            gc.setFill(Color.rgb(255, 255, 255, 0.97 * fade));
        }
        gc.fillText(q, tx, baseY);

        // 右侧按键提示
        String hint = locked ? "尚未开放" : "空格 确认";
        Font hF = Font.font("Microsoft YaHei", FontWeight.BOLD, 14);
        double hW = measureWidth(hF, hint);
        double hx = x0 + barW - hW - 22;
        gc.setFont(hF);
        gc.setFill(Color.color(locked ? 1 : 0.66, locked ? 0.80 : 0.94, locked ? 0.55 : 0.90, 0.95 * fade));
        gc.fillText(hint, hx, baseY);
    }

    /**
     * 站在角色面前时从屏右滑入的细节立绘：不画任何底板/白底/描边，只有立绘与文字，
     * 任其直接叠在王座厅场景上。reveal 0..1 = 展示进度（1 完全到位、0 缩回屏外），
     * 进出共用同一根 ease-out 曲线，因此离开区域时立绘会原路滑出。
     */
    private void drawClassCard(double vw, double vh, int ck, double reveal) {
        Image art = Sprites.heroPortraits[ck];
        if (art == null) {
            return;
        }
        double cardW = Math.min(430, vw * 0.34);
        double targetX = vw - cardW - 12;
        double r = Math.max(0, Math.min(1, reveal));
        double p = 1 - Math.pow(1 - r, 3);          // ease-out cubic
        double fromX = vw + 40;
        double x = fromX + (targetX - fromX) * p;
        Color ac = classAccent(ck);
        boolean locked = ck == LobbyClass.SUMMONER;

        double pad = 16;
        double innerW = cardW - pad * 2;
        double infoH = 244;
        double infoTop = vh - 18 - infoH;
        double artTop = 96;
        double artAvailH = infoTop - artTop - 24;

        // 立绘：透明背景图，contain 等比，底边贴近文字区上沿，避免整体悬空
        double sc = Math.min(innerW / art.getWidth(), artAvailH / art.getHeight());
        double aw = art.getWidth() * sc;
        double ah = art.getHeight() * sc;
        gc.drawImage(art, x + (cardW - aw) / 2, artTop + (artAvailH - ah) / 2, aw, ah);

        // 锁定角标（召唤师）：一行琥珀小字，无底色
        if (locked) {
            shadowLeft(x + pad, artTop + 16,
                    Font.font("Microsoft YaHei", FontWeight.BOLD, 12),
                    "未开放 · 敬请期待", Color.rgb(255, 206, 132));
        }

        // ---- 文字信息底：职业色的半透明面板，只垫文字区（立绘保持无底悬浮不动） ----
        double panelX = x + 8;
        double panelTop = infoTop - 6;
        double panelW = cardW - 16;
        double panelBottom = vh - 14;
        gc.setFill(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.20));
        gc.fillRoundRect(panelX, panelTop, panelW, panelBottom - panelTop, 12, 12);
        gc.setStroke(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.45));
        gc.setLineWidth(1);
        gc.strokeRoundRect(panelX, panelTop, panelW, panelBottom - panelTop, 12, 12);

        // ---- 信息区（文字自带投影，保证在面板与明亮背景上仍清晰） ----
        double iy = infoTop + 8;
        double tx = x + pad;
        shadowLeft(tx, iy + 13, Font.font("Microsoft YaHei", 11),
                CARD_EN[ck], Color.rgb(200, 198, 224));
        shadowLeft(tx, iy + 47, Font.font("Microsoft YaHei", FontWeight.BOLD, 26),
                CARD_NAME[ck], Color.WHITE);
        // 定位：左竖色条 + 文本
        double roleY = iy + 78;
        gc.setFill(ac);
        gc.fillRoundRect(tx, roleY - 13, 4, 17, 2, 2);
        shadowLeft(tx + 11, roleY, Font.font("Microsoft YaHei", 14.5),
                CARD_ROLE[ck], Color.rgb(244, 242, 252));
        // 特征：逐行菱形点
        double fy = iy + 126;
        Font featF = Font.font("Microsoft YaHei", 13);
        for (String f : CARD_FEATS[ck]) {
            gc.setFill(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.95));
            gc.fillRoundRect(tx + 5, fy - 9.5, 5, 5, 1.2, 1.2);
            shadowLeft(tx + 21, fy, featF, f, Color.rgb(228, 226, 242));
            fy += 21;
        }
        if (locked) {
            shadowLeft(tx, fy + 12, Font.font("Microsoft YaHei", 12.5),
                    "● 尚未开放 · 敬请期待", Color.rgb(255, 214, 160));
        } else {
            drawCardBar(tx, fy + 8, "生命", Math.round(CARD_LIFE[ck] * 150) + "",
                    CARD_LIFE[ck], ac);
            drawCardBar(tx, fy + 32, "移速", Math.round(CARD_SPEED[ck] * 235) + "",
                    CARD_SPEED[ck], Color.rgb(120, 220, 255));
        }
    }

    /** 无底数值条：标签 + 数值（带投影），进度只画彩色圆角条 + 细描边，不留白槽 */
    private void drawCardBar(double x, double y, String label, String value,
            double frac, Color color) {
        shadowLeft(x, y + 6, Font.font("Microsoft YaHei", 12.5), label,
                Color.rgb(240, 238, 250));
        shadowLeft(x + 62, y + 6, Font.font("Consolas", 12), value, Color.WHITE);
        double bw = 150;
        double bx = x + 134;
        double bh = 6;
        double f = Math.max(0, Math.min(1, frac));
        gc.setStroke(Color.color(0, 0, 0, 0.5));
        gc.setLineWidth(1);
        gc.strokeRoundRect(bx, y, bw, bh, 3, 3);
        if (f > 0.01) {
            gc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.95));
            gc.fillRoundRect(bx, y, bw * f, bh, 3, 3);
        }
    }

    /** 左对齐文字 + 细投影：不垫底色的前提下保证可读性 */
    private void shadowLeft(double x, double baseY, Font f, String s, Color fill) {
        gc.setFont(f);
        gc.setFill(Color.rgb(0, 0, 0, 0.55));
        gc.fillText(s, x + 1, baseY + 1);
        gc.setFill(fill);
        gc.fillText(s, x, baseY);
    }

    /**
     * 从顶梁高处照向某座祭坛的聚光：顶部窄、落点宽，光在落点最亮。
     * strength≈0..1；只对当前选中的职业调用，其余祭坛无光束。
     */
    private static void drawSpotlight(GraphicsContext g, double cx, double padY, double padR, double strength) {
        double topY = 4;
        double apex = 8;
        // 外圈柔光
        g.setFill(new LinearGradient(cx, topY, cx, padY, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 246, 208, 0)),
                new Stop(1, Color.rgb(255, 244, 205, 0.14 * strength))));
        g.fillPolygon(new double[]{ cx - apex, cx + apex, cx + padR * 1.7, cx - padR * 1.7 },
                new double[]{ topY, topY, padY, padY }, 4);
        // 内束更亮
        g.setFill(new LinearGradient(cx, topY, cx, padY, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 250, 228, 0)),
                new Stop(0.65, Color.rgb(255, 250, 228, 0.10 * strength)),
                new Stop(1, Color.rgb(255, 250, 228, 0.30 * strength))));
        g.fillPolygon(new double[]{ cx - apex * 0.5, cx + apex * 0.5, cx + padR, cx - padR },
                new double[]{ topY, topY, padY, padY }, 4);
        // 最亮的核心窄带
        g.setFill(Color.rgb(255, 252, 235, 0.20 * strength));
        g.fillPolygon(new double[]{ cx - apex * 0.2, cx + apex * 0.2, cx + padR * 0.34, cx - padR * 0.34 },
                new double[]{ topY, topY, padY, padY }, 4);
    }

    /**
     * 三选一面板。由 GameApp 在 pendingChoices>0 时叠加在最上层。
     * 命中区域是 3 个等宽矩形加 1 个重抽按钮。
     */
    public void drawUpgradePanel(Upgrades.Choice[] choices, int rerolls, double vw, double vh) {
        // 半透明黑底
        gc.setFill(Color.rgb(8, 6, 14, 0.78));
        gc.fillRect(0, 0, vw, vh);

        // 标题
        gc.setFont(Font.font("Microsoft YaHei", 22));
        gc.setFill(Color.rgb(245, 245, 250));
        gc.fillText("选择升级", vw / 2 - 56, vh * 0.18);

        gc.setFont(Font.font("Consolas", 13));
        gc.setFill(Color.rgb(180, 180, 200));
        gc.fillText("点击选项即可选择    R 键重抽（剩余 " + rerolls + "）", vw / 2 - 130, vh * 0.18 + 26);

        // 3 个选项卡片
        double cardW = Math.min(280, (vw - 80) / 3);
        double cardH = 220;
        double gap = 20;
        double total = choices.length * cardW + (choices.length - 1) * gap;
        double x0 = (vw - total) / 2;
        double y0 = vh * 0.32;
        gc.setFont(Font.font("Microsoft YaHei", 15));
        for (int i = 0; i < choices.length; i++) {
            Upgrades.Choice c = choices[i];
            if (c == null) {
                continue;
            }
            double bx = x0 + i * (cardW + gap);
            // 背景
            gc.setFill(Color.rgb(24, 20, 38, 0.95));
            gc.fillRoundRect(bx, y0, cardW, cardH, 10, 10);
            // 边框：按稀有度
            Color rarity = switch (c.rarity) {
                case PassiveDef.RARE -> Color.rgb(80, 160, 240);
                case PassiveDef.EPIC -> Color.rgb(240, 160, 60);
                default -> Color.rgb(150, 150, 170);
            };
            gc.setStroke(rarity);
            gc.setLineWidth(c.evolution ? 3 : 1.5);
            gc.strokeRoundRect(bx, y0, cardW, cardH, 10, 10);

            // 类别标签（主动/被动/补给）
            gc.setFill(rarity);
            gc.setFont(Font.font("Consolas", 12));
            gc.fillText(c.label + " · " + c.rarityName, bx + 14, y0 + 22);

            // 名称
            gc.setFill(c.evolution ? Color.rgb(255, 220, 130) : Color.rgb(245, 245, 250));
            gc.setFont(Font.font("Microsoft YaHei", 17));
            gc.fillText(c.name, bx + 14, y0 + 50);

            // 描述
            gc.setFill(Color.rgb(200, 200, 220));
            gc.setFont(Font.font("Microsoft YaHei", 12));
            gc.fillText(c.desc, bx + 14, y0 + 76);

            // 协同提示
            if (!c.synergy.isEmpty()) {
                gc.setFill(c.evolution ? Color.rgb(255, 220, 130) : Color.rgb(150, 230, 180));
                gc.setFont(Font.font("Microsoft YaHei", 12));
                gc.fillText("⚡ " + c.synergy, bx + 14, y0 + 104);
            }

            // 选中提示（覆盖整张卡片的下半部）
            gc.setFill(Color.rgb(255, 255, 255, 0.10));
            gc.fillRect(bx + 10, y0 + cardH - 40, cardW - 20, 30);
            gc.setFill(Color.rgb(220, 220, 235));
            gc.setFont(Font.font("Consolas", 12));
            gc.fillText("点击选择", bx + cardW / 2 - 24, y0 + cardH - 20);
        }
    }
}
