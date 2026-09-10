package com.arcanebrigade.core.enemy;

/**
 * 敌怪系统的全部可调数值与类型标签。
 *
 * 从 Balance 拆出来的唯一理由：敌怪的东西应该和敌怪的行为（EnemyAI）、
 * 刷怪导演（WaveDirector）待在一起，改敌人只翻这一个包，不用在
 * 「玩家 / 技能 / 元素 / 世界」的常量堆里翻找。
 *
 * 数值本身与 Balance 里原先完全一致，只是换了归属。
 */
public final class EnemyStats {

    private EnemyStats() {}

    // ---- 敌人变体标签 ----
    // 敌怪的"类型 id"，存在 World.variant[] 里，驱动 AI 分派与渲染分派。
    /** 敌人变体。普通怪不做特殊行为，其余按类型分派 */
    public static final int V_NORMAL = 0;
    public static final int V_ELITE  = 1;   // 精英：大血厚甲，带护盾
    public static final int V_THIEF  = 2;   // 小偷：偷地上宝石，不攻击
    public static final int V_RANGED = 3;   // 远程：保持距离发射弹幕
    public static final int V_SPLIT  = 4;   // 分裂：死亡裂成数只
    public static final int V_BOSS   = 5;   // Boss：多阶段
    public static final int V_SLIME  = 6;   // 跳跳史莱姆：只会跳跃、不平滑移动
    /** 骨蛇：小 Boss。一个"怪"由多个实体拼成，全身共享一份血 */
    public static final int V_SERPENT = 7;

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

    /**
     * 跳跳史莱姆（V_SLIME）：唯一不做平滑移动的小怪——落地蓄力，然后朝玩家
     * 抛物线跳跃逼近。体型与普通小怪（绿史莱姆）一致。
     */
    public static final float SLIME_RADIUS    = ENEMY_RADIUS;   // 与绿史莱姆同体格
    public static final float SLIME_HP        = 30f;
    public static final float SLIME_DAMAGE    = 10f;
    /** 腾空期间的水平速度 */
    public static final float SLIME_HOP_SPEED = 165f;
    /** 单次腾空时长（秒），决定一跳的水平距离 = HOP_SPEED × AIR_TIME */
    public static final float SLIME_AIR_TIME  = 0.45f;
    /** 抛物线峰值高度（世界单位 ≈ 像素） */
    public static final float SLIME_HOP_HEIGHT = 26f;
    /** 落地后的蓄力停顿（秒），随机取上下界之间 */
    public static final float SLIME_REST_MIN  = 0.55f;
    public static final float SLIME_REST_MAX  = 0.85f;
    /** 起跳方向相对玩家的随机抖动（弧度），避免所有史莱姆落进同一个点 */
    public static final float SLIME_JUMP_JITTER = 0.35f;
    /**
     * 开场保底：第几只怪强制刷成跳跳史莱姆（0 = 关闭保底，退回纯概率）。
     * WaveDirector 的变体掷点用固定种子，本局前 59 次必然全部落空，
     * 不保底的话开局一两分钟一只新怪都看不到。
     */
    public static final int SLIME_INTRO_SPAWN = 4;
    /** 跳跳史莱姆在普通刷怪里的占比（WaveDirector 掷点用） */
    public static final double SLIME_MIX = 0.20;

    // ---- Boss（本轮正式引入：每个阶段末尾一只，一局共 4 只）----
    /**
     * 登场触发等级：玩家升到这些等级时刷对应那只 Boss。
     * 用等级而不是时间，是因为等级直接反映 build 强度——
     * 同样的时间点，一个吃满经验的玩家和一个挂机的玩家该面对的 Boss 强度不该一样。
     * 到等级但上一只还活着时不会叠加，会等它倒下再上（见 WaveDirector）。
     */
    public static final int[]   BOSS_LEVELS   = { 4, 8, 12, 16 };
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

    // ---- 骨蛇（小 Boss：头 + 10 节身体 + 尾，全身共享血量）----
    /**
     * 登场触发等级：刻意卡在四只大 Boss 的 4/8/12/16 中间。
     * 大 Boss 是"阶段考试"，骨蛇是两次考试之间的遭遇战——节奏上一紧一松。
     */
    public static final int[]   SERPENT_LEVELS = { 2, 6, 10, 14 };
    public static final String  SERPENT_NAME   = "骸骨飞蛇";

    /**
     * 身体重复节数（不含头与尾）。用户指定 10 节。
     * 整条蛇的实体数 = SERPENT_BODY + 2（头 + 尾），全部登记为 V_SERPENT。
     */
    public static final int SERPENT_BODY     = 10;
    public static final int SERPENT_SEGMENTS = SERPENT_BODY + 2;

    /**
     * 共享血池。小 Boss 的定位是"打得动但耗时间"，所以约等于同档大 Boss 的一半；
     * 它是 12 个实体共用一个数——打头打身子一个价，玩家不必追着头打。
     */
    public static final float SERPENT_HP  = 1100f;
    public static final float SERPENT_DMG = 16f;

    /** 节与节之间的中心距。比身体美术高度（24）略小，让骨节重叠成一条连续的脊骨 */
    public static final float SERPENT_SPACING = 20f;
    /** 头到第一节身体的中心距。头美术高 76（半高 38），脖子接在精灵底端 */
    public static final float SERPENT_HEAD_GAP = 40f;

    /**
     * 头部飞行速度上限。**必须大于"∞"轨迹自身的最大线速度**，否则头会追不上
     * 轨迹点，把 8 字跑成一个缩水的小圈。
     *
     * 轨迹线速度 = OMEGA × max|d/dφ(cos φ, sin 2φ)|·(R, Ry) ≈ OMEGA × 276 ≈ 276。
     * 这里给 320 留出余量，同时它也高于玩家移速 195——骨蛇是"撵着玩家跑"的小 Boss，
     * 追不上就没威胁了。
     */
    public static final float SERPENT_SPEED = 320f;

    /**
     * "∞"轨迹（Gerono 双纽线）的横/纵半径：横向大、纵向小，才是横躺的 8 字而不是胖葫芦。
     * 轨迹以玩家为中心——玩家跑，整条蛇跟着挪窝。
     */
    public static final float SERPENT_LOOP_R  = 190f;
    public static final float SERPENT_LOOP_RY = 120f;
    /**
     * 跑完一圈的角速度（弧度/秒）。调大 = 攻击更疯、更容易撞上玩家，
     * 但调过头会让轨迹线速度超过 SERPENT_SPEED，头就开始掉队——两者要一起改。
     */
    public static final float SERPENT_OMEGA = 1.0f;

    /** 头与身体的碰撞半径。头大一圈，接触伤害也统一按头的半径算 */
    public static final float SERPENT_HEAD_RADIUS = 16f;
    public static final float SERPENT_BODY_RADIUS = 13f;
    /** 接触伤害冷却。真正防止连击的是目标无敌帧，这里只是兜底 */
    public static final float SERPENT_ATTACK_CD = 0.6f;

    /** 骨蛇在场时普通刷怪的抑制倍率。比大 Boss（0.35）宽松：小 Boss 不独占舞台 */
    public static final float SERPENT_SPAWN_SUPPRESS = 0.6f;
    /** 生成距离。比大 Boss 的 520 近一点，登场更突然 */
    public static final float SERPENT_SPAWN_DIST = 460f;
    /** 击杀掉落的宝石数。整条蛇只结算一次，铺开一圈给玩家捡 */
    public static final int   SERPENT_GEM_DROP = 4;
}
