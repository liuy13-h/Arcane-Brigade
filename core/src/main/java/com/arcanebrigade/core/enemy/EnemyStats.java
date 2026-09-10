package com.arcanebrigade.core.enemy;

/**
 * 骨蛇（小 Boss）的专属数值与变体标签。
 *
 * 关于范围：lobby-king 分支的这个文件是 Balance 里敌怪常量的整包搬家，
 * 但 main 分支的敌怪数值权威仍在 {@code Balance}（含 BOSS_LEVELS = {5,10,15,20}、
 * 战斗事件、奶蛙等 main 独有的内容）。整包搬过来会立刻出现两份互相矛盾的
 * BOSS_LEVELS——lobby-king 那份还是改版前的 {4,8,12,16}。
 *
 * 所以这里只收骨蛇相关的内容：它只服务于同包的 BoneSerpent 与 WaveDirector，
 * 不与 Balance 抢任何常量的归属。其余敌怪数值仍以 Balance 为准。
 */
public final class EnemyStats {

    private EnemyStats() {}

    /**
     * 骨蛇的变体 id。存 World.variant[]，驱动 AI 与渲染分派。
     * 取 7 是因为 main 的 6 号已占用（V_STATUE = 战斗事件「摧毁雕像」的静态靶子），
     * lobby-king 原本把 6 号分给了跳跳史莱姆——main 没有引入史莱姆，故不冲突。
     */
    public static final int V_SERPENT = 7;

    /**
     * 登场触发等级：卡在四只大 Boss（main 为 5/10/15/20）正中间。
     * 大 Boss 是"阶段考试"，骨蛇是两次考试之间的遭遇战——节奏上一紧一松。
     *
     * lobby-king 原值是 {2,6,10,14}，那是配合旧版 BOSS_LEVELS={4,8,12,16} 的；
     * main 的大 Boss 已改到 5/10/15/20，沿用的话 Lv.10 会和"霜寂君王"撞车，
     * 因此重新定档为 {3,8,13,18}。
     */
    public static final int[] SERPENT_LEVELS = { 3, 8, 13, 18 };
    public static final String SERPENT_NAME = "骸骨飞蛇";

    /**
     * 身体重复节数（不含头与尾）。
     * 整条蛇的实体数 = SERPENT_BODY + 2（头 + 尾），全部登记为 V_SERPENT。
     */
    public static final int SERPENT_BODY = 10;
    public static final int SERPENT_SEGMENTS = SERPENT_BODY + 2;

    /**
     * 共享血池。小 Boss 的定位是"打得动但耗时间"，所以约等于同档大 Boss 的一半；
     * 它是 12 个实体共用一个数——打头打身子一个价，玩家不必追着头打。
     */
    public static final float SERPENT_HP = 1100f;
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
    public static final float SERPENT_LOOP_R = 190f;
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
    public static final int SERPENT_GEM_DROP = 4;
}
