package com.arcanebrigade.core;

/**
 * 全部可调数值集中在这里。调平衡只改这个文件，不要在逻辑代码里散落魔法数字。
 */
public final class Balance {

    private Balance() {}

    // ---- 巫师 ----
    public static final float WIZARD_SPEED   = 195f;
    public static final float WIZARD_RADIUS  = 14f;
    public static final float WIZARD_HP      = 100f;
    public static final float WIZARD_REGEN   = 0.6f;   // 每秒回血
    /** 受击无敌帧。没有这个，被几十只怪围住会在同一帧内被打光血，瞬间暴毙 */
    public static final float WIZARD_IFRAME  = 0.35f;
    /** 弓箭手无敌帧：比巫师略短（脆但快，靠走位躲） */
    public static final float ARCHER_IFRAME  = 0.30f;
    /** 战士无敌帧：明显削弱。战士有 15% 减伤 + 击杀回血，无需长时间无敌保护 */
    public static final float WARRIOR_IFRAME = 0.12f;

    // ---- 战士 / 弓箭手 / 召唤师（职业基础属性，设计文档第 2 节）----
    public static final float WARRIOR_HP    = 140f;
    public static final float WARRIOR_SPEED = 180f;
    public static final float ARCHER_HP     = 85f;
    public static final float ARCHER_SPEED  = 205f;
    /** 召唤师：本体偏脆——他有 4 只宠物替他挨打，本体再厚就没弱点了 */
    public static final float SUMMONER_HP    = 90f;
    public static final float SUMMONER_SPEED = 185f;
    public static final float SUMMONER_IFRAME = 0.32f;

    // ---- 职业特性（集中在这里，不在逻辑里散落）----
    /** 战士：受伤减免 15% */
    public static final float WARRIOR_DR        = 0.15f;
    /** 战士：每次击杀回 2 HP */
    public static final float WARRIOR_LIFESTEAL = 2f;
    /** 弓箭手：暴击率 +10% */
    public static final float ARCHER_CRIT       = 0.10f;
    /** 巫师：法术伤害 +10% */
    public static final float WIZARD_SPELL_DMG  = 0.10f;

    // ---- 召唤物（召唤师的宠物）----
    /** 召唤间隔（秒）与每次召唤的数量。到点重新召唤一批，旧的被替换 */
    public static final float SUMMON_INTERVAL = 10f;
    public static final int   SUMMON_COUNT    = 4;
    /** 宠物血 = 当前时间点的普通小怪血 × 该系数（用户要求 2 倍） */
    public static final float MINION_HP_MUL       = 2f;
    public static final float MINION_RADIUS       = 10f;
    public static final float MINION_SPEED        = 215f;
    public static final float MINION_DAMAGE       = 16f;
    public static final float MINION_ATTACK_CD    = 0.6f;
    /** 宠物受击无敌帧：比玩家短，但足以避免在怪堆里被同一帧打光 */
    public static final float MINION_IFRAME       = 0.18f;
    /** 活动范围：离召唤师超过这个距离就被强制拉回（用户要求"只能在身边一定范围活动"） */
    public static final float MINION_LEASH        = 330f;
    /** 护主：主人在这么近的范围内有敌人时，优先扑上去 */
    public static final float MINION_GUARD_RANGE  = 260f;
    /** 指挥：鼠标点击后，在点击点这么大范围内找敌人扑过去 */
    public static final float MINION_ORDER_RANGE  = 460f;
    /** 指挥有效期（秒）。按住鼠标会持续刷新，松手后还能生效这么久 */
    public static final float MINION_ORDER_TIME   = 4f;
    /** 无敌人时跟随主人保持的距离 */
    public static final float MINION_FOLLOW_DIST  = 62f;
    /**
     * 敌人索敌时对宠物的距离偏置（>1）。
     * 宠物要能"护主"拦住怪，但不能把仇恨全抢走——
     * 否则玩家站在后面看戏，召唤师就变成挂机职业了。
     */
    public static final float MINION_THREAT_BIAS  = 1.35f;

    // ---- 元素状态 ----
    // 具体数值（DoT 强度、减速、时长）写在 Spells 表里的每个法术上，
    // 这里只放"全局上限/规则"类数值，避免同一个概念在两处可调。
    /** 冰霜减速的硬上限。多个冰系法术叠加也不能把怪冻成静止，否则可以无限风筝 */
    public static final float ELEM_FROST_MAX_SLOW = 0.60f;
    /** 附着雷电的目标，受到的所有伤害 +20% */
    public static final float ELEM_SHOCK_DMG_AMP  = 0.20f;
    /** 剧毒每秒伤害上限 */
    public static final float ELEM_POISON_MAX_DPS = 30f;
    /** 秘法元素命中时的直接加伤 */
    public static final float ARCANE_DAMAGE_BONUS = 0.20f;

    // ---- 元素反应 ----
    /** 蒸汽爆发（燃烧+冰霜）：范围伤害 + 致盲 */
    public static final float REACTION_STEAM_DAMAGE = 40f;
    public static final float REACTION_STEAM_RADIUS = 80f;
    public static final float REACTION_STEAM_STUN   = 1.0f;

    /** 超导（冰冻+雷电）：按触发这一次命中的伤害百分比追加，并眩晕 */
    public static final float REACTION_SUPERCONDUCT_RATIO = 0.60f;
    public static final float REACTION_SUPERCONDUCT_STUN  = 1.0f;

    /** 过载（燃烧+雷电）：爆炸并把周围敌人推开 */
    public static final float REACTION_OVERLOAD_DAMAGE    = 25f;
    public static final float REACTION_OVERLOAD_RADIUS    = 70f;
    public static final float REACTION_OVERLOAD_KNOCKBACK = 260f;

    /** 击退速度的每秒衰减系数。太小会看到怪被推着滑行很远 */
    public static final float KNOCKBACK_DECAY = 6.0f;

    // ---- 敌人 ----
    public static final float ENEMY_RADIUS   = 12f;
    /** 明显低于玩家的 195，保证"能逃但甩不干净"——低于 70 就会出现追不上的滑稽场面 */
    public static final float ENEMY_SPEED    = 92f;
    /** 碰撞查询时的最大目标半径，投射物搜索范围要按它放宽，否则边缘擦过会漏判 */
    public static final float MAX_TARGET_RADIUS = 16f;
    public static final float ENEMY_HP       = 26f;
    public static final float ENEMY_DAMAGE   = 9f;
    public static final float ENEMY_ATTACK_CD = 0.7f;
    /** 分离力强度，相对移动速度。太大会散开成稀粥，太小会叠成一支穿云箭 */
    public static final float ENEMY_SEPARATION = 0.75f;

    // ---- 刷怪（本轮"减量"：数量压下来，难度交给下面的血量成长曲线）----
    /** 场上敌人硬上限 */
    public static final int   MAX_ENEMIES    = 220;
    /** 软上限：开局场上只留这么多，随时间缓慢放开。看到的怪少了，但每只更硬 */
    public static final int   SOFT_CAP_BASE     = 35;
    public static final float SOFT_CAP_GROWTH   = 0.16f;   // 每秒增加的上限
    public static final float SPAWN_BASE_RATE = 1.25f;  // 开局每秒刷几只
    public static final float SPAWN_RAMP      = 0.0085f; // 每秒递增
    /** 刷怪速率上限。不封顶的话 20 分钟时会变成几百只/秒的洪水 */
    public static final float SPAWN_RATE_CAP  = 7.0f;
    public static final float SPAWN_RING_IN   = 760f;   // 生成环内半径（屏幕外一点）
    public static final float SPAWN_RING_OUT  = 900f;
    public static final float DESPAWN_RANGE   = 1500f; // 超出这个距离直接回收

    // ---- 敌人血量随时间成长（本轮调整：削弱后期成长，让小怪别指数变硬）----
    /** 线性项：每秒 +1.2%（原 1.8%） */
    public static final float ENEMY_HP_GROWTH_LINEAR = 0.012f;
    /** 二次项：后期轻微加速（原 0.000010，削弱后几乎线性） */
    public static final float ENEMY_HP_GROWTH_QUAD   = 0.000003f;
    /** 成长上限。削弱到 16x，避免后期出现打不动的肉墙 */
    public static final float ENEMY_HP_SCALE_CAP     = 16f;

    /**
     * 敌人血量 = 基础血 × 本系数（t = 游戏时间秒）。
     * 参考值（削弱后）：300s≈4.9x、600s≈9.3x、900s≈14.2x、1200s≈16x（封顶）。
     * 调难度改上面三个常量即可，公式集中在这里，不在逻辑里散落。
     */
    public static float enemyHpScale(float t) {
        float s = 1f + t * ENEMY_HP_GROWTH_LINEAR + t * t * ENEMY_HP_GROWTH_QUAD;
        return s > ENEMY_HP_SCALE_CAP ? ENEMY_HP_SCALE_CAP : s;
    }

    // ---- 战斗基础 ----
    /** 暴击伤害倍率基准。致命一击 +35% 是在这个基础上加 */
    public static final float BASE_CRIT_DMG        = 1.5f;
    /** 追踪箭（弓箭手）：每帧最大转向弧度与搜索半径 */
    public static final float HOMING_RANGE = 380f;
    public static final float HOMING_TURN  = 0.16f;   // 约 9°/帧
    public static final float MAX_CRIT_CHANCE      = 0.85f;
    /** 减伤硬上限。不封顶后期能堆到免疫，游戏就没了 */
    public static final float MAX_DAMAGE_REDUCTION = 0.70f;

    // ---- 质变阈值（DESIGN.md "质变阈值"一节）----
    /** 元素共鸣：每装备一种元素，全伤害 +5% */
    public static final float RESONANCE_PER_ELEMENT = 0.05f;
    /** 孤注一掷：只带 1 个主动时的伤害加成 */
    public static final float ALL_IN_DAMAGE         = 0.80f;
    /** 临界质量：穿透达到多少触发 */
    public static final int   CRITICAL_MASS_PIERCE  = 5;
    public static final float CRITICAL_MASS_DAMAGE  = 0.50f;
    /** 弹幕之王：每秒投射物数阈值（不是单次齐射数——3 槽齐射最多 7 发，阈值 8 会永远够不到） */
    public static final float BARRAGE_RATE_THRESHOLD = 8f;
    public static final float BARRAGE_ATTACK_SPEED   = 0.25f;
    /** 连锁反应：元素反应范围加成 */
    public static final float CHAIN_REACTION_RADIUS  = 0.50f;
    /** 元素过载：触发反应时的伤害加成 */
    public static final float ELEM_OVERLOAD_DAMAGE   = 0.30f;

    /** 冰锥穿透达阈值后命中分裂几枚 */
    public static final int   ICE_SPLIT_PIERCE  = 5;
    public static final int   ICE_SPLIT_COUNT   = 3;
    public static final float ICE_SPLIT_SPREAD  = (float) Math.toRadians(70);
    /** 连锁闪电弹射达阈值后留下电网 */
    public static final int   CHAIN_NET_CHAIN     = 6;
    public static final float CHAIN_NET_DURATION  = 2f;
    public static final float CHAIN_NET_RADIUS    = 90f;
    public static final float CHAIN_NET_DPS       = 12f;
    /** 跳弹：命中后转向附近敌人的搜索半径 */
    public static final float RICOCHET_RANGE = 260f;

    // ---- 拾取物 ----
    public static final float PICKUP_RADIUS   = 60f;
    public static final float GEM_RADIUS      = 5f;
    public static final float GEM_VALUE       = 1f;
    /** 宝石存在时间。留够长，避免玩家走回头路时地上的经验已经没了 */
    public static final float GEM_LIFE        = 90f;
    /** 进入拾取范围后的吸附速度 */
    public static final float GEM_MAGNET_SPEED = 340f;
    public static final int   MAX_PICKUPS      = 600;

    // ---- 由主动改造而来的被动 ----
    public static final float ORBITING_DAMAGE   = 8f;
    public static final float ORBITING_INTERVAL = 0.4f;
    public static final float ORBITING_RADIUS   = 90f;

    /** 毒雾：5 伤害每 0.5 秒 = 10 dps，持续 4 秒 */
    public static final float POISON_TRAIL_DPS       = 10f;
    public static final float POISON_TRAIL_RADIUS    = 55f;
    public static final float POISON_TRAIL_DURATION  = 4f;
    /** 移动时每隔多久铺一团毒雾 */
    public static final float POISON_TRAIL_INTERVAL  = 0.35f;
    public static final float POISON_TRAIL_DISTANCE  = 24f;

    public static final float WARCRY_INTERVAL     = 25f;
    public static final float WARCRY_SHIELD       = 30f;
    public static final float WARCRY_RADIUS       = 140f;
    public static final float WARCRY_KNOCKBACK    = 220f;

    public static final float BARRIER_INTERVAL = 20f;
    public static final float BARRIER_SHIELD   = 15f;
    public static final float CHEST_INTERVAL   = 60f;
    public static final float REROLL_INTERVAL  = 20f;

    /** 钉刺陷阱：每走多远布一个 */
    public static final float SPIKE_DISTANCE  = 220f;
    public static final float SPIKE_DAMAGE    = 30f;
    public static final float SPIKE_STUN      = 1f;
    public static final float SPIKE_RADIUS    = 40f;
    public static final float SPIKE_LIFE      = 20f;

    /** 绝境爆发 */
    public static final float LAST_STAND_HP_RATIO  = 0.30f;
    public static final float LAST_STAND_MOVE      = 0.20f;
    public static final float LAST_STAND_DAMAGE    = 0.15f;

    /** 引爆（中毒+燃烧）：按剩余毒伤的多少比例立即结算 */
    public static final float DETONATE_RATIO = 1.0f;

    // ---- 场景地图（D4）----
    /** 一局 20 分钟，分成 N 个阶段，每个阶段一个主题 */
    public static final int   STAGE_COUNT     = 4;
    /** 每阶段时长（秒），总时长 = 求和。当前 4 × 300 = 1200s = 20 分钟 */
    public static final float[] STAGE_DURATIONS = { 300f, 300f, 300f, 300f };

    // ---- 障碍物（D4，用户明确要求场景必须含障碍；本轮"增多变大"）----
    /** 每个场景生成的障碍数量范围（增多：26–40 个，地图更"满"） */
    public static final int   OBSTACLE_COUNT_MIN = 26;
    public static final int   OBSTACLE_COUNT_MAX = 40;
    /** 障碍半径范围（变大：26–72，出现明显的巨石/墙体感） */
    public static final float OBSTACLE_R_MIN = 26f;
    public static final float OBSTACLE_R_MAX = 72f;
    /** 玩家周围的安全圈：障碍不会生成在这里，避免出生即卡死 */
    public static final float OBSTACLE_SAFE_RADIUS = 200f;
    /** 障碍散布的最大半径（围绕玩家）。超出这圈在可视范围外没有意义 */
    public static final float OBSTACLE_SPREAD = 1350f;
    /** 碰撞查询时障碍的最大半径，障碍碰撞查询范围按它放宽 */
    public static final float OBSTACLE_MAX_R = 72f;

    // ---- 敌人变体（D4）----
    /** 精英：体型 ×6、速度 ×1.1、伤害 ×1.5，带一层护盾 */
    public static final float ELITE_HP_MUL    = 6f;
    public static final float ELITE_SPEED_MUL = 1.1f;
    public static final float ELITE_DMG_MUL   = 1.5f;
    public static final float ELITE_SHIELD    = 120f;
    /** 小偷：偷地上的经验宝石，自身不攻击。击杀时掉落翻倍的宝石 */
    public static final float THIEF_HP     = 40f;
    public static final float THIEF_SPEED  = 130f;
    public static final float THIEF_STEAL_RADIUS = 26f;   // 接触宝石即偷走的范围
    /** 分裂怪：死亡时裂成几只，子代 HP 按比例缩小 */
    public static final int   SPLIT_COUNT   = 3;
    public static final float SPLIT_HP_MUL  = 0.45f;
    public static final float SPLIT_RADIUS_MUL = 0.8f;
    /** 远程怪：保持距离并向玩家发射弹幕 */
    public static final float RANGED_HP       = 34f;
    public static final float RANGED_SPEED    = 78f;
    public static final float RANGED_DMG      = 14f;
    public static final float RANGED_CD       = 2.2f;
    public static final float RANGED_BOLT_SPD = 300f;
    public static final float RANGED_RANGE    = 460f;
    public static final float RANGED_KEEP_DIST = 280f;   // 保持的最小距离，太近就后退

    // ---- Boss（本轮正式引入：每个阶段末尾一只，一局共 4 只）----
    /**
     * 登场触发等级：玩家升到这些等级时刷对应那只 Boss。
     * 用等级而不是时间，是因为等级直接反映 build 强度——
     * 同样的时间点，一个吃满经验的玩家和一个挂机的玩家该面对的 Boss 强度不该一样。
     * 到等级但上一只还活着时不会叠加，会等它倒下再上（见 WaveDirector）。
     */
    public static final int[]   BOSS_LEVELS   = { 5, 10, 15, 20 };
    public static final String[] BOSS_NAMES   = { "石心巨像", "熔岩领主", "霜寂君王", "终焉之影" };
    /** 每只 Boss 的血池。第一只别太肉，5 分钟时的 build 打得动 */
    public static final float[] BOSS_HP_TIERS = { 2000f, 4200f, 7200f, 13000f };
    /** 每只 Boss 的接触伤害 */
    public static final float[] BOSS_DMG_TIERS = { 18f, 22f, 26f, 32f };
    /** Boss 在场时普通刷怪速率的倍率：把舞台让给 Boss 战 */
    public static final float BOSS_SPAWN_SUPPRESS = 0.35f;
    /** Boss 生成距离。比普通刷怪环近，确保玩家能看见它压过来 */
    public static final float BOSS_SPAWN_DIST     = 520f;

    /** 兼容旧调用的基础血池，实际以 BOSS_HP_TIERS 为准 */
    public static final float BOSS_HP         = 9000f;
    public static final float BOSS_SPEED      = 52f;
    public static final float BOSS_RADIUS     = 46f;
    public static final float BOSS_DMG        = 22f;
    public static final float BOSS_ATTACK_CD  = 0.9f;
    /** 阶段切换的血量阈值（占总血量比例） */
    public static final float BOSS_PHASE2_HP  = 0.66f;
    public static final float BOSS_PHASE3_HP  = 0.33f;
    /** 预警圈：先在地上画圈 telegraph 秒，然后爆炸，伤害玩家与敌人 */
    public static final float WARNING_TELEGRAPH = 1.1f;
    public static final float WARNING_RADIUS    = 95f;
    public static final float WARNING_DAMAGE    = 38f;
    public static final float WARNING_KNOCKBACK = 220f;
    /** 召唤：每 interval 秒在自身周围召唤 count 只小怪 */
    public static final float BOSS_SUMMON_INTERVAL = 6f;
    public static final int   BOSS_SUMMON_COUNT    = 4;

    // ---- 战士冲刺（每 5 秒一次短距无敌冲刺） ----
    /** 冷却：两次冲刺之间至少隔这么久 */
    public static final float DASH_CD = 5f;
    /** 单次冲刺持续时间（秒），也是无敌帧时长 */
    public static final float DASH_DURATION = 0.22f;
    /** 冲刺速度（单位/秒），乘持续时间 ≈ 123 单位，属"短程" */
    public static final float DASH_SPEED = 560f;

    // ---- 战斗事件（小任务） ----
    /** 三个事件的触发时间（秒）：2 分钟 / 5 分钟 / 8 分钟 */
    public static final float[] EVENT_TIMES  = { 120f, 300f, 480f };
    public static final String[] EVENT_NAMES = { "封印裂隙", "摧毁雕像", "采集蘑菇" };
    /** 各事件完成后的经验奖励：按当前升级所需经验的倍率发放（越靠后越丰厚） */
    public static final float[] EVENT_XP_MUL = { 0.8f, 1.2f, 1.8f };
    /** 事件类型 id（World.eventType() 返回） */
    public static final int EVENT_RIFT = 1;
    public static final int EVENT_STATUE = 2;
    public static final int EVENT_MUSHROOM = 3;
    /** 封印裂隙：圈半径 + 需要在圈内累计坚持的秒数 */
    public static final float RIFT_RADIUS     = 140f;
    public static final float RIFT_HOLD_TIME  = 18f;
    /** 摧毁雕像：数量 + 单只基础血量（再乘时间成长曲线） */
    public static final int   STATUE_COUNT    = 3;
    public static final float STATUE_HP       = 220f;
    public static final float STATUE_RADIUS   = 26f;
    /** 采集蘑菇：数量 + 存活时长（秒） */
    public static final int   MUSHROOM_COUNT  = 8;
    public static final float MUSHROOM_LIFE   = 120f;
    /**
     * 蘑菇直接撒在玩家周围的一圈上（内/外半径），而不是撒在事件中心外——
     * 事件中心本身离玩家就有 260~520，再往外散布最远的一朵能到 900+ 单位，
     * 玩家满地图乱撞也找不齐。角度按等分 + 轻微抖动，避免几朵叠在一起。
     */
    public static final float MUSHROOM_SPAWN_MIN = 130f;
    public static final float MUSHROOM_SPAWN_MAX = 300f;
    /** 蘑菇的吸附半径，比普通经验宝石大一圈——任务道具不该考验走位精度 */
    public static final float MUSHROOM_PICKUP_RADIUS = 110f;

    // ---- 5 关 Boss：奶蛙（玩家等级达到 25 级时从场地中央刷新，独立技能组）----
    /** 刷新条件：玩家等级达到该值 */
    public static final int   MILKY_LEVEL         = 25;
    public static final float MILKY_HP            = 8000f;
    public static final float MILKY_SPEED         = 220f;
    public static final float MILKY_RADIUS        = 48f;
    /** 接触伤害（技能伤害另算） */
    public static final float MILKY_DMG           = 18f;
    /** 靠近玩家到这个距离才起手放技能 */
    public static final float MILKY_TRIGGER_RANGE = 300f;
    /** 技能一 · 蓄力踩地：以自身为中心的整圆，半径与技能二相同，伤害 50（施法 1.5s） */
    public static final float MILKY_STOMP_RANGE   = 253f;
    public static final float MILKY_STOMP_DMG     = 50f;
    public static final float MILKY_STOMP_CD      = 3.0f;
    public static final float MILKY_STOMP_CAST    = 1.5f;
    /** 技能二 · 捧腹大笑：半血以下才会用，圆形范围（比初版缩小 1/3）、伤害 100 */
    public static final float MILKY_LAUGH_RANGE   = 253f;
    public static final float MILKY_LAUGH_DMG     = 100f;
    public static final float MILKY_LAUGH_CD      = 6.0f;
    public static final float MILKY_LAUGH_CAST    = 2.0f;
    /** 触发大笑的血量比例（低于此值才会大笑） */
    public static final float MILKY_LAUGH_HP      = 0.5f;

    // ---- 世界 ----
    public static final float FIXED_STEP     = 1f / 60f;
    /** 世界边界半宽：地图是 [-WORLD_HALF, +WORLD_HALF] 的方形区域 */
    public static final float WORLD_HALF     = 1600f;
    /** 棕色城墙厚度：城墙（棕色部分）作为地图边界，其内侧边缘才是可走区域 */
    public static final float WALL_THICKNESS = 56f;
    /** 可玩区半宽 = 城墙内侧边缘。玩家/敌人/刷怪点都钳制在此，碰不到棕色城墙 */
    public static final float PLAY_HALF      = WORLD_HALF - WALL_THICKNESS;
}
