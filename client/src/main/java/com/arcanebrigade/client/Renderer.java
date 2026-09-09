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

        // 沙漠遗迹模板：先铺外部地面，再把沙地裁进城墙内侧，最后压上城墙
        Color[] pal = STAGE_PAL[w.stage()];
        gc.setFill(pal[0]);
        gc.fillRect(0, 0, vw, vh);
        drawGround(vw, vh, pal);
        drawBoundary(vw, vh, pal);
        drawObstacles(w, alpha, vw, vh);
        drawEntities(w, alpha, vw, vh);
        drawHud(w, vw, vh);
        drawBossBar(w, vw);
    }

    /**
     * 模板风格：沙漠遗迹（用户提供的参考图）。四个阶段共用同一套结构——
     * 沙地 + 龟裂 + 碎石 + 枯灌 + 环形城墙 + 中央石井，只做色调偏移，
     * 保证整局都是同一张模板图的观感，而不是四种不相干的地图。
     *
     * 索引：0 外部地面, 1 沙地A, 2 沙地B, 3 裂纹/碎石, 4 城墙主体, 5 城墙亮面,
     *      6 城墙暗面, 7 枯灌, 8 岩石主体, 9 岩石亮面, 10 岩石暗面
     */
    private static final Color[][] STAGE_PAL = {
        // 0 荒漠遗迹（参考图原色）
        desertPal(0x60422A, 0xCEA876, 0xC49C6A, 0xAC865A, 0xB08458, 0xD0AC82, 0x6E4E34,
                0x6A7A3E, 0x968470, 0xB8A88E, 0x5E4838),
        // 1 黄昏荒漠
        desertPal(0x583822, 0xC89668, 0xBC8A5E, 0xA0744C, 0xA8764E, 0xC89A6C, 0x64442C,
                0x7A6E38, 0x8E7460, 0xB0967C, 0x54402E),
        // 2 灼烬裂谷
        desertPal(0x34221C, 0x8A6450, 0x7E5A48, 0x664638, 0x6E4C40, 0x8E664E, 0x402A24,
                0x5A4A2E, 0x6E5A50, 0x8E7666, 0x3C2C26),
        // 3 终焉废土
        desertPal(0x2C2636, 0x8A7C92, 0x7E7286, 0x665C74, 0x6E6480, 0x8E849E, 0x403850,
                0x4E5A46, 0x6A6278, 0x8A8296, 0x383244),
    };

    private static Color[] desertPal(int outer, int sandA, int sandB, int crack, int wall,
                                     int wallL, int wallD, int shrub,
                                     int rock, int rockL, int rockD) {
        return new Color[] { rgb(outer), rgb(sandA), rgb(sandB), rgb(crack),
                rgb(wall), rgb(wallL), rgb(wallD), rgb(shrub),
                rgb(rock), rgb(rockL), rgb(rockD) };
    }

    private static Color rgb(int v) {
        return Color.rgb((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
    }

    /** 与坐标绑定的确定性哈希：装饰的形状/位置逐帧稳定，不闪烁 */
    private static long hash2(int x, int y) {
        long h = x * 374761393L + y * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return h ^ (h >>> 16);
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

    /**
     * 沙地：只在城墙内侧铺。参考图没有棋盘格，所以底色用单一沙色，
     * 再叠哈希决定的沙丘暗斑 / 龟裂 / 碎石 / 枯灌，做出参考图那种斑驳质感。
     * 装饰逐帧稳定不闪烁，也不增加任何实体开销。
     */
    private void drawGround(double vw, double vh, Color[] pal) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        float H = Balance.WORLD_HALF;
        int c0 = (int) Math.floor(left / TILE);
        int c1 = (int) Math.floor((left + vw) / TILE);
        int r0 = (int) Math.floor(top / TILE);
        int r1 = (int) Math.floor((top + vh) / TILE);
        gc.setFill(pal[1]);
        for (int c = c0; c <= c1; c++) {
            for (int r = r0; r <= r1; r++) {
                double x0 = Math.max(c * TILE, -H);
                double y0 = Math.max(r * TILE, -H);
                double x1 = Math.min((c + 1) * TILE, H);
                double y1 = Math.min((r + 1) * TILE, H);
                if (x1 <= x0 || y1 <= y0) {
                    continue;   // 完全落在城墙外
                }
                // 装饰绘制会改画笔颜色，所以底色必须在每格绘制前重设
                gc.setFill(pal[1]);
                gc.fillRect(x0 - left, y0 - top, x1 - x0, y1 - y0);
                boolean fullyInside = c * TILE >= -H && (c + 1) * TILE <= H
                        && r * TILE >= -H && (r + 1) * TILE <= H;
                if (fullyInside) {
                    drawTileDecor(c, r, left, top, pal);
                }
            }
        }
    }

    /** 每格至多一样装饰：沙丘暗斑 / 龟裂 / 碎石 / 枯灌，全部由哈希决定 */
    private void drawTileDecor(int c, int r, double left, double top, Color[] pal) {
        long h = hash2(c, r);
        int roll = (int) ((h >>> 3) % 100);
        double bx = c * TILE - left;
        double by = r * TILE - top;
        if (roll < 22) {
            // 沙丘起伏：位置与大小都随哈希偏移，避免出现规则的网格感
            double px = bx + TILE * (0.1 + ((h >>> 7) % 70) / 100.0);
            double py = by + TILE * (0.1 + ((h >>> 11) % 70) / 100.0);
            double rad = 12 + ((h >>> 15) % 22);
            gc.setFill(pal[2].deriveColor(0, 1, 1, 0.35));
            gc.fillOval(px - rad, py - rad * 0.7, rad * 2, rad * 1.4);
        } else if (roll < 40) {
            // 龟裂的干土
            double px = bx + TILE * (0.15 + ((h >>> 7) % 65) / 100.0);
            double py = by + TILE * (0.15 + ((h >>> 11) % 65) / 100.0);
            double len = 14 + ((h >>> 15) % 18);
            gc.setStroke(pal[3]);
            gc.setLineWidth(1.5);
            gc.strokeLine(px, py, px + len, py + len * 0.4);
            gc.strokeLine(px + len * 0.5, py + len * 0.2, px + len * 0.8, py - len * 0.3);
        } else if (roll < 58) {
            // 散落碎石
            gc.setFill(pal[3]);
            for (int k = 0; k < 4; k++) {
                double px = bx + TILE * (0.1 + ((h >>> (k * 6 + 5)) % 76) / 100.0);
                double py = by + TILE * (0.1 + ((h >>> (k * 7 + 9)) % 76) / 100.0);
                gc.fillOval(px, py, 4 + (k % 2) * 4, 3 + (k % 2) * 3);
            }
        } else if (roll < 68) {
            // 枯灌：几笔向上的短枝
            double px = bx + TILE * 0.5 + (((h >>> 9) % 24) - 12);
            double py = by + TILE * 0.5 + (((h >>> 13) % 24) - 12);
            gc.setStroke(pal[7]);
            gc.setLineWidth(2);
            for (int k = 0; k < 5; k++) {
                double a = -Math.PI / 2 + (k - 2) * 0.4;
                gc.strokeLine(px, py, px + Math.cos(a) * 10, py + Math.sin(a) * 10);
            }
        }
    }

    /**
     * 环形城墙：沿世界边界 ±24px 铺石块，块长 / 厚度 / 色调由哈希抖动，
     * 复现模板图那种"残破但连续"的遗迹围墙。只画视口内的部分。
     */
    private void drawBoundary(double vw, double vh, Color[] pal) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        double right = left + vw;
        double bottom = top + vh;
        float H = Balance.WORLD_HALF;
        final double BLOCK = 46.0;
        for (int side = 0; side < 4; side++) {
            boolean horiz = side < 2;
            double fixed = (side == 0 || side == 2) ? -H : H;
            double a0 = horiz ? left : top;
            double a1 = horiz ? right : bottom;
            int i0 = (int) Math.floor(a0 / BLOCK) - 1;
            int i1 = (int) Math.floor(a1 / BLOCK) + 1;
            for (int i = i0; i <= i1; i++) {
                long h = hash2(i, side * 977 + 13);
                double jitter = ((h >>> 8) % 7) - 3;
                double blockLen = BLOCK - 5 + jitter * 1.6;
                double pa = i * BLOCK + jitter * 1.8;
                double thick = 46.0;
                double x;
                double y;
                double w;
                double hgt;
                if (horiz) {
                    x = pa;
                    y = fixed - thick / 2;
                    w = blockLen;
                    hgt = thick;
                } else {
                    x = fixed - thick / 2;
                    y = pa;
                    w = thick;
                    hgt = blockLen;
                }
                if (x + w < left || x > right || y + hgt < top || y > bottom) {
                    continue;
                }
                double sx = x - left;   // 世界坐标 -> 屏幕坐标
                double sy = y - top;
                int tone = (int) ((h >>> 20) % 5);
                gc.setFill(tone < 2 ? pal[4] : (tone < 4 ? pal[5] : pal[6]));
                gc.fillRect(sx, sy, w, hgt);
                gc.setFill(pal[5]);   // 内侧受光边
                if (horiz) {
                    gc.fillRect(sx, side == 0 ? sy + hgt - 5 : sy, w, 5);
                } else {
                    gc.fillRect(side == 2 ? sx + w - 5 : sx, sy, 5, hgt);
                }
                gc.setFill(pal[6]);   // 外侧落影
                if (horiz) {
                    gc.fillRect(sx, side == 0 ? sy : sy + hgt - 4, w, 4);
                } else {
                    gc.fillRect(sx, side == 2 ? sy : sy + hgt - 4, w, 4);
                }
            }
        }
    }

    /** 障碍物：模板风格的多边形岩石 / 石井，带落地阴影与受光面，绘制在实体下层 */
    private void drawObstacles(World w, float alpha, double vw, double vh) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        double right = left + vw;
        double bottom = top + vh;
        Color[] pal = STAGE_PAL[w.stage()];
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
            if (w.meta[i] == 3) {
                drawWell(sx, sy, rr, pal);
            } else {
                drawBoulder(sx, sy, rr, i, pal);
            }
        }
    }

    /** 岩石：不规则多边形 + 左上受光面 + 右下落影，形状由实体下标决定且逐帧稳定 */
    private void drawBoulder(double sx, double sy, double rr, int seed, Color[] pal) {
        long h = hash2(seed, 7919);
        int n = 6 + (int) ((h >>> 4) % 3);
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int k = 0; k < n; k++) {
            double a = k * (Math.PI * 2 / n)
                    + (((h >>> (k % 14)) % 100) / 100.0) * (Math.PI * 2 / n) * 0.6;
            double rad = rr * (0.8 + (((h >>> (k * 5 + 3)) % 100) / 100.0) * 0.32);
            xs[k] = sx + Math.cos(a) * rad;
            ys[k] = sy + Math.sin(a) * rad;
        }
        gc.setFill(Color.rgb(0, 0, 0, 0.18));
        gc.fillOval(sx - rr * 0.92, sy + rr * 0.42, rr * 1.84, rr * 0.86);
        gc.setFill(pal[8]);
        gc.fillPolygon(xs, ys, n);
        gc.setStroke(pal[10]);
        gc.setLineWidth(2);
        gc.strokePolygon(xs, ys, n);
        double[] hx = new double[n];
        double[] hy = new double[n];
        for (int k = 0; k < n; k++) {
            hx[k] = sx + (xs[k] - sx) * 0.52 - rr * 0.12;
            hy[k] = sy + (ys[k] - sy) * 0.52 - rr * 0.16;
        }
        gc.setFill(pal[9]);
        gc.fillPolygon(hx, hy, n);
    }

    /** 石井：石圈 + 井口黑洞 + 八块井沿石，对应模板图中央的遗迹水井 */
    private void drawWell(double sx, double sy, double rr, Color[] pal) {
        gc.setFill(Color.rgb(0, 0, 0, 0.2));
        gc.fillOval(sx - rr, sy - rr + 7, rr * 2, rr * 2);
        gc.setFill(pal[8]);
        gc.fillOval(sx - rr, sy - rr, rr * 2, rr * 2);
        gc.setStroke(pal[6]);
        gc.setLineWidth(3);
        gc.strokeOval(sx - rr, sy - rr, rr * 2, rr * 2);
        gc.setFill(rgb(0x26211C));
        gc.fillOval(sx - rr * 0.6, sy - rr * 0.6, rr * 1.2, rr * 1.2);
        gc.setFill(pal[5]);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            double bx = sx + Math.cos(a) * rr * 0.82;
            double by = sy + Math.sin(a) * rr * 0.82;
            gc.fillOval(bx - 5, by - 5, 10, 10);
        }
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
    /** 胜利结算画面：半透明罩层 + 战报。由 GameApp 在 world.victory() 时调用 */
    public void drawVictory(World w, double vw, double vh) {
        gc.setFill(Color.rgb(8, 6, 14, 0.8));
        gc.fillRect(0, 0, vw, vh);

        gc.setFont(Font.font("Microsoft YaHei", 46));
        gc.setFill(Color.rgb(255, 214, 120));
        gc.fillText("胜  利", vw / 2 - 56, vh * 0.32);

        gc.setFont(Font.font("Microsoft YaHei", 16));
        gc.setFill(Color.rgb(235, 230, 220));
        gc.fillText("终焉之影已被击败，奥术旅团凯旋！", vw / 2 - 148, vh * 0.32 + 48);

        int wid = w.wizardCount() > 0 ? w.wizard(0) : -1;
        Loadout lo = (wid >= 0) ? w.loadout(wid) : null;
        int secs = (int) w.time();
        String stats = String.format("用时 %d:%02d    等级 Lv.%d    击杀 %d",
                secs / 60, secs % 60,
                (lo != null ? lo.level : 0), w.killCount());
        gc.setFont(Font.font("Consolas", 15));
        gc.setFill(Color.rgb(200, 200, 215));
        double tw = measurerLayout(stats, Font.font("Consolas", 15));
        gc.fillText(stats, vw / 2 - tw / 2, vh * 0.32 + 96);

        gc.setFont(Font.font("Microsoft YaHei", 13));
        gc.setFill(Color.rgb(160, 160, 180));
        gc.fillText("按 R 重新开始", vw / 2 - 48, vh * 0.32 + 140);
    }

    /** 量字符串像素宽度，同时返回宽度（复用 measurer，避免每帧新建 Text 节点） */
    private double measurerLayout(String s, Font f) {
        measurer.setFont(f);
        measurer.setText(s);
        return measurer.getLayoutBounds().getWidth();
    }

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
