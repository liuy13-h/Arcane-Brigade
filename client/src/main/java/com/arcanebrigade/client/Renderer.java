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
import javafx.scene.text.Font;
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
                        gc.drawImage(hero, sx - 22, sy - 30);
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

    /**
     * 选职业界面。由 GameApp 在开局前叠加在最上层。
     * 三张卡片展示各职业的基础属性、特性、起手武器与技能池预览。
     * 卡片矩形必须与 GameApp.classCardRects 完全一致，否则点不到。
     */
    public void drawClassSelect(double vw, double vh, double[][] rects, double mx, double my) {
        gc.setFill(Color.rgb(14, 12, 22));
        gc.fillRect(0, 0, vw, vh);

        gc.setFont(Font.font("Microsoft YaHei", 30));
        gc.setFill(Color.rgb(245, 245, 250));
        gc.fillText("选择你的职业", vw / 2 - 110, vh * 0.16);

        gc.setFont(Font.font("Consolas", 13));
        gc.setFill(Color.rgb(180, 180, 200));
        gc.fillText("点击卡片或按 1 / 2 / 3 选择", vw / 2 - 95, vh * 0.16 + 26);

        Color[] accent = {
                Color.rgb(200, 140, 255),  // 巫师 紫
                Color.rgb(255, 140, 90),   // 战士 橙红
                Color.rgb(140, 230, 150)   // 弓箭手 绿
        };
        int[] classes = { HeroClass.WIZARD, HeroClass.WARRIOR, HeroClass.ARCHER };

        gc.setFont(Font.font("Microsoft YaHei", 14));
        for (int i = 0; i < 3; i++) {
            double bx = rects[i][0], by = rects[i][1], bw = rects[i][2], bh = rects[i][3];
            int ck = classes[i];
            boolean hover = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;

            gc.setFill(Color.rgb(22, 20, 34, 0.96));
            gc.fillRoundRect(bx, by, bw, bh, 12, 12);
            gc.setStroke(hover ? accent[i] : Color.rgb(90, 90, 110));
            gc.setLineWidth(hover ? 3 : 1.5);
            gc.strokeRoundRect(bx, by, bw, bh, 12, 12);

            // 职业形象预览（卡片右上角）
            Image heroImg = Sprites.heroes[ck];
            if (heroImg != null) {
                double hw = 56, hh = 56;
                gc.drawImage(heroImg, bx + bw - hw - 12, by + 12, hw, hh);
            }

            double x = bx + 18;
            double y = by + 34;

            // 职业名
            gc.setFont(Font.font("Microsoft YaHei", 24));
            gc.setFill(accent[i]);
            gc.fillText(HeroClass.name(ck), x, y);
            y += 36;

            // 基础属性
            gc.setFont(Font.font("Consolas", 14));
            gc.setFill(Color.rgb(220, 220, 235));
            gc.fillText(String.format("生命 %3.0f    移速 %3.0f",
                    HeroClass.baseHp(ck), HeroClass.baseSpeed(ck)), x, y);
            y += 26;

            // 特性
            gc.setFont(Font.font("Microsoft YaHei", 12.5));
            gc.setFill(Color.rgb(255, 220, 150));
            gc.fillText("特性：" + HeroClass.trait(ck), x, y);
            y += 24;

            // 起手武器
            int sid = HeroClass.startSpell(ck);
            SpellDef sd = Spells.get(sid);
            gc.setFill(Color.rgb(200, 200, 220));
            gc.fillText("起手：" + (sd != null ? sd.name : "?"), x, y);
            y += 26;

            // 技能池预览
            gc.setFill(Color.rgb(170, 170, 195));
            gc.fillText("技能池：", x, y);
            y += 20;
            int[] pool = HeroClass.pool(ck);
            gc.setFont(Font.font("Microsoft YaHei", 12));
            for (int s = 0; s < pool.length; s++) {
                SpellDef psd = Spells.get(pool[s]);
                if (psd != null) {
                    gc.fillText("· " + psd.name, x, y);
                    y += 18;
                }
            }
        }
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
