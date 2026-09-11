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
                    box(205, -100, 45, 32, 1)
            },
            new Trap[] { t(-210, 40, 94, 0.9f, 1.2f, 4.0f, 12f, 0), t(260, -5, 94, 0.9f, 1.2f, 4.0f, 12f, 0) },
            new Terrain[] {
                    terrain("流沙", -210, 40, 94, 0.68f, 0), terrain("流沙", 260, -5, 94, 0.68f, 0)
            }),

    LAVA_DUNGEON("熔岩地牢", "动态危险", "裂缝喷火会周期性封锁走位",
            new Obstacle[] {
                    // 对齐 lava-dungeon.png：两座上方祭坛、两堆中央乱石和下方两段断墙。
                    o(-286, -92, 72, 3), o(282, -92, 72, 3), o(-143, -16, 54, 4),
                    o(141, -16, 54, 4), o(-285, 116, 64, 5), o(278, 116, 64, 5)
            },
            new Trap[] {
                    // 四处喷口分别落在背景中可见的熔岩喷焰上，不能留在中央石地。
                    t(-399, 118, 42, 0.8f, 1.0f, 3.1f, 18f, 1),
                    t(-211, 176, 42, 0.8f, 1.0f, 3.1f, 18f, 1),
                    t(213, 176, 42, 0.8f, 1.0f, 3.1f, 18f, 1),
                    t(416, 49, 42, 0.8f, 1.0f, 3.1f, 18f, 1)
            },
            new Terrain[] {
                    terrain("灼热裂隙", -399, 118, 58, 0.78f, 1), terrain("灼热裂隙", -211, 176, 58, 0.78f, 1),
                    terrain("灼热裂隙", 213, 176, 58, 0.78f, 1), terrain("灼热裂隙", 416, 49, 58, 0.78f, 1)
            }),

    STONE_CRYPT("古老石质遗迹", "机关控制", "地刺与箭矢机关会迫使你改变路线",
            new Obstacle[] {
                    // 对齐 stone-crypt.png 的接地底座：柱堆用圆，大碎石用横向胶囊，石棺用独立矩形。
                    o(-210, -150, 48, 6), o(205, -140, 52, 6), capsule(-175, 80, 75, 45, 7),
                    box(-325, 114, 26, 34, 8),
                    // 右下两口石棺共用同一块接地石座；合为一体，避免留下小于角色直径的假通道。
                    box(195, 110, 58, 34, 8)
            },
            new Trap[] {
                    // 两块地刺板与背景完全重合；第三个机关是两侧箭槽之间的横向箭道。
                    t(-285, -48, 45, 1.0f, 0.85f, 3.8f, 20f, 2),
                    t(286, -15, 48, 1.0f, 0.85f, 3.8f, 20f, 2),
                    lane(0, -55, 548, 24, 1.0f, 0.75f, 4.2f, 16f, 3)
            },
            new Terrain[] {
                    // 对齐左右两块地面符文：站上去可快速穿过箭道或绕过地刺。
                    terrain("疾行符文", -395, 59, 56, 1.22f, 2), terrain("疾行符文", 413, 57, 56, 1.22f, 2)
            });

    public enum ObstacleShape { CIRCLE, BOX, CAPSULE }

    /**
     * 静态阻挡物的底部轮廓。halfWidth / halfHeight 仅供 BOX 与 CAPSULE 使用；
     * CIRCLE 只使用 radius。三种形状会被角色移动与弹幕遮挡共用。
     */
    public record Obstacle(float x, float y, float radius, float halfWidth, float halfHeight,
                           ObstacleShape shape, int visual) {
        public float broadRadius() {
            return shape == ObstacleShape.CIRCLE ? radius
                    : (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
        }
        public float footprintHalfWidth() {
            return shape == ObstacleShape.CIRCLE ? radius : halfWidth;
        }
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

    /** phase offsets prevent every trap in a room from firing in lockstep. */
    public record Trap(float x, float y, float radius, float halfWidth, float halfHeight,
                       float telegraph, float active, float cooldown, float damage, int visual,
                       float phaseOffset) {
        public float cycle() { return telegraph + active + cooldown; }
        public float phase(float time) { return (time + phaseOffset) % cycle(); }
        public boolean telegraphing(float time) { return phase(time) < telegraph; }
        /** 箭道使用轴对齐矩形；其余机关维持圆形判定。 */
        public boolean isLane() { return halfWidth > 0f && halfHeight > 0f; }
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
            return p >= telegraph && p < telegraph + active;
        }
    }

    private final String displayName;
    private final String playStyle;
    private final String hazardHint;
    private final Obstacle[] obstacles;
    private final Trap[] traps;
    private final Terrain[] terrain;

    ArenaMap(String displayName, String playStyle, String hazardHint, Obstacle[] obstacles, Trap[] traps,
             Terrain[] terrain) {
        this.displayName = displayName;
        this.playStyle = playStyle;
        this.hazardHint = hazardHint;
        this.obstacles = obstacles;
        this.traps = traps;
        this.terrain = terrain;
    }

    public String displayName() { return displayName; }
    public String playStyle() { return playStyle; }
    public String hazardHint() { return hazardHint; }
    public Obstacle[] obstacles() { return obstacles.clone(); }
    public Trap[] traps() { return traps.clone(); }
    public Terrain[] terrain() { return terrain.clone(); }
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

    /** 原图内侧墙体所包围的可玩边界，保留边缘石墙作为不可进入的视觉缓冲。 */
    public float halfWidth() { return 784f; }
    public float halfHeight() { return 431f; }

    private static Obstacle o(float x, float y, float radius, int visual) {
        return new Obstacle(scale(x), scale(y), scale(radius), 0f, 0f, ObstacleShape.CIRCLE, visual);
    }

    private static Obstacle box(float x, float y, float halfWidth, float halfHeight, int visual) {
        return new Obstacle(scale(x), scale(y), 0f, scale(halfWidth), scale(halfHeight), ObstacleShape.BOX, visual);
    }

    private static Obstacle capsule(float x, float y, float halfWidth, float halfHeight, int visual) {
        return new Obstacle(scale(x), scale(y), 0f, scale(halfWidth), scale(halfHeight), ObstacleShape.CAPSULE, visual);
    }

    private static Trap t(float x, float y, float radius, float telegraph, float active,
                          float cooldown, float damage, int visual) {
        x = scale(x);
        y = scale(y);
        float offset = (x * 0.013f + y * 0.007f + visual * 0.37f) % 1.7f;
        if (offset < 0) offset += 1.7f;
        return new Trap(x, y, scale(radius), 0f, 0f, telegraph, active, cooldown, damage, visual, offset);
    }

    private static Trap lane(float x, float y, float halfWidth, float halfHeight,
                             float telegraph, float active, float cooldown, float damage, int visual) {
        x = scale(x);
        y = scale(y);
        float offset = (x * 0.013f + y * 0.007f + visual * 0.37f) % 1.7f;
        if (offset < 0) offset += 1.7f;
        return new Trap(x, y, 0f, scale(halfWidth), scale(halfHeight),
                telegraph, active, cooldown, damage, visual, offset);
    }

    private static float scale(float value) {
        return value * ASSET_WORLD_SCALE;
    }

    private static Terrain terrain(String label, float x, float y, float radius,
                                   float movementMultiplier, int visual) {
        return new Terrain(label, scale(x), scale(y), scale(radius), movementMultiplier, visual);
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
