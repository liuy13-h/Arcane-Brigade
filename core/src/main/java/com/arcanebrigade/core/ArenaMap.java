package com.arcanebrigade.core;

/**
 * Author-authored scrolling combat rooms. Layout data lives in core so collision,
 * projectile blocking and trap damage always agree with the client presentation.
 */
public enum ArenaMap {
    DESERT_RUINS("荒漠遗迹", "开阔机动", "流沙漩涡会减速并造成擦伤",
            new Obstacle[] {
                    // 只保留真正位于场内的两块掩体；外墙、散石与植被均由底图表达，不再制造隐形阻挡。
                    capsule(-190, 25, 62, 38, 0),
                    box(205, -100, 45, 32, 1),
                    // 延展节点的掩体：只落在战斗台地，保证沙漠仍以大范围拉扯为主。
                    // 核心外环与南侧战区也放置真实掩体；所有可见的大型石体都拥有同一份底座碰撞。
                    worldCapsule(-360, -560, 105, 42, 20), worldCircle(440, 610, 64, 20),
                    worldBox(800, 680, 45, 92, 21),
                    worldCapsule(1_250, 690, 115, 48, 20), worldCircle(1_770, 670, 58, 20),
                    worldBox(2_250, 900, 50, 140, 21), worldCapsule(3_580, 970, 170, 45, 22)
            },
            new Trap[] {
                    // 流沙负责持续减速；沙尘喷口才是短促、可预判的伤害机关。
                    t(-210, 40, 68, 0.80f, 0.35f, 0.75f, 3.0f, 12f, 0),
                    t(260, -5, 68, 0.80f, 0.35f, 0.75f, 3.0f, 12f, 0),
                    // 外环只是轻度压迫，仍留下大范围绕行与撤退空间。
                    worldTrap(-220, -560, 54, 0.90f, 0.35f, 0.70f, 3.4f, 10f, 0),
                    worldTrap(720, 580, 58, 0.85f, 0.35f, 0.75f, 3.2f, 12f, 0),
                    worldTrap(1_500, 320, 62, 0.85f, 0.35f, 0.75f, 3.2f, 12f, 0)
            },
            new Terrain[] {
                    terrain("流沙", -210, 40, 94, 0.68f, 0), terrain("流沙", 260, -5, 94, 0.68f, 0),
                    worldTerrain("风蚀缓沙", -220, -560, 100, 0.84f, 0),
                    worldTerrain("流沙", 420, 620, 130, 0.74f, 0),
                    worldTerrain("流沙", 1_480, 700, 130, 0.70f, 0)
            },
            desertNodes(), desertRoutes()),

    LAVA_DUNGEON("熔岩地牢", "动态危险", "裂缝喷火会周期性封锁走位",
            new Obstacle[] {
                    // 对齐 lava-dungeon.png：两座上方祭坛、两堆中央乱石和下方两段断墙。
                    o(-286, -92, 72, 3), o(282, -92, 72, 3), o(-143, -16, 54, 4),
                    o(141, -16, 54, 4), o(-285, 116, 64, 5), o(278, 116, 64, 5),
                    // 高压节点用岩柱与断桥残片制造掩体，不把连接桥塞成单线作战。
                    worldCircle(1_260, -590, 62, 20), worldBox(1_650, -710, 45, 105, 21),
                    worldCapsule(2_070, -1_180, 130, 45, 22), worldCircle(3_260, -720, 70, 20)
            },
            new Trap[] {
                    // 四处喷口分别落在背景中可见的熔岩喷焰上，不能留在中央石地。
                    t(-399, 118, 42, 0.80f, 0.30f, 0.70f, 3.1f, 18f, 1),
                    t(-211, 176, 42, 0.80f, 0.30f, 0.70f, 3.1f, 18f, 1),
                    t(213, 176, 42, 0.80f, 0.30f, 0.70f, 3.1f, 18f, 1),
                    t(416, 49, 42, 0.80f, 0.30f, 0.70f, 3.1f, 18f, 1),
                    worldTrap(1_420, -480, 52, 0.75f, 0.30f, 0.70f, 2.9f, 18f, 1)
            },
            new Terrain[] {
                    terrain("灼热裂隙", -399, 118, 58, 0.78f, 1), terrain("灼热裂隙", -211, 176, 58, 0.78f, 1),
                    terrain("灼热裂隙", 213, 176, 58, 0.78f, 1), terrain("灼热裂隙", 416, 49, 58, 0.78f, 1)
            },
            lavaNodes(), lavaRoutes()),

    STONE_CRYPT("古老石质遗迹", "机关控制", "地刺与箭矢机关会迫使你改变路线",
            new Obstacle[] {
                    // 对齐 stone-crypt.png 的接地底座：柱堆用圆，大碎石用横向胶囊，石棺用独立矩形。
                    o(-210, -150, 48, 6), o(205, -140, 52, 6), capsule(-175, 80, 75, 45, 7),
                    box(-325, 114, 26, 34, 8),
                    // 右下两口石棺共用同一块接地石座；合为一体，避免留下小于角色直径的假通道。
                    box(195, 110, 58, 34, 8),
                    // 大厅和墓坑的柱/石棺落在节点侧面，留下可绕柱与躲箭的中央回旋区。
                    worldCircle(1_270, -360, 62, 20), worldCircle(1_720, -240, 62, 20),
                    worldBox(2_180, -900, 56, 125, 21), worldCapsule(3_590, 640, 180, 50, 22)
            },
            new Trap[] {
                    // 两块地刺板与背景完全重合；第三个机关是两侧箭槽之间的横向箭道。
                    t(-285, -48, 45, 1.0f, 0.32f, 0.65f, 3.8f, 20f, 2),
                    t(286, -15, 48, 1.0f, 0.32f, 0.65f, 3.8f, 20f, 2),
                    lane(0, -55, 548, 24, 1.0f, 0.32f, 0.60f, 4.2f, 16f, 3),
                    worldTrap(1_500, -520, 54, 0.95f, 0.30f, 0.60f, 3.1f, 20f, 2)
            },
            new Terrain[] {
                    // 对齐左右两块地面符文：站上去可快速穿过箭道或绕过地刺。
                    terrain("疾行符文", -395, 59, 56, 1.22f, 2), terrain("疾行符文", 413, 57, 56, 1.22f, 2)
            },
            cryptNodes(), cryptRoutes());

    public enum ObstacleShape { CIRCLE, BOX, CAPSULE }
    /**
     * 阻挡规则与底座轮廓分离：以后加入可破坏木箱或只挡怪物的机关时，不必再复制碰撞代码。
     * 本轮作者摆放的实物全部是 SOLID；DECORATION 仅供美术装饰数据使用，永不制造隐形墙。
     */
    public enum ObstacleRule {
        SOLID(true, true, false),
        MOVE_ONLY(true, false, false),
        BREAKABLE(true, true, true),
        DECORATION(false, false, false);

        private final boolean blocksMovement;
        private final boolean blocksProjectiles;
        private final boolean destructible;

        ObstacleRule(boolean blocksMovement, boolean blocksProjectiles, boolean destructible) {
            this.blocksMovement = blocksMovement;
            this.blocksProjectiles = blocksProjectiles;
            this.destructible = destructible;
        }

        public boolean blocksMovement() { return blocksMovement; }
        public boolean blocksProjectiles() { return blocksProjectiles; }
        public boolean destructible() { return destructible; }
    }
    /** 连续战区中的功能节点；CORE 复用原始底图，其余节点由主题地表自然延展。 */
    public enum ExpeditionRole { CORE, TRANSITION, COMBAT, ELITE, REWARD, EVENT, BOSS }

    /** 蛇形推进网络中的可战斗空间。半尺寸用于路线判定与客户端绘制，非屏幕边界。 */
    public record ExpeditionNode(String label, ExpeditionRole role, float x, float y,
                                 float halfWidth, float halfHeight) {
        public boolean contains(float px, float py, float radius) {
            return Math.abs(px - x) <= halfWidth - radius && Math.abs(py - y) <= halfHeight - radius;
        }
    }

    /** 两个节点之间的自然连接段。它只限制可走地形，不会绘制成方形房间墙。 */
    public record RouteSection(float x, float y, float halfWidth, float halfHeight) {
        public boolean contains(float px, float py, float radius) {
            return Math.abs(px - x) <= halfWidth - radius && Math.abs(py - y) <= halfHeight - radius;
        }
    }

    /**
     * 静态阻挡物的底部轮廓。halfWidth / halfHeight 仅供 BOX 与 CAPSULE 使用；
     * CIRCLE 只使用 radius。规则单独决定是否阻挡移动与弹幕，装饰永不默认阻挡。
     */
    public record Obstacle(float x, float y, float radius, float halfWidth, float halfHeight,
                           ObstacleShape shape, ObstacleRule rule, int visual) {
        public float broadRadius() {
            return shape == ObstacleShape.CIRCLE ? radius
                    : (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
        }
        public float footprintHalfWidth() {
            return shape == ObstacleShape.CIRCLE ? radius : halfWidth;
        }
        public boolean blocksMovement() { return rule.blocksMovement(); }
        public boolean blocksProjectiles() { return rule.blocksProjectiles(); }
        public boolean destructible() { return rule.destructible(); }
        public boolean overlapsCircle(float px, float py, float targetRadius) {
            return switch (shape) {
                case CIRCLE -> {
                    float dx = px - x;
                    float dy = py - y;
                    float rr = radius + targetRadius;
                    yield dx * dx + dy * dy < rr * rr;
                }
                case BOX -> circleOverlapsBox(px, py, targetRadius, x, y, halfWidth, halfHeight);
                case CAPSULE -> {
                    float capRadius = Math.min(halfWidth, halfHeight);
                    float ax = x, ay = y, bx = x, by = y;
                    if (halfWidth >= halfHeight) {
                        ax -= halfWidth - capRadius;
                        bx += halfWidth - capRadius;
                    } else {
                        ay -= halfHeight - capRadius;
                        by += halfHeight - capRadius;
                    }
                    yield circleOverlapsCapsule(px, py, targetRadius, ax, ay, bx, by, capRadius);
                }
            };
        }
    }

    /** 地图地形：数值与视觉位置共用，避免“踩在符文上却没有效果”。 */
    public record Terrain(String label, float x, float y, float radius, float movementMultiplier, int visual) {
        public boolean contains(float px, float py) {
            float dx = px - x;
            float dy = py - y;
            return dx * dx + dy * dy <= radius * radius;
        }
    }

    /**
     * 机关严格遵循：预警 → 蓄力 → 伤害 → 恢复。visualPadding 必须覆盖角色脚下半径，
     * 因此显示范围始终略大于真实伤害触及范围，杜绝边缘的“看不见伤害”。
     */
    public record Trap(float x, float y, float radius, float halfWidth, float halfHeight,
                       float visualPadding, float telegraph, float arming, float active, float recovery,
                       float damage, int visual, float phaseOffset) {
        public float cycle() { return telegraph + arming + active + recovery; }
        public float phase(float time) { return (time + phaseOffset) % cycle(); }
        public boolean telegraphing(float time) { return phase(time) < telegraph; }
        public boolean arming(float time) {
            float p = phase(time);
            return p >= telegraph && p < telegraph + arming;
        }
        /** 箭道使用轴对齐矩形；其余机关维持圆形判定。 */
        public boolean isLane() { return halfWidth > 0f && halfHeight > 0f; }
        public float visualRadius() { return radius + visualPadding; }
        public float visualHalfWidth() { return isLane() ? halfWidth + visualPadding : visualRadius(); }
        public float visualHalfHeight() { return isLane() ? halfHeight + visualPadding : visualRadius(); }
        public boolean contains(float px, float py, float targetRadius) {
            if (isLane()) {
                return Math.abs(px - x) <= halfWidth + targetRadius
                        && Math.abs(py - y) <= halfHeight + targetRadius;
            }
            float dx = px - x;
            float dy = py - y;
            float rr = radius + targetRadius;
            return dx * dx + dy * dy <= rr * rr;
        }
        public boolean active(float time) {
            float p = phase(time);
            return p >= telegraph + arming && p < telegraph + arming + active;
        }
        public boolean recovering(float time) {
            return phase(time) >= telegraph + arming + active;
        }
    }

    private final String displayName;
    private final String playStyle;
    private final String hazardHint;
    private final Obstacle[] obstacles;
    private final Trap[] traps;
    private final Terrain[] terrain;
    private final ExpeditionNode[] expeditionNodes;
    private final RouteSection[] routeSections;

    ArenaMap(String displayName, String playStyle, String hazardHint, Obstacle[] obstacles, Trap[] traps,
             Terrain[] terrain, ExpeditionNode[] expeditionNodes, RouteSection[] routeSections) {
        this.displayName = displayName;
        this.playStyle = playStyle;
        this.hazardHint = hazardHint;
        this.obstacles = obstacles;
        this.traps = traps;
        this.terrain = terrain;
        this.expeditionNodes = expeditionNodes;
        this.routeSections = routeSections;
    }

    public String displayName() { return displayName; }
    public String playStyle() { return playStyle; }
    public String hazardHint() { return hazardHint; }
    public Obstacle[] obstacles() { return obstacles.clone(); }
    public Trap[] traps() { return traps.clone(); }
    public Terrain[] terrain() { return terrain.clone(); }
    public ExpeditionNode[] expeditionNodes() { return expeditionNodes.clone(); }
    public RouteSection[] routeSections() { return routeSections.clone(); }
    public int expeditionNodeCount() { return expeditionNodes.length; }
    public ExpeditionNode expeditionNode(int index) { return expeditionNodes[index]; }
    public int routeSectionCount() { return routeSections.length; }
    public RouteSection routeSection(int index) { return routeSections[index]; }
    /** 路线以“节点 + 连接段”的并集定义；自然地形而非一张矩形地图决定可走区域。 */
    public boolean isWalkable(float x, float y, float radius) {
        for (ExpeditionNode node : expeditionNodes) {
            if (node.contains(x, y, radius)) return true;
        }
        for (RouteSection section : routeSections) {
            if (section.contains(x, y, radius)) return true;
        }
        return false;
    }
    public ExpeditionNode nodeAt(float x, float y) {
        for (ExpeditionNode node : expeditionNodes) {
            if (node.contains(x, y, 0f)) return node;
        }
        return null;
    }
    /** 返回脚下唯一的地形效果；地图设计刻意不让多个速度效果相叠。 */
    public Terrain terrainAt(float x, float y) {
        for (Terrain area : terrain) {
            if (area.contains(x, y)) {
                return area;
            }
        }
        return null;
    }
    public float movementMultiplierAt(float x, float y) {
        Terrain area = terrainAt(x, y);
        return area == null ? 1f : area.movementMultiplier();
    }
    /**
     * 原地图数据按旧的 1280×720 全景镜头标注；现在改为原图尺寸中的世界坐标，
     * 让镜头能跟随角色平移。三张底图宽 1672px，故 X 轴统一按此比例转换。
     */
    private static final float ASSET_WORLD_SCALE = 1672f / 1280f;

    /** 仅作安全兜底的世界范围；实际移动由 isWalkable 的蛇形路线限制。 */
    public float halfWidth() { return 4_500f; }
    public float halfHeight() { return 2_600f; }

    // 每套数据都从原始地图核心区起步，但转向节奏与节点功能刻意不同，避免三张图只是换皮。
    private static ExpeditionNode[] desertNodes() {
        return new ExpeditionNode[] {
                n("遗迹前庭", ExpeditionRole.CORE, 0, 0, 760, 410),
                // 两块外环与核心宽重叠：玩家可从上、下两侧自然进出，不再只有一个勉强的门洞。
                n("北侧风蚀外环", ExpeditionRole.TRANSITION, -80, -560, 720, 260),
                n("南侧流沙战区", ExpeditionRole.COMBAT, 360, 620, 920, 360),
                n("风蚀断墙", ExpeditionRole.TRANSITION, 1_050, 0, 340, 190),
                n("沙丘遭遇区", ExpeditionRole.COMBAT, 1_480, 520, 550, 410),
                n("流沙宝藏", ExpeditionRole.REWARD, 780, 1_050, 360, 270),
                n("坍塌神殿", ExpeditionRole.ELITE, 2_350, 920, 580, 430),
                n("风暴缓冲台", ExpeditionRole.EVENT, 2_880, 260, 360, 280),
                n("日蚀台地", ExpeditionRole.BOSS, 3_650, 680, 700, 540)
        };
    }

    private static RouteSection[] desertRoutes() {
        return new RouteSection[] {
                route(850, 0, 260, 135), route(1_250, 250, 170, 250),
                route(1_930, 720, 280, 180), route(2_580, 600, 190, 280), route(3_260, 470, 240, 170)
        };
    }

    private static ExpeditionNode[] lavaNodes() {
        return new ExpeditionNode[] {
                n("熔岩门厅", ExpeditionRole.CORE, 0, 0, 760, 410),
                n("断桥前哨", ExpeditionRole.TRANSITION, 960, -120, 320, 170),
                n("火焰祭坛", ExpeditionRole.COMBAT, 1_420, -650, 500, 360),
                n("冷却岩台", ExpeditionRole.REWARD, 710, -1_160, 350, 250),
                n("喷火裂谷", ExpeditionRole.ELITE, 2_120, -1_150, 550, 400),
                n("灰烬避难所", ExpeditionRole.EVENT, 2_700, -510, 350, 260),
                n("熔炉之心", ExpeditionRole.BOSS, 3_520, -820, 720, 540)
        };
    }

    private static RouteSection[] lavaRoutes() {
        return new RouteSection[] {
                route(820, -100, 240, 120), route(1_160, -370, 150, 260),
                route(1_040, -900, 330, 145), route(1_650, -900, 260, 150),
                route(2_420, -820, 180, 285), route(3_080, -660, 230, 160)
        };
    }

    private static ExpeditionNode[] cryptNodes() {
        return new ExpeditionNode[] {
                n("初始墓室", ExpeditionRole.CORE, 0, 0, 760, 410),
                n("墓道转角", ExpeditionRole.TRANSITION, 940, 220, 320, 170),
                n("石柱大厅", ExpeditionRole.COMBAT, 1_500, -300, 560, 400),
                n("侧向密室", ExpeditionRole.REWARD, 1_020, -1_030, 350, 260),
                n("塌陷墓坑", ExpeditionRole.ELITE, 2_250, -820, 590, 430),
                n("封印前厅", ExpeditionRole.EVENT, 2_820, -130, 360, 280),
                n("王陵主殿", ExpeditionRole.BOSS, 3_650, 320, 720, 560)
        };
    }

    private static RouteSection[] cryptRoutes() {
        return new RouteSection[] {
                route(820, 160, 230, 120), route(1_180, -10, 150, 255),
                route(1_200, -670, 340, 150), route(1_830, -560, 250, 160),
                route(2_540, -470, 185, 290), route(3_210, 100, 250, 175)
        };
    }

    private static ExpeditionNode n(String label, ExpeditionRole role, float x, float y, float halfWidth, float halfHeight) {
        return new ExpeditionNode(label, role, x, y, halfWidth, halfHeight);
    }

    private static RouteSection route(float x, float y, float halfWidth, float halfHeight) {
        return new RouteSection(x, y, halfWidth, halfHeight);
    }

    /** 延展节点已经使用世界坐标，不能复用旧底图的 scale 坐标转换。visual >= 20 表示无底图掩体。 */
    private static Obstacle worldCircle(float x, float y, float radius, int visual) {
        return new Obstacle(x, y, radius, 0f, 0f, ObstacleShape.CIRCLE, ObstacleRule.SOLID, visual);
    }

    private static Obstacle worldBox(float x, float y, float halfWidth, float halfHeight, int visual) {
        return new Obstacle(x, y, 0f, halfWidth, halfHeight, ObstacleShape.BOX, ObstacleRule.SOLID, visual);
    }

    private static Obstacle worldCapsule(float x, float y, float halfWidth, float halfHeight, int visual) {
        return new Obstacle(x, y, 0f, halfWidth, halfHeight, ObstacleShape.CAPSULE, ObstacleRule.SOLID, visual);
    }

    private static Obstacle o(float x, float y, float radius, int visual) {
        return new Obstacle(scale(x), scale(y), scale(radius), 0f, 0f, ObstacleShape.CIRCLE, ObstacleRule.SOLID, visual);
    }

    private static Obstacle box(float x, float y, float halfWidth, float halfHeight, int visual) {
        return new Obstacle(scale(x), scale(y), 0f, scale(halfWidth), scale(halfHeight), ObstacleShape.BOX, ObstacleRule.SOLID, visual);
    }

    private static Obstacle capsule(float x, float y, float halfWidth, float halfHeight, int visual) {
        return new Obstacle(scale(x), scale(y), 0f, scale(halfWidth), scale(halfHeight), ObstacleShape.CAPSULE, ObstacleRule.SOLID, visual);
    }

    private static Trap t(float x, float y, float radius, float telegraph, float arming, float active,
                          float recovery, float damage, int visual) {
        x = scale(x);
        y = scale(y);
        float offset = (x * 0.013f + y * 0.007f + visual * 0.37f) % 1.7f;
        if (offset < 0) offset += 1.7f;
        return new Trap(x, y, scale(radius), 0f, 0f, scale(22f), telegraph, arming, active, recovery,
                damage, visual, offset);
    }

    private static Trap lane(float x, float y, float halfWidth, float halfHeight,
                             float telegraph, float arming, float active, float recovery, float damage, int visual) {
        x = scale(x);
        y = scale(y);
        float offset = (x * 0.013f + y * 0.007f + visual * 0.37f) % 1.7f;
        if (offset < 0) offset += 1.7f;
        return new Trap(x, y, 0f, scale(halfWidth), scale(halfHeight),
                scale(22f), telegraph, arming, active, recovery, damage, visual, offset);
    }

    private static Trap worldTrap(float x, float y, float radius, float telegraph, float arming, float active,
                                  float recovery, float damage, int visual) {
        float offset = (x * 0.013f + y * 0.007f + visual * 0.37f) % 1.7f;
        if (offset < 0) offset += 1.7f;
        return new Trap(x, y, radius, 0f, 0f, 22f, telegraph, arming, active, recovery, damage, visual, offset);
    }

    private static float scale(float value) {
        return value * ASSET_WORLD_SCALE;
    }

    private static Terrain terrain(String label, float x, float y, float radius,
                                   float movementMultiplier, int visual) {
        return new Terrain(label, scale(x), scale(y), scale(radius), movementMultiplier, visual);
    }

    private static Terrain worldTerrain(String label, float x, float y, float radius,
                                        float movementMultiplier, int visual) {
        return new Terrain(label, x, y, radius, movementMultiplier, visual);
    }

    private static boolean circleOverlapsBox(float px, float py, float targetRadius,
                                             float cx, float cy, float halfWidth, float halfHeight) {
        float nearestX = Math.max(cx - halfWidth, Math.min(cx + halfWidth, px));
        float nearestY = Math.max(cy - halfHeight, Math.min(cy + halfHeight, py));
        float dx = px - nearestX;
        float dy = py - nearestY;
        return dx * dx + dy * dy < targetRadius * targetRadius;
    }

    private static boolean circleOverlapsCapsule(float px, float py, float targetRadius,
                                                 float ax, float ay, float bx, float by, float capsuleRadius) {
        float sx = bx - ax;
        float sy = by - ay;
        float len2 = sx * sx + sy * sy;
        float t = len2 <= 1e-4f ? 0f : ((px - ax) * sx + (py - ay) * sy) / len2;
        t = Math.max(0f, Math.min(1f, t));
        float dx = px - (ax + sx * t);
        float dy = py - (ay + sy * t);
        float rr = capsuleRadius + targetRadius;
        return dx * dx + dy * dy < rr * rr;
    }
}
