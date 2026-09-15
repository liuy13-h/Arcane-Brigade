package com.arcanebrigade.core;

/**
 * 全部可调数值集中在这里。调平衡只改这个文件，不要在逻辑代码里散落魔法数字。
 */
public final class Balance {

    private Balance() {}

    // ---- 法师 ----
    public static final float WIZARD_SPEED   = 200f;
    public static final float WIZARD_RADIUS  = 14f;
    public static final float WIZARD_HP      = 200f;
    public static final float WIZARD_REGEN   = 0.6f;   // 每秒回血
    /** 受击无敌帧。没有这个，被几十只怪围住会在同一帧内被打光血，瞬间暴毙 */
    public static final float WIZARD_IFRAME  = 0.35f;
    /** 弓箭手无敌帧：比法师略短（脆但快，靠走位躲） */
    public static final float ARCHER_IFRAME  = 0.30f;

    // ---- 主动位移（冲刺）：弓箭手 & 战士通用，空格朝鼠标方向突进一小段 ----
    // 战士本就是该技能的原始设计目标（空格=冲刺，单次 5 秒 CD），弓箭手复用同一机制
    // 并**额外支持储存次数**——一次最多攒 3 发，每发独立 5 秒 CD，耗光后会一颗颗补回来。
    // 这种"弹药"模型让弓箭手可以做连击位移，战士仍是单次 CD，两套手感不同。
    /** 弓箭手位移冷却（秒）——同时是单发的 CD 与一颗新充能的恢复时长 */
    public static final float ARCHER_DASH_CD     = 6.5f;
    /** 弓箭手位移总距离（像素）。一小段，约 0.16 秒走完 */
    public static final float ARCHER_DASH_DIST   = 160f;
    /** 弓箭手位移持续时长（秒）。speed = DIST / TIME ≈ 1000 px/s，像一个快速突进 */
    public static final float ARCHER_DASH_TIME   = 0.16f;
    /** 弓箭手位移期间的无敌帧（秒），让位移能真正用来躲弹幕/接触伤害 */
    public static final float ARCHER_DASH_IFRAME = 0.16f;
    /** 弓箭手可同时储存的冲刺发数。出生即满；空格一发一发地扣，每 CD 5s 补一发 */
    public static final int   ARCHER_DASH_MAX    = 3;

    /** 战士位移冷却（秒）。与弓箭手同步，可独立调 */
    public static final float WARRIOR_DASH_CD     = 5f;
    /** 战士位移总距离（像素） */
    public static final float WARRIOR_DASH_DIST   = 160f;
    /** 战士位移持续时长（秒） */
    public static final float WARRIOR_DASH_TIME   = 0.16f;
    /** 战士位移期间的无敌帧（秒） */
    public static final float WARRIOR_DASH_IFRAME = 0.16f;

    // ---- 战士 · 蓄力重击（长按鼠标左键蓄力，松开释放）----
    /**
     * 蓄力判定：按住左键累计到该时长（秒）才视为"蓄力"，此时近战扇形不再自动触发，
     * 改为抬起时释放一次重击。短于此值即普通挥砍（保持原有手感）。
     */
    public static final float WARRIOR_CHARGE_MIN      = 0.25f;
    /** 蓄满所需时长（秒）。超过按满算，不会无限增强 */
    public static final float WARRIOR_CHARGE_MAX      = 1.00f;
    /** 满蓄力时扇形半径倍率（斩击范围放大） */
    public static final float WARRIOR_CHARGE_RADIUS   = 2.00f;
    /** 满蓄力时扇形张角倍率（斩击更宽） */
    public static final float WARRIOR_CHARGE_ANGLE    = 1.50f;
    /** 满蓄力时伤害倍率 */
    public static final float WARRIOR_CHARGE_DAMAGE   = 2.60f;
    /**
     * 满蓄力重击的击退倍率。战士重击是全游戏击退最强的一击——
     * 普通命中只有 HIT_KNOCKBACK 的小幅推挤，重击则能把怪掀出去。
     */
    public static final float WARRIOR_CHARGE_KNOCKBACK_MUL = 3.20f;
    /** 战士无敌帧：明显削弱。战士有 15% 减伤 + 击杀回血，无需长时间无敌保护 */
    public static final float WARRIOR_IFRAME = 0.12f;

    // ---- 战士 / 弓箭手 / 召唤师（职业基础属性，设计文档第 2 节）----
    public static final float WARRIOR_HP    = 280f;
    public static final float WARRIOR_SPEED = 190f;
    public static final float ARCHER_HP     = 160f;
    public static final float ARCHER_SPEED  = 230f;
    /** 召唤师：本体偏脆——他有 4 只宠物替他挨打，本体再厚就没弱点了 */
    public static final float SUMMONER_HP    = 180f;
    public static final float SUMMONER_SPEED = 180f;
    public static final float SUMMONER_IFRAME = 0.32f;

    // ---- 职业特性（集中在这里，不在逻辑里散落）----
    /** 战士：受伤减免 15% */
    public static final float WARRIOR_DR        = 0.15f;
    /** 战士：每次击杀回 2 HP */
    public static final float WARRIOR_LIFESTEAL = 2f;
    /** 弓箭手：暴击率 +10% */
    public static final float ARCHER_CRIT       = 0.10f;
    /** 法师：法术伤害 +10% */
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

    // ---- 命中击退（所有攻击对敌怪的小幅推挤）----
    /**
     * 每次命中敌人时附带的基准击退速度（像素/秒）。
     * 刻意做小：只是让打击"有手感"，不该把怪打出攻击范围。
     * 法术自带的 knockback（盾击/裂地）叠在这个基准之上；战士蓄力重击再乘倍率。
     */
    public static final float HIT_KNOCKBACK = 70f;
    /** 弹幕命中的击退衰减（远程推挤比近战更轻，避免弓箭手/巫师把怪推出弹道） */
    public static final float HIT_KNOCKBACK_RANGED_MUL = 0.55f;

    // ---- 敌人 ----
    public static final float ENEMY_RADIUS   = 12f;
    /** 明显低于玩家的 195，保证"能逃但甩不干净"——低于 70 就会出现追不上的滑稽场面 */
    public static final float ENEMY_SPEED    = 92f;
    /** 碰撞查询时的最大目标半径，投射物搜索范围要按它放宽，否则边缘擦过会漏判 */
    public static final float MAX_TARGET_RADIUS = 16f;
    public static final float ENEMY_HP       = 52f;   // 小怪血翻倍
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
    public static final float MAX_DAMAGE_REDUCTION = 0.60f;   // 减伤上限：达到后不再刷新减伤类被动
    /** Boss 的被控抗性：眩晕 / 冰冻 / 减速 / 击退 的效果按此削弱（0.7 = 只吃 30% 控制） */
    public static final float BOSS_CC_RESIST = 0.70f;

    // ---- 质变阈值（DESIGN.md "质变阈值"一节）----
    /** 元素共鸣：每装备一种元素，全伤害 +5% */
    public static final float RESONANCE_PER_ELEMENT = 0.10f;
    /** 孤注一掷：只带 1 个主动时的伤害加成 */
    public static final float ALL_IN_DAMAGE         = 1.50f;
    /** 孤注一掷：冷却 −50%（换算成攻速倍率 1/(1-0.5)） */
    public static final float ALL_IN_CD_REDUCTION   = 0.50f;
    /** 临界质量：穿透达到多少触发 */
    public static final int   CRITICAL_MASS_PIERCE  = 5;
    public static final float CRITICAL_MASS_DAMAGE  = 0.50f;
    /** 弹幕之王：每秒投射物数阈值（不是单次齐射数——3 槽齐射最多 7 发，阈值 8 会永远够不到） */
    public static final float BARRAGE_RATE_THRESHOLD = 8f;
    public static final float BARRAGE_ATTACK_SPEED   = 0.25f;
    /** 连锁反应：元素反应范围加成 */
    public static final float CHAIN_REACTION_RADIUS  = 0.50f;
    /** 元素过载：触发反应时的伤害加成 */
    public static final float ELEM_OVERLOAD_DAMAGE   = 0.60f;

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
    public static final float BARRIER_SHIELD   = 50f;
    public static final float CHEST_INTERVAL   = 60f;
    public static final float REROLL_INTERVAL  = 20f;

    /** 钉刺陷阱：每走多远布一个 */
    public static final float SPIKE_DISTANCE  = 220f;
    public static final float SPIKE_DAMAGE    = 30f;
    public static final float SPIKE_STUN      = 1f;
    public static final float SPIKE_RADIUS    = 40f;
    public static final float SPIKE_LIFE      = 20f;

    /** 绝境爆发 */
    public static final float LAST_STAND_HP_RATIO  = 0.50f;
    public static final float LAST_STAND_MOVE      = 0.40f;
    public static final float LAST_STAND_DAMAGE    = 0.50f;

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
    /** 最大障碍包围半径。蛇形战区的长残墙/掩体可到约 176，查询必须覆盖其完整底座。 */
    public static final float OBSTACLE_MAX_R = 220f;

    // ---- 敌人变体（D4）----
    /** 精英：体型 ×6、速度 ×1.1、伤害 ×1.5，带一层护盾 */
    public static final float ELITE_HP_MUL    = 12f;  // 精英血翻倍（原 ×6）
    public static final float ELITE_SPEED_MUL = 1.1f;
    public static final float ELITE_DMG_MUL   = 1.5f;
    public static final float ELITE_SHIELD    = 120f;
    /** 小偷：偷地上的经验宝石，自身不攻击。击杀时掉落翻倍的宝石 */
    public static final float THIEF_HP     = 80f;
    public static final float THIEF_SPEED  = 130f;
    public static final float THIEF_STEAL_RADIUS = 26f;   // 接触宝石即偷走的范围
    /** 分裂怪：死亡时裂成几只，子代 HP 按比例缩小 */
    public static final int   SPLIT_COUNT   = 3;
    public static final float SPLIT_HP_MUL  = 0.45f;
    public static final float SPLIT_RADIUS_MUL = 0.8f;
    /** 远程怪：保持距离并向玩家发射弹幕 */
    public static final float RANGED_HP       = 68f;
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
    public static final String[] BOSS_NAMES   = { "石心巨像", "熔岩飞龙", "霜寂飞龙", "终焉之影" };
    /** 每只 Boss 的血池。第一只别太肉，5 分钟时的 build 打得动 */
    public static final float[] BOSS_HP_TIERS = { 4000f, 8400f, 14400f, 26000f };   // 全部翻倍
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
    // ---- 飞碟 Boss（tier 0）专属：追踪炮弹（低频率、慢速、有限追踪、命中或过期爆炸）----
    /**
     * 两轮齐射之间的间隔（秒）。刻意做慢：飞碟的主循环仍是预警圈 + 召唤，
     * 追踪弹只是穿插的"小威胁"，不至于把战斗节奏拖进弹幕地狱。
     */
    public static final float BOSS_HOMING_CD          = 4.5f;
    /** 单轮齐射数量（四向散布，绕 Boss 一圈） */
    public static final int   BOSS_HOMING_COUNT       = 4;
    /** 炮弹飞行速度（像素/秒）。比全部职业的基础移速都慢——刻意留下"可绕开"的窗口 */
    public static final float BOSS_HOMING_SPD         = 130f;
    /** 每帧最大转向弧度。值很小 ≈ 2.3°/帧：直瞄困难，纯靠甩尾糊玩家脸 */
    public static final float BOSS_HOMING_TURN        = 0.04f;
    /** 炮弹索敌半径（用来决定"锁谁"） */
    public static final float BOSS_HOMING_SEEK        = 760f;
    /** 炮弹寿命（秒）：玩家甩掉并超过这个时长则原地自爆（避免遗留在场上一辈子） */
    public static final float BOSS_HOMING_LIFE        = 4.5f;
    /** 命中或自爆时的直接伤害 */
    public static final float BOSS_HOMING_DMG         = 22f;
    /** 命中或自爆时的爆炸半径（AOE） */
    public static final float BOSS_HOMING_BLAST_R     = 70f;
    /** 命中或自爆时的击退 */
    public static final float BOSS_HOMING_BLAST_KB    = 130f;
    /** 炮弹的碰撞半径（命中判定用，比基础弹幕稍大以保证能擦到走位） */
    public static final float BOSS_HOMING_RADIUS      = 9f;

    // ---- 飞龙 Boss（tier 1 / tier 2）专属：定向直线飞行弹幕 + 灼烧带 ----
    /**
     * 飞龙的招牌动作：朝玩家所在方向吐出一颗/两颗慢速火球，沿弹道留下一条持续 5 秒的
     * 灼烧地面。玩家走过灼烧区即持续扣血。
     * 设计上比飞碟追踪弹更"好躲"——慢速直线，可以横向走出弹道；但灼烧带的"惩罚窗口"
     * 长达 5 秒，逼玩家要么绕路，要么吃灼烧伤害。
     */
    /** 飞龙火球飞行速度（像素/秒）。比飞碟追踪弹还慢，玩家有充足反应时间横向脱离 */
    public static final float DRAGON_BOLT_SPD         = 145f;
    /** 飞龙火球的命中半径（稍大，保证擦边能命中） */
    public static final float DRAGON_BOLT_RADIUS      = 11f;
    /** 飞龙火球直接命中玩家的伤害 */
    public static final float DRAGON_BOLT_DMG         = 26f;
    /** 飞龙火球的飞行寿命（秒）。到期没碰到玩家就原地消散，不再留灼烧 */
    public static final float DRAGON_BOLT_LIFE        = 3.0f;
    /** 灼烧区半径（比火球本身大一圈，"中弹带"自然铺开） */
    public static final float DRAGON_BURN_RADIUS      = 46f;
    /** 灼烧区持续时长（秒）。5 秒是用户明确要求的"长时间惩罚窗口" */
    public static final float DRAGON_BURN_DURATION    = 5.0f;
    /** 灼烧区每秒伤害（持续 5 秒 → 累计 5 × DPS） */
    public static final float DRAGON_BURN_DPS         = 18f;
    /** 飞行过程中每隔多远播种一团灼烧。太小则连成线会卡视野，太大则稀疏给玩家空档 */
    public static final float DRAGON_BURN_STEP        = 38f;
    /** tier 1 飞龙（熔岩飞龙）：单发火球的齐射 CD（秒） */
    public static final float DRAGON_BOLT_CD_TIER1    = 4.5f;
    /** tier 2 飞龙（霜寂飞龙）：双发齐射的 CD（秒）——刻意比 tier 1 长一档，避免双发+短 CD 变成弹幕墙 */
    public static final float DRAGON_BOLT_CD_TIER2    = 6.0f;
    /** tier 2 双发的扇形展开角（弧度）。两发朝向玩家方向 ± 半角，给玩家"在两发之间溜过去"的窗口 */
    public static final float DRAGON_BOLT_TIER2_HALF  = (float) Math.toRadians(15);

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
    public static final float MILKY_HP            = 30000f;
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
    /** 触发大笑的血量比例（低于此值即进入二阶段：不只是解锁大笑，还会整体强化） */
    public static final float MILKY_LAUGH_HP      = 0.5f;

    // ---- 奶蛙 · 阶段强化 ----
    /** 一阶段减伤：受到的伤害减少 50% */
    public static final float MILKY_DR                 = 0.50f;
    /** 一阶段移速加成（叠加在 MILKY_SPEED 之上） */
    public static final float MILKY_SPEED_BONUS        = 100f;
    /** 二阶段（血量 ≤ MILKY_LAUGH_HP）减伤：提升到 80% */
    public static final float MILKY_PHASE2_DR          = 0.80f;
    /** 二阶段额外移速加成（在一阶段之上再加） */
    public static final float MILKY_PHASE2_SPEED_BONUS = 50f;
    /** 二阶段技能范围倍率（+25%） */
    public static final float MILKY_PHASE2_RANGE_MUL   = 1.25f;

    // ---- 王宫最终决战：国王（第一阶段；第二/三阶段在后续版本接入）----
    /**
     * 第一阶段：血 30000 / 与 Boss 同尺寸。
     * 常态站桩不移动，只在技能前摇期间按 KING1_SPEED 随机走位躲弹幕。
     * 不造成接触伤害——用户设定「只有一个技能」，贴脸打王不会被啃。
     */
    public static final float KING1_HP       = 30000f;
    public static final float KING1_SPEED    = 175f;
    public static final float KING1_RADIUS   = 46f;
    /**
     * 唯一技能：每 8 秒释放一次，半径 180，1 秒前摇 + 地面预警圈
     * （前摇与提示圈的机制与既有 Boss 的 spawnWarning 完全同款；前摇期间国王随机走位）。
     * 伤害 80（用户给定）。
     */
    public static final float KING1_SKILL_CD        = 8f;
    public static final float KING1_SKILL_TELEGRAPH = 1f;
    public static final float KING1_SKILL_RADIUS    = 180f;
    public static final float KING1_SKILL_DAMAGE    = 80f;
    /**
     * 默认属性（三阶段共用）：每 15 秒按被动获得顺序失去一个被动并损失 10 点生命。
     * 转阶段时计时重置（阶段二接入时生效）。
     */
    public static final float KING_ATTRITION_INTERVAL = 15f;
    public static final float KING_ATTRITION_HP       = 10f;
    /** 竞技场（王座厅）边界：玩家与国王都被限制在这个矩形里，相机锁定场地中心 */
    public static final float ARENA_HALF_X   = 580f;
    public static final float ARENA_TOP      = -300f;
    public static final float ARENA_BOTTOM   = 330f;
    /** 国王初始站位（王座前）与玩家入场站位（殿中下方） */
    public static final float ARENA_KING_X   = 0f;
    public static final float ARENA_KING_Y   = -170f;
    public static final float ARENA_ENTER_X  = 0f;
    public static final float ARENA_ENTER_Y  = 220f;

    // ---- 王宫最终决战：国王第二阶段 ----
    /**
     * 第二阶段：血 60000（用户给定）+ 常驻 20% 减伤（用户给定）。
     * 移动为「按与最近玩家的距离分档」的压迫式走位（用户给定的三档速度），
     * 全程追击玩家，不再保持距离/绕行：
     *   > 400      → 250 直线逼近
     *   250 ~ 400  → 205 逼近
     *   < 250      → 170 贴身追击
     * 位移由 updateKing2 手动驱动；贴身接触伤害 40（与一阶段不同：一阶段无接触伤害）。
     */
    public static final float KING2_HP       = 60000f;
    public static final float KING2_RADIUS   = 50f;
    public static final float KING2_SPEED_FAR  = 250f;
    public static final float KING2_SPEED_MID  = 205f;
    public static final float KING2_SPEED_NEAR = 170f;
    /** 距离分档阈值（用户给定的 400 / 250） */
    public static final float KING2_BAND_FAR  = 400f;
    public static final float KING2_BAND_MID  = 250f;
    /** 贴身接触伤害（用户给定）：走通用敌人接触通道，0.7 秒一次、受受击方无敌帧门控 */
    public static final float KING2_CONTACT_DAMAGE = 40f;
    /** 常驻减伤 20%（用户给定）：二阶段国王受到的伤害先砍掉两成 */
    public static final float KING2_DR = 0.20f;
    /**
     * 魔弹：用户给定每轮齐射 5 颗（扇形 ±15°），移速 130，追踪所有玩家，
     * 追踪 4 秒后消失，消失后 1 秒再释放（即每 5 秒一轮）；伤害 25。
     */
    public static final float KING2_BOLT_SPEED    = 130f;
    public static final float KING2_BOLT_TRACK    = 4f;
    public static final float KING2_BOLT_REST     = 1f;
    public static final float KING2_BOLT_DAMAGE   = 25f;
    /** 每轮齐射的魔弹数量（用户给定 5 颗） */
    public static final int   KING2_BOLT_COUNT    = 5;
    /** 扇形齐射的相邻两发夹角（±15°） */
    public static final float KING2_BOLT_SPREAD   = (float) Math.toRadians(15);
    /** 追踪弹转向速率（弧度/秒）：低于玩家移速的回头速度，跑动可以甩开 */
    public static final float KING2_BOLT_TURN     = 1.6f;
    /**
     * 地面攻击提示：每 6 秒一次（用户给定），1.5 秒前摇，半径 150（用户给定）。
     * 伤害 80（用户给定）。
     */
    public static final float KING2_AOE_CD        = 6f;
    public static final float KING2_AOE_TELEGRAPH = 1.5f;
    public static final float KING2_AOE_RADIUS    = 150f;
    public static final float KING2_AOE_DAMAGE    = 80f;
    /**
     * 裂隙刷怪：每 5 秒在王宫中展开一道裂隙，每道爬出 2 只魔物；
     * 同屏魔物数量封顶 10（防卡顿），裂隙视觉存活 1.1 秒。
     * （原 8 秒，用户要求提高召唤小怪的频率 → 5 秒）
     */
    public static final float KING2_RIFT_CD      = 5f;
    public static final int   KING2_RIFT_COUNT   = 2;
    public static final int   KING2_MAX_ENEMIES  = 10;
    public static final float KING2_RIFT_FX_TTL  = 1.1f;
    public static final float KING2_RIFT_FX_R    = 120f;
    /** 二阶段开始：角色损失 20 点移速（用户给定） */
    public static final float KING2_SPEED_PENALTY = 20f;
    /** 二阶段王座厅背景宽度（王宫二阶段.gif，按高度覆盖屏幕得到的宽度，便于微调对位） */
    public static final float ARENA_BG2_W = 1079f;

    // ---- 王宫最终决战：国王第三阶段（王座本体）----
    /**
     * 第三阶段（王座本体）：血 60000 × 2（用户给定，两管血、总池 120000），
     * 坐在王座上不再走路，靠「随机传送」位移——每 3 秒瞬移到玩家 50 以内，
     * 落位后 1 秒前摇、100 范围爆发 30 伤害。前摇期间减伤从 90% 降为 20%（用户给定，即输出窗口）。
     * 第二管血开始时王座分裂成两个（见 World.spawnKingTwin）。
     */
    /** 三阶段单管血量（用户给定 60000）：血池 = KING3_HP_PER_BAR × 2，第二管开始时分裂 */
    public static final float KING3_HP_PER_BAR = 60000f;
    public static final float KING3_HP         = KING3_HP_PER_BAR * 2f;
    public static final float KING3_RADIUS     = 80f;
    /** 贴身接触伤害 30（用户给定）：比二阶段的 40 更轻，走通用敌人接触通道；
     *  技能前摇窗口（kingCasting / kingTwinCasting 红光期）内整段豁免——释放技能时不咬人（用户给定） */
    public static final float KING3_CONTACT_DAMAGE = 30f;
    /** 常驻减伤 90%（用户给定）：王座受到的伤害先砍掉九成 */
    public static final float KING3_DR         = 0.90f;
    /** 传送前摇期间的减伤 20%（用户给定）：蓄力时露出破绽，给玩家输出窗口 */
    public static final float KING3_DR_CAST    = 0.20f;
    /** 传送：每 3 秒一轮（用户给定） */
    public static final float KING3_TELE_CD    = 3f;
    /** 分裂后分身传送相对本体的延迟 2 秒（用户给定）：本体瞬移 2 秒后分身才起跳，两尊不同时瞬移 */
    public static final float KING3_TWIN_TELE_DELAY = 2f;
    /** 落点距玩家的最大距离 50（用户给定）与最小距离（避免与玩家完全重叠） */
    public static final float KING3_TELE_RANGE = 50f;
    public static final float KING3_TELE_MIN   = 12f;
    /** 传送落位后的前摇 1 秒（用户给定），到期爆发 */
    public static final float KING3_TELE_TELEGRAPH = 1f;
    /** 爆发范围 100（用户给定）与伤害 30（用户给定） */
    public static final float KING3_TELE_RADIUS = 100f;
    public static final float KING3_TELE_DAMAGE = 30f;
    /** 王座每次造成伤害都会从深渊汲取 200 生命（用户给定） */
    public static final float KING3_HEAL_HIT   = 200f;
    /** 地刺：每 5 秒一批（用户给定），在玩家周围 KING3_SPIKE_RANGE 内随机位置长出，1 秒前摇 + 提示 */
    public static final float KING3_SPIKE_CD        = 5f;
    public static final float KING3_SPIKE_TELEGRAPH = 1f;
    public static final float KING3_SPIKE_RANGE     = 150f;
    public static final float KING3_SPIKE_RADIUS    = 40f;
    /** 地刺伤害：用户未指定，与传送爆发一致取 30 */
    public static final float KING3_SPIKE_DAMAGE    = 30f;
    /** 出刺后的刺身视觉存活时长 */
    public static final float KING3_SPIKE_FX_TTL    = 0.6f;
    /**
     * 深渊牵引：每 5 秒一次（用户给定），把玩家以 30 速拉向王座（用户：「王座视为深渊」），
     * 每次持续 1.5 秒（用户确认），合计被拽约 45px。
     */
    public static final float KING3_PULL_CD    = 5f;
    public static final float KING3_PULL_DUR   = 1.5f;
    public static final float KING3_PULL_SPEED = 30f;
    /** 裂隙召唤 Boss：每 25 秒一只（用户给定），从除奶蛙外的 Boss 池随机抽档 */
    public static final float KING3_SUMMON_CD   = 25f;
    public static final float KING3_SUMMON_FX_TTL = 1.4f;
    /** 三阶段魔弹齐射数量（用户给定 5 颗；二阶段同样 5 颗） */
    public static final int   KING3_BOLT_COUNT  = 5;
    /** 三阶段裂隙魔物数量（用户给定每次 4 只；二阶段维持每道 2 只） */
    public static final int   KING3_RIFT_COUNT  = 4;

    // ---- 世界 ----
    public static final float FIXED_STEP     = 1f / 60f;
    /** 世界边界半宽：地图是 [-WORLD_HALF, +WORLD_HALF] 的方形区域。
     *  必须大到足以容纳全部地图内容（Boss 节点延伸到 x≈4350），否则城墙边界会落在
     *  可玩区之内、玩家能走出墙外踩进虚空，并在节点边缘撞到看不见的空气墙。 */
    public static final float WORLD_HALF     = 4800f;
    /** 棕色城墙厚度：城墙（棕色部分）作为地图边界，其内侧边缘才是可走区域 */
    public static final float WALL_THICKNESS = 56f;
    /** 可玩区半宽 = 城墙内侧边缘。玩家/敌人/刷怪点都钳制在此，碰不到棕色城墙 */
    public static final float PLAY_HALF      = WORLD_HALF - WALL_THICKNESS;
}
