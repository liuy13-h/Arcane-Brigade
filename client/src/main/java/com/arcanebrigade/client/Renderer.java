package com.arcanebrigade.client;

import com.arcanebrigade.core.Balance;
import com.arcanebrigade.core.ArenaMap;
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
    /** 启动参数 -Dab.debugColliders=true 时显示所有底座碰撞，供地图校准使用。 */
    private static final boolean DEBUG_COLLIDERS = Boolean.getBoolean("ab.debugColliders");

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

    /** 当前鼠标屏幕坐标，手动开火模式的准星用 */
    private double mouseX, mouseY;

    /** 手动暂停状态，暂停按钮文字 + 暂停罩层用 */
    private boolean paused;

    public Renderer(Canvas canvas) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
    }

    public void setPaused(boolean p) {
        this.paused = p;
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

    public double getCamX() {
        return camX;
    }

    public double getCamY() {
        return camY;
    }

    public void setMouse(double x, double y) {
        this.mouseX = x;
        this.mouseY = y;
    }

    /** 开火切换按钮矩形 [x, y, w, h]。GameApp 命中判定与 drawHud 严格共用同一位置 */
    public static double[] fireButtonRect(double vw, double vh) {
        return new double[] { 16, 64, 150, 30 };
    }

    /** 暂停按钮矩形 [x, y, w, h]。GameApp 命中判定与 drawHud 严格共用同一位置 */
    public static double[] pauseButtonRect(double vw, double vh) {
        return new double[] { 16, 104, 110, 30 };
    }

    /** 阵亡结算的「继续」按钮矩形 [x, y, w, h]（右下角）。GameApp 命中判定与绘制共用 */
    public static double[] continueButtonRect(double vw, double vh) {
        double w = 168, h = 46;
        return new double[] { vw - w - 40, vh - h - 60, w, h };
    }

    /** 暂停菜单「继续战斗」按钮矩形 [x, y, w, h]。GameApp 命中判定与绘制共用 */
    public static double[] pauseResumeRect(double vw, double vh) {
        double w = 200, h = 48;
        return new double[] { vw / 2 - w - 16, vh * 0.56, w, h };
    }

    /** 暂停菜单「退出结算」按钮矩形 [x, y, w, h]。GameApp 命中判定与绘制共用 */
    public static double[] pauseQuitRect(double vw, double vh) {
        double w = 200, h = 48;
        return new double[] { vw / 2 + 16, vh * 0.56, w, h };
    }

    public void draw(World w, float alpha) {
        double vw = canvas.getWidth();
        double vh = canvas.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        updateCamera(w, alpha);

        Image battleMap = w.arenaMap().ordinal() < Sprites.battleMaps.length
                ? Sprites.battleMaps[w.arenaMap().ordinal()] : null;
        Color[] pal = arenaPalette(w.arenaMap(), w.stage());
        // 先铺整条蛇形战区，再把原始 PNG 作为起始核心区叠入；离开核心后不会露出矩形底图边缘或空白。
        drawExpeditionGround(w, vw, vh, pal);
        // 原始 PNG 只承担起始核心的美术记忆；进入后续节点后不再把它的矩形边缘带进视野。
        boolean showCoreArt = Math.abs(camX) < 900f && Math.abs(camY) < 560f;
        if (battleMap != null && showCoreArt) {
            // 原始地图仍是核心节点，但以略微融合的方式接到程序化延展地表上。
            double left = camX - vw / 2;
            double top = camY - vh / 2;
            double mapX = -battleMap.getWidth() / 2 - left;
            double mapY = -battleMap.getHeight() / 2 - top;
            gc.setGlobalAlpha(0.94);
            gc.drawImage(battleMap, mapX, mapY, battleMap.getWidth(), battleMap.getHeight());
            gc.setGlobalAlpha(1.0);
            gc.setFill(Color.color(0.02, 0.02, 0.05, 0.12));
            gc.fillRect(0, 0, vw, vh);
        } else if (battleMap == null) {
            drawObstacles(w, alpha, vw, vh);
        }
        if (battleMap != null) drawExtensionObstacles(w, vw, vh);
        drawArenaTerrain(w, vw, vh);
        if (DEBUG_COLLIDERS) drawColliderDebug(w, vw, vh);
        drawArenaTraps(w, vw, vh);
        drawEventWorld(w, alpha, vw, vh);   // 战斗事件：封印裂隙圈 / 蘑菇 / 雕像（部分在 entities 里）
        drawEntities(w, alpha, vw, vh);
        drawHud(w, vw, vh);
        drawBossBar(w, vw);
        drawEventHud(w, vw, vh);            // 事件进度条 + 完成横幅（最上层）
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

    private static final Color[] LAVA_PAL = desertPal(0x171317, 0x3A302E, 0x4C3B35, 0x6E3024,
            0x4A3B3A, 0x78564A, 0x21181A, 0xA44D26, 0x675A57, 0x9A7A63, 0x312625);
    private static final Color[] CRYPT_PAL = desertPal(0x121720, 0x4E5861, 0x59646B, 0x313A43,
            0x414B55, 0x74818B, 0x252C34, 0x7B7153, 0x5C6670, 0x95A0A9, 0x323940);

    private static Color[] arenaPalette(ArenaMap map, int stage) {
        return switch (map) {
            case LAVA_DUNGEON -> LAVA_PAL;
            case STONE_CRYPT -> CRYPT_PAL;
            default -> STAGE_PAL[Math.max(0, Math.min(stage, STAGE_PAL.length - 1))];
        };
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
        // 地图是比视口更大的连续场地：镜头跟随主控，但绝不越过墙体边缘。
        if (w.arenaMap() != null) {
            if (w.wizardCount() == 0) {
                camX = 0f;
                camY = 0f;
                camReady = true;
                return;
            }
            int id = w.wizard(0);
            float rx = w.px[id] + (w.x[id] - w.px[id]) * alpha;
            float ry = w.py[id] + (w.y[id] - w.py[id]) * alpha;
            float maxCamX = Math.max(0f, w.arenaMap().halfWidth() - (float) canvas.getWidth() / 2f);
            float maxCamY = Math.max(0f, w.arenaMap().halfHeight() - (float) canvas.getHeight() / 2f);
            float targetX = Math.max(-maxCamX, Math.min(maxCamX, rx));
            float targetY = Math.max(-maxCamY, Math.min(maxCamY, ry));
            if (!camReady) {
                camX = targetX;
                camY = targetY;
                camReady = true;
            } else {
                // 比旧版自由镜头略快，减少大规模弹幕下的拖拽感。
                camX += (targetX - camX) * 0.20f;
                camY += (targetY - camY) * 0.20f;
            }
            return;
        }
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

    /**
     * 连续地图底板：背景是主题外缘，节点和连接段才是可走地面。圆角、断续描边和主题细节
     * 用来把路线读成沙丘/岩桥/墓道，而不是一组相互拼接的矩形房间。
     */
    private void drawExpeditionGround(World w, double vw, double vh, Color[] pal) {
        ArenaMap map = w.arenaMap();
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        // 荒漠的路线外是深沙而非黑墙；熔岩与墓室则保留压迫感更强的外缘。
        gc.setFill(map == ArenaMap.DESERT_RUINS ? pal[2].deriveColor(0, 0.84, 0.86, 1) : pal[0]);
        gc.fillRect(0, 0, vw, vh);

        gc.setFill(pal[2].deriveColor(0, 1, 1, 0.90));
        for (int i = 0; i < map.routeSectionCount(); i++) {
            ArenaMap.RouteSection route = map.routeSection(i);
            double x = route.x() - route.halfWidth() - left;
            double y = route.y() - route.halfHeight() - top;
            double width = route.halfWidth() * 2;
            double height = route.halfHeight() * 2;
            gc.fillRoundRect(x, y, width, height, Math.min(120, height), Math.min(120, height));
        }

        for (int i = 0; i < map.expeditionNodeCount(); i++) {
            ArenaMap.ExpeditionNode node = map.expeditionNode(i);
            double x = node.x() - node.halfWidth() - left;
            double y = node.y() - node.halfHeight() - top;
            double width = node.halfWidth() * 2;
            double height = node.halfHeight() * 2;
            gc.setFill(node.role() == ArenaMap.ExpeditionRole.BOSS ? pal[3].deriveColor(0, 1, 1, 0.88)
                    : node.role() == ArenaMap.ExpeditionRole.REWARD ? pal[1].deriveColor(0, 1, 1.08, 0.96)
                    : pal[1]);
            gc.fillRoundRect(x, y, width, height, Math.min(180, height), Math.min(180, height));
        }

        int c0 = (int) Math.floor(left / TILE);
        int c1 = (int) Math.floor((left + vw) / TILE);
        int r0 = (int) Math.floor(top / TILE);
        int r1 = (int) Math.floor((top + vh) / TILE);
        for (int c = c0; c <= c1; c++) {
            for (int r = r0; r <= r1; r++) {
                double wx = (c + 0.5) * TILE;
                double wy = (r + 0.5) * TILE;
                if (!map.isWalkable((float) wx, (float) wy, 0f)) continue;
                drawExpeditionDecor(map, c, r, left, top, pal);
            }
        }
    }

    /** 地图主题决定延展区的细节语言：沙丘、熔岩裂痕、墓室石砖不会互相换皮。 */
    private void drawExpeditionDecor(ArenaMap map, int c, int r, double left, double top, Color[] pal) {
        long h = hash2(c, r);
        double bx = c * TILE - left;
        double by = r * TILE - top;
        int roll = (int) ((h >>> 3) % 100);
        switch (map) {
            case DESERT_RUINS -> drawTileDecor(c, r, left, top, pal);
            case LAVA_DUNGEON -> {
                if (roll < 36) {
                    double px = bx + 10 + ((h >>> 9) % 42);
                    double py = by + 12 + ((h >>> 15) % 38);
                    gc.setStroke(pal[7].deriveColor(0, 1, 1.25, 0.72));
                    gc.setLineWidth(2.2);
                    gc.strokeLine(px, py, px + 24, py + 8);
                    gc.strokeLine(px + 13, py + 4, px + 18, py - 14);
                } else if (roll < 56) {
                    gc.setFill(pal[6].deriveColor(0, 1, 1, 0.65));
                    gc.fillOval(bx + 12 + ((h >>> 8) % 30), by + 16 + ((h >>> 14) % 24), 14, 8);
                }
            }
            case STONE_CRYPT -> {
                gc.setStroke(pal[3].deriveColor(0, 1, 1, 0.52));
                gc.setLineWidth(1.2);
                gc.strokeRect(bx + 2, by + 2, TILE - 4, TILE - 4);
                if (roll < 18) {
                    gc.setFill(pal[6].deriveColor(0, 1, 1, 0.52));
                    gc.fillOval(bx + 12 + ((h >>> 9) % 34), by + 15 + ((h >>> 15) % 30), 10, 7);
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
     * 环形城墙（棕色部分 = 地图边界）：铺在可玩区最外缘，从城墙内侧边缘（PLAY_HALF）
     * 一直延伸到世界外缘（WORLD_HALF）。玩家/敌人被钳制在城墙内侧，碰不到棕色墙体。
     * 块长 / 色调由哈希抖动，复现模板图那种"残破但连续"的遗迹围墙，只画视口内的部分。
     */
    private void drawBoundary(double vw, double vh, Color[] pal) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        double right = left + vw;
        double bottom = top + vh;
        float H = Balance.WORLD_HALF;
        float T = Balance.WALL_THICKNESS;
        float inner = Balance.PLAY_HALF;    // 城墙内侧边缘 = 可玩区边界
        final double BLOCK = 46.0;
        for (int side = 0; side < 4; side++) {
            boolean horiz = side < 2;
            double a0 = horiz ? left : top;
            double a1 = horiz ? right : bottom;
            int i0 = (int) Math.floor(a0 / BLOCK) - 1;
            int i1 = (int) Math.floor(a1 / BLOCK) + 1;
            for (int i = i0; i <= i1; i++) {
                long h = hash2(i, side * 977 + 13);
                double jitter = ((h >>> 8) % 7) - 3;
                double blockLen = BLOCK - 5 + jitter * 1.6;
                double pa = i * BLOCK + jitter * 1.8;
                double x;
                double y;
                double w;
                double hgt;
                if (horiz) {
                    x = pa;
                    y = (side == 0) ? -H : inner;   // 顶墙 -H..-inner，底墙 inner..H
                    w = blockLen;
                    hgt = T;
                } else {
                    x = (side == 2) ? -H : inner;   // 左墙 -H..-inner，右墙 inner..H
                    y = pa;
                    w = T;
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
                gc.setFill(pal[5]);   // 内侧受光边（面向可玩区）
                if (horiz) {
                    gc.fillRect(sx, side == 0 ? sy + hgt - 5 : sy, w, 5);
                } else {
                    gc.fillRect(side == 2 ? sx + w - 5 : sx, sy, 5, hgt);
                }
                gc.setFill(pal[6]);   // 外侧落影（面向世界边缘）
                if (horiz) {
                    gc.fillRect(sx, side == 0 ? sy : sy + hgt - 4, w, 4);
                } else {
                    gc.fillRect(side == 2 ? sx : sx + w - 4, sy, 4, hgt);
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
        Color[] pal = arenaPalette(w.arenaMap(), w.stage());
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
            if (w.arenaMap() == ArenaMap.DESERT_RUINS && w.meta[i] == 2) {
                drawWell(sx, sy, rr, pal);
            } else {
                drawBoulder(sx, sy, rr, i, pal);
            }
        }
    }

    /**
     * 核心 PNG 已经包含它自己的美术障碍；延展区没有贴图，所以只画 visual >= 20 的作者碰撞体。
     * 绘制形状直接读 ArenaMap，保证玩家看到的接地轮廓与 World 的阻挡判定是一份数据。
     */
    private void drawExtensionObstacles(World w, double vw, double vh) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        Color[] pal = arenaPalette(w.arenaMap(), w.stage());
        ArenaMap.Obstacle[] obstacles = w.arenaMap().obstacles();
        for (int i = 0; i < obstacles.length; i++) {
            ArenaMap.Obstacle obstacle = obstacles[i];
            if (obstacle.visual() < 20) continue;
            double sx = obstacle.x() - left;
            double sy = obstacle.y() - top;
            double halfW = obstacle.footprintHalfWidth();
            double halfH = obstacle.shape() == ArenaMap.ObstacleShape.CIRCLE ? obstacle.radius() : obstacle.halfHeight();
            if (sx + halfW < 0 || sx - halfW > vw || sy + halfH < 0 || sy - halfH > vh) continue;
            switch (obstacle.shape()) {
                case CIRCLE -> drawBoulder(sx, sy, obstacle.radius(), 10_000 + i, pal);
                case BOX, CAPSULE -> {
                    double width = obstacle.halfWidth() * 2;
                    double height = obstacle.halfHeight() * 2;
                    double x = sx - obstacle.halfWidth();
                    double y = sy - obstacle.halfHeight();
                    double arc = obstacle.shape() == ArenaMap.ObstacleShape.CAPSULE ? Math.min(width, height) : 12;
                    gc.setFill(Color.color(0.03, 0.03, 0.05, 0.32));
                    gc.fillRoundRect(x + 8, y + 10, width, height, arc, arc);
                    gc.setFill(pal[8]);
                    gc.fillRoundRect(x, y, width, height, arc, arc);
                    gc.setStroke(pal[10]);
                    gc.setLineWidth(3);
                    gc.strokeRoundRect(x + 2, y + 2, width - 4, height - 4, arc, arc);
                    gc.setStroke(pal[9].deriveColor(0, 1, 1, 0.68));
                    gc.setLineWidth(2);
                    gc.strokeLine(x + 8, y + 8, x + width - 10, y + 8);
                }
            }
        }
    }

    /** 地图机关在实体下层绘制，预警颜色与伤害窗口颜色严格区分。 */
    private void drawArenaTraps(World w, double vw, double vh) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        for (int i = 0; i < w.trapCount(); i++) {
            ArenaMap.Trap trap = w.trap(i);
            if (trap == null) continue;
            double sx = trap.x() - left;
            double sy = trap.y() - top;
            double halfW = trap.isLane() ? trap.halfWidth() : trap.radius();
            double halfH = trap.isLane() ? trap.halfHeight() : trap.radius();
            if (sx + halfW < 0 || sx - halfW > vw || sy + halfH < 0 || sy - halfH > vh) continue;
            boolean active = w.trapActive(i);
            boolean warning = w.trapTelegraphing(i);
            Color c = switch (trap.visual()) {
                case 1 -> Color.rgb(255, 94, 40);      // 熔岩喷口
                case 2 -> Color.rgb(110, 222, 255);    // 墓室机关
                case 3 -> Color.rgb(229, 184, 104);    // 箭道
                default -> Color.rgb(235, 186, 92);    // 流沙
            };
            double alpha = active ? 0.44 : warning ? 0.20 : 0.08;
            gc.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
            gc.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), active ? 0.98 : warning ? 0.72 : 0.30));
            gc.setLineWidth(active ? 3.0 : 1.5);
            if (trap.isLane()) {
                gc.fillRoundRect(sx - halfW, sy - halfH, halfW * 2, halfH * 2, 8, 8);
                gc.strokeRoundRect(sx - halfW, sy - halfH, halfW * 2, halfH * 2, 8, 8);
                if (active) {
                    double shift = (w.time() * 360) % 96;
                    gc.setLineWidth(2.4);
                    for (double ax = sx - halfW + shift; ax < sx + halfW; ax += 96) {
                        gc.strokeLine(ax - 18, sy, ax + 12, sy);
                        gc.strokeLine(ax + 12, sy, ax + 4, sy - 6);
                        gc.strokeLine(ax + 12, sy, ax + 4, sy + 6);
                    }
                }
            } else {
                gc.fillOval(sx - trap.radius(), sy - trap.radius(), trap.radius() * 2, trap.radius() * 2);
                gc.strokeOval(sx - trap.radius(), sy - trap.radius(), trap.radius() * 2, trap.radius() * 2);
            }
            if (trap.visual() == 2 && active) {
                gc.setStroke(Color.rgb(225, 235, 240, 0.9));
                gc.setLineWidth(2);
                for (int spike = -2; spike <= 2; spike++) {
                    gc.strokeLine(sx + spike * 18, sy + trap.radius() * 0.45,
                            sx + spike * 18 + 7, sy - trap.radius() * 0.45);
                }
            }
        }
    }

    /** 可交互地形在陷阱下层绘制：慢速区是暖色涟漪，符文是冷色脉冲。 */
    private void drawArenaTerrain(World w, double vw, double vh) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        for (ArenaMap.Terrain terrain : w.arenaMap().terrain()) {
            double sx = terrain.x() - left;
            double sy = terrain.y() - top;
            double radius = terrain.radius();
            if (sx + radius < 0 || sx - radius > vw || sy + radius < 0 || sy - radius > vh) {
                continue;
            }
            Color color = switch (terrain.visual()) {
                case 1 -> Color.rgb(255, 92, 38);
                case 2 -> Color.rgb(104, 224, 255);
                default -> Color.rgb(235, 186, 92);
            };
            double pulse = 1d + Math.sin(w.time() * 3d + terrain.x() * 0.01d) * 0.05d;
            double pr = radius * pulse;
            double alpha = terrain.movementMultiplier() < 1f ? 0.08 : 0.12;
            gc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            gc.fillOval(sx - pr, sy - pr, pr * 2, pr * 2);
            gc.setStroke(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.48));
            gc.setLineWidth(terrain.movementMultiplier() < 1f ? 1.3 : 2.1);
            gc.strokeOval(sx - pr, sy - pr, pr * 2, pr * 2);
        }
    }

    /** 碰撞校准叠层：形状直接读取 ArenaMap 数据，避免显示一套、实际计算另一套。 */
    private void drawColliderDebug(World w, double vw, double vh) {
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        gc.setFill(Color.rgb(66, 214, 255, 0.16));
        gc.setStroke(Color.rgb(66, 214, 255, 0.96));
        gc.setLineWidth(2);
        for (ArenaMap.Obstacle obstacle : w.arenaMap().obstacles()) {
            double sx = obstacle.x() - left;
            double sy = obstacle.y() - top;
            switch (obstacle.shape()) {
                case CIRCLE -> {
                    double radius = obstacle.radius();
                    gc.fillOval(sx - radius, sy - radius, radius * 2, radius * 2);
                    gc.strokeOval(sx - radius, sy - radius, radius * 2, radius * 2);
                }
                case BOX -> {
                    double width = obstacle.halfWidth() * 2;
                    double height = obstacle.halfHeight() * 2;
                    gc.fillRect(sx - obstacle.halfWidth(), sy - obstacle.halfHeight(), width, height);
                    gc.strokeRect(sx - obstacle.halfWidth(), sy - obstacle.halfHeight(), width, height);
                }
                case CAPSULE -> {
                    double width = obstacle.halfWidth() * 2;
                    double height = obstacle.halfHeight() * 2;
                    double arc = Math.min(width, height);
                    gc.fillRoundRect(sx - obstacle.halfWidth(), sy - obstacle.halfHeight(), width, height, arc, arc);
                    gc.strokeRoundRect(sx - obstacle.halfWidth(), sy - obstacle.halfHeight(), width, height, arc, arc);
                }
            }
            gc.strokeLine(sx - 4, sy, sx + 4, sy);
            gc.strokeLine(sx, sy - 4, sx, sy + 4);
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
                    // 走动时用 GIF 动画帧，站住时切回静态形象（×2 立绘按自然尺寸绘制）。
                    // 动画相位用世界时间驱动——同一个时间所有单位看到的是同一帧，不各播各的。
                    double mvx = w.x[i] - w.px[i];
                    double mvy = w.y[i] - w.py[i];
                    boolean moving = mvx * mvx + mvy * mvy > 1e-3;
                    Image img = null;
                    boolean fromGif = false;
                    if (moving && ck < Sprites.heroWalk.length) {
                        GifDecoder.Animation anim = Sprites.heroWalk[ck];
                        if (anim != null) {
                            img = anim.frameAt(w.time());
                            fromGif = true;
                        }
                    }
                    if (img == null && ck < Sprites.heroes.length) {
                        img = Sprites.heroes[ck];
                    }
                    if (img != null) {
                        if (fromGif) {
                            // GIF 帧是 32×32，放大到与 ×2 立绘一致（64×64），底边贴地
                            gc.drawImage(img, sx - 32, sy - 44, 64, 64);
                        } else {
                            // ×2 立绘按自然尺寸 1:1 绘制（清晰像素），底边贴角色位置
                            double hw = img.getWidth();
                            double hh = img.getHeight();
                            gc.drawImage(img, Math.round(sx - hw / 2), Math.round(sy + 4 - hh));
                        }
                    }
                }
                case World.KIND_ENEMY -> {
                    if (w.variant[i] == World.V_BOSS) {
                        drawBossSprite(w, i, sx, sy);
                    } else if (w.variant[i] == World.V_STATUE) {
                        drawStatue(sx, sy, rr);
                    } else {
                        gc.drawImage(Sprites.enemies[w.meta[i] % Sprites.enemies.length], sx - 16, sy - 16);
                    }
                    drawEnemyStatus(w, i, sx, sy);
                    if (w.hp[i] < w.maxHp[i]) {
                        float f = Math.max(0f, w.hp[i] / w.maxHp[i]);
                        gc.setFill(Color.rgb(30, 12, 16));
                        gc.fillRect(sx - 13, sy - rr - 10, 26, 4);
                        gc.setFill(Color.rgb(235, 70, 90));
                        gc.fillRect(sx - 12, sy - rr - 9, 24 * f, 2);
                    }
                }
                case World.KIND_MINION -> {
                    // 宠物：秘能仆从，带一条细血条（血是普通小怪的 2 倍，值得看）
                    if (Sprites.minion != null) {
                        gc.drawImage(Sprites.minion, sx - 14, sy - 18, 28, 28);
                    }
                    float mf = Math.max(0f, w.hp[i] / Math.max(1f, w.maxHp[i]));
                    gc.setFill(Color.rgb(30, 12, 16, 0.85));
                    gc.fillRect(sx - 12, sy - rr - 9, 24, 4);
                    gc.setFill(Color.rgb(150, 215, 255));
                    gc.fillRect(sx - 11, sy - rr - 8, 22 * mf, 2);
                }
                case World.KIND_PROJECTILE -> {
                    // 敌人弹幕用统一的"敌意红"，玩家弹幕按元素上色——两者不能混成一种颜色，
                    // 否则弹幕海里根本分不清哪颗是要躲的、哪颗是自己打的。
                    Image img = (w.team[i] == World.TEAM_ENEMY)
                            ? Sprites.enemyBolt
                            : Sprites.bolts[defElem(w, i)];
                    gc.drawImage(img, sx - img.getWidth() / 2, sy - img.getHeight() / 2);
                }
                case World.KIND_PICKUP -> {
                    if (w.meta[i] == World.PICKUP_CHEST) {
                        // 宝箱
                        gc.setFill(Color.rgb(255, 215, 80, 0.95));
                        gc.fillOval(sx - 8, sy - 8, 16, 16);
                        gc.setStroke(Color.rgb(180, 130, 30));
                        gc.setLineWidth(2);
                        gc.strokeOval(sx - 8, sy - 8, 16, 16);
                    } else if (w.meta[i] == World.PICKUP_MUSHROOM) {
                        drawMushroom(sx, sy, w.time());
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

    /**
     * Boss 形象：用 resources 里的立绘（jpg），圆形裁剪后画出来。
     * 立绘是方形照片素材，不裁圆就会在沙漠地图上贴一个突兀的方块；
     * 找不到素材时退回程序化画法（史莱姆放大版），保证不会白屏。
     */
    private void drawBossSprite(World w, int i, double sx, double sy) {
        int tier = w.bossTier();
        Image img = (tier >= 0 && tier < Sprites.bosses.length) ? Sprites.bosses[tier] : null;
        double size = w.r[i] * 2.7;
        if (img != null) {
            double cy = sy - size * 0.06;
            gc.save();
            gc.beginPath();
            gc.arc(sx, cy, size * 0.5, size * 0.5, 0, 360);
            gc.closePath();
            gc.clip();
            gc.drawImage(img, sx - size * 0.5, cy - size * 0.5, size, size);
            gc.restore();
            // 裁剪边缘描一圈，把方图切圆的接缝藏起来
            gc.setStroke(Color.rgb(20, 14, 22, 0.85));
            gc.setLineWidth(2);
            gc.strokeOval(sx - size * 0.5, cy - size * 0.5, size, size);
        } else {
            gc.setFill(Color.rgb(150, 60, 70));
            gc.fillOval(sx - w.r[i], sy - w.r[i], w.r[i] * 2, w.r[i] * 2);
        }
    }

    /** 战斗事件「摧毁雕像」：灰色石像，底座 + 柱身 + 裂纹，可被摧毁 */
    private void drawStatue(double sx, double sy, double rr) {
        double h = rr * 2.6;
        // 底座
        gc.setFill(Color.rgb(96, 88, 84));
        gc.fillRoundRect(sx - rr * 0.9, sy - h * 0.12, rr * 1.8, h * 0.22, 4, 4);
        // 柱身（梯形近似）
        gc.setFill(Color.rgb(150, 142, 138));
        gc.fillRoundRect(sx - rr * 0.62, sy - h, rr * 1.24, h * 0.88, 5, 5);
        gc.setFill(Color.rgb(120, 114, 110));
        gc.fillRoundRect(sx - rr * 0.62, sy - h, rr * 1.24, h * 0.5, 5, 5);
        // 裂纹
        gc.setStroke(Color.rgb(70, 66, 64, 0.9));
        gc.setLineWidth(1.5);
        gc.strokeLine(sx - rr * 0.2, sy - h * 0.8, sx + rr * 0.1, sy - h * 0.55);
        gc.strokeLine(sx + rr * 0.1, sy - h * 0.55, sx - rr * 0.05, sy - h * 0.3);
        // 头部
        gc.setFill(Color.rgb(168, 158, 152));
        gc.fillOval(sx - rr * 0.4, sy - h - rr * 0.35, rr * 0.8, rr * 0.7);
    }

    /** 战斗事件「采集蘑菇」：红顶白点的蘑菇 */
    private void drawMushroom(double sx, double sy, float time) {
        double bob = Math.sin(time * 3.0) * 1.5;
        // 菌柄
        gc.setFill(Color.rgb(230, 214, 180));
        gc.fillRoundRect(sx - 4, sy - 10 + bob, 8, 14, 3, 3);
        // 菌盖
        gc.setFill(Color.rgb(210, 70, 70));
        gc.fillArc(sx - 12, sy - 18 + bob, 24, 16, 0, 180, javafx.scene.shape.ArcType.ROUND);
        gc.fillRect(sx - 12, sy - 10 + bob, 24, 3);
        // 白点
        gc.setFill(Color.rgb(250, 240, 230));
        gc.fillOval(sx - 7, sy - 15 + bob, 3, 3);
        gc.fillOval(sx + 3, sy - 14 + bob, 3, 3);
        gc.fillOval(sx - 1, sy - 16 + bob, 3, 3);
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
        // 右上角玩家 ID（「设置」里可关）
        if (GameConfig.showPlayerId && GameConfig.playerId != null) {
            gc.setFont(hudFont);
            String tag = "ID " + GameConfig.playerId;
            double tw = measureWidth(hudFont, tag);
            gc.setFill(Color.rgb(12, 10, 18, 0.45));
            gc.fillRoundRect(vw - tw - 28, 10, tw + 16, 24, 6, 6);
            gc.setFill(Color.rgb(228, 224, 240, 0.92));
            gc.fillText(tag, vw - tw - 20, 26);
        }

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
        if (w.wizardCount() > 0) {
            int hero = w.wizard(0);
            ArenaMap.Terrain terrain = w.arenaMap().terrainAt(w.x[hero], w.y[hero]);
            if (terrain != null) {
                gc.setFill(terrain.movementMultiplier() < 1f ? Color.rgb(255, 182, 102) : Color.rgb(126, 232, 255));
                gc.fillText(terrain.label() + String.format("  移速 x%.2f", terrain.movementMultiplier()),
                        16, lo != null && lo.stats.loneWolfActive ? 64 : 46);
            }
            ArenaMap.ExpeditionNode node = w.currentExpeditionNode();
            gc.setFill(Color.rgb(232, 220, 180));
            gc.fillText("推进：" + (node == null ? "连接段" : node.label()), vw - 190, 28);
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

        // 开火模式切换按钮（点击切换自动/手动）
        boolean auto = w.isAutoFire();
        double[] fb = fireButtonRect(vw, vh);
        gc.setFill(Color.rgb(18, 16, 30, 0.85));
        gc.fillRect(fb[0], fb[1], fb[2], fb[3]);
        gc.setStroke(auto ? Color.rgb(110, 200, 255) : Color.rgb(255, 170, 90));
        gc.setLineWidth(1.5);
        gc.strokeRect(fb[0], fb[1], fb[2], fb[3]);
        gc.setFont(hudFont);
        gc.setFill(auto ? Color.rgb(160, 220, 255) : Color.rgb(255, 200, 120));
        gc.fillText(auto ? "自动开火：开" : "自动开火：关", fb[0] + 10, fb[1] + 21);

        // 暂停按钮（点击或按 ESC 切换）
        double[] pb = pauseButtonRect(vw, vh);
        gc.setFill(Color.rgb(18, 16, 30, 0.85));
        gc.fillRect(pb[0], pb[1], pb[2], pb[3]);
        gc.setStroke(paused ? Color.rgb(255, 200, 120) : Color.rgb(150, 150, 170));
        gc.setLineWidth(1.5);
        gc.strokeRect(pb[0], pb[1], pb[2], pb[3]);
        gc.setFont(hudFont);
        gc.setFill(paused ? Color.rgb(255, 210, 140) : Color.rgb(210, 210, 225));
        gc.fillText(paused ? "继续" : "暂停", pb[0] + 10, pb[1] + 21);

        // 召唤师 HUD：宠物数量 + 下次召唤倒计时（放在两个按钮下方）
        if (lo.classKind == HeroClass.SUMMONER) {
            gc.setFont(hudFont);
            gc.setFill(Color.rgb(215, 185, 255));
            gc.fillText(String.format("宠物 %d/%d   下次召唤 %.1fs",
                    w.minionCount(w.wizard(0)), Balance.SUMMON_COUNT,
                    Math.max(0f, w.summonTimer(w.wizard(0)))), 16, 152);
        }

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

        // 手动开火准星：显示在鼠标位置
        if (!w.isAutoFire()) {
            double cx = mouseX, cy = mouseY, r = 9;
            gc.setStroke(Color.rgb(255, 200, 120, 0.9));
            gc.setLineWidth(1.5);
            gc.strokeLine(cx - r, cy, cx + r, cy);
            gc.strokeLine(cx, cy - r, cx, cy + r);
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }
    }

    /** 战斗事件的世界层绘制：封印裂隙圈（蘑菇/雕像由 drawEntities 负责） */
    private void drawEventWorld(World w, float alpha, double vw, double vh) {
        if (w.eventType() != Balance.EVENT_RIFT) {
            return;
        }
        double left = camX - vw / 2;
        double top = camY - vh / 2;
        double sx = w.eventX() - left;
        double sy = w.eventY() - top;
        double rr = Balance.RIFT_RADIUS;
        if (sx + rr < 0 || sx - rr > vw || sy + rr < 0 || sy - rr > vh) {
            return;
        }
        float ratio = Math.min(1f, w.eventProgress() / Math.max(1f, w.eventGoal()));
        // 脉动：随世界时间呼吸，圈内闪烁紫光
        double pulse = 1.0 + Math.sin(w.time() * 4.0) * 0.02;
        double pr = rr * pulse;

        // 圈内半透明填充（紫），越接近完成越亮
        gc.setFill(Color.rgb(120, 70, 220, 0.16 + ratio * 0.18));
        gc.fillOval(sx - pr, sy - pr, pr * 2, pr * 2);
        // 外圈
        gc.setStroke(Color.rgb(170, 120, 255, 0.9));
        gc.setLineWidth(3);
        gc.strokeOval(sx - pr, sy - pr, pr * 2, pr * 2);
        // 进度弧（顶部起，顺时针）
        gc.setStroke(Color.rgb(235, 210, 255, 0.95));
        gc.setLineWidth(5);
        gc.strokeArc(sx - pr, sy - pr, pr * 2, pr * 2, -90, -360 * ratio, javafx.scene.shape.ArcType.OPEN);
        // 圆心提示
        gc.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 13));
        gc.setFill(Color.rgb(235, 215, 255));
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 13), sx, sy - rr - 14,
                "封印裂隙", Color.rgb(235, 215, 255), Color.rgb(30, 10, 50));
    }

    /** 战斗事件的 HUD：顶部居中进度条 + 完成横幅提示 */
    private void drawEventHud(World w, double vw, double vh) {
        String name = w.eventName();
        if (name.isEmpty()) {
            // 没有进行中事件时，若刚完成则显示横幅
            if (w.eventBannerT() > 0f) {
                drawEventBanner(vw, vh, "任务完成 +经验");
            }
            return;
        }
        // 顶部居中进度条
        double bw = 360, bh = 22;
        double bx = vw / 2 - bw / 2, by = 60;
        float ratio = Math.min(1f, w.eventProgress() / Math.max(1f, w.eventGoal()));
        gc.setFill(Color.rgb(12, 10, 18, 0.72));
        gc.fillRoundRect(bx - 4, by - 4, bw + 8, bh + 8, 8, 8);
        gc.setFill(Color.rgb(28, 22, 40, 0.9));
        gc.fillRoundRect(bx, by, bw, bh, 6, 6);
        gc.setFill(Color.rgb(150, 110, 240));
        gc.fillRect(bx + 3, by + 3, (bw - 6) * ratio, bh - 6);

        String label;
        if (w.eventType() == Balance.EVENT_RIFT) {
            label = String.format("封印裂隙：在圈内坚持 %.0f / %.0f 秒",
                    w.eventProgress(), w.eventGoal());
        } else if (w.eventType() == Balance.EVENT_STATUE) {
            label = String.format("摧毁雕像：%d / %d",
                    (int) w.eventProgress(), (int) w.eventGoal());
        } else {
            label = String.format("采集蘑菇：%d / %d",
                    (int) w.eventProgress(), (int) w.eventGoal());
        }
        gc.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        double tw = measureWidth(gc.getFont(), label);
        gc.setFill(Color.rgb(245, 238, 255));
        gc.fillText(label, vw / 2 - tw / 2, by - 8);

        if (w.eventBannerT() > 0f) {
            drawEventBanner(vw, vh, "任务完成 +经验");
        }
    }

    private void drawEventBanner(double vw, double vh, String text) {
        Font f = Font.font("Microsoft YaHei", FontWeight.BOLD, 26);
        double tw = measureWidth(f, text);
        double bw = tw + 48, bh = 50;
        double bx = vw / 2 - bw / 2, by = vh * 0.30;
        gc.setFill(Color.rgb(20, 14, 34, 0.9));
        gc.fillRoundRect(bx, by, bw, bh, 12, 12);
        gc.setStroke(Color.rgb(190, 150, 255, 0.9));
        gc.setLineWidth(2);
        gc.strokeRoundRect(bx, by, bw, bh, 12, 12);
        gc.setFont(f);
        gc.setFill(Color.rgb(255, 226, 150));
        gc.fillText(text, bx + 24, by + bh / 2 + 9);
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

    /** 被动栏：无上限，多行居中排布，从底向上堆叠。每个格子用稀有度代表色 + 名称 + 层数 */
    private void drawPassiveBar(World w, double vw, double vh) {
        Loadout lo = w.loadout(w.wizard(0));
        if (lo == null) {
            return;
        }
        int n = lo.passives.size();
        if (n == 0) {
            return;
        }
        double pw = 92, ph = 38, gap = 6;
        int perRow = Math.max(1, (int) ((vw - 24) / (pw + gap)));
        int rows = (n + perRow - 1) / perRow;
        double baseY = vh - 128;   // 底行顶部

        Font f = Font.font("Microsoft YaHei", 11);
        Font fNum = Font.font("Consolas", 12);
        for (int i = 0; i < n; i++) {
            int row = i / perRow;
            int col = i % perRow;
            int inRow = Math.min(perRow, n - row * perRow);
            double total = inRow * pw + (inRow - 1) * gap;
            double x0 = (vw - total) / 2;
            double bx = x0 + col * (pw + gap);
            double by = baseY - (rows - 1 - row) * (ph + gap);
            int pid = lo.passives.get(i);
            PassiveDef d = Passives.get(pid);
            if (d == null) {
                continue;
            }
            Color rarity = switch (d.rarity) {
                case PassiveDef.RARE -> Color.rgb(80, 160, 240);
                case PassiveDef.EPIC -> Color.rgb(240, 160, 60);
                default -> Color.rgb(110, 110, 130);
            };
            gc.setFill(Color.rgb(24, 20, 38, 0.9));
            gc.fillRect(bx, by, pw, ph);
            gc.setStroke(rarity);
            gc.setLineWidth(d.kind == PassiveDef.Kind.MUTATION ? 2.5 : 1);
            gc.strokeRect(bx, by, pw, ph);

            gc.setFont(f);
            gc.setFill(Color.rgb(232, 232, 244));
            gc.fillText(d.name, bx + 6, by + 16);
            if (lo.pstacks.get(i) > 1) {
                gc.setFill(rarity);
                gc.setFont(fNum);
                gc.fillText("x" + lo.pstacks.get(i), bx + pw - 24, by + 16);
                gc.setFont(f);
            }
            gc.setFill(Color.rgb(150, 150, 170));
            gc.fillText(kindLabel(d.kind), bx + 6, by + 32);
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

    /** 弹体的元素（玩家弹按技能定义查，敌人弹恒为 NONE——反正会走敌意红分支） */
    private static int defElem(World w, int i) {
        SpellDef def = Spells.get(w.meta[i]);
        return def != null ? def.element : Element.NONE;
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
    /** 手动暂停罩层：半透明蒙版 + "已暂停"提示 + 继续战斗/退出结算按钮。由 GameApp 在手动暂停时调用 */
    public void drawPauseOverlay(double vw, double vh) {
        gc.setFill(Color.rgb(10, 8, 18, 0.55));
        gc.fillRect(0, 0, vw, vh);

        gc.setFont(Font.font("Microsoft YaHei", 42));
        gc.setFill(Color.rgb(235, 232, 245));
        double tw = measurerLayout("已 暂 停", Font.font("Microsoft YaHei", 42));
        gc.fillText("已 暂 停", vw / 2 - tw / 2, vh * 0.42);

        gc.setFont(Font.font("Microsoft YaHei", 15));
        gc.setFill(Color.rgb(170, 170, 190));
        tw = measurerLayout("按 ESC 或点击按钮", Font.font("Microsoft YaHei", 15));
        gc.fillText("按 ESC 或点击按钮", vw / 2 - tw / 2, vh * 0.42 + 40);

        // 「继续战斗」按钮
        double[] rb = pauseResumeRect(vw, vh);
        boolean rHover = mouseX >= rb[0] && mouseX <= rb[0] + rb[2]
                && mouseY >= rb[1] && mouseY <= rb[1] + rb[3];
        gc.setFill(rHover ? Color.rgb(46, 88, 66) : Color.rgb(30, 46, 44));
        gc.fillRoundRect(rb[0], rb[1], rb[2], rb[3], 10, 10);
        gc.setStroke(Color.rgb(130, 220, 170, rHover ? 1.0 : 0.75));
        gc.setLineWidth(rHover ? 2.2 : 1.5);
        gc.strokeRoundRect(rb[0], rb[1], rb[2], rb[3], 10, 10);
        Font bf = Font.font("Microsoft YaHei", FontWeight.BOLD, 17);
        gc.setFont(bf);
        gc.setFill(Color.rgb(225, 245, 232));
        gc.fillText("继续战斗", rb[0] + rb[2] / 2 - measureWidth(bf, "继续战斗") / 2,
                rb[1] + rb[3] / 2 + 6);

        // 「退出结算」按钮
        double[] qb = pauseQuitRect(vw, vh);
        boolean qHover = mouseX >= qb[0] && mouseX <= qb[0] + qb[2]
                && mouseY >= qb[1] && mouseY <= qb[1] + qb[3];
        gc.setFill(qHover ? Color.rgb(96, 54, 48) : Color.rgb(52, 38, 44));
        gc.fillRoundRect(qb[0], qb[1], qb[2], qb[3], 10, 10);
        gc.setStroke(Color.rgb(235, 150, 130, qHover ? 1.0 : 0.75));
        gc.setLineWidth(qHover ? 2.2 : 1.5);
        gc.strokeRoundRect(qb[0], qb[1], qb[2], qb[3], 10, 10);
        gc.setFont(bf);
        gc.setFill(Color.rgb(245, 225, 220));
        gc.fillText("退出结算", qb[0] + qb[2] / 2 - measureWidth(bf, "退出结算") / 2,
                qb[1] + qb[3] / 2 + 6);
    }

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

    /**
     * 阵亡结算画面：半透明罩层 + 战报面板 + 右下角「继续」按钮（回大厅）。
     * 由 GameApp 在 world.defeat() 时调用。数据取世界冻结的战报快照，不会随画面跳动。
     */
    public void drawDefeatOverlay(World w, double vw, double vh) {
        gc.setFill(Color.rgb(6, 4, 10, 0.86));
        gc.fillRect(0, 0, vw, vh);

        // ---- 标题 ----
        World.Summary s = w.summary();
        boolean aband = (s != null && s.abandoned);
        Font titleF = Font.font("Microsoft YaHei", FontWeight.BOLD, 46);
        String title = aband ? "已 结 算" : "阵  亡";
        gc.setFont(titleF);
        gc.setFill(aband ? Color.rgb(210, 180, 130) : Color.rgb(226, 96, 106));
        gc.fillText(title, vw / 2 - measureWidth(titleF, title) / 2, vh * 0.20);

        Font subF = Font.font("Microsoft YaHei", 15);
        String sub = aband ? "战斗提前结束，旅团从容撤退" : "勇者倒下了，但奥术旅团的传说仍在延续";
        gc.setFont(subF);
        gc.setFill(Color.rgb(198, 190, 200));
        gc.fillText(sub, vw / 2 - measureWidth(subF, sub) / 2, vh * 0.20 + 36);

        // ---- 战报面板 ----
        int secs = (int) (s != null ? s.time : w.time());
        String[][] rows = {
                { "存活时间", String.format("%d:%02d", secs / 60, secs % 60) },
                { "最终等级", "Lv." + (s != null ? s.level : 1) },
                { "击杀小怪", String.valueOf(s != null ? s.minionKills : w.minionKills()) },
                { "击杀 BOSS", String.valueOf(s != null ? s.bossKills : w.bossKills()) },
                { "战场任务", (s != null ? s.events : 0) + " / 3" },
                { "主动技能", (s != null ? s.spells : 0) + " / 3" },
                { "被动强化", (s != null ? s.passives : 0) + " 层" },
        };

        double pw = Math.min(460, vw * 0.66);
        double rowH = 34;
        double ph = 30 + rows.length * rowH + 10;
        double px = vw / 2 - pw / 2;
        double py = vh * 0.30;

        gc.setFill(Color.rgb(20, 16, 30, 0.92));
        gc.fillRoundRect(px, py, pw, ph, 14, 14);
        gc.setStroke(Color.rgb(150, 96, 110, 0.70));
        gc.setLineWidth(1.5);
        gc.strokeRoundRect(px, py, pw, ph, 14, 14);

        Font kf = Font.font("Microsoft YaHei", 14);
        Font vf = Font.font("Consolas", FontWeight.BOLD, 16);
        double ry = py + 30 + 12;
        for (String[] r : rows) {
            gc.setFont(kf);
            gc.setFill(Color.rgb(190, 184, 200));
            gc.fillText(r[0], px + 34, ry);
            gc.setFont(vf);
            gc.setFill(Color.rgb(255, 226, 180));
            gc.fillText(r[1], px + pw - 34 - measureWidth(vf, r[1]), ry);
            ry += rowH;
        }

        // ---- 右下角「继续」按钮 ----
        double[] b = continueButtonRect(vw, vh);
        boolean hover = mouseX >= b[0] && mouseX <= b[0] + b[2]
                && mouseY >= b[1] && mouseY <= b[1] + b[3];
        gc.setFill(hover ? Color.rgb(74, 58, 30) : Color.rgb(38, 32, 52));
        gc.fillRoundRect(b[0], b[1], b[2], b[3], 10, 10);
        gc.setStroke(Color.rgb(255, 208, 130, hover ? 1.0 : 0.75));
        gc.setLineWidth(hover ? 2.2 : 1.5);
        gc.strokeRoundRect(b[0], b[1], b[2], b[3], 10, 10);
        Font bf = Font.font("Microsoft YaHei", FontWeight.BOLD, 18);
        String bt = "继  续";
        gc.setFont(bf);
        gc.setFill(Color.rgb(255, 226, 170));
        gc.fillText(bt, b[0] + b[2] / 2 - measureWidth(bf, bt) / 2, b[1] + b[3] / 2 + 6);

        // ---- 底部提示 ----
        Font tipF = Font.font("Microsoft YaHei", 12.5);
        String tip = "点击「继续」返回准备大厅（亦可按 R）";
        gc.setFont(tipF);
        gc.setFill(Color.rgb(150, 146, 166));
        gc.fillText(tip, vw / 2 - measureWidth(tipF, tip) / 2, vh - 26);
    }

    /** 量字符串像素宽度，同时返回宽度（复用 measurer，避免每帧新建 Text 节点） */
    private double measurerLayout(String s, Font f) {
        measurer.setFont(f);
        measurer.setText(s);
        return measurer.getLayoutBounds().getWidth();
    }

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
            { "生命 90 · 召唤协战", "每 10 秒召唤 4 只宠物", "宠物护主 · 鼠标指挥集火" } };
    /** 数值条：0..1 的归一值（召唤师已开放，接 main 的真实属性） */
    private static final double[] CARD_LIFE = { 0, 100 / 150.0, 140 / 150.0, 85 / 150.0, 90 / 150.0 };
    private static final double[] CARD_SPEED = { 0, 195 / 235.0, 180 / 235.0, 205 / 235.0, 185 / 235.0 };

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
            int cardClass, double reveal, boolean showGuide, TaskSystem tasks,
            TaskSystem.Category taskCategory, boolean taskOpen, ArenaMap arenaMap, boolean mapOpen) {
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

        // ---- 左上角操作指引（可隐藏） ----
        drawLobbyGuide(showGuide);
        drawLobbyTasks(tasks, taskCategory, taskOpen);
        drawLobbyMapSelect(arenaMap, mapOpen);

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

            // ---- 名牌：按状态分层（未选暗底 / 选中职业色描边+✓） ----
            String nm = LobbyClass.name(ck);
            String plateMain = sel ? "✓ " + nm : nm;
            String plateSuffix = "";
            Font pfM = Font.font("Microsoft YaHei", sel ? FontWeight.BOLD : FontWeight.NORMAL,
                    sel ? 15 : 14);
            Font pfS = Font.font("Microsoft YaHei", 11);
            double plateH = sel ? 28 : 24;
            double plateTop = feetY + 8;
            Color plateBg;
            Color plateEdge = null;
            Color plateMainCol;
            if (sel) {
                plateBg = Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.26);
                plateEdge = Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.95);
                plateMainCol = Color.WHITE;
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
        // 站在门内的玩家，才能看到出发提示；未选职业时被挡住
        boolean canGo = chosen != 0;
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
            String label = "已选 · " + LobbyClass.name(chosen);
            Font cf = Font.font("Microsoft YaHei", FontWeight.BOLD, 13);
            double cw = measureWidth(cf, label);
            double cTop = py - 60;
            double cH = 24;
            gc.setFill(Color.rgb(6, 6, 16, 0.75));
            gc.fillRoundRect(px - cw / 2 - 10, cTop, cw + 20, cH, 8, 8);
            gc.setStroke(Color.color(lac.getRed(), lac.getGreen(), lac.getBlue(), 0.9));
            gc.setLineWidth(1.5);
            gc.strokeRoundRect(px - cw / 2 - 10, cTop, cw + 20, cH, 8, 8);
            gc.setFont(cf);
            gc.setFill(lac);
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
        String prefix = "「" + LobbyClass.name(target) + "」";
        String q = (chosen != 0 ? "确定改选为这位勇者出征吗？" : "确定选择这位勇者出征吗？");
        gc.setFont(qF);
        gc.setFill(Color.color(ac.getRed(), ac.getGreen(), ac.getBlue(), 0.98 * fade));
        gc.fillText(prefix, tx, baseY);
        tx += measureWidth(qF, prefix) + 4;
        gc.setFill(Color.rgb(255, 255, 255, 0.97 * fade));
        gc.fillText(q, tx, baseY);

        // 右侧按键提示
        String hint = "空格 确认";
        Font hF = Font.font("Microsoft YaHei", FontWeight.BOLD, 14);
        double hW = measureWidth(hF, hint);
        double hx = x0 + barW - hW - 22;
        gc.setFont(hF);
        gc.setFill(Color.color(0.66, 0.94, 0.90, 0.95 * fade));
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
        drawCardBar(tx, fy + 8, "生命", Math.round(CARD_LIFE[ck] * 150) + "",
                CARD_LIFE[ck], ac);
        drawCardBar(tx, fy + 32, "移速", Math.round(CARD_SPEED[ck] * 235) + "",
                CARD_SPEED[ck], Color.rgb(120, 220, 255));
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

    // ------------------------------------------------------------------
    // 主菜单（TITLE）
    //
    // 启动后第一个画面：整幅标题美术（3072×2048，底部内嵌五个菜单按钮）。
    // 采用 contain 等比铺放——任意窗口比例下整张图都完整可见、左右留暗底，
    // 按钮命中区永远和美术里印着的位置一致。GameApp 与 Renderer 都只走
    // menuButtons()/menuHit() 这一份几何，避免"画的框"和"点的框"错位。
    // ------------------------------------------------------------------

    /** 主界面按钮数量 */
    public static final int MENU_COUNT = 5;
    /** 覆盖层种类：无 / 操作说明 / 设置 / 多人联机（占位） */
    public static final int OVER_NONE = 0;
    public static final int OVER_HELP = 1;
    public static final int OVER_SETTINGS = 2;
    public static final int OVER_MULTI = 3;

    /** 标题画面设计基准尺寸（与美术原图一致）。布局 / 命中区都用它换算。 */
    private static final double TITLE_W = 3072.0;
    private static final double TITLE_H = 2048.0;

    /** 五个菜单按钮在标题画（图像坐标）里的命中矩形：{x0, y0, x1, y1}。 */
    private static final int[][] MENU_BOX = {
            { 416, 1905, 836, 2048 },   // 0 开始游戏
            { 866, 1905, 1286, 2048 },  // 1 多人联机 1-4 人
            { 1330, 1905, 1750, 2048 }, // 2 设置
            { 1758, 1905, 2178, 2048 }, // 3 操作说明
            { 2183, 1905, 2603, 2048 }, // 4 退出游戏
    };

    /** 按钮文字（仅美术缺失兜底绘制时用；美术在位时字是印在图画里的） */
    private static final String[] MENU_LABELS = {
            "开始游戏", "多人联机 1–4 人", "设置", "操作说明", "退出游戏" };

    private static final String[] HELP_LINES = {
            "移动：WASD / 方向键 —— 本作为幸存者玩法，战斗自动开火，你只管走位。",
            "准备大厅：靠近勇者后按 空格 / E —— 招募同行（可再靠近他人改选）。",
            "出征：走进大厅下方光门按 E —— 已选可出战职业即可开战。",
            "升级三选一：鼠标点击卡片选择；按 R 键可免费重抽一次。",
            "胜利：存活满 20 分钟并击败最终 Boss；全队倒下则失败。",
    };

    /** 屏幕坐标小矩形：命中判定与绘制共用同一份 */
    public record Rect(double x, double y, double w, double h) {
        public boolean hit(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /**
     * 覆盖层面板几何（屏幕坐标）。close 是「返回」钮；clickable 是面板内
     * 可点击的行（目前仅「设置」的全屏切换行）。其余文字只读不点击。
     */
    public record OverlayGeom(double px, double py, double pw, double ph,
                              Rect close, Rect[] clickable) {}

    /** 标题画面 contain 布局下五个按钮的屏幕矩形（与窗口尺寸无关的换算基准） */
    public static Rect[] menuButtons(double vw, double vh) {
        double s = Math.min(vw / TITLE_W, vh / TITLE_H);
        double ox = (vw - TITLE_W * s) / 2;
        double oy = (vh - TITLE_H * s) / 2;
        Rect[] out = new Rect[MENU_BOX.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Rect(ox + MENU_BOX[i][0] * s, oy + MENU_BOX[i][1] * s,
                    (MENU_BOX[i][2] - MENU_BOX[i][0]) * s,
                    (MENU_BOX[i][3] - MENU_BOX[i][1]) * s);
        }
        return out;
    }

    /** 屏幕坐标命中主界面按钮；没点上返回 -1 */
    public static int menuHit(double mx, double my, double vw, double vh) {
        Rect[] bs = menuButtons(vw, vh);
        for (int i = 0; i < bs.length; i++) {
            if (bs[i].hit(mx, my)) {
                return i;
            }
        }
        return -1;
    }

    /** 覆盖层面板几何。宽度、纵向位置随窗口缩放，各覆盖层高度按内容定 */
    public static OverlayGeom menuOverlayGeom(double vw, double vh, int overlay) {
        double pw = Math.min(720, vw - 140);
        double px = (vw - pw) / 2;
        double py = vh * 0.13;
        double ph = switch (overlay) {
            case OVER_HELP -> 470.0;
            case OVER_SETTINGS -> 400.0;
            default -> 360.0;
        };
        Rect close = new Rect(px + pw - 148 - 26, py + ph - 52 - 24, 148, 52);
        Rect[] clickable = overlay == OVER_SETTINGS
                ? new Rect[] { new Rect(px + 48, py + 118, pw - 96, 72) }
                : new Rect[0];
        return new OverlayGeom(px, py, pw, ph, close, clickable);
    }

    /**
     * 画主菜单。contain 布局保证整张标题画可见；hover 高亮只在无覆盖层时出现；
     * overlay != OVER_NONE 时在最上层画对应面板（操作说明 / 设置 / 多人联机占位）。
     */
    public void drawTitle(double t, int hover, int overlay, boolean fullscreen) {
        double vw = canvas.getWidth();
        double vh = canvas.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        // 暗底（含 contain 的左右留边）
        gc.setFill(Color.rgb(8, 6, 16));
        gc.fillRect(0, 0, vw, vh);

        double s = Math.min(vw / TITLE_W, vh / TITLE_H);
        double ox = (vw - TITLE_W * s) / 2;
        double oy = (vh - TITLE_H * s) / 2;

        Image art = Sprites.titleScreen;
        if (art != null) {
            gc.drawImage(art, ox, oy, TITLE_W * s, TITLE_H * s);
        } else {
            drawTitleFallback(vw, vh);   // 缺美术也能玩：画大字 + 按钮
        }

        if (overlay == OVER_NONE && hover >= 0 && hover < MENU_BOX.length) {
            int[] b = MENU_BOX[hover];
            drawMenuHover(ox + b[0] * s, oy + b[1] * s,
                    (b[2] - b[0]) * s, (b[3] - b[1]) * s, t);
        }
        if (overlay != OVER_NONE) {
            drawMenuOverlay(overlay, fullscreen);
        }
    }

    /** 按钮悬停暖色光晕（围绕命中框向外扩一圈，微弱呼吸） */
    private void drawMenuHover(double x, double y, double w, double h, double t) {
        double pulse = 0.5 + 0.5 * Math.sin(t * 3.0);
        gc.setFill(Color.rgb(255, 200, 120, 0.15 + 0.12 * pulse));
        gc.fillRoundRect(x - 7, y - 7, w + 14, h + 14, 14, 14);
        gc.setStroke(Color.rgb(255, 214, 140, 0.9));
        gc.setLineWidth(2.5);
        gc.strokeRoundRect(x - 7, y - 7, w + 14, h + 14, 14, 14);
    }

    /** 美术缺失兜底：中央大标题 + 底部程序化按钮，保证主界面功能不受影响 */
    private void drawTitleFallback(double vw, double vh) {
        double s = Math.min(vw / TITLE_W, vh / TITLE_H);
        double ox = (vw - TITLE_W * s) / 2;
        double oy = (vh - TITLE_H * s) / 2;
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 58),
                ox + TITLE_W * s / 2, oy + TITLE_H * s * 0.24,
                "奥 术 旅 团", Color.rgb(255, 227, 168), Color.rgb(10, 6, 2, 0.6));
        drawTextSoft(gc, Font.font("Consolas", 21),
                ox + TITLE_W * s / 2, oy + TITLE_H * s * 0.24 + 50,
                "A R C A N E   B R I G A D E", Color.rgb(150, 150, 180), null);
        Font bf = Font.font("Microsoft YaHei", FontWeight.BOLD, 15);
        Rect[] bs = menuButtons(vw, vh);
        for (int i = 0; i < bs.length; i++) {
            Rect r = bs[i];
            gc.setFill(Color.rgb(70, 52, 26, 0.9));
            gc.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
            gc.setStroke(Color.rgb(200, 160, 90, 0.85));
            gc.setLineWidth(1.5);
            gc.strokeRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
            drawTextSoft(gc, bf, r.x() + r.w() / 2, r.y() + r.h() / 2 + 5,
                    MENU_LABELS[i], Color.rgb(240, 226, 200), null);
        }
    }

    /** 主界面上层覆盖面板：操作说明 / 设置 / 多人联机（开发中占位） */
    private void drawMenuOverlay(int overlay, boolean fullscreen) {
        if (overlay == OVER_SETTINGS) {   // 「设置」面板已重做，走独立绘制
            drawSettingsOverlay();
            return;
        }
        double vw = canvas.getWidth();
        double vh = canvas.getHeight();
        OverlayGeom g = menuOverlayGeom(vw, vh, overlay);

        gc.setFill(Color.rgb(5, 3, 10, 0.62));
        gc.fillRect(0, 0, vw, vh);

        gc.setFill(Color.rgb(21, 17, 32, 0.98));
        gc.fillRoundRect(g.px(), g.py(), g.pw(), g.ph(), 16, 16);
        gc.setStroke(Color.rgb(150, 120, 66));
        gc.setLineWidth(2);
        gc.strokeRoundRect(g.px(), g.py(), g.pw(), g.ph(), 16, 16);

        String head = switch (overlay) {
            case OVER_HELP -> "操 作 说 明";
            case OVER_SETTINGS -> "设 置";
            default -> "多人联机 · 敬请期待";
        };
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 23),
                g.px() + g.pw() / 2, g.py() + 58, head,
                Color.rgb(255, 227, 168), Color.rgb(12, 6, 2, 0.5));
        gc.setStroke(Color.rgb(120, 96, 58, 0.8));
        gc.setLineWidth(1);
        gc.strokeLine(g.px() + 40, g.py() + 82, g.px() + g.pw() - 40, g.py() + 82);

        Font body = Font.font("Microsoft YaHei", 15);
        Font small = Font.font("Microsoft YaHei", 12.5);
        double lx = g.px() + 52;
        Color faint = Color.rgb(198, 198, 216);
        Color dim = Color.rgb(174, 174, 198);

        switch (overlay) {
            case OVER_HELP -> {
                double y = g.py() + 122;
                gc.setFont(body);
                for (String line : HELP_LINES) {
                    gc.setFill(faint);
                    gc.fillText(line, lx, y);
                    y += 40;
                }
                gc.setFill(dim);
                gc.setFont(small);
                gc.fillText("提示：可出战职业为 巫师 / 战士 / 弓箭手 / 召唤师。", lx, y + 14);
            }
            case OVER_SETTINGS -> {
                Rect r = g.clickable()[0];
                boolean on = fullscreen;
                gc.setFill(on ? Color.rgb(94, 72, 28) : Color.rgb(46, 42, 64));
                gc.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
                gc.setStroke(on ? Color.rgb(255, 200, 120) : Color.rgb(132, 124, 152));
                gc.setLineWidth(on ? 2 : 1.5);
                gc.strokeRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
                Font f = Font.font("Microsoft YaHei", 17);
                gc.setFont(f);
                gc.setFill(Color.rgb(238, 234, 246));
                gc.fillText("全屏模式", r.x() + 24, r.y() + r.h() / 2 + 6);
                Font fs = Font.font("Microsoft YaHei", FontWeight.BOLD, 17);
                String state = on ? "开" : "关";
                double sw = measureWidth(fs, state);
                gc.setFont(fs);
                gc.setFill(on ? Color.rgb(255, 210, 130) : Color.rgb(176, 172, 192));
                gc.fillText(state, r.x() + r.w() - 24 - sw, r.y() + r.h() / 2 + 6);
                gc.setFont(small);
                gc.setFill(dim);
                gc.fillText("点击上方开关切换全屏 / 窗口模式（亦可用 F11 快捷切换）。", lx, g.py() + 248);
                gc.fillText("标题画面在任意窗口比例下等比完整显示；大厅与战斗画面随窗口自适应。", lx, g.py() + 274);
            }
            default -> {
                String[] lines = {
                        "「多人联机 · 1–4 人在线合作」正在开发中，敬请期待！",
                        "当前为本地单机抢先体验：从准备大厅招募一位勇者出征，",
                        "在自动开火的幸存者战斗中存活 20 分钟并击败最终 Boss。",
                        "在线合作将在后续版本加入，感谢你的关注！",
                };
                double y = g.py() + 152;
                for (String line : lines) {
                    drawTextSoft(gc, Font.font("Microsoft YaHei", 15.5),
                            g.px() + g.pw() / 2, y, line, faint, null);
                    y += 40;
                }
            }
        }

        // 面板右下角「返回」钮
        drawMenuClose(g.close());
    }

    /** 覆盖层右下角的「返回」钮 */
    private void drawMenuClose(Rect r) {
        gc.setFill(Color.rgb(150, 120, 60));
        gc.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
        gc.setStroke(Color.rgb(96, 76, 44));
        gc.setLineWidth(1.5);
        gc.strokeRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 17),
                r.x() + r.w() / 2, r.y() + r.h() / 2 + 6, "返 回",
                Color.rgb(32, 24, 10), null);
    }

    // ------------------------------------------------------------------
    // 「设置」面板
    //
    // 音量（总/BGM/音效/语音）、显示玩家 ID 开关、显示模式（窗口/全屏/
    // 无边框窗口）、窗口分辨率、帧率上限。数值来源与落地都在 GameConfig，
    // 这里只负责画。几何统一由 settingsGeom() 产出，命中/绘制不分叉。
    // ------------------------------------------------------------------

    private static final String[] VOL_LABELS = { "总音量", "BGM 音量", "音效 音量", "语音 音量" };
    private static final String[] MODE_LABELS = { "窗口", "全屏", "无边框窗口" };
    private static final String[] RES_LABELS = { "1280×720", "1600×900", "1920×1080" };
    private static final String[] FPS_LABELS = { "30", "60", "120" };

    /** 设置面板几何：全部控件矩形（屏幕坐标）。GameApp 命中与绘制共用。 */
    public record SettingsGeom(double px, double py, double pw, double ph,
            Rect close, Rect[] volumes, Rect showId,
            Rect[] modes, Rect[] resolutions, Rect[] fps) {}

    /** 三选一的行：把 contentW 三等分，各段间 16px 间距 */
    private static Rect[] segmentRow(double x, double y, double w) {
        double gap = 16;
        double sw = (w - gap * 2) / 3;
        return new Rect[] {
                new Rect(x, y, sw, 40),
                new Rect(x + sw + gap, y, sw, 40),
                new Rect(x + (sw + gap) * 2, y, sw, 40),
        };
    }

    /** 计算「设置」面板几何。所有竖向行距由常量顺序推得，绘制/命中不会错位。 */
    public static SettingsGeom settingsGeom(double vw, double vh) {
        double pw = Math.min(820, vw - 120);
        double px = (vw - pw) / 2;
        double contentX = px + 48;
        double contentW = pw - 96;

        // 竖向节奏（相对面板顶）
        double rowTop = 118;                       // 第一条滑块上缘
        double rowStep = 52;
        double showTop = rowTop + 4 * rowStep + 10;    // 开关行
        double seg1Top = showTop + 40 + 34;            // 显示模式（上方预留 caption）
        double seg2Top = seg1Top + 54;                 // 窗口分辨率
        double seg3Top = seg2Top + 54;                 // 帧率上限
        double closeTop = seg3Top + 40 + 16;           // 返回钮
        double phRel = closeTop + 46 + 22;

        double ph = Math.min(phRel, vh - 16);
        double py = Math.max(6, (vh - ph) / 2);

        Rect[] vols = new Rect[GameConfig.VOLUME_COUNT];
        for (int i = 0; i < vols.length; i++) {
            vols[i] = new Rect(contentX + 118, py + rowTop + i * rowStep,
                    contentW - 118 - 64, 26);
        }
        Rect showId = new Rect(contentX, py + showTop, contentW, 40);
        Rect[] modes = segmentRow(contentX, py + seg1Top, contentW);
        Rect[] res = segmentRow(contentX, py + seg2Top, contentW);
        Rect[] fps = segmentRow(contentX, py + seg3Top, contentW);
        Rect close = new Rect(px + pw / 2 - 90, py + closeTop, 180, 46);
        return new SettingsGeom(px, py, pw, ph, close, vols, showId, modes, res, fps);
    }

    /** 画「设置」整块覆盖层：美术风镶金面板 + 分区托盘 + 精致控件。 */
    private void drawSettingsOverlay() {
        double vw = canvas.getWidth();
        double vh = canvas.getHeight();
        SettingsGeom g = settingsGeom(vw, vh);
        double px = g.px(), py = g.py(), pw = g.pw(), ph = g.ph();
        double cx = px + pw / 2;
        double contentX = px + 48;

        // 压暗背景
        gc.setFill(Color.rgb(3, 2, 9, 0.68));
        gc.fillRect(0, 0, vw, vh);

        // ---- 面板：下方投影 + 竖向渐变深板 + 双层描边（外层金、内层暗象牙） ----
        gc.setFill(Color.rgb(0, 0, 0, 0.42));
        gc.fillRoundRect(px + 3, py + 7, pw, ph, 18, 18);
        gc.setFill(new LinearGradient(0, py, 0, py + ph, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(46, 38, 70)),
                new Stop(0.5, Color.rgb(30, 24, 48)),
                new Stop(1, Color.rgb(19, 15, 32))));
        gc.fillRoundRect(px, py, pw, ph, 18, 18);
        gc.setStroke(Color.rgb(214, 178, 100));
        gc.setLineWidth(2.2);
        gc.strokeRoundRect(px, py, pw, ph, 18, 18);
        gc.setStroke(Color.rgb(255, 226, 168, 0.20));
        gc.setLineWidth(1);
        gc.strokeRoundRect(px + 3.5, py + 3.5, pw - 7, ph - 7, 15, 15);

        // ---- 标题「设 置」+ 两侧鎏金饰线 ----
        Font headFont = Font.font("Microsoft YaHei", FontWeight.BOLD, 24);
        drawTextSoft(gc, headFont, cx, py + 56, "设 置",
                Color.rgb(255, 224, 160), Color.rgb(10, 5, 2, 0.6));
        double hw = measureWidth(headFont, "设 置");
        gc.setStroke(Color.rgb(255, 206, 128, 0.55));
        gc.setLineWidth(1.2);
        gc.strokeLine(px + 44, py + 56, cx - hw / 2 - 26, py + 56);
        gc.strokeLine(cx + hw / 2 + 26, py + 56, px + pw - 44, py + 56);
        // 标题下细金线 + 中央小菱形
        gc.setStroke(Color.rgb(190, 148, 84, 0.8));
        gc.strokeLine(px + 40, py + 84, px + pw - 40, py + 84);
        gc.setFill(Color.rgb(255, 214, 140));
        double[] dx = { cx - 4, cx, cx + 4, cx };
        double[] dy = { py + 84, py + 80, py + 84, py + 88 };
        gc.fillPolygon(dx, dy, 4);

        Font label = Font.font("Microsoft YaHei", 14);
        Font value = Font.font("Consolas", 13);

        // ================= 分区一：音量 =================
        Rect v0 = g.volumes()[0];
        Rect v3 = g.volumes()[GameConfig.VOLUME_COUNT - 1];
        drawGroupBox(px + 22, v0.y() - 14, pw - 44, (v3.y() + v3.h()) - v0.y() + 20);
        gc.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 13));
        gc.setFill(Color.rgb(255, 210, 130));
        gc.fillText("◈ 音 量", contentX, v0.y() - 20);
        for (int i = 0; i < GameConfig.VOLUME_COUNT; i++) {
            Rect r = g.volumes()[i];
            gc.setFont(label);
            gc.setFill(Color.rgb(226, 223, 238));
            gc.fillText(VOL_LABELS[i], contentX, r.y() + r.h() / 2 + 5);

            double v = GameConfig.volume(i);
            // 轨道底
            gc.setFill(Color.rgb(10, 8, 18));
            gc.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 9, 9);
            gc.setStroke(Color.rgb(40, 34, 58));
            gc.setLineWidth(1);
            gc.strokeRoundRect(r.x(), r.y(), r.w(), r.h(), 9, 9);
            // 填充（金→橙渐变）
            double fw = Math.max(5, r.w() * v / 100.0);
            gc.setFill(new LinearGradient(r.x(), 0, r.x() + r.w(), 0, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.rgb(255, 208, 128)),
                    new Stop(1, Color.rgb(232, 140, 70))));
            gc.fillRoundRect(r.x(), r.y(), fw, r.h(), 9, 9);
            // 轨道端部圆形封口让高亮不露直角（在填充端再盖个圆）
            double midY = r.y() + r.h() / 2;
            gc.fillOval(r.x() + fw - r.h(), r.y(), r.h(), r.h());
            // 拇指旋钮
            double knobX = Math.max(r.x() + 6, Math.min(r.x() + r.w(), r.x() + fw));
            gc.setFill(Color.rgb(24, 17, 8));
            gc.fillOval(knobX - 7, midY - 7, 14, 14);   // 深色旋钮底
            gc.setStroke(Color.rgb(255, 226, 170));
            gc.setLineWidth(2);
            gc.strokeOval(knobX - 7, midY - 7, 14, 14);
            gc.setFill(Color.rgb(255, 226, 170));
            gc.fillOval(knobX - 2.5, midY - 2.5, 5, 5);
            // 数值
            gc.setFont(value);
            gc.setFill(Color.rgb(250, 240, 214));
            gc.fillText(String.valueOf((int) v), r.x() + r.w() + 16, midY + 4);
        }

        // ================= 显示玩家 ID 开关 =================
        Rect sid = g.showId();
        boolean idOn = GameConfig.showPlayerId;
        drawGroupBox(px + 22, sid.y() - 12, pw - 44, sid.h() + 24);
        // 行底色渐变
        gc.setFill(idOn
                ? new LinearGradient(0, sid.y(), 0, sid.y() + sid.h(), false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.rgb(96, 72, 30)), new Stop(1, Color.rgb(66, 50, 24)))
                : new LinearGradient(0, sid.y(), 0, sid.y() + sid.h(), false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.rgb(54, 49, 74)), new Stop(1, Color.rgb(38, 34, 54))));
        gc.fillRoundRect(sid.x(), sid.y(), sid.w(), sid.h(), 10, 10);
        gc.setStroke(idOn ? Color.rgb(255, 210, 130) : Color.rgb(150, 142, 176));
        gc.setLineWidth(idOn ? 2 : 1.2);
        gc.strokeRoundRect(sid.x(), sid.y(), sid.w(), sid.h(), 10, 10);
        gc.setFont(Font.font("Microsoft YaHei", 15));
        gc.setFill(Color.rgb(240, 236, 250));
        gc.fillText("显示玩家 ID", sid.x() + 20, sid.y() + sid.h() / 2 + 5);
        // 右侧状态胶囊
        double pillW = 74, pillH = 32;
        double pillX = sid.x() + sid.w() - pillW - 18;
        double pillY = sid.y() + (sid.h() - pillH) / 2;
        gc.setFill(idOn ? Color.rgb(84, 168, 104) : Color.rgb(120, 110, 128));
        gc.fillRoundRect(pillX, pillY, pillW, pillH, 16, 16);
        gc.setStroke(Color.rgb(255, 255, 255, 0.25));
        gc.setLineWidth(1);
        gc.strokeRoundRect(pillX, pillY, pillW, pillH, 16, 16);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 14),
                pillX + pillW / 2, pillY + pillH / 2 + 5, idOn ? "开" : "关",
                Color.WHITE, null);

        // ================= 画面与性能：三组三选一 =================
        Rect fps0 = g.fps()[0];
        Rect m0 = g.modes()[0];
        drawGroupBox(px + 22, m0.y() - 34, pw - 44, (fps0.y() + 40) - m0.y() + 40);
        drawSegGroup(g.modes(), MODE_LABELS, GameConfig.displayMode, "显示模式");
        drawSegGroup(g.resolutions(), RES_LABELS, resolutionIndex(), "窗口分辨率");
        drawSegGroup(g.fps(), FPS_LABELS, fpsIndex(), "帧率上限");

        // 底部小提示（右侧返回钮左侧）
        Font tip = Font.font("Microsoft YaHei", 12);
        gc.setFont(tip);
        gc.setFill(Color.rgb(168, 164, 192));
        String tipTxt = "设置自动保存 · F11 切换全屏";
        drawTextSoft(gc, tip, g.px() + 48 + measureWidth(tip, tipTxt) / 2,
                g.close().y() - 12, tipTxt, Color.rgb(168, 164, 192), null);

        drawMenuClose(g.close());
    }

    /** 给一组设置项垫一层浅色托盘（分区观感） */
    private void drawGroupBox(double x, double y, double w, double h) {
        gc.setFill(Color.rgb(255, 236, 190, 0.045));
        gc.fillRoundRect(x, y, w, h, 12, 12);
        gc.setStroke(Color.rgb(184, 146, 88, 0.30));
        gc.setLineWidth(1);
        gc.strokeRoundRect(x, y, w, h, 12, 12);
    }

    /** 一横排三选一（选中的金色浮雕），caption 画在整行上方 */
    private void drawSegGroup(Rect[] segs, String[] labels, int selected, String caption) {
        gc.setFont(Font.font("Microsoft YaHei", 12.5));
        gc.setFill(Color.rgb(206, 202, 226));
        gc.fillText("◆ " + caption, segs[0].x(), segs[0].y() - 10);
        for (int i = 0; i < segs.length; i++) {
            Rect r = segs[i];
            boolean sel = i == selected;
            if (sel) {
                gc.setFill(new LinearGradient(0, r.y(), 0, r.y() + r.h(), false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.rgb(214, 176, 110)),
                        new Stop(1, Color.rgb(150, 104, 46))));
                gc.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
                gc.setStroke(Color.rgb(255, 228, 170));
                gc.setLineWidth(2);
                gc.strokeRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
                drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 14),
                        r.x() + r.w() / 2, r.y() + r.h() / 2 + 5, labels[i],
                        Color.rgb(48, 30, 6), null);
            } else {
                gc.setFill(new LinearGradient(0, r.y(), 0, r.y() + r.h(), false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.rgb(64, 58, 88)),
                        new Stop(1, Color.rgb(44, 40, 64))));
                gc.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
                gc.setStroke(Color.rgb(140, 132, 168));
                gc.setLineWidth(1.2);
                gc.strokeRoundRect(r.x(), r.y(), r.w(), r.h(), 10, 10);
                drawTextSoft(gc, Font.font("Microsoft YaHei", 14),
                        r.x() + r.w() / 2, r.y() + r.h() / 2 + 5, labels[i],
                        Color.rgb(214, 212, 230), null);
            }
        }
    }

    private static int resolutionIndex() {
        for (int i = 0; i < GameConfig.RESOLUTIONS.length; i++) {
            if (GameConfig.RESOLUTIONS[i][0] == GameConfig.winW
                    && GameConfig.RESOLUTIONS[i][1] == GameConfig.winH) {
                return i;
            }
        }
        return 0;
    }

    private static int fpsIndex() {
        for (int i = 0; i < GameConfig.FPS_CHOICES.length; i++) {
            if (GameConfig.FPS_CHOICES[i] == GameConfig.fpsCap) {
                return i;
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 大厅地图选择：左侧透明按钮与三张纯场景预览卡（不叠角色立绘）
    // ------------------------------------------------------------------

    public record MapSelectGeom(Rect toggle, Rect panel, Rect[] cards) {}

    public static MapSelectGeom lobbyMapSelectGeom(double vw, double vh) {
        double x = 16;
        Rect toggle = new Rect(x, 198, 174, 34);
        double w = Math.min(330, Math.max(286, vw * 0.265));
        double y = 242;
        Rect panel = new Rect(x, y, w, 3 * 86 + 56);
        Rect[] cards = new Rect[ArenaMap.values().length];
        for (int i = 0; i < cards.length; i++) cards[i] = new Rect(x + 10, y + 45 + i * 86, w - 20, 76);
        return new MapSelectGeom(toggle, panel, cards);
    }

    private void drawLobbyMapSelect(ArenaMap selected, boolean open) {
        MapSelectGeom g = lobbyMapSelectGeom(canvas.getWidth(), canvas.getHeight());
        Rect toggle = g.toggle();
        gc.setFill(Color.rgb(8, 7, 15, 0.52));
        gc.fillRoundRect(toggle.x(), toggle.y(), toggle.w(), toggle.h(), 10, 10);
        gc.setStroke(Color.rgb(132, 210, 235, 0.55));
        gc.setLineWidth(1.1);
        gc.strokeRoundRect(toggle.x(), toggle.y(), toggle.w(), toggle.h(), 10, 10);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 13), toggle.x() + 58, toggle.y() + 22,
                "◇ 地图", Color.rgb(181, 235, 250), null);
        if (!open) return;
        Rect panel = g.panel();
        gc.setFill(Color.rgb(8, 7, 15, 0.72));
        gc.fillRoundRect(panel.x(), panel.y(), panel.w(), panel.h(), 14, 14);
        gc.setStroke(Color.rgb(132, 210, 235, 0.55));
        gc.strokeRoundRect(panel.x(), panel.y(), panel.w(), panel.h(), 14, 14);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 15), panel.x() + 17, panel.y() + 27,
                "选择战场", Color.rgb(222, 245, 255), null);
        ArenaMap[] maps = ArenaMap.values();
        for (int i = 0; i < maps.length; i++) {
            ArenaMap map = maps[i];
            Rect card = g.cards()[i];
            boolean isSelected = map == selected;
            Color accent = map == ArenaMap.LAVA_DUNGEON ? Color.rgb(255, 119, 62)
                    : map == ArenaMap.STONE_CRYPT ? Color.rgb(135, 214, 238) : Color.rgb(232, 187, 101);
            gc.setFill(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), isSelected ? 0.23 : 0.09));
            gc.fillRoundRect(card.x(), card.y(), card.w(), card.h(), 10, 10);
            gc.setStroke(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), isSelected ? 0.94 : 0.42));
            gc.setLineWidth(isSelected ? 2.2 : 1.0);
            gc.strokeRoundRect(card.x(), card.y(), card.w(), card.h(), 10, 10);
            // 预览只绘制关卡本身，绝不把大厅角色压到地图图面上。
            Image preview = i < Sprites.mapPreviews.length ? Sprites.mapPreviews[i] : null;
            if (preview != null) {
                gc.drawImage(preview, card.x() + 10, card.y() + 12, 72, 52);
            } else {
                gc.setFill(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.32));
                gc.fillRoundRect(card.x() + 10, card.y() + 12, 72, 52, 7, 7);
            }
            drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 13), card.x() + 96, card.y() + 29,
                    map.displayName(), Color.WHITE, null);
            drawTextSoft(gc, Font.font("Microsoft YaHei", 11), card.x() + 96, card.y() + 49,
                    map.playStyle() + " · " + map.hazardHint(), Color.rgb(210, 215, 226), null);
        }
    }

    // ------------------------------------------------------------------
    // 大厅任务栏：左侧低透明度面板，避免覆盖王座与职业选择主视觉
    // ------------------------------------------------------------------

    /** 任务按钮、切换标签和领取按钮的命中区域，供 GameApp 与绘制共用。 */
    public record TaskGeom(Rect toggle, Rect panel, Rect dailyTab, Rect weeklyTab, Rect[] claims) {}

    public static TaskGeom lobbyTaskGeom(double vw, double vh, int taskCount) {
        double x = 16;
        Rect toggle = new Rect(x, 154, 174, 34);
        double panelY = 198;
        double panelW = Math.min(328, Math.max(280, vw * 0.265));
        double panelH = 112 + taskCount * 72;
        Rect panel = new Rect(x, panelY, panelW, panelH);
        Rect daily = new Rect(x + 12, panelY + 43, (panelW - 34) / 2, 27);
        Rect weekly = new Rect(daily.x() + daily.w() + 10, panelY + 43, daily.w(), 27);
        Rect[] claims = new Rect[taskCount];
        for (int i = 0; i < taskCount; i++) {
            claims[i] = new Rect(x + panelW - 82, panelY + 85 + i * 72, 66, 25);
        }
        return new TaskGeom(toggle, panel, daily, weekly, claims);
    }

    private void drawLobbyTasks(TaskSystem tasks, TaskSystem.Category category, boolean open) {
        if (tasks == null) return;
        double vw = canvas.getWidth();
        double vh = canvas.getHeight();
        TaskSystem.TaskView[] views = tasks.tasks(category);
        TaskGeom g = lobbyTaskGeom(vw, vh, views.length);
        Rect toggle = g.toggle();
        gc.setFill(Color.rgb(8, 7, 15, 0.52));
        gc.fillRoundRect(toggle.x(), toggle.y(), toggle.w(), toggle.h(), 10, 10);
        gc.setStroke(Color.rgb(255, 214, 140, 0.55));
        gc.setLineWidth(1.1);
        gc.strokeRoundRect(toggle.x(), toggle.y(), toggle.w(), toggle.h(), 10, 10);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 13),
                toggle.x() + 48, toggle.y() + 22, "◆ 任务", Color.rgb(255, 225, 165), null);
        int claimable = tasks.claimableCount();
        if (claimable > 0) {
            gc.setFill(Color.rgb(225, 92, 75, 0.92));
            gc.fillOval(toggle.x() + toggle.w() - 28, toggle.y() + 8, 18, 18);
            drawTextSoft(gc, Font.font("Consolas", FontWeight.BOLD, 11),
                    toggle.x() + toggle.w() - 19, toggle.y() + 21, String.valueOf(claimable), Color.WHITE, null);
        }
        if (!open) return;

        Rect p = g.panel();
        // 透明度压低，让大厅壁饰和角色仍可被看到。
        gc.setFill(Color.rgb(8, 7, 15, 0.64));
        gc.fillRoundRect(p.x(), p.y(), p.w(), p.h(), 14, 14);
        gc.setStroke(Color.rgb(232, 191, 112, 0.52));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(p.x(), p.y(), p.w(), p.h(), 14, 14);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 16),
                p.x() + 18, p.y() + 27, "旅团任务", Color.rgb(255, 228, 175), null);
        drawTextSoft(gc, Font.font("Microsoft YaHei", 12), p.x() + p.w() - 52, p.y() + 27,
                "印记 " + tasks.marks(), Color.rgb(171, 231, 255), null);
        drawTaskTab(g.dailyTab(), "每日", category == TaskSystem.Category.DAILY);
        drawTaskTab(g.weeklyTab(), "每周", category == TaskSystem.Category.WEEKLY);

        for (int i = 0; i < views.length; i++) {
            TaskSystem.TaskView task = views[i];
            double y = p.y() + 78 + i * 72;
            gc.setFill(Color.rgb(255, 234, 185, 0.08));
            gc.fillRoundRect(p.x() + 10, y, p.w() - 20, 63, 9, 9);
            gc.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 13));
            gc.setFill(task.claimed() ? Color.rgb(160, 160, 170) : Color.rgb(245, 238, 218));
            gc.fillText(task.title(), p.x() + 20, y + 21);
            gc.setFont(Font.font("Microsoft YaHei", 11.5));
            gc.setFill(Color.rgb(205, 197, 214));
            gc.fillText(task.detail(), p.x() + 20, y + 39);
            double progress = Math.min(1.0, task.progress() / (double) task.target());
            gc.setFill(Color.rgb(0, 0, 0, 0.32));
            gc.fillRoundRect(p.x() + 20, y + 46, p.w() - 126, 8, 4, 4);
            gc.setFill(task.complete() ? Color.rgb(117, 211, 156, 0.85) : Color.rgb(157, 190, 255, 0.78));
            gc.fillRoundRect(p.x() + 20, y + 46, (p.w() - 126) * progress, 8, 4, 4);
            drawTextSoft(gc, Font.font("Consolas", 10.5), p.x() + p.w() - 102, y + 53,
                    Math.min(task.progress(), task.target()) + "/" + task.target(), Color.rgb(231, 224, 238), null);
            Rect claim = g.claims()[i];
            if (task.claimed()) {
                drawTaskButton(claim, "已领取", Color.rgb(120, 120, 130, 0.46));
            } else if (task.claimable()) {
                drawTaskButton(claim, "领取", Color.rgb(95, 178, 136, 0.82));
            } else {
                drawTaskButton(claim, "+" + task.reward(), Color.rgb(80, 100, 130, 0.46));
            }
        }
    }

    private void drawTaskTab(Rect r, String label, boolean selected) {
        gc.setFill(selected ? Color.rgb(196, 151, 77, 0.44) : Color.rgb(255, 255, 255, 0.07));
        gc.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 7, 7);
        gc.setStroke(Color.rgb(255, 216, 145, selected ? 0.82 : 0.35));
        gc.setLineWidth(1);
        gc.strokeRoundRect(r.x(), r.y(), r.w(), r.h(), 7, 7);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 12), r.x() + r.w() / 2,
                r.y() + 19, label, selected ? Color.rgb(255, 232, 177) : Color.rgb(196, 191, 205), null);
    }

    private void drawTaskButton(Rect r, String label, Color fill) {
        gc.setFill(fill);
        gc.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 7, 7);
        gc.setStroke(Color.rgb(232, 228, 236, 0.40));
        gc.setLineWidth(0.9);
        gc.strokeRoundRect(r.x(), r.y(), r.w(), r.h(), 7, 7);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 11), r.x() + r.w() / 2,
                r.y() + 17, label, Color.WHITE, null);
    }

    /** 入局五秒简报，冻结模拟期间告诉玩家本局可推进的任务。 */
    public void drawTaskBrief(TaskSystem tasks, double secondsLeft) {
        double vw = canvas.getWidth(), vh = canvas.getHeight();
        gc.setFill(Color.rgb(5, 4, 10, 0.70));
        gc.fillRect(0, 0, vw, vh);
        double w = Math.min(470, vw * 0.52), h = 248, x = (vw - w) / 2, y = (vh - h) / 2;
        gc.setFill(Color.rgb(20, 17, 31, 0.90));
        gc.fillRoundRect(x, y, w, h, 18, 18);
        gc.setStroke(Color.rgb(238, 193, 104, 0.78));
        gc.setLineWidth(1.5);
        gc.strokeRoundRect(x, y, w, h, 18, 18);
        String[] lines = tasks.runBrief();
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 24), x + w / 2, y + 48,
                lines[0], Color.rgb(255, 230, 177), null);
        for (int i = 1; i < lines.length; i++) {
            drawTextSoft(gc, Font.font("Microsoft YaHei", i == 3 ? 12 : 16), x + w / 2,
                    y + 88 + (i - 1) * 37, lines[i], i == 3 ? Color.rgb(185, 193, 210) : Color.rgb(237, 232, 245), null);
        }
        drawTextSoft(gc, Font.font("Consolas", FontWeight.BOLD, 14), x + w / 2, y + h - 23,
                "准备出征  " + (int) Math.ceil(secondsLeft), Color.rgb(154, 210, 255), null);
    }

    // ------------------------------------------------------------------
    // 大厅左上角操作指引（可收起 / 展开）
    // ------------------------------------------------------------------

    /** 大厅指引行的【按键、说明】 */
    private static final String[][] LOBBY_GUIDE = {
            { "WASD / 方向键", "移动走位" },
            { "空格 / E", "靠近勇者招募同行" },
            { "E", "走进下方光门出征" },
    };

    /** 指引面板几何：展开态的面板 + 「✕」隐藏钮 + 收起态的「展开」钮。 */
    public record GuideGeom(Rect panel, Rect hide, Rect open,
            double chipW, double descX, double firstRowY, double rowStep) {}

    /** 计算大厅指引几何。GameApp（命中隐藏钮）与绘制共用，保证点哪是哪。 */
    public static GuideGeom lobbyGuideGeom(double vw, double vh) {
        Font keyFont = Font.font("Microsoft YaHei", FontWeight.BOLD, 12.5);
        Font descFont = Font.font("Microsoft YaHei", 13);
        double maxKey = 0, maxDesc = 0;
        for (String[] op : LOBBY_GUIDE) {
            maxKey = Math.max(maxKey, measureWidth(keyFont, op[0]));
            maxDesc = Math.max(maxDesc, measureWidth(descFont, op[1]));
        }
        double chipW = maxKey + 20;
        double gx = 16, gy = 14;
        double guideW = 16 + 10 + chipW + 10 + maxDesc + 14;
        double guideH = 8 + 22 + 6 + LOBBY_GUIDE.length * 27 + 12;
        Rect panel = new Rect(gx, gy, guideW, guideH);
        Rect hide = new Rect(gx + guideW - 46, gy + 4, 36, 26);   // 面板内右上角
        String openLabel = "❖ 操作指引 ▸";
        double ow = measureWidth(Font.font("Microsoft YaHei", 14), openLabel) + 22;
        Rect open = new Rect(gx, gy, ow, 34);
        double descX = gx + 14 + chipW + 10;
        double firstRowY = gy + 22 + 22;   // 首行文字基线
        return new GuideGeom(panel, hide, open, chipW, descX, firstRowY, 27);
    }

    /** 画左上操作指引。show=false 时只留一个可点开的小钮。 */
    private void drawLobbyGuide(boolean show) {
        GuideGeom gg = lobbyGuideGeom(canvas.getWidth(), canvas.getHeight());
        Font keyFont = Font.font("Microsoft YaHei", FontWeight.BOLD, 12.5);
        Font descFont = Font.font("Microsoft YaHei", 13);
        if (!show) {
            Rect o = gg.open();
            gc.setFill(Color.rgb(7, 5, 14, 0.60));
            gc.fillRoundRect(o.x(), o.y(), o.w(), o.h(), 9, 9);
            gc.setStroke(Color.rgb(255, 214, 140, 0.75));
            gc.setLineWidth(1.2);
            gc.strokeRoundRect(o.x(), o.y(), o.w(), o.h(), 9, 9);
            drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 13),
                    o.x() + o.w() / 2, o.y() + o.h() / 2 + 5, "❖ 操作指引 ▸",
                    Color.rgb(255, 220, 150), null);
            return;
        }
        Rect p = gg.panel();
        gc.setFill(Color.rgb(7, 5, 14, 0.60));
        gc.fillRoundRect(p.x(), p.y(), p.w(), p.h(), 12, 12);
        gc.setStroke(Color.rgb(180, 142, 86, 0.55));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(p.x(), p.y(), p.w(), p.h(), 12, 12);
        gc.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 13));
        gc.setFill(Color.rgb(255, 222, 158));
        gc.fillText("◆ 操作指引", p.x() + 14, p.y() + 22);
        double lineY = gg.firstRowY();
        for (String[] op : LOBBY_GUIDE) {
            double chipX = p.x() + 14;
            gc.setFill(Color.rgb(255, 210, 130, 0.18));
            gc.fillRoundRect(chipX, lineY - 13, gg.chipW(), 19, 6, 6);
            gc.setStroke(Color.rgb(255, 214, 140, 0.75));
            gc.setLineWidth(1);
            gc.strokeRoundRect(chipX, lineY - 13, gg.chipW(), 19, 6, 6);
            drawTextSoft(gc, keyFont, chipX + gg.chipW() / 2, lineY + 1, op[0],
                    Color.rgb(255, 220, 150), null);
            gc.setFont(descFont);
            gc.setFill(Color.rgb(238, 234, 248));
            gc.fillText(op[1], gg.descX(), lineY + 1);
            lineY += gg.rowStep();
        }
        // 右上角隐藏钮「✕」
        Rect h = gg.hide();
        gc.setFill(Color.rgb(255, 210, 130, 0.16));
        gc.fillRoundRect(h.x(), h.y(), h.w(), h.h(), 7, 7);
        gc.setStroke(Color.rgb(255, 214, 140, 0.7));
        gc.setLineWidth(1);
        gc.strokeRoundRect(h.x(), h.y(), h.w(), h.h(), 7, 7);
        drawTextSoft(gc, Font.font("Microsoft YaHei", FontWeight.BOLD, 13),
                h.x() + h.w() / 2, h.y() + h.h() / 2 + 5, "✕",
                Color.rgb(255, 220, 150), null);
    }
}
