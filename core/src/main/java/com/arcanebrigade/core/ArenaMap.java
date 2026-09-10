package com.arcanebrigade.core;

/**
 * Author-authored single-screen combat rooms.  Layout data lives in core so collision,
 * projectile blocking and trap damage always agree with the client presentation.
 */
public enum ArenaMap {
    DESERT_RUINS("荒漠遗迹", "开阔机动", "流沙漩涡会减速并造成擦伤",
            new Obstacle[] {
                    o(-520, -250, 78, 0), o(470, -260, 94, 0), o(-360, 180, 62, 1),
                    o(360, 200, 72, 1), o(0, 330, 58, 2), o(-40, -80, 46, 2)
            },
            new Trap[] { t(-210, 40, 94, 0.9f, 1.2f, 4.0f, 12f, 0), t(260, -5, 94, 0.9f, 1.2f, 4.0f, 12f, 0) }),

    LAVA_DUNGEON("熔岩地牢", "动态危险", "裂缝喷火会周期性封锁走位",
            new Obstacle[] {
                    o(-470, -215, 86, 3), o(470, -215, 86, 3), o(-290, 175, 62, 4),
                    o(280, 175, 62, 4), o(0, -45, 70, 5), o(0, 300, 48, 4)
            },
            new Trap[] { t(-250, -20, 78, 0.8f, 1.0f, 3.1f, 18f, 1), t(250, -20, 78, 0.8f, 1.0f, 3.1f, 18f, 1), t(0, 205, 74, 0.8f, 1.0f, 3.1f, 18f, 1) }),

    STONE_CRYPT("古老石质遗迹", "机关控制", "地刺与箭矢机关会迫使你改变路线",
            new Obstacle[] {
                    o(-425, -180, 74, 6), o(410, -180, 74, 6), o(-290, 205, 66, 7),
                    o(270, 210, 66, 7), o(0, -35, 50, 8), o(0, 305, 45, 8)
            },
            new Trap[] { t(-210, -10, 68, 1.0f, 0.85f, 3.8f, 20f, 2), t(220, -10, 68, 1.0f, 0.85f, 3.8f, 20f, 2), t(0, 165, 62, 1.0f, 0.85f, 3.8f, 20f, 2) });

    public record Obstacle(float x, float y, float radius, int visual) {}

    /** phase offsets prevent every trap in a room from firing in lockstep. */
    public record Trap(float x, float y, float radius, float telegraph, float active,
                       float cooldown, float damage, int visual, float phaseOffset) {
        public float cycle() { return telegraph + active + cooldown; }
        public float phase(float time) { return (time + phaseOffset) % cycle(); }
        public boolean telegraphing(float time) { return phase(time) < telegraph; }
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

    ArenaMap(String displayName, String playStyle, String hazardHint, Obstacle[] obstacles, Trap[] traps) {
        this.displayName = displayName;
        this.playStyle = playStyle;
        this.hazardHint = hazardHint;
        this.obstacles = obstacles;
        this.traps = traps;
    }

    public String displayName() { return displayName; }
    public String playStyle() { return playStyle; }
    public String hazardHint() { return hazardHint; }
    public Obstacle[] obstacles() { return obstacles.clone(); }
    public Trap[] traps() { return traps.clone(); }
    /** 与 1280×720 的固定战斗镜头对齐，留出边缘墙体和安全缓冲。 */
    public float halfWidth() { return 600f; }
    public float halfHeight() { return 330f; }

    private static Obstacle o(float x, float y, float radius, int visual) {
        return new Obstacle(x, y, radius, visual);
    }

    private static Trap t(float x, float y, float radius, float telegraph, float active,
                          float cooldown, float damage, int visual) {
        float offset = (x * 0.013f + y * 0.007f + visual * 0.37f) % 1.7f;
        if (offset < 0) offset += 1.7f;
        return new Trap(x, y, radius, telegraph, active, cooldown, damage, visual, offset);
    }
}
