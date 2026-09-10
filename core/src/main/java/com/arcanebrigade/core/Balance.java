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

    // ---- 敌人 / 刷怪 / 变体 / Boss ----
    // 全部敌怪数值见 com.arcanebrigade.core.enemy.EnemyStats，
    // 敌怪行为见同包 EnemyAI，刷怪节奏见同包 WaveDirector。

    // ---- 世界 ----
    public static final float FIXED_STEP     = 1f / 60f;
    /** 世界边界半宽：地图是 [-WORLD_HALF, +WORLD_HALF] 的方形区域 */
    public static final float WORLD_HALF     = 1600f;
    /** 棕色城墙厚度：城墙（棕色部分）作为地图边界，其内侧边缘才是可走区域 */
    public static final float WALL_THICKNESS = 56f;
    /** 可玩区半宽 = 城墙内侧边缘。玩家/敌人/刷怪点都钳制在此，碰不到棕色城墙 */
    public static final float PLAY_HALF      = WORLD_HALF - WALL_THICKNESS;
}
