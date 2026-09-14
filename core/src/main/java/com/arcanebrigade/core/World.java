package com.arcanebrigade.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.arcanebrigade.core.enemy.BoneSerpent;
import com.arcanebrigade.core.enemy.EnemyStats;
import com.arcanebrigade.core.enemy.WaveDirector;

/**
 * 世界模拟。这是整个游戏的权威状态，不依赖任何 UI 框架。
 *
 * 存储采用 SoA（Structure of Arrays）+ 空闲链表复用，目的是：
 *   1. 上千实体时避免逐对象 GC；
 *   2. 快照序列化时可以直接按数组批量打包。
 *
 * 固定步长推进，dt 恒为 Balance.FIXED_STEP，保证可重放、帧间一致。
 */
public final class World {

    public static final int MAX = 8192;

    public static final int KIND_FREE       = 0;
    public static final int KIND_WIZARD     = 1;
    public static final int KIND_ENEMY      = 2;
    public static final int KIND_PROJECTILE = 3;
    public static final int KIND_PICKUP     = 4;
    /** 纯视觉特效：不移动、不参与碰撞、寿命极短。爆炸与闪电链都用它 */
    public static final int KIND_FX         = 5;
    /** 地面区域：毒雾 / 火场 / 钉刺陷阱 / 电网，每 tick 对范围内敌人造成伤害 */
    public static final int KIND_ZONE       = 6;
    /** 障碍物：静态碰撞体，玩家 / 敌人 / 弹幕都绕不过去（用户明确要求场景必须含障碍） */
    public static final int KIND_OBSTACLE   = 7;
    /** 召唤物（召唤师的宠物）：友方单位，替主人扛伤与输出，生命周期由主人掌控 */
    public static final int KIND_MINION     = 8;

    /** KIND_ZONE 子类型。meta 字段区分 */
    public static final int ZONE_POISON  = 0;
    public static final int ZONE_FIRE    = 1;
    public static final int ZONE_TRAP    = 2;
    public static final int ZONE_CHAIN   = 3;
    /** 预警圈：先在地上显示 telegraph 秒，到期对玩家与敌人爆炸 */
    public static final int ZONE_WARNING = 4;
    /** 三阶段地刺：警示期（1 秒前摇 + 提示），到期刺出并结算伤害 */
    public static final int ZONE_KING_SPIKE_TELE = 5;
    /** 三阶段地刺：出刺后的刺身视觉（纯视觉载体，不参与 tick 伤害） */
    public static final int ZONE_KING_SPIKE = 6;
    /**
     * 飞龙灼烧带：用 KIND_ZONE 但**不走通用 updateZones**——单独由 updateDragonBurns tick，
     * 只对玩家/宠物生效（飞龙火球留下的"惩罚带"，不能用来烧怪）。
     * 渲染按 ZONE_FIRE 的火焰地效处理，客户端判断 meta==ZONE_DRAGON_BURN 时用同款红橙色。
     */
    public static final int ZONE_DRAGON_BURN = 7;

    /** 敌人变体。普通怪不做特殊行为，其余按类型分派 */
    public static final int V_NORMAL = 0;
    public static final int V_ELITE  = 1;   // 精英：大血厚甲，带护盾
    public static final int V_THIEF  = 2;   // 小偷：偷地上宝石，不攻击
    public static final int V_RANGED = 3;   // 远程：保持距离发射弹幕
    public static final int V_SPLIT  = 4;   // 分裂：死亡裂成数只
    public static final int V_BOSS   = 5;   // Boss：多阶段
    public static final int V_STATUE = 6;   // 战斗事件「摧毁雕像」：不移动不攻击的静态靶子
    /**
     * 骨蛇（小 Boss）：一个"怪"由多个实体拼成，全身共享一份血。
     * id 取自 EnemyStats.V_SERPENT（7），这里再导出一次是为了让 World 的 switch
     * 与其它变体写在一起，读代码时不必去 enemy 包里查号。
     */
    public static final int V_SERPENT = com.arcanebrigade.core.enemy.EnemyStats.V_SERPENT;

    /** 拾取物 meta 子类型 */
    public static final int PICKUP_GEM      = 0;
    public static final int PICKUP_CHEST    = 1;
    public static final int PICKUP_MUSHROOM = 2;   // 战斗事件「采集蘑菇」

    /**
     * FX 子类型。为了不再为特效开新数组，借用现有字段：
     *   FX_BLAST —— 圆形爆炸，半径在 r
     *   FX_BEAM  —— 线段，终点借存 vx/vy
     *   FX_ARC   —— 近战扇形，半径在 r，朝向借存 dmg
     */
    public static final int FX_BLAST = 0;
    public static final int FX_BEAM  = 1;
    public static final int FX_ARC   = 2;
    /** FX_RIFT —— 裂隙漩涡，半径在 r */
    public static final int FX_RIFT  = 3;

    /** 阵营。友军伤害与命中过滤都靠它：投射物只打不同阵营。 */
    public static final int TEAM_PLAYER = 0;
    public static final int TEAM_ENEMY  = 1;

    // ---- 组件数组 ----
    public final float[] x = new float[MAX];
    public final float[] y = new float[MAX];
    /** 上一逻辑帧位置，渲染插值用 */
    public final float[] px = new float[MAX];
    public final float[] py = new float[MAX];
    public final float[] vx = new float[MAX];
    public final float[] vy = new float[MAX];
    public final float[] r  = new float[MAX];
    /** 障碍物底部轮廓；只在 KIND_OBSTACLE 实体上读取。 */
    private final int[] obstacleShape = new int[MAX];
    private final float[] obstacleHalfW = new float[MAX];
    private final float[] obstacleHalfH = new float[MAX];
    /** 每个障碍的规则来自 ArenaMap；轮廓与阻挡策略必须同时保留，不能再由 visual 推断。 */
    private final boolean[] obstacleBlocksMovement = new boolean[MAX];
    private final boolean[] obstacleBlocksProjectiles = new boolean[MAX];
    private final boolean[] obstacleDestructible = new boolean[MAX];
    public final float[] hp = new float[MAX];
    public final float[] maxHp = new float[MAX];
    public final float[] speed = new float[MAX];
    public final float[] dmg = new float[MAX];
    public final float[] life = new float[MAX];
    public final float[] cd = new float[MAX];
    /** 受击无敌剩余时间，&lt;=0 才能再次受伤 */
    public final float[] iframe = new float[MAX];
    /** 主动位移（冲刺）冷却剩余（秒），&lt;=0 才就绪。弓箭手：每发充能恢复 1 格的计时；战士：单发 CD */
    public final float[] dashCd = new float[MAX];
    /**
     * 弓箭手的冲刺弹药数（0..ARCHER_DASH_MAX）。出生即满发。
     * 每用一发减 1，CD 到 0 再回 1，直到满发为止。
     * 战士与无冲刺职业该字段恒为 0（不画 HUD）。
     */
    public final int[] dashCharges = new int[MAX];
    /** 主动位移剩余持续时长（秒），&gt;0 时按 dash 速度位移 */
    public final float[] dashTime = new float[MAX];

    /** 战士是否正处于"蓄力重击"状态（上一帧判定，用于识别松开帧的释放） */
    private boolean warriorCharging;
    /** 松开鼠标当帧锁存的蓄力进度 0..1，供这次重击放大斩击与伤害 */
    private float warriorChargePower;


    public final int[] kind = new int[MAX];
    public final int[] team = new int[MAX];
    /** 谁发射的。友军伤害判定要用，别等到 D6 才加。 */
    public final int[] owner = new int[MAX];
    /** 敌人外观变体 / 投射物的法术 id / FX 子类型 */
    public final int[] meta = new int[MAX];
    /** 投射物剩余穿透次数 */
    public final int[] pierce = new int[MAX];
    /** 投射物上一个命中的目标。穿透时不加这个，同一个目标会被每帧重复扣血 */
    public final int[] lastHit = new int[MAX];
    /** 投射物携带的爆炸半径（已经乘过 areaMul）。0 = 不炸 */
    public final float[] projAoe = new float[MAX];
    /** 投射物携带的弹射数（已经加过 shockChainAdd）。0 = 不弹 */
    public final int[] projChain = new int[MAX];
    /** 投射物携带的跳弹次数（已经加过 bounceAdd）。命中后剩余时转向下一个目标 */
    public final int[] projBounce = new int[MAX];
    /** 投射物当前追踪的目标 id（跳弹用） */
    public final int[] projTarget = new int[MAX];

    // ---- 敌人变体 / 小偷 / 护盾 ----
    /** 敌人变体：普通 / 精英 / 小偷 / 远程 / 分裂 / Boss */
    public final int[] variant = new int[MAX];
    /** 敌人护盾（精英与 Boss 用），先扣盾再扣血 */
    public final float[] enemyShield = new float[MAX];
    /** 小偷携带的宝石数，死亡时翻倍掉落 */
    public final float[] carry = new float[MAX];
    /**
     * 骨蛇每一节归属的**头节点 id**（头节点存自己）。非骨蛇恒为 -1。
     *
     * 骨蛇是"一个怪、十二个实体"：每节都是普通 KIND_ENEMY（所以索敌、碰撞、
     * 弹幕命中、空间哈希全部照旧白嫖），但血量只有一份，存在头节点上。
     * damage() 靠这张表把任意一节的挨打转发到头——玩家打头打身子一个价。
     */
    public final int[] serpent = new int[MAX];

    // ---- 元素与状态 ----
    /** 附着的元素 id（Element.*）。目前只挂在敌人身上 */
    public final int[] elem = new int[MAX];
    public final float[] elemT = new float[MAX];
    /** 元素强度：火焰=每秒伤害，冰霜=减速比例，雷电=受伤增伤比例 */
    public final float[] elemP = new float[MAX];
    /** 眩晕/致盲剩余。>0 时敌人既不移动也不攻击 */
    public final float[] stunT = new float[MAX];
    /** 击退速度，每帧按 Balance.KNOCKBACK_DECAY 衰减 */
    public final float[] kx = new float[MAX];
    public final float[] ky = new float[MAX];

    /** 召唤师的下一次召唤倒计时（秒）。只有召唤师职业在用 */
    public final float[] summonT = new float[MAX];
    /** 宠物的站位序号（0..SUMMON_COUNT-1），用于分散站位，避免 4 只叠成一个点 */
    public final int[] slot = new int[MAX];

    public final boolean[] alive = new boolean[MAX];

    private final int[] freeList = new int[MAX];
    private int freeTop;
    private int high;          // 已分配过的最大 index + 1
    private int liveCount;

    private final IntList wizards = new IntList(8);
    /** 存活宠物列表，每帧重建。数量个位数，重建比维护增删更不容易出错 */
    private final IntList minions = new IntList(16);
    private int enemiesAlive;
    private int kills;
    /**
     * 骨蛇上一次结算伤害的**世界时间戳**，用于"一帧只挨一次打"。
     * time 每步递增一个 FIXED_STEP，同一帧内的多次 damage() 拿到的是同一个值，
     * 所以相等即同帧。初值 -1f 永远不会等于正常时间，无需在生成时重置。
     */
    private float serpentHitT = -1f;

    private float time;
    private final Random rng;
    /** 本局锁定的单屏关卡；所有碰撞与陷阱由这一份关卡数据驱动。 */
    private final ArenaMap arenaMap;
    /** 刷怪选点缓存：只在生成时写入，避免为路线选点产生临时对象。 */
    private float routeSpawnX;
    private float routeSpawnY;
    private final SpatialHash enemyHash = new SpatialHash(64f);
    /** 障碍物空间哈希。障碍是静态的，只在场景切换时整体重建，所以每帧只查询不重建 */
    private final SpatialHash obstacleHash = new SpatialHash(64f);
    private final WaveDirector director = new WaveDirector();
    /** 是否允许波次导演刷怪（调试用 -Dab.noSpawn 关闭，只留 Boss） */
    private boolean spawningEnabled = true;
    /** 正在结算奶蛙技能伤害（用于判定「角色是否被奶蛙击败」） */
    private boolean milkyHitActive;
    /** 本局角色是否被奶蛙的技能打死（阵亡画面据此显示专属图） */
    private boolean killedByMilky;

    // ---- 骨蛇（lobby-king 引入的小 Boss，多段软体）----
    /** 场上同时最多 N 条骨蛇，避免段数把实体池撑爆 */
    public static final int MAX_BONE_SERPENT = 4;
    /** 每条骨蛇的段数（含头尾），决定了 int[SEGMENT_COUNT] 的尺寸 */
    public static final int BONE_SERPENT_SEG = BoneSerpent.SEGMENT_COUNT;
    /** 当前在场骨蛇实例：head=-1 表示该槽空 */
    private final int[] boneSerpentHead = new int[MAX_BONE_SERPENT];
    {
        // 槽位默认 -1（空），不用 Arrays.fill 也行，但显式一次更直观
        for (int i = 0; i < MAX_BONE_SERPENT; i++) boneSerpentHead[i] = -1;
    }
    /**
     * 每条骨蛇自己的段 id 列表（按 slot 索引）。每条蛇的段独立存，与 lobby-king
     * 「一条共享 IntList」的设计不同——后者同时只能放一条在更新的蛇，多条并存会
     * 互相覆盖。本字段让 MAX_BONE_SERPENT=4 条蛇能同时存活、同时被顺序更新。
     */
    private final IntList[] serpentSegsBySlot = new IntList[MAX_BONE_SERPENT];
    /** 各段之间的历史位置（骨蛇的「拖尾」用）。每槽 int[BONE_SERPENT_SEG][HIST]，存 World.x/y 的副本 */
    private final float[][][] serpentHistX = new float[MAX_BONE_SERPENT][BONE_SERPENT_SEG][BoneSerpent.HIST];
    private final float[][][] serpentHistY = new float[MAX_BONE_SERPENT][BONE_SERPENT_SEG][BoneSerpent.HIST];

    /**
     * 当前正在被 BoneSerpent.update 处理的骨蛇段数组。
     * 用作「更新上下文」——BoneSerpent.update 只接收 head 一个参数，不知道自己
     * 是第几条蛇；让它读 w.serpentSegmentCount() / w.serpentSegment(n) 时，本字段
     * 已经在 updateBoneSerpent(int slot) 开头被填好。
     *
     * 为什么不用 thread-local：World 是单线程的，而且同一帧里多条蛇是顺序更新的，
     * 一个字段就够用。线程/上下文切换只会让代码复杂、不会带来性能。
     */
    private final int[] curSerpentSegs = new int[BONE_SERPENT_SEG];
    {
        // 必须显式填 -1：int[] 默认是 0，而 0 是合法实体 id（法师本人），
        // 第一次 setSerpentContext 之前会让 serpentSegment(0) 返回一个"活着的
        // 非骨蛇实体"，把 BoneSerpent.followBody 和外部调用方一起骗过去。
        for (int i = 0; i < BONE_SERPENT_SEG; i++) curSerpentSegs[i] = -1;
    }

    /** 把槽 slot 的段快照到 curSerpentSegs，供 BoneSerpent.update 访问 */
    private void setSerpentContext(int slot) {
        IntList list = serpentSegsBySlot[slot];
        int n = (list != null) ? Math.min(list.size(), BONE_SERPENT_SEG) : 0;
        for (int i = 0; i < n; i++) {
            curSerpentSegs[i] = list.get(i);
        }
        // 剩余槽位填 -1，BoneSerpent.followBody 看到 -1 会 skip
        for (int i = n; i < BONE_SERPENT_SEG; i++) {
            curSerpentSegs[i] = -1;
        }
    }

    /** BoneSerpent.update 用：当前正在更新的骨蛇总段数 */
    public int serpentSegmentCount() {
        return BONE_SERPENT_SEG;
    }

    /** 骨蛇第 n 段的 enemies[] id（无效时返回 -1） */
    public int serpentSegment(int n) {
        if (n < 0 || n >= BONE_SERPENT_SEG) {
            return -1;
        }
        int id = curSerpentSegs[n];
        if (id >= 0 && alive[id]) {
            return id;
        }
        // 更新上下文之外（step 之前、或当场没有蛇在更新）退回常驻的槽位列表。
        // curSerpentSegs 只在 BoneSerpent.update 期间被 setSerpentContext 填过，
        // 没有这层兜底，任何 step() 之前的调用都会拿到 -1——对 BoneSerpent 之外的
        // 调用方（冒烟测试、外部查询）来说，这个公开访问器就等于永远返回 -1。
        // 多条蛇并存时取第一条活着的；更新期间永远走上面的快路径，不受影响。
        for (int slot = 0; slot < MAX_BONE_SERPENT; slot++) {
            IntList list = serpentSegsBySlot[slot];
            if (list == null || n >= list.size()) {
                continue;
            }
            int sid = list.get(n);
            if (sid >= 0 && alive[sid]) {
                return sid;
            }
        }
        return -1;
    }

    /** 推进骨蛇 slot（由 updateEnemies 调用）。头死了就释放槽位 */
    private void updateBoneSerpent(int slot, float dt) {
        int head = boneSerpentHead[slot];
        if (head < 0 || !alive[head] || serpent[head] != head) {
            boneSerpentHead[slot] = -1;
            return;
        }
        // head 死时 kill 会清掉自己槽位的段列表（serpentSegsBySlot[slot]），那时跳过
        IntList list = serpentSegsBySlot[slot];
        if (list == null || list.size() == 0) {
            boneSerpentHead[slot] = -1;
            if (bossId == head) {
                bossId = -1;
            }
            return;
        }
        setSerpentContext(slot);
        BoneSerpent.update(this, head, dt);
        if (!alive[head]) {
            boneSerpentHead[slot] = -1;
            if (bossId == head) {
                bossId = -1;
            }
        }
    }

    // ---- 场景 / 阶段 ----
    private int stage;
    private float stageTimer;
    private boolean obstaclesGenerated;
    private int bossId = -1;
    /** 当前 Boss 是第几只（BOSS_LEVELS 的下标），HUD 显示名字用 */
    private int bossTier;
    /** 击败奶蛙（5 关 Boss）后置位，客户端据此暂停并弹胜利画面 */
    private boolean victory;
    /** 主控玩家阵亡后置位，客户端据此冻结并弹结算画面 */
    private boolean defeat;
    /** 本局击败的 Boss 数量（kills 含 Boss，结算要分开显示） */
    private int bossKills;
    /** 终局战报快照：胜利或阵亡时冻结一份，避免结算画面上的数字继续跳动 */
    private Summary summary;
    /** 击败奶蛙的终局奖励技能卡名字（游戏结束无法三选一，记下来给胜利画面展示） */
    public String victoryCardName;
    /** 主动退出（暂停菜单「退出结算」）：与阵亡同屏展示战报，但标题不同 */
    private boolean abandoned;
    /** 开火模式：true=自动索敌开火，false=手动（朝鼠标方向，按住开火） */
    private boolean autoFire = true;
    /** 预警圈倒计时（phase≥2 才有） */
    private float bossWarningTimer;
    /** 召唤小怪倒计时（phase≥3 才有） */
    private float bossSummonTimer;
    /**
     * 飞碟 Boss（tier 0）专属：追踪炮弹齐射的倒计时。
     * 场上没有飞碟时此字段无意义（更新时按 tier==0 才扣时间）。
     */
    private float bossHomingTimer;
    /**
     * 飞龙 Boss（tier 1 / tier 2）专属：飞行火球的齐射倒计时。
     * tier 1 单发 → DRAGON_BOLT_CD_TIER1，tier 2 双发 → DRAGON_BOLT_CD_TIER2。
     */
    private float bossBoltTimer;
    /** 场上所有"飞龙火球"实体 id（用于每帧推进 + 播种灼烧带） */
    private final IntList dragonBolts = new IntList(8);
    /** 场上所有"飞龙灼烧带"实体 id（5 秒内持续扣血，独立 tick 不走 updateZones） */
    private final IntList dragonBurns = new IntList(32);
    /** 飞龙火球飞行过程中已播种的距离（每凑够 DRAGON_BURN_STEP 就归零重铺一团） */
    private final float[] burnStep = new float[MAX];

    // ---- 战斗事件（小任务）状态 ----
    /** 下一个待触发事件的 EVENT_TIMES 下标 */
    private int eventIndex;
    /** 当前进行中的事件类型（Balance.EVENT_RIFT / STATUE / MUSHROOM，0=无） */
    private int eventType;
    /** 进度：裂隙=已坚持秒数；雕像=已摧毁数；蘑菇=已采集数 */
    private float eventProgress;
    /** 目标：裂隙=RIFT_HOLD_TIME；雕像=STATUE_COUNT；蘑菇=MUSHROOM_COUNT */
    private float eventGoal;
    /** 事件区域中心（裂隙圈 / 雕像与蘑菇的散布中心） */
    private float eventX;
    private float eventY;
    /** 本局已完成的事件数（结算显示） */
    private int eventCompleted;
    /** 「任务完成 +经验」横幅剩余显示秒数 */
    private float eventBannerT;
    /** 事件生成的雕像 / 蘑菇实体 id，用于清理与统计 */
    private final IntList eventIds = new IntList(16);

    // ---- 5 关 Boss：奶蛙（玩家等级触发，场地中央） ----
    private int milkyId = -1;
    private boolean milkySpawned;
    private float milkyStompCd;
    private float milkyLaughCd;
    /** 奶蛙施法状态：0=移动/待机，1=蓄力踩地，2=捧腹大笑 */
    private int milkyCast;
    private float milkyCastT;
    /** true=朝右（用右向动画/镜像判断） */
    private boolean milkyFaceRight;
    /** 踩地动画是否用镜像版（玩家在左侧时） */
    private boolean milkyMirror;
    /**
     * 技能施法时长直接取 Balance 配置。动画由客户端按该时长归一化播放，
     * 所以即使 GIF 比 1.5s 长，也会被压缩到施法时间内完整播完一次。
     */
    private float milkyStompCastDur = Balance.MILKY_STOMP_CAST;
    private float milkyLaughCastDur = Balance.MILKY_LAUGH_CAST;

    /**
     * 奶蛙被击败倒地后置位：客户端据此触发强制剧情 CG（锁操作、全程自动、不直接结算）。
     * 与 victory 无关——通关判定已移交给后续的王宫决战。一旦置位不会复位。
     */
    private boolean milkyFallen;
    /** 奶蛙倒下的位置：CG 镜头聚焦与消散特效的锚点 */
    private float milkyDownX;
    private float milkyDownY;

    // ---- 王宫最终决战：国王（第一阶段；第二/三阶段后续接入） ----
    /** true=已进入王宫决战场景（竞技场）：不再刷怪 / 推进沙漠阶段 / 生成事件 */
    private boolean kingArena;
    /** 国王实体 id；-1 表示不在场 */
    private int kingId = -1;
    /**
     * 三阶段第二管血分裂出的分身实体 id；-1 表示未分裂/已收场。
     * 分身与本体共享一个血池：伤害在 damage() 里统一记在本体上，分身每帧同步本体血量。
     */
    private int kingTwinId = -1;
    /** 第二管血的分裂是否已触发（三阶段内一次性；防分身意外收场后重复分裂） */
    private boolean kingSplit;
    /** 国王阶段：0=未开战，1/2/3=对应阶段 */
    private int kingPhase;
    /** 技能冷却（阶段一：每 8 秒一次） */
    private float kingSkillCd;
    /** 前摇剩余（>0 = 施法中随机走位躲弹幕；预警圈与前摇同时开始，圈到期即爆炸） */
    private float kingTelegraphT;
    /** 消耗计时：每 15 秒按被动获得顺序失去一个被动并损失 10 点生命 */
    private float kingAttritionT;
    /** 国王朝向（渲染选动画用） */
    private boolean kingFaceRight = true;
    /** 三阶段分身的朝向（渲染用；本体朝向见 kingFaceRight） */
    private boolean kingTwinFaceRight = true;
    /** 三阶段分身传送：距下一轮的剩余时间（跟随本体节拍错开——本体瞬移后 KING3_TWIN_TELE_DELAY 秒起跳） */
    private float kingTwinTeleCd;
    /** 三阶段分身传送前摇剩余（>0 = 前摇中：落点预警 + 到期爆发） */
    private float kingTwinTeleT;
    /** 前摇随机走位：距下次换方向的剩余时间 */
    private float kingDodgeT;
    /** 前摇随机走位方向（单位向量） */
    private float kingDodgeDx;
    private float kingDodgeDy;
    /**
     * 一阶段被击破：客户端据此弹出决裂对白（国王跪地 → 一切刚刚开始）。
     * 对白播完由客户端调用 beginKingPhase2，国王在王座上以二阶段重生。
     */
    private boolean kingFallen;
    /** 一阶段国王倒下的位置（对白演出：倒地剪影锚点） */
    private float kingDownX;
    private float kingDownY;
    /** 二阶段魔弹：距下一轮齐射的剩余时间（发射后 = 追踪 6s + 间隔 4s = 10s） */
    private float kingBoltCd;
    /** 二阶段地面攻击提示：距下一次施放的剩余时间 */
    private float kingAoeCd;
    /** 二阶段裂隙刷怪：距下一道裂隙的剩余时间 */
    private float kingRiftCd;
    /** 二阶段惩罚：玩家移速损失（0=未进入二阶段；进入后恒为 KING2_SPEED_PENALTY） */
    private float kingSpeedPenalty;
    /**
     * 二阶段被击破：客户端据此弹出「王座本体」过渡剧情（国王碎裂 → 「最终决战」标题卡）。
     * 对白播完由客户端调用 beginKingPhase3，王座觉醒进入三阶段。
     */
    private boolean kingFallen2;
    /** 三阶段传送：距下一轮的剩余时间（每 3 秒一轮） */
    private float kingTeleCd;
    /** 三阶段传送前摇剩余（>0 = 前摇中：减伤降为 20% 的输出窗口 + 落点预警圈） */
    private float kingTeleT;
    /** 三阶段地刺：距下一批的剩余时间 */
    private float kingSpikeCd;
    /** 三阶段深渊牵引：距下一次的剩余时间 */
    private float kingPullCd;
    /** 本次牵引的剩余时间（>0 = 玩家正被拽向王座） */
    private float kingPullT;
    /** 三阶段裂隙召唤 Boss：距下一次的剩余时间 */
    private float kingSummonCd;

    /**
     * 玩家的指挥指令：鼠标点击（或按住）时记下世界坐标。
     * 宠物的第一优先级是"朝这里进攻"，有效期 MINION_ORDER_TIME 秒，
     * 按住鼠标会持续刷新——这样想让宠物打哪边，就把鼠标按在哪边。
     */
    private float orderX;
    private float orderY;
    private float orderT;

    /** 每个角色的技能配置，只有玩家实体有 */
    private final Loadout[] loadout = new Loadout[MAX];

    /** 各元素反应触发次数，压测与调试用 */
    private final int[] reactionCounts = new int[8];

    /**
     * 查询结果复用缓冲。注意：不可重入，任何方法在遍历它期间不能再发起新 query。
     * 因此所有"嵌套查询"（连锁弹射、爆炸）一律走 scratch2，绝不能混用。
     */
    private final IntList scratch = new IntList(512);
    private final IntList scratch2 = new IntList(512);
    /** 连锁闪电的"已命中"列表，防止弹回同一个目标 */
    private final IntList chainUsed = new IntList(64);

    /** 击杀回调，D3 的掉落经验会挂在这里 */
    public interface KillListener {
        void onKill(int victimId, float x, float y, int meta);
    }
    private KillListener killListener;

    public World(long seed) {
        this(seed, ArenaMap.DESERT_RUINS);
    }

    public World(long seed, ArenaMap arenaMap) {
        this.rng = new Random(seed);
        this.arenaMap = (arenaMap == null) ? ArenaMap.DESERT_RUINS : arenaMap;
    }

    // ------------------------------------------------------------------
    // 实体管理
    // ------------------------------------------------------------------

    public int alloc(int k, float sx, float sy, float radius, int teamId) {
        int id;
        if (freeTop > 0) {
            id = freeList[--freeTop];
        } else if (high < MAX) {
            id = high++;
        } else {
            return -1;   // 池满，直接放弃生成（比崩掉好）
        }
        alive[id] = true;
        kind[id] = k;
        team[id] = teamId;
        x[id] = sx;
        y[id] = sy;
        px[id] = sx;
        py[id] = sy;
        vx[id] = 0f;
        vy[id] = 0f;
        r[id] = radius;
        hp[id] = 1f;
        maxHp[id] = 1f;
        speed[id] = 0f;
        dmg[id] = 0f;
        life[id] = -1f;
        cd[id] = 0f;
        iframe[id] = 0f;
        owner[id] = -1;
        meta[id] = 0;
        pierce[id] = 0;
        lastHit[id] = -1;
        projAoe[id] = 0f;
        projChain[id] = 0;
        projBounce[id] = 0;
        projTarget[id] = -1;
        elem[id] = Element.NONE;
        elemT[id] = 0f;
        elemP[id] = 0f;
        stunT[id] = 0f;
        kx[id] = 0f;
        ky[id] = 0f;
        variant[id] = V_NORMAL;
        enemyShield[id] = 0f;
        carry[id] = 0f;
        serpent[id] = -1;          // 非骨蛇：没有归属的头节点
        summonT[id] = 0f;
        slot[id] = 0;
        liveCount++;
        return id;
    }

    public void kill(int id) {
        if (id < 0 || !alive[id]) {
            return;
        }
        alive[id] = false;
        // 骨蛇：头一死，整条散架。身体/尾巴走 despawn（不计击杀、不掉宝石），
        // 所以一条蛇只算一次击杀、只掉一次战利品。
        if (variant[id] == V_SERPENT && serpent[id] == id) {
            // 找出 head 所在的槽，把该槽的整条段列表都散架
            int slot = -1;
            for (int s2 = 0; s2 < MAX_BONE_SERPENT; s2++) {
                if (boneSerpentHead[s2] == id) { slot = s2; break; }
            }
            if (slot >= 0) {
                IntList list = serpentSegsBySlot[slot];
                if (list != null) {
                    for (int p = 0; p < list.size(); p++) {
                        int seg = list.get(p);
                        if (seg != id && seg >= 0 && alive[seg] && serpent[seg] == id) {
                            despawn(seg);
                        }
                    }
                    list.clear();
                }
                boneSerpentHead[slot] = -1;
            }
        }
        if (id == bossId) {
            // 按等级刷的 Boss 现在只是中途精英：倒下只清阶段标记，不再结束对局
            bossId = -1;   // Boss 倒下：清掉阶段技能标记，下一帧 updateBossPhase 也会兜底
        }
        if (id == milkyId) {
            milkyId = -1;  // 奶蛙血量归零：消失（客户端据此停掉专属 BGM）
            // 前置剧情触发点：击败奶蛙不再直接通关。记录倒地位置并置 milkyFallen，
            // 客户端据此锁定操作、强制播放剧情 CG；胜利判定移交给后续的王宫决战。
            milkyFallen = true;
            milkyDownX = x[id];
            milkyDownY = y[id];
            // 手动收尾：不走下方通用击杀块——避免弹升级三选一 / 掉宝石 / 胜利结算，
            // 让战场在剧情开始前保持安静（只保留击杀计数）。
            enemiesAlive--;
            kills++;
            bossKills++;
            kind[id] = KIND_FREE;
            liveCount--;
            if (freeTop < MAX) {
                freeList[freeTop++] = id;
            }
            return;
        }
        if (id == kingTwinId) {
            // 王座分身：血量并入本体血池（damage 已转发），正常流程不会独立死亡——
            // 这里兜底走安静收尾：不计击杀、不掉落、不弹升级。
            kingTwinId = -1;
            enemiesAlive--;
            kind[id] = KIND_FREE;
            liveCount--;
            if (freeTop < MAX) {
                freeList[freeTop++] = id;
            }
            return;
        }
        if (id == kingId) {
            int phaseFallen = kingPhase;
            kingId = -1;
            kingPhase = 0;
            // 手动收尾：国王不走通用击杀块（不弹升级三选一、不掉宝石），计数照常。
            enemiesAlive--;
            kills++;
            bossKills++;
            kind[id] = KIND_FREE;
            liveCount--;
            if (freeTop < MAX) {
                freeList[freeTop++] = id;
            }
            if (phaseFallen >= 3) {
                // 三阶段击破 = 真通关：王座本体轰然崩解（分裂出的分身随血池归零一起崩解）
                if (kingTwinId >= 0) {
                    despawn(kingTwinId);
                    kingTwinId = -1;
                }
                victory = true;
                summary = snapshot(true, false, firstWizard());
                return;
            }
            if (phaseFallen >= 2) {
                // 二阶段击破：不结算。记录倒地锚点并置 kingFallen2，客户端据此弹
                // 「王座本体」过渡剧情；播完调用 beginKingPhase3，王座觉醒进入最终决战。
                kingFallen2 = true;
                kingDownX = x[id];
                kingDownY = y[id];
                return;
            }
            // 一阶段击破：不直接结算。记录倒地位置并置 kingFallen，客户端据此弹决裂对白；
            // 对白播完调用 beginKingPhase2 —— 国王在王座上以二阶段重生（消耗计时重置）。
            kingFallen = true;
            kingDownX = x[id];
            kingDownY = y[id];
            return;
        }
        int k = kind[id];
        if (k == KIND_ENEMY) {
            enemiesAlive--;
            kills++;
            // 骨蛇按 Boss 计（否则它的击杀会混进"普通击杀"里，结算数字对不上）
            if (variant[id] == V_BOSS || variant[id] == V_SERPENT) {
                bossKills++;
            }
            // 击杀按等级刷的 Boss：奖励一张技能卡（升级三选一面板会因此弹出）
            if (variant[id] == V_BOSS) {
                int wz = firstWizard();
                if (wz >= 0) {
                    grantBossCard(wz);
                }
            }
            healWarriorsOnKill();
            if (killListener != null) {
                killListener.onKill(id, x[id], y[id], meta[id]);
            }
            int v = variant[id];
            if (v == V_SERPENT) {
                // 小 Boss：一圈宝石铺开，别全叠在一个点上
                int drop = com.arcanebrigade.core.enemy.EnemyStats.SERPENT_GEM_DROP;
                for (int g = 0; g < drop; g++) {
                    float a = (float) (Math.PI * 2) * g / drop;
                    spawnXpGem(x[id] + (float) Math.cos(a) * 30f,
                            y[id] + (float) Math.sin(a) * 30f, Balance.GEM_VALUE);
                }
            } else if (v == V_THIEF) {
                // 小偷：掉落携带的 2 倍宝石（至少 1 个）
                int drop = Math.max(1, (int) (carry[id] * 2f));
                for (int g = 0; g < drop; g++) {
                    float a = rng.nextFloat() * (float) (Math.PI * 2);
                    spawnXpGem(x[id] + (float) Math.cos(a) * 14f,
                            y[id] + (float) Math.sin(a) * 14f, Balance.GEM_VALUE);
                }
            } else if (v == V_SPLIT) {
                // 分裂：死亡裂成数只普通子代，HP 按比例缩小（子代不再分裂）
                int childHp = (int) (maxHp[id] * Balance.SPLIT_HP_MUL);
                for (int s = 0; s < Balance.SPLIT_COUNT; s++) {
                    float a = rng.nextFloat() * (float) (Math.PI * 2);
                    int cid = spawnEnemy(x[id] + (float) Math.cos(a) * 22f,
                            y[id] + (float) Math.sin(a) * 22f, meta[id], V_NORMAL);
                    if (cid >= 0) {
                        maxHp[cid] = childHp;
                        hp[cid] = childHp;
                        r[cid] *= Balance.SPLIT_RADIUS_MUL;
                    }
                }
                spawnXpGem(x[id], y[id], Balance.GEM_VALUE);
            } else {
                spawnXpGem(x[id], y[id], Balance.GEM_VALUE);
            }
        }
        if (k == KIND_WIZARD && !defeat) {
            // 主控玩家阵亡：立刻冻一份战报，客户端据此停止推进并弹结算画面。
            // 之前这里什么都不做，玩家死后游戏会一直空转（没有单位可操作）却永远不结束。
            if (milkyHitActive) {
                killedByMilky = true;   // 死于奶蛙技能：阵亡画面显示专属图与「压力！」
            }
            defeat = true;
            summary = snapshot(false, false, id);
        }
        kind[id] = KIND_FREE;
        liveCount--;
        if (freeTop < MAX) {
            freeList[freeTop++] = id;
        }
    }

    /**
     * 非击杀移除：走出范围、寿命耗尽。
     * 与 kill() 的区别是不计击杀数、不触发掉落回调——否则把怪刷没了也算你杀的。
     */
    public void despawn(int id) {
        if (id < 0 || !alive[id]) {
            return;
        }
        alive[id] = false;
        if (kind[id] == KIND_ENEMY) {
            enemiesAlive--;
        }
        kind[id] = KIND_FREE;
        liveCount--;
        if (freeTop < MAX) {
            freeList[freeTop++] = id;
        }
    }

    // ------------------------------------------------------------------
    // 生成器
    // ------------------------------------------------------------------

    public int spawnWizard(float sx, float sy) {
        return spawnWizard(sx, sy, HeroClass.WIZARD);
    }

    /**
     * 按职业生成玩家。设置基础 HP / 移速、起手主动技能、起手被动（法师带"重抽"），
     * 并把职业记到 Loadout 上——Stats.recompute 据此套用职业特性（减伤 / 暴击 / 法伤）。
     */
    public int spawnWizard(float sx, float sy, int classKind) {
        int id = alloc(KIND_WIZARD, sx, sy, Balance.WIZARD_RADIUS, TEAM_PLAYER);
        if (id < 0) {
            return -1;
        }
        float hp0 = HeroClass.baseHp(classKind);
        maxHp[id] = hp0;
        hp[id] = hp0;
        speed[id] = HeroClass.baseSpeed(classKind);
        Loadout lo = new Loadout();
        lo.classKind = classKind;
        lo.set(0, HeroClass.startSpell(classKind));
        for (int pid : HeroClass.startPassives(classKind)) {
            lo.addPassive(pid);
        }
        loadout[id] = lo;
        wizards.add(id);
        dashCd[id] = 0f;       // 出生即可用一次位移
        // 弓箭手：出生即满发；其他职业不画冲刺 HUD，charge 字段无意义
        dashCharges[id] = (classKind == HeroClass.ARCHER) ? Balance.ARCHER_DASH_MAX : 0;
        dashTime[id] = 0f;
        vx[id] = 0f;
        vy[id] = 0f;
        return id;
    }

    public int spawnEnemy(float sx, float sy) {
        return spawnEnemy(sx, sy, rng.nextInt(3), V_NORMAL);
    }

    /**
     * 生成敌人。baseType 是外观（0 史莱姆 / 1 蝙蝠 / 2 兽人），variant 是行为变体。
     * 变体的数值修正（精英 / 小偷 / 远程 / 分裂）集中在这里，不在逻辑里散落。
     */
    public int spawnEnemy(float sx, float sy, int baseType, int variant) {
        int id = alloc(KIND_ENEMY, sx, sy, Balance.ENEMY_RADIUS, TEAM_ENEMY);
        if (id < 0) {
            return -1;
        }
        this.variant[id] = variant;
        meta[id] = baseType;

        float hp0 = Balance.ENEMY_HP;
        float sp0 = Balance.ENEMY_SPEED;
        float dm0 = Balance.ENEMY_DAMAGE;
        if (variant == V_ELITE) {
            hp0 *= Balance.ELITE_HP_MUL;
            sp0 *= Balance.ELITE_SPEED_MUL;
            dm0 *= Balance.ELITE_DMG_MUL;
        } else if (variant == V_THIEF) {
            hp0 = Balance.THIEF_HP;
            sp0 = Balance.THIEF_SPEED;
            dm0 = 0f;       // 小偷不主动攻击玩家
        } else if (variant == V_RANGED) {
            hp0 = Balance.RANGED_HP;
            sp0 = Balance.RANGED_SPEED;
            dm0 = 0f;       // 伤害来自弹幕而非接触
        } else if (variant == V_STATUE) {
            // 战斗事件「摧毁雕像」：不移动、不攻击的静态靶子，血偏厚
            hp0 = Balance.STATUE_HP;
            sp0 = 0f;
            dm0 = 0f;
        }
        // 血量随时间成长：怪的数量砍掉后，难度主要由这条曲线承担。
        // 统一在这里乘，避免每个生成点各乘一次导致叠加。
        // Boss 不吃这条曲线——它有自己的 BOSS_HP_TIERS 分档。
        float scale = (variant == V_BOSS) ? 1f : Balance.enemyHpScale(time);
        maxHp[id] = hp0 * scale;
        hp[id] = maxHp[id];
        speed[id] = sp0;
        dmg[id] = dm0;
        if (variant == V_STATUE) {
            r[id] = Balance.STATUE_RADIUS;   // 雕像更大，作为可摧毁目标更醒目
        }
        if (variant == V_ELITE) {
            // 护盾也跟着涨，但涨幅封顶，否则后期精英变成打不破的壳
            enemyShield[id] = Balance.ELITE_SHIELD * Math.min(scale, 6f);
        }
        // RANGED 用 cd 当发射计时器；其余接触伤害由 updateEnemies 维护 cd
        cd[id] = (variant == V_RANGED) ? Balance.RANGED_CD : 0f;
        enemiesAlive++;
        return id;
    }

    /**
     * 生成投射物。所有数值（power / pierce / aoe / chain / bounce）都已经在 castOne 里
     * 乘过 Stats 的修正，这里不再二次计算。
     */
    public int spawnProjectile(SpellDef def, int spellId, float sx, float sy, float dx, float dy,
                               int ownerId, float power, int pierce, float aoe,
                               int chain, int bounce) {
        int id = alloc(KIND_PROJECTILE, sx, sy, def.boltRadius, team[ownerId]);
        if (id < 0) {
            return -1;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) {
            despawn(id);
            return -1;
        }
        vx[id] = dx / len * def.speed;
        vy[id] = dy / len * def.speed;
        dmg[id] = power;
        life[id] = def.boltLife();
        owner[id] = ownerId;
        meta[id] = spellId;          // 记住"基础形态 id"，用于回查 def 与判断进化
        this.pierce[id] = pierce;
        projAoe[id] = aoe;
        projChain[id] = chain;
        projBounce[id] = bounce;
        lastHit[id] = -1;
        projTarget[id] = -1;
        return id;
    }

    /**
     * 在指定位置生成地面区域。owner 用于友伤判定（D6 加），D3 阶段都算玩家阵营的。
     */
    public int spawnZone(int subtype, float sx, float sy, float radius, float duration,
                         float dps, int element, int ownerId) {
        int id = alloc(KIND_ZONE, sx, sy, radius, ownerId >= 0 ? team[ownerId] : TEAM_PLAYER);
        if (id < 0) {
            return -1;
        }
        life[id] = duration;
        speed[id] = duration;
        dmg[id] = dps;
        meta[id] = subtype;
        elem[id] = element;
        owner[id] = ownerId;
        cd[id] = 0f;
        iframe[id] = 0f;             // 陷阱用：0 = 待触发，1 = 已触发
        return id;
    }

    /**
     * 在指定位置生成经验宝石。value 是经验值。
     * meta=0 普通宝石，meta=1 宝箱（拾取直接给一次升级）
     */
    public int spawnXpGem(float sx, float sy, float value) {
        int id = alloc(KIND_PICKUP, sx, sy, Balance.GEM_RADIUS, TEAM_PLAYER);
        if (id < 0) {
            return -1;
        }
        vx[id] = 0f;
        vy[id] = 0f;
        dmg[id] = value;
        life[id] = Balance.GEM_LIFE;
        meta[id] = 0;
        return id;
    }

    /** 宝箱（拾取 → 立即升级一次） */
    public int spawnChest(float sx, float sy) {
        int id = alloc(KIND_PICKUP, sx, sy, Balance.GEM_RADIUS + 2f, TEAM_PLAYER);
        if (id < 0) {
            return -1;
        }
        vx[id] = 0f;
        vy[id] = 0f;
        dmg[id] = 0f;
        life[id] = Balance.GEM_LIFE;
        meta[id] = 1;       // 1 = 宝箱
        return id;
    }

    /** 蘑菇（战斗事件「采集蘑菇」）：玩家走过即拾取，计入事件进度并给少量经验。 */
    public int spawnMushroom(float sx, float sy) {
        int id = alloc(KIND_PICKUP, sx, sy, 9f, TEAM_PLAYER);
        if (id < 0) {
            return -1;
        }
        vx[id] = 0f;
        vy[id] = 0f;
        dmg[id] = Balance.GEM_VALUE;
        life[id] = Balance.MUSHROOM_LIFE;
        meta[id] = PICKUP_MUSHROOM;
        return id;
    }

    /**
     * 视觉特效。FX 不移动，所以 vx/vy 借给 FX_BEAM 存终点坐标，
     * speed 借给总寿命（渲染要按 life/speed 算淡出比例）。
     */
    public int spawnFx(int fxType, float sx, float sy, float ex, float ey,
                       float radius, float ttl, int element) {
        int id = alloc(KIND_FX, sx, sy, Math.max(radius, 1f), TEAM_PLAYER);
        if (id < 0) {
            return -1;
        }
        vx[id] = ex;
        vy[id] = ey;
        life[id] = ttl;
        speed[id] = ttl;
        meta[id] = fxType;
        elem[id] = element;
        return id;
    }

    /** 在玩家周围的环形区域外圈生成敌人（屏幕外，避免"凭空出现"） */
    public void spawnAtEdge() {
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        float dist = Balance.SPAWN_RING_IN
                + rng.nextFloat() * (Balance.SPAWN_RING_OUT - Balance.SPAWN_RING_IN);
        if (!pickRouteSpawn(x[w], y[w], dist, Balance.ENEMY_RADIUS)) {
            return;
        }

        int id = spawnEnemy(routeSpawnX, routeSpawnY);
        if (id < 0) {
            return;
        }
        // 血量成长已经在 spawnEnemy 里按 Balance.enemyHpScale(time) 统一乘过
        speed[id] = Balance.ENEMY_SPEED * (0.85f + rng.nextFloat() * 0.35f);
    }

    /** 在屏幕外环形生成一只指定变体的敌人（WaveDirector 分派用） */
    public void spawnEnemyVariant(int variant) {
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        float dist = Balance.SPAWN_RING_IN
                + rng.nextFloat() * (Balance.SPAWN_RING_OUT - Balance.SPAWN_RING_IN);
        if (!pickRouteSpawn(x[w], y[w], dist, Balance.ENEMY_RADIUS)) {
            return;
        }
        // 变体数值（精英×6 / 小偷 / 远程）与时间成长都在 spawnEnemy 里定好
        spawnEnemy(routeSpawnX, routeSpawnY, rng.nextInt(3), variant);
    }

    /**
     * 生成一个 Boss。tier 是 BOSS_LEVELS / BOSS_HP_TIERS 的下标（0..3），
     * 血池、伤害、护盾都按档位取，越靠后越硬。
     *
     * 生成位置在玩家外侧但仍在可视范围内（比普通刷怪环更近），
     * 让玩家能看见 Boss 压过来，而不是"凭空出现在脸上"。
     * 记录 bossId 供 updateBossPhase 驱动阶段技能。
     */
    public void spawnBoss(int tier) {
        int w = firstWizard();
        float tx = (w >= 0) ? x[w] : 0f;
        float ty = (w >= 0) ? y[w] : 0f;
        int t = Math.max(0, Math.min(tier, Balance.BOSS_HP_TIERS.length - 1));
        if (!pickRouteSpawn(tx, ty, Balance.BOSS_SPAWN_DIST, Balance.BOSS_RADIUS)) {
            return;
        }
        int id = spawnEnemy(routeSpawnX, routeSpawnY, rng.nextInt(3), V_BOSS);
        if (id < 0) {
            return;
        }
        maxHp[id] = Balance.BOSS_HP_TIERS[t];
        hp[id] = maxHp[id];
        speed[id] = Balance.BOSS_SPEED;
        r[id] = Balance.BOSS_RADIUS;
        dmg[id] = Balance.BOSS_DMG_TIERS[t];
        // 护盾按档位递增：后面的 Boss 先得破壳才能掉血
        enemyShield[id] = Balance.ELITE_SHIELD * (1f + t * 0.6f);
        bossId = id;
        bossTier = t;
        bossWarningTimer = Balance.WARNING_TELEGRAPH + 1.5f;
        bossSummonTimer = Balance.BOSS_SUMMON_INTERVAL;
        // 飞碟（tier 0）：首轮追踪弹从第一个 CD 开始计时，让玩家一上来就被告知「会飞弹」的压迫感
        bossHomingTimer = (t == 0) ? Balance.BOSS_HOMING_CD : 0f;
        // 飞龙（tier 1 / 2）：首轮火球按对应 CD 起步，让飞龙出场就能立刻吐火——节奏跟飞碟对齐
        bossBoltTimer = (t == 1) ? Balance.DRAGON_BOLT_CD_TIER1
                       : (t == 2) ? Balance.DRAGON_BOLT_CD_TIER2 : 0f;
    }

    /**
     * 在蛇形路线内挑选屏幕外生成点。优先使用玩家前后相连的可走段；若当前段没有合适
     * 的远端落点，退回当前战斗节点的边缘，而不是把怪刷到沙海 / 熔岩 / 坍塌墙体里。
     */
    private boolean pickRouteSpawn(float originX, float originY, float desiredDistance, float radius) {
        float baseAngle = rng.nextFloat() * (float) (Math.PI * 2);
        for (int attempt = 0; attempt < 24; attempt++) {
            float angle = baseAngle + attempt * ((float) (Math.PI * 2) / 24f);
            float distance = desiredDistance * (0.82f + (attempt % 4) * 0.05f);
            float sx = originX + (float) Math.cos(angle) * distance;
            float sy = originY + (float) Math.sin(angle) * distance;
            if (arenaMap.isWalkable(sx, sy, radius)) {
                routeSpawnX = sx;
                routeSpawnY = sy;
                return true;
            }
        }
        // 兜底：可走区已是城墙内侧整片连续区域，直接在原点沿随机方向取一点并钳制到边界内。
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        routeSpawnX = clampX(originX + (float) Math.cos(ang) * desiredDistance);
        routeSpawnY = clampY(originY + (float) Math.sin(ang) * desiredDistance);
        return arenaMap.isWalkable(routeSpawnX, routeSpawnY, radius);
    }

    /**
     * 生成一条骨蛇（小 Boss）。在 WaveDirector 判定玩家等级到 SERPENT_LEVELS
     * 且大 Boss 不在场时调用。
     *
     * 一条骨蛇是 N 个 V_SERPENT 敌人实体（头 / 身体×N / 尾），共享血池、共用 head id。
     * 这里把段全塞进 slot 自己的 serpentSegsBySlot[slot]（按槽独立存），每段的 serpent[i]
     * 都指向 head，这样 damage(id) 看到非头节点就把伤害转到头节点；kill(head) 时按 V_SERPENT
     * 分支把整条蛇的段一并 despawn。head 自己也用 bossId 通道占位，从而和大 Boss 互斥。
     *
     * 段内沿主轴排开，让 followBody 立刻有合理的初值，避免开怪时蛇身"瞬移"到轨迹上。
     */
    public int spawnBoneSerpent() {
        int slot = -1;
        for (int i = 0; i < MAX_BONE_SERPENT; i++) {
            if (boneSerpentHead[i] < 0) { slot = i; break; }
        }
        if (slot < 0) {
            return -1;   // 槽位满了：现在最多 4 条并存，足够用了
        }
        int wiz = firstWizard();
        if (wiz < 0) {
            return -1;
        }
        float tx = x[wiz];
        float ty = y[wiz];
        if (!pickRouteSpawn(tx, ty, EnemyStats.SERPENT_SPAWN_DIST, EnemyStats.SERPENT_HEAD_RADIUS)) {
            return -1;
        }
        float sx = routeSpawnX;
        float sy = routeSpawnY;

        // 给本槽建一个独立的段列表，理论上前一条已经死了（slot=-1 时不会到这）
        IntList segList = serpentSegsBySlot[slot];
        if (segList == null) {
            segList = new IntList(BONE_SERPENT_SEG);
            serpentSegsBySlot[slot] = segList;
        } else {
            segList.clear();
        }
        int head = -1;
        for (int s = 0; s < BONE_SERPENT_SEG; s++) {
            int id = alloc(KIND_ENEMY, sx + s * EnemyStats.SERPENT_SPACING, sy,
                    (s == 0) ? EnemyStats.SERPENT_HEAD_RADIUS : EnemyStats.SERPENT_BODY_RADIUS,
                    TEAM_ENEMY);
            if (id < 0) {
                // 池满了：把已分配的段散架
                for (int p = 0; p < segList.size(); p++) {
                    despawn(segList.get(p));
                }
                segList.clear();
                boneSerpentHead[slot] = -1;
                return -1;
            }
            // 头一节（s==0）作为 bossId 占位，让 spawnBoneSerpent 与 spawnBoss 天然互斥
            if (s == 0) {
                head = id;
                boneSerpentHead[slot] = head;
                bossId = head;            // 与大 Boss 互斥的通道
                bossTier = -2;            // 标记是骨蛇；HUD/阶段逻辑按 bossTier 走
                bossWarningTimer = 0f;    // 小 Boss 不需要预警圈，直接出现
                bossSummonTimer = 0f;
            }
            variant[id] = V_SERPENT;
            meta[id] = 0;
            maxHp[id] = EnemyStats.SERPENT_HP;
            hp[id] = EnemyStats.SERPENT_HP;
            speed[id] = EnemyStats.SERPENT_SPEED;
            dmg[id] = EnemyStats.SERPENT_DMG;
            cd[id] = 0f;
            enemyShield[id] = 0f;
            carry[id] = 0f;
            summonT[id] = 0f;
            serpent[id] = head;          // 每一节都指向 head（head 自己 serpent==head）
            enemiesAlive++;
            segList.add(id);
        }
        return head;
    }

    /** 当前场上是否有骨蛇存活。大 Boss 优先级仍更高（共用 bossId 通道时只可能有一只） */
    public boolean serpentActive() {
        return boneSerpentHead[0] >= 0
                || boneSerpentHead[1] >= 0
                || boneSerpentHead[2] >= 0
                || boneSerpentHead[3] >= 0;
    }

    // ------------------------------------------------------------------
    // 推进
    // ------------------------------------------------------------------

    public void step(float dt, InputCommand in) {
        time += dt;

        for (int i = 0; i < high; i++) {
            px[i] = x[i];
            py[i] = y[i];
        }

        if (!kingArena) {
            updateStage(dt);      // 阶段推进 + 首次障碍生成（决战场景不推进沙漠阶段）
        }
        rebuildEnemyHash();
        updateOrder(dt, in);      // 鼠标指挥指令的有效期
        updateStatus(dt);
        updateWizards(dt, in);
        updateSummoners(dt);      // 召唤师：到点召唤一批宠物
        if (!kingArena) {
            updateEvents(dt);     // 战斗事件：到点触发 + 进度推进 + 完成发经验（决战中不再触发）
        }
        specialPassiveTick(dt);
        if (spawningEnabled) {
            director.update(this, dt);   // 调试可关闭：只打 Boss，不刷小怪
        }
        updateEnemies(dt);
        updateMinions(dt);        // 宠物 AI：护主 / 听指挥 / 拴绳
        if (bossId >= 0) {
            updateBossPhase(dt);  // Boss 阶段技能（预警圈 / 召唤）
        }
        // 奶蛙：玩家等级达到 MILKY_LEVEL 时从场地中央刷新，之后走自己的状态机
        if (!milkySpawned && firstWizard() >= 0 && playerLevel() >= Balance.MILKY_LEVEL) {
            spawnMilky();
        }
        if (milkyId >= 0) {
            updateMilky(dt);
        }
        if (kingId >= 0) {
            updateKing(dt);   // 王宫决战：国王技能 + 消耗机制（追击在 updateEnemies 里）
        }
        castSpells(dt, in);
        updateProjectiles(dt);
        updateDragonBolts(dt);     // 飞龙火球：定向飞行 + 沿途播种灼烧带
        updatePickups(dt);
        updateZones(dt);
        updateDragonBurns(dt);     // 飞龙灼烧带：玩家/宠物路过持续扣血
        updateArenaTraps();
        updateFx(dt);
        if (!kingArena) {
            cullDistant();   // 竞技场场地小、实体不会跑远，不需要回收
        }
    }

    /**
     * 阶段仍负责波次节奏，但关卡本体不再随阶段随机重刷：一张地图就是一整局
     * 固定的单屏战场，玩家可以学习掩体和机关的位置。
     */
    private void updateStage(float dt) {
        if (!obstaclesGenerated) {
            if (firstWizard() >= 0) {
                generateObstacles(0);
                obstaclesGenerated = true;
            }
            return;
        }
        stageTimer += dt;
        if (stage < Balance.STAGE_COUNT - 1
                && stageTimer >= Balance.STAGE_DURATIONS[stage]) {
            stageTimer -= Balance.STAGE_DURATIONS[stage];
            stage++;
        }
    }

    /** 清空所有障碍物（场景切换时调用） */
    private void clearObstacles() {
        for (int i = 0; i < high; i++) {
            if (alive[i] && kind[i] == KIND_OBSTACLE) {
                alive[i] = false;
                kind[i] = KIND_FREE;
                liveCount--;
                if (freeTop < MAX) {
                    freeList[freeTop++] = i;
                }
            }
        }
        obstacleHash.beginFrame();
    }

    /**
     * 生成作者摆放的障碍物。这里特意不使用随机数：地图图像、碰撞、投射物遮挡
     * 三者必须一一对应，不能再出现“画面里是石柱，实际碰撞在别处”的情况。
     */
    private void generateObstacles(int stage) {
        obstacleHash.beginFrame();
        for (ArenaMap.Obstacle obstacle : arenaMap.obstacles()) {
            spawnObstacle(obstacle);
        }
    }

    /** 机关四拍循环：预警 → 蓄力 → 伤害窗口 → 恢复。只对玩家/宠物结算，避免环境自行清场。 */
    private void updateArenaTraps() {
        for (ArenaMap.Trap trap : arenaMap.traps()) {
            if (!trap.active(time) || trap.damage() <= 0f) {
                continue;
            }
            damageTrapTargets(trap);
        }
    }

    private void damageTrapTargets(ArenaMap.Trap trap) {
        for (int n = 0; n < wizards.size(); n++) {
            int id = wizards.get(n);
            if (!alive[id] || iframe[id] > 0f || !trap.contains(x[id], y[id], r[id])) {
                continue;
            }
            damage(id, trap.damage());
            iframe[id] = heroIframe(id);
        }
        for (int n = 0; n < minions.size(); n++) {
            int id = minions.get(n);
            if (!alive[id] || iframe[id] > 0f || !trap.contains(x[id], y[id], r[id])) {
                continue;
            }
            damage(id, trap.damage());
            iframe[id] = Balance.MINION_IFRAME;
        }
    }

    private int spawnObstacle(ArenaMap.Obstacle obstacle) {
        int id = alloc(KIND_OBSTACLE, obstacle.x(), obstacle.y(), obstacle.broadRadius(), TEAM_ENEMY);
        if (id < 0) {
            return -1;
        }
        meta[id] = obstacle.visual();
        obstacleShape[id] = obstacle.shape().ordinal();
        obstacleHalfW[id] = obstacle.halfWidth();
        obstacleHalfH[id] = obstacle.halfHeight();
        obstacleBlocksMovement[id] = obstacle.blocksMovement();
        obstacleBlocksProjectiles[id] = obstacle.blocksProjectiles();
        obstacleDestructible[id] = obstacle.destructible();
        obstacleHash.insert(x[id], y[id], id);
        return id;
    }

    private void rebuildEnemyHash() {
        enemyHash.beginFrame();
        minions.clear();
        for (int i = 0; i < high; i++) {
            if (!alive[i]) {
                continue;
            }
            if (kind[i] == KIND_ENEMY) {
                enemyHash.insert(x[i], y[i], i);
            } else if (kind[i] == KIND_MINION) {
                minions.add(i);
            }
        }
    }

    /** 指挥指令：按住鼠标持续刷新，松手后再延续 MINION_ORDER_TIME 秒 */
    private void updateOrder(float dt, InputCommand in) {
        if ((in.buttons & InputCommand.BUTTON_ORDER) != 0) {
            orderX = in.aimX;
            orderY = in.aimY;
            orderT = Balance.MINION_ORDER_TIME;
        } else if (orderT > 0f) {
            orderT -= dt;
        }
    }

    // ------------------------------------------------------------------
    // 战斗事件（小任务）
    // ------------------------------------------------------------------

    /** 到点触发下一个小任务，并推进当前任务进度；完成后发放经验。 */
    private void updateEvents(float dt) {
        if (victory || defeat) {
            return;
        }
        if (eventBannerT > 0f) {
            eventBannerT -= dt;
        }
        // 到点触发：若上一个任务还没做完，则顶掉它（旧实体清理、无奖励），保证节奏不被拖死
        if (eventType == 0 && eventIndex < Balance.EVENT_TIMES.length
                && time >= Balance.EVENT_TIMES[eventIndex]) {
            startEvent(eventIndex);
            eventIndex++;
        }
        if (eventType == 0) {
            return;
        }

        int w = firstWizard();
        if (w < 0) {
            return;
        }
        switch (eventType) {
            case Balance.EVENT_RIFT -> {
                float dx = x[w] - eventX;
                float dy = y[w] - eventY;
                float d2 = dx * dx + dy * dy;
                float rr = Balance.RIFT_RADIUS + Balance.WIZARD_RADIUS;
                if (d2 <= rr * rr) {
                    eventProgress += dt;
                }
                if (eventProgress >= eventGoal) {
                    completeEvent();
                }
            }
            case Balance.EVENT_STATUE -> {
                // 雕像被击杀会从 eventIds 里剔除，剩余数量即进度
                int remain = 0;
                for (int n = 0; n < eventIds.size(); n++) {
                    int e = eventIds.get(n);
                    if (alive[e] && kind[e] == KIND_ENEMY) {
                        remain++;
                    }
                }
                eventProgress = Balance.STATUE_COUNT - remain;
                if (remain == 0) {
                    completeEvent();
                }
            }
            case Balance.EVENT_MUSHROOM -> {
                if (eventProgress >= eventGoal) {
                    completeEvent();
                }
            }
            default -> { }
        }
    }

    /** 开启第 idx 个事件（下标对齐 Balance.EVENT_TIMES）。 */
    private void startEvent(int idx) {
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        // 事件中心：离玩家一小段距离，避免直接压在玩家脸上
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        float dist = 260f + rng.nextFloat() * 260f;
        eventX = clampX(x[w] + (float) Math.cos(ang) * dist);
        eventY = clampY(y[w] + (float) Math.sin(ang) * dist);
        eventProgress = 0f;
        eventIds.clear();

        if (idx == 0) {   // 封印裂隙
            eventType = Balance.EVENT_RIFT;
            eventGoal = Balance.RIFT_HOLD_TIME;
        } else if (idx == 1) {   // 摧毁雕像
            eventType = Balance.EVENT_STATUE;
            eventGoal = Balance.STATUE_COUNT;
            for (int s = 0; s < Balance.STATUE_COUNT; s++) {
                float a = rng.nextFloat() * (float) (Math.PI * 2);
                float d = 70f + rng.nextFloat() * 150f;
                int id = spawnEnemy(clampX(eventX + (float) Math.cos(a) * d),
                        clampY(eventY + (float) Math.sin(a) * d), 0, V_STATUE);
                if (id >= 0) {
                    eventIds.add(id);
                }
            }
        } else {   // 采集蘑菇
            eventType = Balance.EVENT_MUSHROOM;
            eventGoal = Balance.MUSHROOM_COUNT;
            // 以玩家为圆心撒一圈：事件中心离玩家几百单位，继续围着中心散布的话
            // 最远的一朵会跑到 900+ 单位外，找齐 8 朵全靠运气。
            eventX = x[w];
            eventY = y[w];
            for (int m = 0; m < Balance.MUSHROOM_COUNT; m++) {
                // 等分角度 + 抖动：既铺满一圈，又不会几朵挤在一处
                float a = (float) (Math.PI * 2 * m / Balance.MUSHROOM_COUNT)
                        + (rng.nextFloat() - 0.5f) * 0.8f;
                float d = Balance.MUSHROOM_SPAWN_MIN
                        + rng.nextFloat() * (Balance.MUSHROOM_SPAWN_MAX - Balance.MUSHROOM_SPAWN_MIN);
                float sx = eventX + (float) Math.cos(a) * d;
                float sy = eventY + (float) Math.sin(a) * d;
                if (!arenaMap.isWalkable(sx, sy, 10f)) {
                    continue;
                }
                int id = spawnMushroom(sx, sy);
                if (id >= 0) {
                    eventIds.add(id);
                }
            }
        }
    }

    /** 调试用：强制开启第 idx 个战斗事件（跳过触发时间）。冒烟 / 手动测试用。 */
    public void forceEvent(int idx) {
        if (eventType != 0) {
            return;
        }
        if (idx < 0 || idx >= Balance.EVENT_TIMES.length) {
            return;
        }
        startEvent(idx);
    }

    /** 完成任务：清场 + 发经验 + 横幅提示。 */
    private void completeEvent() {
        if (eventType == 0) {
            return;
        }
        // 清理事件实体（蘑菇/雕像），裂隙无实体
        for (int n = 0; n < eventIds.size(); n++) {
            int e = eventIds.get(n);
            if (alive[e] && (kind[e] == KIND_ENEMY || kind[e] == KIND_PICKUP)) {
                despawn(e);
            }
        }
        eventIds.clear();
        // 发经验：所有存活玩家（当前是单主控，但写通用点没坏处）
        for (int n = 0; n < wizards.size(); n++) {
            int id = wizards.get(n);
            if (!alive[id] || loadout[id] == null) {
                continue;
            }
            Loadout lo = loadout[id];
            float mul = Balance.EVENT_XP_MUL[eventIndex - 1 < 0 ? 0 : eventIndex - 1];
            lo.gainXp(Loadout.xpForLevel(lo.level) * mul);
        }
        eventCompleted++;
        eventBannerT = 3.5f;
        eventType = 0;
        eventProgress = 0f;
        eventGoal = 0f;
    }

    /** 暂停菜单「退出结算」：主动结束本局，冻结战报（标题与阵亡区分）。 */
    public void abandon() {
        if (victory || defeat || abandoned) {
            return;
        }
        abandoned = true;
        summary = snapshot(false, true, firstWizard());
    }

    /** 采集一朵蘑菇：由 updatePickups 在玩家拾取时回调，推进蘑菇事件进度。 */
    private void takeMushroom() {
        if (eventType == Balance.EVENT_MUSHROOM) {
            eventProgress++;
        }
    }

    /** 元素状态的持续效果：燃烧跳伤害、到期清除。冰霜与雷电是被动修正，在读取处生效 */
    private void updateStatus(float dt) {
        // 每帧算一次就够，别在上千实体的循环里反复调 exp
        float decay = (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
        for (int i = 0; i < high; i++) {
            if (!alive[i] || kind[i] != KIND_ENEMY) {
                continue;
            }
            if (stunT[i] > 0f) {
                stunT[i] -= dt;
            }
            kx[i] *= decay;
            ky[i] *= decay;

            if (elemT[i] <= 0f) {
                continue;
            }
            elemT[i] -= dt;
            if (elem[i] == Element.FIRE || elem[i] == Element.POISON) {
                damage(i, elemP[i] * dt);
                if (!alive[i]) {
                    continue;
                }
            }
            if (elemT[i] <= 0f) {
                elem[i] = Element.NONE;
                elemP[i] = 0f;
            }
        }
    }

    private void updateWizards(float dt, InputCommand in) {
        for (int n = 0; n < wizards.size(); n++) {
            int id = wizards.get(n);
            if (!alive[id]) {
                continue;
            }
            Loadout lo = loadout[id];
            Stats st = (lo != null) ? lo.stats : null;
            int ck = (lo != null) ? lo.classKind : HeroClass.WIZARD;

            // 同步最大生命：被动变化后 maxHpAdd 也会变，每帧同步一次最简单
            if (st != null) {
                float newMax = HeroClass.baseHp(ck) + st.maxHpAdd;
                if (maxHp[id] != newMax) {
                    maxHp[id] = newMax;
                    if (hp[id] > newMax) {
                        hp[id] = newMax;
                    }
                }
            }

            // 绝境爆发判定
            if (st != null && st.lastStand && maxHp[id] > 0f
                    && hp[id] / maxHp[id] < Balance.LAST_STAND_HP_RATIO) {
                st.lastStandActive = true;
            } else if (st != null) {
                st.lastStandActive = false;
            }

            float moveMul = (st != null) ? st.moveMul : 1f;
            if (st != null && st.lastStandActive) {
                moveMul *= 1f + Balance.LAST_STAND_MOVE;
            }

            // 主动位移（冲刺）：空格触发，朝鼠标方向飞速位移一小段（弓箭手 & 战士）
            handleDash(in, id, ck);

            // 基础移速：职业速度 - 二阶段惩罚（王座转场 -20，见 beginKingPhase2）；40 保底后乘地图移速系数
            float baseSpeed = Math.max(40f, HeroClass.baseSpeed(ck) - kingSpeedPenalty)
                    * arenaMap.movementMultiplierAt(x[id], y[id]);
            x[id] += in.dx * baseSpeed * moveMul * dt;
            y[id] += in.dy * baseSpeed * moveMul * dt;
            // 位移期间的额外冲量（叠加在普通移动之上）
            if (dashTime[id] > 0f) {
                x[id] += vx[id] * dt;
                y[id] += vy[id] * dt;
                dashTime[id] -= dt;
                if (dashTime[id] <= 0f) {
                    dashTime[id] = 0f;
                    vx[id] = 0f;
                    vy[id] = 0f;
                }
            }
            resolveObstacles(id);
            if (kingArena) {
                clampKingArena(id);   // 决战场景：限制在王座厅竞技场内
            } else {
                clampToWorld(id);     // 玩家也被棕色城墙（边界）挡在内侧
            }
            if (iframe[id] > 0f) {
                iframe[id] -= dt;
            }
            // 主动位移冷却推进：战士单发 CD；弓箭手只缺弹药时计充能
            if (ck == HeroClass.ARCHER) {
                // 弓箭手：满发就停表，绝不往后跑负数
                if (dashCharges[id] < Balance.ARCHER_DASH_MAX) {
                    dashCd[id] -= dt;
                    if (dashCd[id] <= 0f) {
                        dashCharges[id]++;
                        dashCd[id] = (dashCharges[id] < Balance.ARCHER_DASH_MAX)
                                ? Balance.ARCHER_DASH_CD : 0f;
                    }
                }
            } else if (ck == HeroClass.WARRIOR) {
                if (dashCd[id] > 0f) {
                    dashCd[id] -= dt;
                }
            }
            // 回血：基础 + 被动 + 绝境爆发
            float regen = Balance.WIZARD_REGEN
                    + (st != null ? st.regenAdd : 0f);
            if (hp[id] < maxHp[id]) {
                hp[id] = Math.min(maxHp[id], hp[id] + regen * dt);
            }
        }
    }

    /**
     * 主动位移（冲刺）：空格按下且冷却就绪时，朝鼠标所指方向飞速位移一小段。
     *   - 弓箭手：充能型，出生满 3 发；每用一发扣 1 颗，每 5s 补 1 颗
     *   - 战士：单发 CD，5 秒一次
     * 位移期间附带短暂无敌帧，使其能真正用来躲避弹幕与接触伤害。
     * 位移用 vx/vy 承载冲量（与渲染朝向共用，冲刺时人物会朝位移方向），结束时归零。
     */
    private void handleDash(InputCommand in, int id, int ck) {
        if (ck != HeroClass.ARCHER && ck != HeroClass.WARRIOR) {
            return;     // 仅弓箭手与战士拥有主动位移（巫师/召唤师无）
        }
        if ((in.buttons & InputCommand.BUTTON_DASH) == 0) {
            return;     // 本帧未触发
        }
        // 就绪判定：弓箭手看充能数（>0 才能冲），战士看 dashCd 归零
        if (ck == HeroClass.ARCHER) {
            if (dashCharges[id] <= 0) {
                return;     // 弹药耗尽，硬等下一发
            }
        } else {
            if (dashCd[id] > 0f) {
                return;     // 战士的 CD 没好
            }
        }
        float dx = in.aimX - x[id];
        float dy = in.aimY - y[id];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            // 鼠标正好压在身上：改用当前移动输入方向；都没有则取消本次位移
            dx = in.dx;
            dy = in.dy;
            len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-3f) {
                return;
            }
        }
        float nx = dx / len;
        float ny = dy / len;
        // 按职业取对应的位移参数（集中在 Balance）
        float dashDist, dashTimeVal, dashCdReset, dashIframe;
        if (ck == HeroClass.WARRIOR) {
            dashDist    = Balance.WARRIOR_DASH_DIST;
            dashTimeVal = Balance.WARRIOR_DASH_TIME;
            dashCdReset = Balance.WARRIOR_DASH_CD;
            dashIframe  = Balance.WARRIOR_DASH_IFRAME;
        } else { // ARCHER
            dashDist    = Balance.ARCHER_DASH_DIST;
            dashTimeVal = Balance.ARCHER_DASH_TIME;
            dashCdReset = Balance.ARCHER_DASH_CD;
            dashIframe  = Balance.ARCHER_DASH_IFRAME;
        }
        float dashSpeed = dashDist / dashTimeVal;
        vx[id] = nx * dashSpeed;
        vy[id] = ny * dashSpeed;
        this.dashTime[id] = dashTimeVal;
        // 扣弹药 vs 重置战士 CD
        if (ck == HeroClass.ARCHER) {
            dashCharges[id]--;
            // dashCd 仍是下一发的充能倒计时：耗完一发后启动下一发的充能
            this.dashCd[id] = dashCdReset;
        } else { // WARRIOR
            this.dashCd[id] = dashCdReset;
        }
        // 位移无敌：取较大者，避免覆盖已有的受击无敌
        iframe[id] = Math.max(iframe[id], dashIframe);
    }

    private void updateEnemies(float dt) {
        for (int i = 0; i < high; i++) {
            if (kind[i] != KIND_ENEMY || !alive[i]) {
                continue;
            }
            // 被眩晕：不动也不打，但击退位移照常结算（看起来才像被炸飞）
            if (stunT[i] > 0f) {
                x[i] += kx[i] * dt;
                y[i] += ky[i] * dt;
                continue;
            }

            int v = variant[i];
            if (v == V_THIEF) {
                updateThief(i, dt);
                continue;
            }
            if (v == V_RANGED) {
                updateRanged(i, dt);
                continue;
            }
            if (v == V_STATUE) {
                // 战斗事件「摧毁雕像」：不移动、不攻击，纯靶子
                continue;
            }
            if (v == V_SERPENT) {
                // 骨蛇：只在头节点（serpent[i] == i）调度一次整蛇更新，
                // 身体/尾巴走 updateBoneSerpent 内的 context 跳过
                if (serpent[i] == i) {
                    int slot = -1;
                    for (int s2 = 0; s2 < MAX_BONE_SERPENT; s2++) {
                        if (boneSerpentHead[s2] == i) { slot = s2; break; }
                    }
                    if (slot >= 0) {
                        updateBoneSerpent(slot, dt);
                    }
                }
                continue;
            }
            // 其余（普通 / 精英 / 分裂 / Boss）走下方通用追击 + 接触伤害

            // 追击目标含宠物：宠物挡在怪和玩家之间时，怪会先啃宠物——这就是"护主"的实质
            int target = nearestPlayerUnit(x[i], y[i]);
            if (target < 0) {
                continue;
            }
            // 冰霜减速
            float slow = (elem[i] == Element.FROST)
                    ? Math.min(elemP[i], Balance.ELEM_FROST_MAX_SLOW) * ccMul(i) : 0f;
            float moveSpeed = speed[i] * (1f - slow) * arenaMap.movementMultiplierAt(x[i], y[i]);

            float dx = x[target] - x[i];
            float dy = y[target] - y[i];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1e-3f) {
                vx[i] = dx / len * moveSpeed;
                vy[i] = dy / len * moveSpeed;
            }

            // 同类分离力：不做这一步，几百只怪会叠成一个点，手感全毁。
            float sepX = 0f;
            float sepY = 0f;
            enemyHash.query(x[i], y[i], r[i] * 2.2f, scratch);
            for (int n = 0; n < scratch.size(); n++) {
                int o = scratch.get(n);
                if (o == i || !alive[o] || kind[o] != KIND_ENEMY) {
                    continue;
                }
                float ox = x[i] - x[o];
                float oy = y[i] - y[o];
                float d2 = ox * ox + oy * oy;
                float minD = r[i] + r[o];
                if (d2 > 1e-4f && d2 < minD * minD) {
                    float d = (float) Math.sqrt(d2);
                    float push = (minD - d) / minD;
                    sepX += ox / d * push;
                    sepY += oy / d * push;
                }
            }
            // 必须夹紧到单位长度。怪堆在一起时会累加出 5~10 的合力，
            // 不夹住就直接把怪弹飞出地图，表现为"刷出来的怪凭空消失"。
            float sepLen = (float) Math.sqrt(sepX * sepX + sepY * sepY);
            if (sepLen > 1f) {
                sepX /= sepLen;
                sepY /= sepLen;
            }
            x[i] += (vx[i] + sepX * moveSpeed * Balance.ENEMY_SEPARATION + kx[i]) * dt;
            y[i] += (vy[i] + sepY * moveSpeed * Balance.ENEMY_SEPARATION + ky[i]) * dt;
            resolveObstacles(i);   // 障碍碰撞推出（敌人也绕不过去）
            if (kingArena) {
                clampKingArena(i);
            } else {
                clampToWorld(i);   // 敌人同样被棕色城墙挡在内侧，不会被挤飞出去
            }

            // 接触伤害
            float ndx = x[target] - x[i];
            float ndy = y[target] - y[i];
            float nlen = (float) Math.sqrt(ndx * ndx + ndy * ndy);
            if (nlen < r[i] + r[target]) {
                cd[i] -= dt;
                if (cd[i] <= 0f) {
                    if (iframe[target] <= 0f) {
                        damage(target, dmg[i]);
                        // 三阶段被动：王座贴身咬中英雄也汲取生命（宠物挨打不回，否则四只宠物能白喂血）
                        if (i == kingId && kind[target] == KIND_WIZARD) {
                            kingDrain(1);
                        }
                        // 无敌帧：宠物用固定短帧，玩家用职业基础 + 灵巧被动
                        iframe[target] = (kind[target] == KIND_MINION)
                                ? Balance.MINION_IFRAME : heroIframe(target);
                    }
                    cd[i] = Balance.ENEMY_ATTACK_CD;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 敌人变体行为
    // ------------------------------------------------------------------

    /** 小偷：追最近的宝石吸收（不攻击玩家），被击杀时掉落翻倍 */
    private void updateThief(int id, float dt) {
        if (stunT[id] > 0f) {
            stunT[id] -= dt;
            x[id] += kx[id] * dt;
            y[id] += ky[id] * dt;
            kx[id] *= (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
            ky[id] *= (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
            return;
        }
        int gem = nearestPickup(x[id], y[id]);
        int target = nearestWizard(x[id], y[id]);
        float tx, ty;
        if (gem >= 0) {
            tx = x[gem]; ty = y[gem];
        } else if (target >= 0) {
            tx = x[target]; ty = y[target];
        } else {
            return;
        }
        float dx = tx - x[id], dy = ty - y[id];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1e-3f) {
            float moveSpeed = speed[id] * arenaMap.movementMultiplierAt(x[id], y[id]);
            vx[id] = dx / len * moveSpeed;
            vy[id] = dy / len * moveSpeed;
        }
        x[id] += vx[id] * dt;
        y[id] += vy[id] * dt;
        // 击退：小偷的移动分支会覆写 vx/vy，所以 kx/ky 必须单独叠加，否则挨打毫无反馈
        x[id] += kx[id] * dt;
        y[id] += ky[id] * dt;
        kx[id] *= (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
        ky[id] *= (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
        resolveObstacles(id);
        clampToWorld(id);
        if (gem >= 0) {
            float gdx = x[gem] - x[id], gdy = y[gem] - y[id];
            if (gdx * gdx + gdy * gdy <= Balance.THIEF_STEAL_RADIUS * Balance.THIEF_STEAL_RADIUS) {
                carry[id] += 1f;
                despawn(gem);
            }
        }
    }

    /** 远程怪：保持距离并向玩家发射弹幕 */
    private void updateRanged(int id, float dt) {
        if (stunT[id] > 0f) {
            stunT[id] -= dt;
            x[id] += kx[id] * dt;
            y[id] += ky[id] * dt;
            kx[id] *= (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
            ky[id] *= (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
            return;
        }
        int target = nearestWizard(x[id], y[id]);
        if (target < 0) {
            return;
        }
        float dx = x[target] - x[id], dy = y[target] - y[id];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1e-3f) {
            float nx = dx / len, ny = dy / len;
            float move;
            if (len < Balance.RANGED_KEEP_DIST) {
                move = -speed[id] * 0.8f;
            } else if (len > Balance.RANGED_RANGE) {
                move = speed[id] * 0.6f;
            } else {
                move = 0f;
            }
            vx[id] = nx * move;
            vy[id] = ny * move;
            x[id] += vx[id] * dt;
            y[id] += vy[id] * dt;
            // 击退：远程怪的 vx/vy 是"站位速度"，kx/ky 单独叠加才看得到挨打反馈
            x[id] += kx[id] * dt;
            y[id] += ky[id] * dt;
            kx[id] *= (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
            ky[id] *= (float) Math.exp(-Balance.KNOCKBACK_DECAY * dt);
            resolveObstacles(id);
            clampToWorld(id);   // 远程怪同样被边界挡住
            cd[id] -= dt;
            if (cd[id] <= 0f && len <= Balance.RANGED_RANGE) {
                spawnProjectileEnemy(id, x[target], y[target]);
                cd[id] = Balance.RANGED_CD;
            }
        }
    }

    /** 敌方弹幕（远程怪 / Boss 用）：朝目标位置发射 */
    private void spawnProjectileEnemy(int ownerId, float tx, float ty) {
        int id = alloc(KIND_PROJECTILE, x[ownerId], y[ownerId], 6f, TEAM_ENEMY);
        if (id < 0) {
            return;
        }
        float dx = tx - x[ownerId], dy = ty - y[ownerId];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            despawn(id);
            return;
        }
        vx[id] = dx / len * Balance.RANGED_BOLT_SPD;
        vy[id] = dy / len * Balance.RANGED_BOLT_SPD;
        dmg[id] = Balance.RANGED_DMG;
        life[id] = 4f;
        owner[id] = ownerId;
        meta[id] = 0;
        pierce[id] = 0;
        lastHit[id] = -1;
        projAoe[id] = 0f;
        projChain[id] = 0;
        projBounce[id] = 0;
        projTarget[id] = -1;
    }

    /** Boss 阶段技能：预警圈 + 召唤 + 飞碟专属追踪弹。Boss 的追击与接触伤害由 updateEnemies 通用逻辑驱动 */
    private void updateBossPhase(float dt) {
        int b = bossId;
        if (!alive[b]) {
            bossId = -1;
            return;
        }
        float ratio = hp[b] / maxHp[b];
        int phase = ratio > Balance.BOSS_PHASE2_HP ? 1 : (ratio > Balance.BOSS_PHASE3_HP ? 2 : 3);

        bossWarningTimer -= dt;
        if (phase >= 2 && bossWarningTimer <= 0f) {
            int w = firstWizard();
            if (w >= 0) {
                spawnWarning(x[w], y[w]);
            }
            bossWarningTimer = phase >= 3 ? 3.5f : 5f;
        }
        if (phase >= 3) {
            bossSummonTimer -= dt;
            if (bossSummonTimer <= 0f) {
                for (int s = 0; s < Balance.BOSS_SUMMON_COUNT; s++) {
                    float a = rng.nextFloat() * (float) (Math.PI * 2);
                    int cid = spawnEnemy(x[b] + (float) Math.cos(a) * 70f,
                            y[b] + (float) Math.sin(a) * 70f, rng.nextInt(3), V_NORMAL);
                    if (cid >= 0) {
                        // spawnEnemy 已按当前时间乘过成长系数，这里只给召唤物一点额外血量
                        maxHp[cid] *= 1.5f;
                        hp[cid] = maxHp[cid];
                    }
                }
                bossSummonTimer = Balance.BOSS_SUMMON_INTERVAL;
            }
        }
        // 飞碟 Boss（tier 0）专属：低频率齐射追踪弹，四向散开，缓慢追踪。
        // 仅在 phase >= 1 起就启用——飞碟的"招牌动作"不该等半血才放。
        if (bossTier == 0) {
            bossHomingTimer -= dt;
            if (bossHomingTimer <= 0f) {
                spawnBossHomingVolley(b);
                bossHomingTimer = Balance.BOSS_HOMING_CD;
            }
        }
        // 飞龙 Boss（tier 1 熔岩飞龙 / tier 2 霜寂飞龙）专属：
        // 定向直线慢速火球 + 飞行路径上撒 5 秒灼烧带。
        // tier 1 单发、tier 2 双发（双发时 CD 稍长一档，避免弹幕墙）。
        if (bossTier == 1 || bossTier == 2) {
            bossBoltTimer -= dt;
            if (bossBoltTimer <= 0f) {
                spawnDragonBoltVolley(b);
                bossBoltTimer = (bossTier == 1)
                        ? Balance.DRAGON_BOLT_CD_TIER1
                        : Balance.DRAGON_BOLT_CD_TIER2;
            }
        }
    }

    /**
     * 在 Boss 周围均分 BOSS_HOMING_COUNT 个方向各发射一颗追踪弹。
     * 初始朝向沿"该方向"斜飞出去，再由每帧转向逻辑缓慢追玩家——刻意做"撒出去再追"。
     */
    private void spawnBossHomingVolley(int boss) {
        if (!alive[boss]) {
            return;
        }
        int count = Balance.BOSS_HOMING_COUNT;
        // 起始角度带一点点随机抖动，避免多轮弹道完全重合
        float baseAngle = rng.nextFloat() * (float) (Math.PI * 2);
        for (int i = 0; i < count; i++) {
            float a = baseAngle + (float) (Math.PI * 2) * i / count;
            int id = alloc(KIND_PROJECTILE,
                    x[boss] + (float) Math.cos(a) * (r[boss] + 6f),
                    y[boss] + (float) Math.sin(a) * (r[boss] + 6f),
                    Balance.BOSS_HOMING_RADIUS, TEAM_ENEMY);
            if (id < 0) {
                continue;
            }
            // 弹道初始速度按"沿出膛方向 + 一点斜向上"的合成方向飞出去
            vx[id] = (float) Math.cos(a) * Balance.BOSS_HOMING_SPD;
            vy[id] = (float) Math.sin(a) * Balance.BOSS_HOMING_SPD;
            dmg[id] = Balance.BOSS_HOMING_DMG;
            life[id] = Balance.BOSS_HOMING_LIFE;
            owner[id] = boss;
            // meta 用一个对玩家无歧义的"魔法"id：之后渲染用这个判定是否画追踪弹
            meta[id] = -1;
            pierce[id] = 0;
            lastHit[id] = -1;
            projAoe[id] = Balance.BOSS_HOMING_BLAST_R;
            projChain[id] = 0;
            projBounce[id] = 0;
            projTarget[id] = -1;
        }
    }

    // ------------------------------------------------------------------
    // 飞龙 Boss（tier 1 熔岩飞龙 / tier 2 霜寂飞龙）专属：定向飞行火球 + 灼烧带
    // ------------------------------------------------------------------

    /**
     * 朝玩家当前所在方向吐火球。tier 1 单发、tier 2 双发（扇形 ±半角展开）。
     * 火球是"定向直线"——不追踪，只是朝着按下发射键那一刻的玩家方向直飞。
     * 飞行过程中按固定间距播种灼烧带（DRAGON_BURN_STEP 一团），
     * 玩家即使躲开火球本体，也要花 5 秒绕开身后的灼烧带。
     */
    private void spawnDragonBoltVolley(int boss) {
        if (!alive[boss]) {
            return;
        }
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        // 瞄准方向：朝向玩家当前位置
        float dx = x[w] - x[boss];
        float dy = y[w] - y[boss];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            return;
        }
        float baseAngle = (float) Math.atan2(dy, dx);
        int count = (bossTier == 2) ? 2 : 1;
        float half = Balance.DRAGON_BOLT_TIER2_HALF;
        for (int i = 0; i < count; i++) {
            // i=0 用 -half、i=1 用 +half；count==1 时偏移为 0
            float a = baseAngle + (count == 1 ? 0f : (i == 0 ? -half : half));
            int id = alloc(KIND_PROJECTILE,
                    x[boss] + (float) Math.cos(a) * (r[boss] + 4f),
                    y[boss] + (float) Math.sin(a) * (r[boss] + 4f),
                    Balance.DRAGON_BOLT_RADIUS, TEAM_ENEMY);
            if (id < 0) {
                continue;
            }
            vx[id] = (float) Math.cos(a) * Balance.DRAGON_BOLT_SPD;
            vy[id] = (float) Math.sin(a) * Balance.DRAGON_BOLT_SPD;
            dmg[id] = Balance.DRAGON_BOLT_DMG;
            life[id] = Balance.DRAGON_BOLT_LIFE;
            owner[id] = boss;
            // meta = -2：飞龙火球的"魔法"id（与飞碟追踪弹 -1 错开）
            meta[id] = -2;
            pierce[id] = 0;
            lastHit[id] = -1;
            projAoe[id] = 0f;
            projChain[id] = 0;
            projBounce[id] = 0;
            projTarget[id] = -1;
            // 起飞瞬间先铺第一团灼烧，免得火球刚出膛玩家就已经路过
            spawnDragonBurn(x[id], y[id]);
            // 飞行播种进度：从 0 开始累加，每走够 DRAGON_BURN_STEP 再铺一团
            burnStep[id] = 0f;
            dragonBolts.add(id);
        }
    }

    /**
     * 在指定点种一团 5 秒灼烧带（ZONE_FIRE）。
     * 走 updateZones 的常规周期 tick 路径，但需要把"打敌人"改成"打玩家/宠物"——
     * 这里在 spawnDragonBurn 里把 owner 标为 TEAM_ENEMY 的飞龙本体，
     * 然后在 updateDragonBolts 里**单独维护一个飞龙灼烧带列表**，
     * 每帧对玩家/宠物做距离判定并按 tick 频率扣血，避免污染 updateZones 的通用逻辑。
     * （直接把伤害写进 dmg 数组 + 单独 tick，最小改动现有 zone 系统。）
     */
    private void spawnDragonBurn(float sx, float sy) {
        int id = alloc(KIND_ZONE, sx, sy, Balance.DRAGON_BURN_RADIUS, TEAM_ENEMY);
        if (id < 0) {
            return;
        }
        life[id] = Balance.DRAGON_BURN_DURATION;
        speed[id] = Balance.DRAGON_BURN_DURATION;
        // 把"持续 DPS"塞进 dmg（标准 zone 用法，updateZones 也是这么读的）
        dmg[id] = Balance.DRAGON_BURN_DPS;
        elem[id] = Element.FIRE;
        // 用 iframe 当 tick 计时器（0 = 刚铺、0.5 = 已 tick 过一次）
        iframe[id] = 0f;
        cd[id] = 0f;
        owner[id] = -1;     // Boss 来源，zone 走不到元素叠加公式里
        // 单独 sub type：让 updateZones 跳过，由 updateDragonBurns 接管
        meta[id] = ZONE_DRAGON_BURN;
        dragonBurns.add(id);
    }

    /**
     * 每帧推进所有飞行中的飞龙火球：
     * ① 按 vx/vy 移动；② 每飞够 DRAGON_BURN_STEP 在当前位置铺一团灼烧；
     * ③ 撞玩家/宠物/障碍/到寿就消散。
     * 撞玩家按标准 onHit 路径：damage + 设 iframe；撞宠物同理；
     * 撞障碍/世界边：直接消散（不爆，灼烧带已经留下去了）。
     */
    private void updateDragonBolts(float dt) {
        for (int n = dragonBolts.size() - 1; n >= 0; n--) {
            int id = dragonBolts.get(n);
            if (!alive[id]) {
                dragonBolts.removeAt(n);
                continue;
            }
            float px = x[id];
            float py = y[id];
            // 1) 推进位置
            x[id] = px + vx[id] * dt;
            y[id] = py + vy[id] * dt;
            // 2) 寿命倒数
            life[id] -= dt;
            // 3) 跨出可玩区 → 消散（避免遗留在场外）
            float half = Balance.PLAY_HALF;
            if (x[id] < -half || x[id] > half || y[id] < -half || y[id] > half) {
                despawn(id);
                dragonBolts.removeAt(n);
                continue;
            }
            // 4) 寿命到 0 → 消散（不再留灼烧）
            if (life[id] <= 0f) {
                despawn(id);
                dragonBolts.removeAt(n);
                continue;
            }
            // 5) 飞行过程中按固定间距播种灼烧带
            burnStep[id] += Balance.DRAGON_BOLT_SPD * dt;
            if (burnStep[id] >= Balance.DRAGON_BURN_STEP) {
                burnStep[id] = 0f;
                spawnDragonBurn(x[id], y[id]);
            }
            // 6) 撞障碍（用标准 pushOutOfObstacle 的"是否重叠"判定，简化版：圆 vs 圆）
            if (overlapsAnyObstacle(x[id], y[id], r[id])) {
                despawn(id);
                dragonBolts.removeAt(n);
                continue;
            }
            // 7) 撞玩家
            boolean hit = false;
            for (int w = 0; w < wizards.size(); w++) {
                int wz = wizards.get(w);
                if (!alive[wz]) continue;
                float ddx = x[wz] - x[id];
                float ddy = y[wz] - y[id];
                float rr = r[id] + r[wz];
                if (ddx * ddx + ddy * ddy <= rr * rr) {
                    if (iframe[wz] <= 0f) {
                        damage(wz, dmg[id]);
                        iframe[wz] = heroIframe(wz);
                    }
                    hit = true;
                    break;
                }
            }
            // 8) 撞宠物（召唤师也要被飞龙打到）
            if (!hit) {
                for (int m = 0; m < minions.size(); m++) {
                    int mn = minions.get(m);
                    if (!alive[mn]) continue;
                    float ddx = x[mn] - x[id];
                    float ddy = y[mn] - y[id];
                    float rr = r[id] + r[mn];
                    if (ddx * ddx + ddy * ddy <= rr * rr) {
                        if (iframe[mn] <= 0f) {
                            damage(mn, dmg[id]);
                            iframe[mn] = Balance.MINION_IFRAME;
                        }
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                despawn(id);
                dragonBolts.removeAt(n);
            }
        }
    }

    /**
     * 每帧推进所有飞龙灼烧带：玩家/宠物走过即按 tick 节奏扣血。
     * 复用 updateZones 的 0.5s tick 间隔：把"累计时间"塞进 iframe 字段，到 0.5 就结算一次并清零。
     * 这样玩家踩在灼烧带里就是 5 秒 × (DRAGON_BURN_DPS × 0.5) = 5 × DPS 的累计伤害。
     */
    private void updateDragonBurns(float dt) {
        for (int n = dragonBurns.size() - 1; n >= 0; n--) {
            int zid = dragonBurns.get(n);
            if (!alive[zid]) {
                dragonBurns.removeAt(n);
                continue;
            }
            life[zid] -= dt;
            if (life[zid] <= 0f) {
                despawn(zid);
                dragonBurns.removeAt(n);
                continue;
            }
            // 每 0.5 秒 tick 一次
            iframe[zid] += dt;
            if (iframe[zid] < 0.5f) {
                continue;
            }
            iframe[zid] = 0f;
            float radius = r[zid];
            float dps = dmg[zid];
            float halfTick = dps * 0.5f;   // 单次 tick 伤害 = DPS × 0.5s
            // 玩家：踩到就吃
            for (int w = 0; w < wizards.size(); w++) {
                int wz = wizards.get(w);
                if (!alive[wz]) continue;
                float ddx = x[wz] - x[zid];
                float ddy = y[wz] - y[zid];
                float rr = radius + r[wz];
                if (ddx * ddx + ddy * ddy <= rr * rr && iframe[wz] <= 0f) {
                    damage(wz, halfTick);
                    iframe[wz] = heroIframe(wz);
                }
            }
            // 宠物：同样吃
            for (int m = 0; m < minions.size(); m++) {
                int mn = minions.get(m);
                if (!alive[mn]) continue;
                float ddx = x[mn] - x[zid];
                float ddy = y[mn] - y[zid];
                float rr = radius + r[mn];
                if (ddx * ddx + ddy * ddy <= rr * rr && iframe[mn] <= 0f) {
                    damage(mn, halfTick);
                    iframe[mn] = Balance.MINION_IFRAME;
                }
            }
        }
    }

    /**
     * 飞龙火球撞障碍的简化版：圆 vs 圆。箱子和胶囊也按外接圆粗判——
     * 火球撞到就消散，不需要"贴边滑行"那么精细。
     */
    private boolean overlapsAnyObstacle(float cx, float cy, float cr) {
        obstacleHash.query(cx, cy, cr + Balance.OBSTACLE_MAX_R, scratch2);
        for (int n = 0; n < scratch2.size(); n++) {
            int o = scratch2.get(n);
            if (!alive[o] || kind[o] != KIND_OBSTACLE || !obstacleBlocksMovement[o]) {
                continue;
            }
            float ddx = x[o] - cx;
            float ddy = y[o] - cy;
            float rr = cr + r[o];
            if (ddx * ddx + ddy * ddy <= rr * rr) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 5 关 Boss：奶蛙
    // ------------------------------------------------------------------

    /** 在场地中央生成奶蛙。属性取 Balance.MILKY_*，不带护盾。 */
    private void spawnMilky() {
        int id = spawnEnemy(0f, 0f, 0, V_BOSS);
        if (id < 0) {
            return;
        }
        maxHp[id] = Balance.MILKY_HP;
        hp[id] = maxHp[id];
        speed[id] = Balance.MILKY_SPEED;
        r[id] = Balance.MILKY_RADIUS;
        dmg[id] = 0f;   // 取消奶蛙与角色的碰撞伤害（只靠技能打人）
        enemyShield[id] = 0f;
        milkyId = id;
        milkySpawned = true;
        milkyStompCd = Balance.MILKY_STOMP_CD;
        milkyLaughCd = Balance.MILKY_LAUGH_CD;
        milkyCast = 0;
        milkyCastT = 0f;
        milkyFaceRight = true;
        milkyMirror = false;
    }

    /**
     * 奶蛙行为：靠近玩家才起手；施法期间原地不动。
     * 技能一「蓄力踩地」→ 朝玩家所在侧的半圆，伤害 50；
     * 技能二「捧腹大笑」→ 半血以下才用，圆形大范围伤害 100，频率低。
     * 未施法时的追击由通用 updateEnemies 按 speed 驱动。
     */
    private void updateMilky(float dt) {
        int m = milkyId;
        if (!alive[m]) {
            milkyId = -1;
            return;
        }
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        float dx = x[w] - x[m];
        float dy = y[w] - y[m];
        // 只有不施法时才更新朝向：技能起手后朝向与判定范围都要锁死，播完前不变
        if (milkyCast == 0) {
            milkyFaceRight = dx >= 0f;
        }

        if (milkyStompCd > 0f) {
            milkyStompCd -= dt;
        }
        if (milkyLaughCd > 0f) {
            milkyLaughCd -= dt;
        }

        if (milkyCast != 0) {
            // 技能一旦起手就完整播完：施法期间锁移动、不响应新技能，直到动作走完
            speed[m] = 0f;
            milkyCastT += dt;
            if (milkyCastT >= milkyCastDur()) {
                // milkyHitActive：让 kill() 能识别「这次死亡是奶蛙造成的」
                milkyHitActive = true;
                if (milkyCast == 2) {
                    damagePlayersInRadius(x[m], y[m], Balance.MILKY_LAUGH_RANGE,
                            Balance.MILKY_LAUGH_DMG);
                    milkyLaughCd = Balance.MILKY_LAUGH_CD;
                } else {
                    damagePlayersInRadius(x[m], y[m], Balance.MILKY_STOMP_RANGE,
                            Balance.MILKY_STOMP_DMG);
                    milkyStompCd = Balance.MILKY_STOMP_CD;
                }
                milkyHitActive = false;
                milkyCast = 0;
                milkyCastT = 0f;
            }
            return;
        }

        speed[m] = Balance.MILKY_SPEED;
        // dmg[m] 恒为 0：奶蛙不造成碰撞伤害（技能伤害另算）

        boolean near = dx * dx + dy * dy
                <= Balance.MILKY_TRIGGER_RANGE * Balance.MILKY_TRIGGER_RANGE;
        if (!near) {
            return;
        }
        boolean half = hp[m] <= maxHp[m] * Balance.MILKY_LAUGH_HP;
        if (half && milkyLaughCd <= 0f) {
            milkyCast = 2;
            milkyCastT = 0f;
        } else if (milkyStompCd <= 0f) {
            milkyCast = 1;
            milkyCastT = 0f;
            // 起手瞬间定下动画用哪一套（仅视觉；整圆范围不分方向）
            milkyMirror = !milkyFaceRight;
        }
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 王宫最终决战：国王（第一/二/三阶段，整体接入）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 王宫最终决战：国王（第一阶段）
    // ------------------------------------------------------------------

    /**
     * 进入王宫决战：清空战场（小怪 / 投射物 / 掉落 / 障碍 / 骨蛇），
     * 玩家归位殿中，国王在王座前刷新（阶段一：30000 血 / 常态站桩，技能前摇期间随机走位躲弹幕）。
     * 由客户端在王宫大殿按空格时调用，之后走正常战斗循环。
     *
     * TODO（设计需求，单机先记录不实现）：与国王战斗时，玩家自动索敌应把队友视为敌人并造成 10% 伤害。
     */
    public void enterKingArena() {
        clearBattlefield();
        kingArena = true;
        spawningEnabled = false;
        milkySpawned = true;       // 防御：调试直进决战时别让奶蛙在后面复活
        int w = firstWizard();
        if (w >= 0) {
            x[w] = Balance.ARENA_ENTER_X;
            y[w] = Balance.ARENA_ENTER_Y;
            px[w] = x[w];
            py[w] = y[w];
            kx[w] = 0f;
            ky[w] = 0f;
        }
        kingPhase = 1;
        spawnKing1();
    }

    /** 决战开场清场：非玩家实体一律移出战场（不计击杀、不掉落） */
    private void clearBattlefield() {
        for (int i = 0; i < high; i++) {
            if (!alive[i] || kind[i] == KIND_FREE || kind[i] == KIND_WIZARD) {
                continue;
            }
            despawn(i);
        }
        // 骨蛇是"一怪多实体"的特殊链路，头槽与段列表要单独清干净
        for (int s = 0; s < MAX_BONE_SERPENT; s++) {
            boneSerpentHead[s] = -1;
            if (serpentSegsBySlot[s] != null) {
                serpentSegsBySlot[s].clear();
            }
        }
        bossId = -1;
        milkyId = -1;
        eventIds.clear();
        eventType = 0;
        eventBannerT = 0f;
        enemyHash.beginFrame();
        obstacleHash.beginFrame();
    }

    /** 阶段一的国王：30000 血 / 无接触伤害（唯一攻击手段是 8 秒一圈的 AOE）；站桩出生，位移全部走手动路径 */
    private void spawnKing1() {
        int id = spawnEnemy(Balance.ARENA_KING_X, Balance.ARENA_KING_Y, 0, V_BOSS);
        if (id < 0) {
            return;
        }
        maxHp[id] = Balance.KING1_HP;
        hp[id] = maxHp[id];
        // 移速由 updateKing / dodgeKing 逐帧驱动（常态站桩、前摇随机走位）。
        // speed 锁 0——否则 updateEnemies 的通用追击会把国王拖向玩家
        speed[id] = 0f;
        r[id] = Balance.KING1_RADIUS;
        dmg[id] = 0f;
        enemyShield[id] = 0f;
        kingId = id;
        kingSkillCd = Balance.KING1_SKILL_CD;
        kingTelegraphT = 0f;
        kingAttritionT = Balance.KING_ATTRITION_INTERVAL;
        kingFaceRight = true;
        kingDodgeT = 0f;          // 首次前摇立即滚出随机方向
        kingDodgeDx = 0f;
        kingDodgeDy = 1f;
    }

    /**
     * 国王行为（第一阶段）：
     * 常态站桩不动，只在技能前摇的 1.4 秒里随机走位躲弹幕——
     * 位移全部走 dodgeKing 手动路径，speed 恒锁 0（通用追击不参与）。
     * 另管技能节拍与默认属性（每 15 秒流失一个被动 + 扣 10 血）。
     */
    private void updateKing(float dt) {
        int k = kingId;
        if (k < 0 || !alive[k]) {
            // 一阶段击破后到二阶段重生之间 kingId=-1（等对白转场）：直接跳过
            kingId = -1;
            return;
        }
        if (kingPhase == 1) {
            speed[k] = 0f;   // 位移只走手动路径，别让通用追击把国王拖向玩家
            if (kingTelegraphT > 0f) {
                // 施法前摇：一边蓄力一边随机走位躲弹幕
                kingTelegraphT -= dt;
                dodgeKing(dt);
            } else {
                if (kingSkillCd > 0f) {
                    kingSkillCd -= dt;
                }
                if (kingSkillCd <= 0f) {
                    fireKingSkill1();   // 起手：生成预警圈；下一帧起进入 1.4 秒随机走位
                }
            }
        } else if (kingPhase >= 3) {
            speed[k] = 0f;   // 三阶段不走路：位移全靠传送（updateKing3 手动路径）
            // 两管血（60000×2）：第一管打空即分裂出分身（整场只触发一次）
            if (!kingSplit && hp[k] <= Balance.KING3_HP_PER_BAR) {
                spawnKingTwin();
            }
            updateKing3(dt);
            updateKingTwin(dt);
        } else if (kingPhase == 2) {
            speed[k] = 0f;   // 二阶段同样走手动路径：距离带走位在 updateKing2 里
            updateKing2(dt);
        }
        // 默认属性：每 15 秒按被动获得顺序失去一个被动并损失 10 点生命（转阶段时重置计时）
        kingAttritionT -= dt;
        if (kingAttritionT <= 0f) {
            kingAttritionT = Balance.KING_ATTRITION_INTERVAL;
            int w = firstWizard();
            if (w >= 0) {
                Loadout lo = loadout[w];
                if (lo != null) {
                    lo.loseEarliestPassive();
                }
                hp[w] -= Balance.KING_ATTRITION_HP;
                if (hp[w] <= 0f) {
                    kill(w);   // 流失扣血扣死照样算阵亡（不给回血缓冲）
                }
            }
        }
    }

    /**
     * 技能前摇期间的随机走位（躲弹幕）：每 0.25~0.6 秒换一个方向，撞到竞技场边界就反弹。
     * 速度用 KING1_SPEED；朝向跟着走位方向（幅度太小不翻转，免得原地左右抖）。
     */
    private void dodgeKing(float dt) {
        int k = kingId;
        if (k < 0) {
            return;
        }
        kingDodgeT -= dt;
        if (kingDodgeT <= 0f) {
            float ang = rng.nextFloat() * (float) (Math.PI * 2);
            kingDodgeDx = (float) Math.cos(ang);
            kingDodgeDy = (float) Math.sin(ang);
            kingDodgeT = 0.25f + rng.nextFloat() * 0.35f;
        }
        float nx = x[k] + kingDodgeDx * Balance.KING1_SPEED * dt;
        float ny = y[k] + kingDodgeDy * Balance.KING1_SPEED * dt;
        if (nx < -Balance.ARENA_HALF_X || nx > Balance.ARENA_HALF_X) {
            kingDodgeDx = -kingDodgeDx;   // 撞墙：水平分量反弹，避免贴着边界抖动
            nx = x[k] + kingDodgeDx * Balance.KING1_SPEED * dt;
        }
        if (ny < Balance.ARENA_TOP || ny > Balance.ARENA_BOTTOM) {
            kingDodgeDy = -kingDodgeDy;
            ny = y[k] + kingDodgeDy * Balance.KING1_SPEED * dt;
        }
        x[k] = nx;
        y[k] = ny;
        clampKingArena(k);
        // 方向接近竖直时（水平分量太小）不翻转朝向，免得原地左右抖
        if (Math.abs(kingDodgeDx) > 0.25f) {
            kingFaceRight = kingDodgeDx >= 0f;
        }
    }

    /** 阶段一技能：以玩家当前位置为中心，生成半径 180 的预警圈（1 秒前摇后爆炸） */
    private void fireKingSkill1() {
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        kingSkillCd = Balance.KING1_SKILL_CD;
        kingTelegraphT = Balance.KING1_SKILL_TELEGRAPH;
        spawnZone(ZONE_WARNING, x[w], y[w], Balance.KING1_SKILL_RADIUS,
                Balance.KING1_SKILL_TELEGRAPH, Balance.KING1_SKILL_DAMAGE, Element.NONE, -1);
    }

    /** 竞技场边界：玩家与国王都钳制在 [±ARENA_HALF_X] × [ARENA_TOP, ARENA_BOTTOM] 内 */
    private void clampKingArena(int id) {
        if (x[id] < -Balance.ARENA_HALF_X) {
            x[id] = -Balance.ARENA_HALF_X;
        } else if (x[id] > Balance.ARENA_HALF_X) {
            x[id] = Balance.ARENA_HALF_X;
        }
        if (y[id] < Balance.ARENA_TOP) {
            y[id] = Balance.ARENA_TOP;
        } else if (y[id] > Balance.ARENA_BOTTOM) {
            y[id] = Balance.ARENA_BOTTOM;
        }
    }

    // ------------------------------------------------------------------
    // 王宫最终决战：国王第二阶段
    // ------------------------------------------------------------------

    /**
     * 一阶段被击破 → 对白播完后的转场：
     * 国王在王座上以二阶段重生（血池换新、消耗计时重置），角色损失 20 点移速，
     * 并立刻展开第一道裂隙——王宫里的魔物从此不断涌出。
     */
    public void beginKingPhase2() {
        kingFallen = false;             // 一次性信号：转场后清掉，防对白重复触发
        kingPhase = 2;
        if (kingId >= 0) {
            // 调试直进（-Dab.kingPhase=2）时一阶段的国王实体还在场：先清掉防两尊国王同屏。
            // 正常流程走击破分支——kill() 已把 kingId 置 -1，此处必然是无操作。
            despawn(kingId);
            kingId = -1;
        }
        kingSpeedPenalty = Balance.KING2_SPEED_PENALTY;
        int w = firstWizard();
        if (w >= 0) {
            // 把玩家拉回殿中入场位：对白结束时玩家可能贴在倒地位置附近，拉开距离给二阶段开场
            x[w] = Balance.ARENA_ENTER_X;
            y[w] = Balance.ARENA_ENTER_Y;
            px[w] = x[w];
            py[w] = y[w];
            kx[w] = 0f;
            ky[w] = 0f;
        }
        kingAttritionT = Balance.KING_ATTRITION_INTERVAL;   // 转阶段：消耗计时重置
        spawnKing2();
        openKingRift();                 // 开场先来一道裂隙，魔物开始源源不断
    }

    /** 二阶段的国王：60000 血（常驻 20% 减伤），位移全走 updateKing2 手动路径；贴身接触伤害 40 */
    private void spawnKing2() {
        int id = spawnEnemy(Balance.ARENA_KING_X, Balance.ARENA_KING_Y, 0, V_BOSS);
        if (id < 0) {
            return;
        }
        maxHp[id] = Balance.KING2_HP;
        hp[id] = maxHp[id];
        speed[id] = 0f;                 // 位移由 updateKing2 逐帧驱动
        r[id] = Balance.KING2_RADIUS;
        // 贴身接触伤害：走 updateEnemies 的通用接触通道（0.7s 一次，受受击方无敌帧门控）
        dmg[id] = Balance.KING2_CONTACT_DAMAGE;
        enemyShield[id] = 0f;
        kingId = id;
        kingBoltCd = Balance.KING2_BOLT_TRACK + Balance.KING2_BOLT_REST;   // 首轮魔弹：入场 5 秒后
        kingAoeCd = Balance.KING2_AOE_CD;
        kingRiftCd = Balance.KING2_RIFT_CD;
        kingTelegraphT = 0f;
        kingFaceRight = true;
    }

    // ------------------------------------------------------------------
    // 王宫最终决战：国王第三阶段（王座本体）
    // ------------------------------------------------------------------

    /**
     * 二阶段被击破 → 对白播完后的转场：
     * 王座本体觉醒，以三阶段登场（120000 血 / 常驻 90% 减伤 / 传送位移）。
     * 保留二阶段全部机制（魔弹 / 地面预警圈 / 裂隙刷怪），叠加深渊新招：
     * 传送落点爆发、地刺、深渊牵引（「王座视为深渊」）、裂隙召唤 Boss、造成伤害回血。
     */
    public void beginKingPhase3() {
        kingFallen2 = false;            // 一次性信号：转场后清掉，防对白重复触发
        kingPhase = 3;
        if (kingId >= 0) {
            // 调试直进（-Dab.kingPhase=3）时旧阶段的国王实体还在场：先清掉防同屏。
            // 正常流程杀入三阶段时 kill() 已把 kingId 置 -1，此处必然是无操作。
            despawn(kingId);
            kingId = -1;
        }
        // 分裂状态复位：清掉可能残留的分身（调试直进/重开时防双子残留）
        kingSplit = false;
        if (kingTwinId >= 0) {
            despawn(kingTwinId);
            kingTwinId = -1;
        }
        kingTwinTeleT = 0f;
        kingTwinTeleCd = 0f;
        int w = firstWizard();
        if (w >= 0) {
            // 把玩家拉回殿中入场位：对白结束时玩家可能贴在王座附近，拉开距离给三阶段开场
            x[w] = Balance.ARENA_ENTER_X;
            y[w] = Balance.ARENA_ENTER_Y;
            px[w] = x[w];
            py[w] = y[w];
            kx[w] = 0f;
            ky[w] = 0f;
        }
        kingAttritionT = Balance.KING_ATTRITION_INTERVAL;   // 转阶段：消耗计时重置
        spawnKing3();
        openKingRift();                 // 开场先来一道裂隙，魔物继续源源不断
    }

    /**
     * 三阶段的王座本体：血池 60000×2 / 常驻 90% 减伤，位移全靠传送（updateKing3 驱动）；
     * 贴身接触伤害 30（用户给定，比二阶段的 40 更轻）。二阶段的魔弹 / 预警圈 / 裂隙节拍全部保留
     * （三阶段魔弹 5 颗、裂隙每次 4 只）。
     */
    private void spawnKing3() {
        int id = spawnEnemy(Balance.ARENA_KING_X, Balance.ARENA_KING_Y, 0, V_BOSS);
        if (id < 0) {
            return;
        }
        maxHp[id] = Balance.KING3_HP;
        hp[id] = maxHp[id];
        speed[id] = 0f;                 // 位移由 updateKing3 的传送驱动，不走路
        r[id] = Balance.KING3_RADIUS;
        dmg[id] = Balance.KING3_CONTACT_DAMAGE;
        enemyShield[id] = 0f;
        kingId = id;
        kingBoltCd = Balance.KING2_BOLT_TRACK + Balance.KING2_BOLT_REST;   // 首轮魔弹：入场 5 秒后
        kingAoeCd = Balance.KING2_AOE_CD;
        kingRiftCd = Balance.KING2_RIFT_CD;
        kingTeleCd = Balance.KING3_TELE_CD;     // 首轮传送：3 秒后
        kingTeleT = 0f;
        kingSpikeCd = Balance.KING3_SPIKE_CD;   // 首批地刺：5 秒后
        kingPullCd = Balance.KING3_PULL_CD;     // 首次牵引：5 秒后
        kingPullT = 0f;
        kingSummonCd = Balance.KING3_SUMMON_CD; // 首只召唤 Boss：25 秒后
        kingTelegraphT = 0f;
        kingFaceRight = true;
    }

    /**
     * 第二管血分裂：血池降到 KING3_HP_PER_BAR（第一管打空）时，
     * 王座本体裂出一个分身——两尊共享同一个血池（见 damage 的伤害转发），
     * 玩家把血池彻底打空才算真正击破。整场只触发一次（kingSplit 守卫）。
     * 分身复刻三阶段本体的行为：常态不动、每 3 秒传送（落点 1 秒前摇后爆发），
     * 但传送与本体错开——本体瞬移 KING3_TWIN_TELE_DELAY 秒后分身才起跳，两尊不同时瞬移。
     */
    private void spawnKingTwin() {
        int k = kingId;
        if (k < 0 || !alive[k]) {
            return;
        }
        kingSplit = true;
        // 从本体旁边 76px 随机方向裂出，钳回竞技场
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        int id = spawnEnemy(x[k] + (float) Math.cos(ang) * 76f,
                y[k] + (float) Math.sin(ang) * 76f, 0, V_BOSS);
        if (id < 0) {
            return;
        }
        maxHp[id] = Balance.KING3_HP;
        hp[id] = hp[k];                 // 出生即与共享血池同血
        speed[id] = 0f;                 // 位移走 updateKingTwin 的手动追击路径
        r[id] = Balance.KING3_RADIUS;
        dmg[id] = Balance.KING3_CONTACT_DAMAGE;
        enemyShield[id] = 0f;
        clampKingArena(id);
        kingTwinId = id;
        // 首轮传送：与本体错开——本体下一次瞬移（max(CD, 前摇)）之后再等 KING3_TWIN_TELE_DELAY 秒才起跳
        kingTwinTeleCd = Math.max(kingTeleCd, kingTeleT) + Balance.KING3_TWIN_TELE_DELAY;
        kingTwinTeleT = 0f;
        int w = firstWizard();
        kingTwinFaceRight = (w < 0) || x[w] >= x[id];
        spawnFx(FX_BLAST, x[k], y[k], 0f, 0f, 110f, 0.5f, Element.NONE);   // 分裂爆闪
    }

    /**
     * 分身行为（三阶段设定与本体一致，但传送错开 KING3_TWIN_TELE_DELAY 秒）：不走路——
     * 每 3 秒随机传送到玩家 50 以内，落位后 1 秒前摇，前摇到期以落点为中心爆发 30 伤害；
     * 起跳节拍由本体的瞬移校正（startKingTele 里把这里的 CD 重设为延迟量），不与本体同时起跳。
     * 血量每帧从共享血池同步（受到的伤害已在 damage() 里转发给本体）。
     * 本体收场时由 kill() 的三阶段分支随杀随清，这里只做兜底清理。
     */
    private void updateKingTwin(float dt) {
        int t = kingTwinId;
        if (t < 0) {
            return;
        }
        if (!alive[t]) {
            kingTwinId = -1;
            return;
        }
        int k = kingId;
        if (k < 0 || !alive[k]) {
            return;
        }
        hp[t] = hp[k];                  // 共享血池：分身的血条/受击反馈与本体一致
        speed[t] = 0f;
        // 传送节拍：与本体错开（本体瞬移后 2 秒起跳，1 秒前摇含在 3 秒周期内），前摇到期爆发后立刻抽下一个落点
        if (kingTwinTeleT > 0f) {
            kingTwinTeleT -= dt;
            if (kingTwinTeleT <= 0f) {
                explodeTwinTele();
            }
        }
        kingTwinTeleCd -= dt;
        if (kingTwinTeleCd <= 0f && kingTwinTeleT <= 0f) {
            startTwinTele();
            kingTwinTeleCd = Balance.KING3_TELE_CD;
        }
    }

    /** 分身传送落位：与本体同款（玩家 KING3_TELE_RANGE 内随机抽一点，瞬移过去并起 1 秒前摇） */
    private void startTwinTele() {
        int t = kingTwinId;
        int w = firstWizard();
        if (t < 0 || !alive[t] || w < 0) {
            return;
        }
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        float dist = Balance.KING3_TELE_MIN
                + rng.nextFloat() * (Balance.KING3_TELE_RANGE - Balance.KING3_TELE_MIN);
        x[t] = x[w] + (float) Math.cos(ang) * dist;
        y[t] = y[w] + (float) Math.sin(ang) * dist;
        clampKingArena(t);      // 落点钳回竞技场（钳制只会让落点更靠近玩家，不破坏 50 的承诺）
        kingTwinFaceRight = x[w] >= x[t];
        kingTwinTeleT = Balance.KING3_TELE_TELEGRAPH;
    }

    /**
     * 分身传送前摇到期：以落点为中心 100 范围爆发 30 伤害。
     * 每次命中玩家同样从深渊汲取 200 生命（与本体同款的王座被动）。
     */
    private void explodeTwinTele() {
        int t = kingTwinId;
        if (t < 0 || !alive[t]) {
            return;
        }
        spawnFx(FX_BLAST, x[t], y[t], 0f, 0f, Balance.KING3_TELE_RADIUS, 0.34f, Element.NONE);
        int hits = 0;
        for (int n = 0; n < wizards.size(); n++) {
            int wz = wizards.get(n);
            if (!alive[wz]) {
                continue;
            }
            float dx = x[wz] - x[t];
            float dy = y[wz] - y[t];
            float rr = Balance.KING3_TELE_RADIUS + r[wz];
            if (dx * dx + dy * dy <= rr * rr && iframe[wz] <= 0f) {
                damage(wz, Balance.KING3_TELE_DAMAGE);
                iframe[wz] = heroIframe(wz);
                hits++;
            }
        }
        if (hits > 0) {
            kingDrain(hits);
        }
    }

    /**
     * 三阶段行为（王座本体）：
     *   移动：不走路——每 3 秒随机传送到玩家 50 以内，落位后 1 秒前摇
     *         （减伤 90% → 20% 的输出窗口），前摇到期以落点为中心 100 范围爆发 30 伤害；
     *   保留：魔弹（每轮 5 连发）/ 地面预警圈 / 裂隙刷怪（三阶段每次 4 只）；
     *   新增：地刺（5 秒一批）、深渊牵引（5 秒一次，玩家被按 30 速拽向王座）、
     *         裂隙召唤 Boss（25 秒一只）；
     *   被动：每次对玩家造成伤害都从深渊汲取 200 生命（见 kingDrain 的各调用点）。
     */
    private void updateKing3(float dt) {
        // 传送节拍：CD 常驻推进（1 秒前摇含在 3 秒周期内），前摇到期爆发后立刻抽下一个落点
        if (kingTeleT > 0f) {
            kingTeleT -= dt;
            if (kingTeleT <= 0f) {
                explodeKingTele();
            }
        }
        kingTeleCd -= dt;
        if (kingTeleCd <= 0f && kingTeleT <= 0f) {
            startKingTele();
            kingTeleCd = Balance.KING3_TELE_CD;
        }
        // 保留：魔弹每轮（追踪 4s + 间隔 1s）对玩家扇形齐射（每轮 5 颗）
        kingBoltCd -= dt;
        if (kingBoltCd <= 0f) {
            fireKingBolts2();
            kingBoltCd = Balance.KING2_BOLT_TRACK + Balance.KING2_BOLT_REST;
        }
        // 保留：地面攻击提示每 6 秒（玩家脚下 150 圈、1.5 秒前摇）
        kingAoeCd -= dt;
        if (kingAoeCd <= 0f) {
            fireKingAoe2();
            kingAoeCd = Balance.KING2_AOE_CD;
        }
        // 保留：裂隙刷怪每 5 秒（三阶段每次 4 只 / 二阶段每道 2 只）
        kingRiftCd -= dt;
        if (kingRiftCd <= 0f) {
            openKingRift();
            kingRiftCd = Balance.KING2_RIFT_CD;
        }
        // 地刺：每 5 秒在玩家附近随机位置亮起警示圈（1 秒后刺出）
        kingSpikeCd -= dt;
        if (kingSpikeCd <= 0f) {
            spawnKingSpike();
            kingSpikeCd = Balance.KING3_SPIKE_CD;
        }
        // 深渊牵引：每 5 秒把玩家往王座（深渊）拽 1.5 秒
        kingPullCd -= dt;
        if (kingPullCd <= 0f) {
            kingPullT = Balance.KING3_PULL_DUR;
            kingPullCd = Balance.KING3_PULL_CD;
        }
        if (kingPullT > 0f) {
            applyKingPull(dt);
            kingPullT -= dt;
        }
        // 裂隙召唤 Boss：每 25 秒一只（除奶蛙外的随机档位）
        kingSummonCd -= dt;
        if (kingSummonCd <= 0f) {
            summonKingBoss();
            kingSummonCd = Balance.KING3_SUMMON_CD;
        }
    }

    /** 传送落位：在玩家 KING3_TELE_RANGE 内随机抽一点，瞬移过去并起 1 秒前摇 */
    private void startKingTele() {
        int k = kingId;
        int w = firstWizard();
        if (k < 0 || w < 0) {
            return;
        }
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        float dist = Balance.KING3_TELE_MIN
                + rng.nextFloat() * (Balance.KING3_TELE_RANGE - Balance.KING3_TELE_MIN);
        x[k] = x[w] + (float) Math.cos(ang) * dist;
        y[k] = y[w] + (float) Math.sin(ang) * dist;
        clampKingArena(k);      // 落点钳回竞技场（钳制只会让落点更靠近玩家，不破坏 50 的承诺）
        kingFaceRight = x[w] >= x[k];
        kingTeleT = Balance.KING3_TELE_TELEGRAPH;
        // 分身错开：本体起跳后 KING3_TWIN_TELE_DELAY 秒才轮到分身瞬移（用户要求：不要一起瞬移）
        if (kingTwinId >= 0) {
            kingTwinTeleCd = Balance.KING3_TWIN_TELE_DELAY;
        }
    }

    /**
     * 传送前摇到期：以王座落点为中心 100 范围爆发 30 伤害。
     * 每次命中玩家都从深渊汲取 200 生命（kingDrain）。
     */
    private void explodeKingTele() {
        int k = kingId;
        if (k < 0 || !alive[k]) {
            return;
        }
        spawnFx(FX_BLAST, x[k], y[k], 0f, 0f, Balance.KING3_TELE_RADIUS, 0.34f, Element.NONE);
        int hits = 0;
        for (int n = 0; n < wizards.size(); n++) {
            int wz = wizards.get(n);
            if (!alive[wz]) {
                continue;
            }
            float dx = x[wz] - x[k];
            float dy = y[wz] - y[k];
            float rr = Balance.KING3_TELE_RADIUS + r[wz];
            if (dx * dx + dy * dy <= rr * rr && iframe[wz] <= 0f) {
                damage(wz, Balance.KING3_TELE_DAMAGE);
                iframe[wz] = heroIframe(wz);
                hits++;
            }
        }
        if (hits > 0) {
            kingDrain(hits);
        }
    }

    /** 三阶段地刺：在玩家周围随机位置生成警示圈（1 秒前摇，到期刺出 30 伤害） */
    private void spawnKingSpike() {
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        float dist = rng.nextFloat() * Balance.KING3_SPIKE_RANGE;
        spawnZone(ZONE_KING_SPIKE_TELE,
                clampCoord(x[w] + (float) Math.cos(ang) * dist),
                clampCoord(y[w] + (float) Math.sin(ang) * dist),
                Balance.KING3_SPIKE_RADIUS, Balance.KING3_SPIKE_TELEGRAPH,
                Balance.KING3_SPIKE_DAMAGE, Element.NONE, -1);
    }

    /** 深渊牵引：1.5 秒内每帧把玩家朝王座方向推 KING3_PULL_SPEED（用户：「视为玩家向深渊走」） */
    private void applyKingPull(float dt) {
        int k = kingId;
        if (k < 0 || !alive[k]) {
            return;
        }
        for (int n = 0; n < wizards.size(); n++) {
            int wz = wizards.get(n);
            if (!alive[wz]) {
                continue;
            }
            float dx = x[k] - x[wz];
            float dy = y[k] - y[wz];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > 1e-3f) {
                x[wz] += dx / d * Balance.KING3_PULL_SPEED * dt;
                y[wz] += dy / d * Balance.KING3_PULL_SPEED * dt;
                clampKingArena(wz);
            }
        }
    }

    /**
     * 三阶段召唤：从裂隙里爬出一只 Boss（除奶蛙外的随机档位）。
     * 不走 spawnBoss 的全局 bossId 通道（那会接管 HUD 血条与阶段技能），
     * 用 V_BOSS 变体 + 档位数值直接落位；档位存进 carry 供渲染取形象。
     */
    private void summonKingBoss() {
        if (enemiesAlive >= Balance.KING2_MAX_ENEMIES) {
            return;     // 同屏魔物已封顶：这次召唤跳过
        }
        float rx = (rng.nextFloat() * 2f - 1f) * (Balance.ARENA_HALF_X - 90f);
        float ry = Balance.ARENA_TOP + 70f
                + rng.nextFloat() * (Balance.ARENA_BOTTOM - Balance.ARENA_TOP - 110f);
        spawnFx(FX_RIFT, rx, ry, 0f, 0f, Balance.KING2_RIFT_FX_R, Balance.KING3_SUMMON_FX_TTL, Element.NONE);
        int tier = rng.nextInt(Balance.BOSS_HP_TIERS.length);   // 除奶蛙外的 4 档随机（池里本来就没有奶蛙）
        int id = spawnEnemy(rx, ry, rng.nextInt(3), V_BOSS);
        if (id < 0) {
            return;
        }
        maxHp[id] = Balance.BOSS_HP_TIERS[tier];
        hp[id] = maxHp[id];
        speed[id] = Balance.BOSS_SPEED;
        r[id] = Balance.BOSS_RADIUS;
        dmg[id] = Balance.BOSS_DMG_TIERS[tier];
        enemyShield[id] = Balance.ELITE_SHIELD * (1f + tier * 0.6f);
        carry[id] = tier;       // 借用 carry 存档位：渲染按它取 Boss 形象
    }

    /**
     * 三阶段被动：王座每次造成伤害都从深渊汲取生命（每次命中 +200，可叠加）。
     * 调用点：传送爆发 / 地刺 / 接触咬中 / 魔弹命中。
     */
    private void kingDrain(int times) {
        if (kingPhase < 3 || kingId < 0 || !alive[kingId] || times <= 0) {
            return;
        }
        int k = kingId;
        hp[k] = Math.min(maxHp[k], hp[k] + Balance.KING3_HEAL_HIT * times);
    }

    /**
     * 二阶段行为：追击走位 + 三类技能节拍（魔弹 / 地面提示 / 裂隙刷怪）。
     * AOE 前摇期间国王站定蓄力（用户要求）——给玩家留出躲预警圈的反应窗口，
     * 前摇结束才恢复追击。
     */
    private void updateKing2(float dt) {
        int k = kingId;
        // 施法显示：AOE 前摇期间脚下泛红光（与一阶段共用 kingCasting 渲染通道）
        if (kingTelegraphT > 0f) {
            kingTelegraphT -= dt;
        } else {
            moveKing2(dt);
        }

        // 魔弹：每轮（追踪 4s + 间隔 1s）对每个玩家扇形齐射 5 颗
        kingBoltCd -= dt;
        if (kingBoltCd <= 0f) {
            fireKingBolts2();
            kingBoltCd = Balance.KING2_BOLT_TRACK + Balance.KING2_BOLT_REST;
        }
        // 地面攻击提示：每 10 秒，半径 150，1.5 秒前摇
        kingAoeCd -= dt;
        if (kingAoeCd <= 0f) {
            fireKingAoe2();
            kingAoeCd = Balance.KING2_AOE_CD;
        }
        // 裂隙刷怪：王宫中不断出现魔物
        kingRiftCd -= dt;
        if (kingRiftCd <= 0f) {
            openKingRift();
            kingRiftCd = Balance.KING2_RIFT_CD;
        }
    }

    /** 二阶段走位：本体追击（细节见 chaseKing） */
    private void moveKing2(float dt) {
        chaseKing(kingId, dt);
    }

    /**
     * 国王系追击（二阶段本体专用。三阶段本体与分身都不走路，位移全走传送）：
     * 用户给定「会追玩家」——全程朝最近玩家直线追击，只按距离换档：
     * > 400 → 195；250~400 → 175；< 250 → 160。
     */
    private void chaseKing(int k, float dt) {
        if (k < 0 || !alive[k]) {
            return;
        }
        int w = nearestWizard(x[k], y[k]);
        if (w < 0) {
            return;
        }
        float dx = x[w] - x[k];
        float dy = y[w] - y[k];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            return;
        }
        float speed = (len > Balance.KING2_BAND_FAR) ? Balance.KING2_SPEED_FAR
                : (len > Balance.KING2_BAND_MID) ? Balance.KING2_SPEED_MID
                : Balance.KING2_SPEED_NEAR;
        float mvx = dx / len * speed;
        float mvy = dy / len * speed;
        x[k] += mvx * dt;
        y[k] += mvy * dt;
        clampKingArena(k);
        // 朝向：水平移动分量足够大才翻转（接近垂直追击时避免原地抖）
        if (Math.abs(mvx) > 20f) {
            kingFaceRight = mvx >= 0f;
        }
    }

    /**
     * 二阶段魔弹：对每个存活玩家扇形齐射 5 颗追踪弹
     * （相邻两发 ±15°，移速 130，追踪 4 秒后消失）。
     * 「消失后 1 秒再释放」由 kingBoltCd = 4 + 1 保证。
     */
    private void fireKingBolts2() {
        int k = kingId;
        if (k < 0) {
            return;
        }
        // 二阶段与三阶段均为每轮 5 颗（用户给定）
        int boltCount = kingPhase >= 3 ? Balance.KING3_BOLT_COUNT : Balance.KING2_BOLT_COUNT;
        for (int n = 0; n < wizards.size(); n++) {
            int w = wizards.get(n);
            if (!alive[w]) {
                continue;
            }
            for (int b = 0; b < boltCount; b++) {
                // 以「国王→目标」方向为中轴，左右对称铺开
                float off = (b - (boltCount - 1) * 0.5f) * Balance.KING2_BOLT_SPREAD;
                spawnKingBolt(k, w, off);
            }
        }
    }

    /**
     * 发一颗国王魔弹：从国王体表（半径处）沿偏转 off 弧度后的方向射出，
     * projTarget 记目标 id，交由 updateProjectiles 限速转向追踪。
     * 枪口偏移保证贴身时弹体不会生成在玩家体内当帧命中、齐射可见。
     */
    private void spawnKingBolt(int casterId, int targetId, float off) {
        float dx = x[targetId] - x[casterId];
        float dy = y[targetId] - y[casterId];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            dx = 0f;
            dy = 1f;
            len = 1f;
        }
        float ax = dx / len, ay = dy / len;                     // 目标方向
        float ca = (float) Math.cos(off), sa = (float) Math.sin(off);
        float bx = ax * ca - ay * sa, by = ax * sa + ay * ca;   // 扇形偏转后的发射方向
        int id = alloc(KIND_PROJECTILE,
                x[casterId] + bx * Balance.KING2_RADIUS,
                y[casterId] + by * Balance.KING2_RADIUS,
                7f, TEAM_ENEMY);
        if (id < 0) {
            return;
        }
        vx[id] = bx * Balance.KING2_BOLT_SPEED;
        vy[id] = by * Balance.KING2_BOLT_SPEED;
        dmg[id] = Balance.KING2_BOLT_DAMAGE;
        life[id] = Balance.KING2_BOLT_TRACK;    // 追踪 4 秒后自然消失
        owner[id] = casterId;
        meta[id] = 0;
        pierce[id] = 0;
        lastHit[id] = -1;
        projAoe[id] = 0f;
        projChain[id] = 0;
        projBounce[id] = 0;
        projTarget[id] = targetId;              // 追踪目标
    }

    /** 二阶段地面攻击：以玩家当前位置为中心，半径 150、1.5 秒前摇的预警圈 */
    private void fireKingAoe2() {
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        kingTelegraphT = Balance.KING2_AOE_TELEGRAPH;   // 渲染：国王脚下泛红光
        spawnZone(ZONE_WARNING, x[w], y[w], Balance.KING2_AOE_RADIUS,
                Balance.KING2_AOE_TELEGRAPH, Balance.KING2_AOE_DAMAGE, Element.NONE, -1);
    }

    /**
     * 在王宫中展开一道裂隙并爬出魔物（三阶段每次 4 只 / 二阶段每道 2 只）。
     * 裂隙视觉走 FX_RIFT（紫色漩涡，1.1 秒张开→收合），
     * 魔物直接落在裂隙上；同屏数量被 KING2_MAX_ENEMIES 封顶，避免把实体池挤爆。
     */
    private void openKingRift() {
        if (enemiesAlive >= Balance.KING2_MAX_ENEMIES) {
            return;
        }
        float rx = (rng.nextFloat() * 2f - 1f) * (Balance.ARENA_HALF_X - 90f);
        float ry = Balance.ARENA_TOP + 70f
                + rng.nextFloat() * (Balance.ARENA_BOTTOM - Balance.ARENA_TOP - 110f);
        spawnFx(FX_RIFT, rx, ry, 0f, 0f, Balance.KING2_RIFT_FX_R, Balance.KING2_RIFT_FX_TTL, Element.NONE);
        int mobCount = kingPhase >= 3 ? Balance.KING3_RIFT_COUNT : Balance.KING2_RIFT_COUNT;
        for (int s = 0; s < mobCount; s++) {
            float a = rng.nextFloat() * (float) (Math.PI * 2);
            int id = spawnEnemy(
                    clampCoord(rx + (float) Math.cos(a) * 30f),
                    clampCoord(ry + (float) Math.sin(a) * 30f),
                    rng.nextInt(3), V_NORMAL);
            if (id < 0) {
                break;
            }
        }
    }

    /** 把单个坐标钳制到可玩区内（国王裂隙/地刺落点用，避免刷进棕色城墙） */
    private static float clampCoord(float v) {
        return Math.max(-Balance.PLAY_HALF, Math.min(Balance.PLAY_HALF, v));
    }
    // 召唤物（召唤师的宠物）
    // ------------------------------------------------------------------

    /**
     * 生成一只宠物。血 = 当前时间点的普通小怪血 × MINION_HP_MUL（用户要求 2 倍）。
     * 用"当前小怪血"而不是固定值，是因为小怪血随时间成长——
     * 宠物血量跟着涨，才不会在后期变成一碰就碎的纸片。
     */
    public int spawnMinion(int ownerId, int slotIndex) {
        float ang = (float) (Math.PI * 2) * slotIndex / Math.max(1, Balance.SUMMON_COUNT);
        int id = alloc(KIND_MINION,
                clampX(x[ownerId] + (float) Math.cos(ang) * 34f),
                clampY(y[ownerId] + (float) Math.sin(ang) * 34f),
                Balance.MINION_RADIUS, TEAM_PLAYER);
        if (id < 0) {
            return -1;
        }
        float hp0 = Balance.ENEMY_HP * Balance.enemyHpScale(time) * Balance.MINION_HP_MUL;
        maxHp[id] = hp0;
        hp[id] = hp0;
        speed[id] = Balance.MINION_SPEED;
        dmg[id] = Balance.MINION_DAMAGE;
        owner[id] = ownerId;
        slot[id] = slotIndex;
        cd[id] = 0f;
        minions.add(id);
        return id;
    }

    /** 召唤师：每 SUMMON_INTERVAL 秒重新召唤一批宠物 */
    private void updateSummoners(float dt) {
        for (int n = 0; n < wizards.size(); n++) {
            int id = wizards.get(n);
            if (!alive[id]) {
                continue;
            }
            Loadout lo = loadout[id];
            if (lo == null || lo.classKind != HeroClass.SUMMONER) {
                continue;
            }
            summonT[id] -= dt;
            if (summonT[id] <= 0f) {
                summonBatch(id);
                summonT[id] = Balance.SUMMON_INTERVAL;
            }
        }
    }

    /**
     * 召唤一批宠物：先解散上一批，再召满血的 4 只。
     * 替换而不是叠加——叠加的话 20 分钟后会拖着几十只宠物，屏幕和性能都受不了。
     */
    private void summonBatch(int ownerId) {
        for (int n = minions.size() - 1; n >= 0; n--) {
            int m = minions.get(n);
            if (alive[m] && owner[m] == ownerId) {
                despawn(m);
                // 必须同步从列表里摘掉：despawn 会把 id 放回 freeList，
                // 紧接着的 alloc 又会拿到同一个 id，留着旧条目就会变成重复项
                // ——HUD 会瞬间显示 8/4，updateMinions 也会把同一只宠物算两遍。
                minions.removeAt(n);
            }
        }
        for (int s = 0; s < Balance.SUMMON_COUNT; s++) {
            spawnMinion(ownerId, s);
        }
    }

    /** 宠物 AI：听指挥 > 护主 > 跟随；任何时候都不能跑出拴绳半径 */
    private void updateMinions(float dt) {
        for (int n = 0; n < minions.size(); n++) {
            int i = minions.get(n);
            if (!alive[i] || kind[i] != KIND_MINION) {
                continue;
            }
            int o = owner[i];
            if (o < 0 || !alive[o]) {
                despawn(i);      // 主人没了，召唤物随之消散
                continue;
            }

            float dxo = x[o] - x[i];
            float dyo = y[o] - y[i];
            float distOwner = (float) Math.sqrt(dxo * dxo + dyo * dyo);
            boolean outOfLeash = distOwner > Balance.MINION_LEASH;

            int target = outOfLeash ? -1 : pickMinionTarget(i, o);

            float tx, ty;
            if (target >= 0) {
                tx = x[target];
                ty = y[target];
            } else if (distOwner > Balance.MINION_FOLLOW_DIST) {
                // 没目标（或跑太远被拽回）：回到主人身边，按站位序号散开，别糊在脚下
                tx = x[o] + (float) Math.cos(slot[i] * 1.57f) * Balance.MINION_FOLLOW_DIST;
                ty = y[o] + (float) Math.sin(slot[i] * 1.57f) * Balance.MINION_FOLLOW_DIST;
            } else {
                tx = x[i];
                ty = y[i];
            }

            float dx = tx - x[i];
            float dy = ty - y[i];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1e-3f) {
                // 被拴绳拽回时提速，避免宠物在边界外"拉皮筋"
                float sp = speed[i] * (outOfLeash ? 1.3f : 1f);
                vx[i] = dx / len * sp;
                vy[i] = dy / len * sp;
                x[i] += vx[i] * dt;
                y[i] += vy[i] * dt;
            }
            separateMinions(i);
            resolveObstacles(i);
            clampToWorld(i);      // 宠物同样被棕色城墙挡在内侧

            // 接触伤害：宠物是近战撞击，伤害吃主人的全伤害倍率
            if (target >= 0) {
                float ndx = x[target] - x[i];
                float ndy = y[target] - y[i];
                float nl = (float) Math.sqrt(ndx * ndx + ndy * ndy);
                if (nl < r[i] + r[target]) {
                    damage(target, minionDamage(i));
                    cd[i] = Balance.MINION_ATTACK_CD;
                }
            }
        }
    }

    /** 宠物彼此推开。只跟同一个主人的宠物算，数量个位数，O(m²) 足够 */
    private void separateMinions(int i) {
        int o = owner[i];
        for (int k = 0; k < minions.size(); k++) {
            int m = minions.get(k);
            if (m == i || !alive[m] || owner[m] != o) {
                continue;
            }
            float sx = x[i] - x[m];
            float sy = y[i] - y[m];
            float d2 = sx * sx + sy * sy;
            float minD = r[i] + r[m];
            if (d2 > 1e-4f && d2 < minD * minD) {
                float d = (float) Math.sqrt(d2);
                float push = (minD - d) * 0.5f;
                x[i] += sx / d * push;
                y[i] += sy / d * push;
            }
        }
    }

    private float minionDamage(int minionId) {
        Loadout lo = loadout[owner[minionId]];
        float mul = (lo != null) ? lo.stats.dmgMul : 1f;
        return dmg[minionId] * mul;
    }

    /**
     * 宠物选敌：第一优先是"朝鼠标点击的位置进攻"，其次才退回护主。
     * 两种情况下的目标都要离主人足够近——否则扑过去的路上就会被拴绳拽回来，
     * 表现为宠物在原地抽搐。
     */
    private int pickMinionTarget(int i, int o) {
        float reach = Balance.MINION_LEASH - 24f;
        int t = -1;
        if (orderT > 0f) {
            t = nearestEnemyWithin(orderX, orderY, Balance.MINION_ORDER_RANGE, x[o], y[o], reach);
        }
        if (t < 0) {
            t = nearestEnemyWithin(x[o], y[o], Balance.MINION_GUARD_RANGE, x[o], y[o], reach);
        }
        return t;
    }

    /** 在 (sx,sy) 附近找最近的敌人，且该敌人离锚点 (cx,cy) 不超过 maxFromAnchor */
    private int nearestEnemyWithin(float sx, float sy, float range,
                                   float cx, float cy, float maxFromAnchor) {
        enemyHash.query(sx, sy, range, scratch2);
        int best = -1;
        float bestD2 = range * range;
        float lim2 = maxFromAnchor * maxFromAnchor;
        for (int n = 0; n < scratch2.size(); n++) {
            int e = scratch2.get(n);
            if (!alive[e] || kind[e] != KIND_ENEMY) {
                continue;
            }
            float ox = x[e] - cx;
            float oy = y[e] - cy;
            if (ox * ox + oy * oy > lim2) {
                continue;
            }
            float dx = x[e] - sx;
            float dy = y[e] - sy;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = e;
            }
        }
        return best;
    }

    /** 玩家的"可攻击目标"：本体 + 宠物。宠物挡在路上就会被怪优先啃（护主的实质） */
    public int nearestPlayerUnit(float sx, float sy) {
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int n = 0; n < wizards.size(); n++) {
            int w = wizards.get(n);
            if (!alive[w]) {
                continue;
            }
            float dx = x[w] - sx;
            float dy = y[w] - sy;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestScore) {
                bestScore = d2;
                best = w;
            }
        }
        float bias = Balance.MINION_THREAT_BIAS * Balance.MINION_THREAT_BIAS;
        for (int n = 0; n < minions.size(); n++) {
            int m = minions.get(n);
            if (!alive[m]) {
                continue;
            }
            float dx = x[m] - sx;
            float dy = y[m] - sy;
            float d2 = (dx * dx + dy * dy) * bias;
            if (d2 < bestScore) {
                bestScore = d2;
                best = m;
            }
        }
        return best;
    }

    /** 预警圈：先在地上显示 telegraph 秒，到期对玩家与敌人爆炸 */
    private void spawnWarning(float sx, float sy) {
        spawnZone(ZONE_WARNING, sx, sy, Balance.WARNING_RADIUS,
                Balance.WARNING_TELEGRAPH, Balance.WARNING_DAMAGE, Element.NONE, -1);
    }

    /** 把实体推出与之重叠的障碍物（玩家与敌人共用） */
    /** 与障碍做一次碰撞推出。public 是为了让 BoneSerpent 这类外部 AI 模块能复用 */
    public void resolveObstacles(int id) {
        obstacleHash.query(x[id], y[id], r[id] + Balance.OBSTACLE_MAX_R, scratch2);
        for (int n = 0; n < scratch2.size(); n++) {
            int o = scratch2.get(n);
            if (!alive[o] || kind[o] != KIND_OBSTACLE || !obstacleBlocksMovement[o]) {
                continue;
            }
            pushOutOfObstacle(id, o);
        }
    }

    /** 角色移动与投射物遮挡共用的“圆形目标是否碰到障碍底座”判定。 */
    private boolean overlapsObstacle(int obstacle, float cx, float cy, float targetRadius) {
        return switch (ArenaMap.ObstacleShape.values()[obstacleShape[obstacle]]) {
            case CIRCLE -> circleOverlaps(cx, cy, targetRadius, x[obstacle], y[obstacle], r[obstacle]);
            case BOX -> circleOverlapsBox(cx, cy, targetRadius, x[obstacle], y[obstacle],
                    obstacleHalfW[obstacle], obstacleHalfH[obstacle]);
            case CAPSULE -> {
                float capRadius = Math.min(obstacleHalfW[obstacle], obstacleHalfH[obstacle]);
                float ax = x[obstacle], ay = y[obstacle], bx = x[obstacle], by = y[obstacle];
                if (obstacleHalfW[obstacle] >= obstacleHalfH[obstacle]) {
                    ax -= obstacleHalfW[obstacle] - capRadius;
                    bx += obstacleHalfW[obstacle] - capRadius;
                } else {
                    ay -= obstacleHalfH[obstacle] - capRadius;
                    by += obstacleHalfH[obstacle] - capRadius;
                }
                yield circleOverlapsCapsule(cx, cy, targetRadius, ax, ay, bx, by, capRadius);
            }
        };
    }

    /** 将移动实体推出形状化障碍，消除圆形近似在墙与箱子两侧制造的假阻挡。 */
    private void pushOutOfObstacle(int id, int obstacle) {
        switch (ArenaMap.ObstacleShape.values()[obstacleShape[obstacle]]) {
            case CIRCLE -> pushOutOfCircle(id, x[obstacle], y[obstacle], r[obstacle], 1f, 0f);
            case BOX -> pushOutOfBox(id, obstacle);
            case CAPSULE -> {
                float capRadius = Math.min(obstacleHalfW[obstacle], obstacleHalfH[obstacle]);
                boolean horizontal = obstacleHalfW[obstacle] >= obstacleHalfH[obstacle];
                float ax = x[obstacle], ay = y[obstacle], bx = x[obstacle], by = y[obstacle];
                if (horizontal) {
                    ax -= obstacleHalfW[obstacle] - capRadius;
                    bx += obstacleHalfW[obstacle] - capRadius;
                } else {
                    ay -= obstacleHalfH[obstacle] - capRadius;
                    by += obstacleHalfH[obstacle] - capRadius;
                }
                float sx = bx - ax, sy = by - ay;
                float len2 = sx * sx + sy * sy;
                float t = len2 <= 1e-4f ? 0f : ((x[id] - ax) * sx + (y[id] - ay) * sy) / len2;
                t = Math.max(0f, Math.min(1f, t));
                pushOutOfCircle(id, ax + sx * t, ay + sy * t, capRadius,
                        horizontal ? 0f : 1f, horizontal ? 1f : 0f);
            }
        }
    }

    private void pushOutOfBox(int id, int obstacle) {
        float halfW = obstacleHalfW[obstacle];
        float halfH = obstacleHalfH[obstacle];
        float minX = x[obstacle] - halfW, maxX = x[obstacle] + halfW;
        float minY = y[obstacle] - halfH, maxY = y[obstacle] + halfH;
        float nearestX = Math.max(minX, Math.min(maxX, x[id]));
        float nearestY = Math.max(minY, Math.min(maxY, y[id]));
        float dx = x[id] - nearestX, dy = y[id] - nearestY;
        float d2 = dx * dx + dy * dy;
        if (d2 >= r[id] * r[id]) {
            return;
        }
        if (d2 > 1e-4f) {
            float distance = (float) Math.sqrt(d2);
            float push = r[id] - distance + 0.01f;
            x[id] += dx / distance * push;
            y[id] += dy / distance * push;
            return;
        }
        float left = x[id] - minX, right = maxX - x[id];
        float top = y[id] - minY, bottom = maxY - y[id];
        float nearestSide = Math.min(Math.min(left, right), Math.min(top, bottom));
        if (nearestSide == left) x[id] = minX - r[id] - 0.01f;
        else if (nearestSide == right) x[id] = maxX + r[id] + 0.01f;
        else if (nearestSide == top) y[id] = minY - r[id] - 0.01f;
        else y[id] = maxY + r[id] + 0.01f;
    }

    private void pushOutOfCircle(int id, float cx, float cy, float radius, float fallbackX, float fallbackY) {
        float dx = x[id] - cx, dy = y[id] - cy;
        float d2 = dx * dx + dy * dy;
        float contactDistance = r[id] + radius;
        if (d2 >= contactDistance * contactDistance) {
            return;
        }
        if (d2 <= 1e-4f) {
            x[id] += fallbackX * (contactDistance + 0.01f);
            y[id] += fallbackY * (contactDistance + 0.01f);
            return;
        }
        float distance = (float) Math.sqrt(d2);
        float push = contactDistance - distance + 0.01f;
        x[id] += dx / distance * push;
        y[id] += dy / distance * push;
    }

    private static boolean circleOverlaps(float cx, float cy, float radius, float ox, float oy, float obstacleRadius) {
        float dx = cx - ox, dy = cy - oy, rr = radius + obstacleRadius;
        return dx * dx + dy * dy < rr * rr;
    }

    private static boolean circleOverlapsBox(float cx, float cy, float radius, float ox, float oy,
                                             float halfW, float halfH) {
        float nearestX = Math.max(ox - halfW, Math.min(ox + halfW, cx));
        float nearestY = Math.max(oy - halfH, Math.min(oy + halfH, cy));
        float dx = cx - nearestX, dy = cy - nearestY;
        return dx * dx + dy * dy < radius * radius;
    }

    private static boolean circleOverlapsCapsule(float cx, float cy, float radius, float ax, float ay,
                                                 float bx, float by, float capsuleRadius) {
        float sx = bx - ax, sy = by - ay;
        float len2 = sx * sx + sy * sy;
        float t = len2 <= 1e-4f ? 0f : ((cx - ax) * sx + (cy - ay) * sy) / len2;
        t = Math.max(0f, Math.min(1f, t));
        return circleOverlaps(cx, cy, radius, ax + sx * t, ay + sy * t, capsuleRadius);
    }

    /**
     * 把实体留在连续战区。先按节点/连接段的自然边缘回退，再保留一个很远的安全兜底，
     * 不再把所有移动压回固定矩形房间。
     */
    /** 将实体回退到作者定义的连续可走战区；骨蛇等外部 AI 也必须遵守同一份路线边界。 */
    public void clampToWorld(int id) {
        if (!arenaMap.isWalkable(x[id], y[id], r[id])) {
            if (arenaMap.isWalkable(px[id], py[id], r[id])) {
                x[id] = px[id];
                y[id] = py[id];
            }
        }
        float hx = arenaMap.halfWidth() - r[id];
        float hy = arenaMap.halfHeight() - r[id];
        if (x[id] < -hx) {
            x[id] = -hx;
        } else if (x[id] > hx) {
            x[id] = hx;
        }
        if (y[id] < -hy) {
            y[id] = -hy;
        } else if (y[id] > hy) {
            y[id] = hy;
        }
    }

    /** 玩家受击无敌帧：按职业取基础值 + 灵巧被动加成。public：BoneSerpent.bite() 复用 */
    public float heroIframe(int wid) {
        Loadout lo = loadout[wid];
        int ck = (lo != null) ? lo.classKind : HeroClass.WIZARD;
        float add = (lo != null && lo.stats != null) ? lo.stats.iframeAdd : 0f;
        return HeroClass.baseIframe(ck) + add;
    }

    /** 连续战区的世界安全兜底；正常生成由 pickRouteSpawn 保证落在可走路线内。 */
    private float clampX(float v) {
        return Math.max(-Balance.PLAY_HALF, Math.min(Balance.PLAY_HALF, v));
    }

    private float clampY(float v) {
        return Math.max(-Balance.PLAY_HALF, Math.min(Balance.PLAY_HALF, v));
    }

    /** 最近的经验宝石（小偷用） */
    private int nearestPickup(float sx, float sy) {
        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        for (int i = 0; i < high; i++) {
            if (kind[i] != KIND_PICKUP || !alive[i]) {
                continue;
            }
            float dx = x[i] - sx, dy = y[i] - sy;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 法术
    // ------------------------------------------------------------------

    /** 施法。自动模式下自动索敌放技能；手动模式下按住开火键朝 aim 方向放。 */
    private void castSpells(float dt, InputCommand in) {
        boolean manual = !autoFire;
        /**
         * 战士蓄力重击：长按左键累计到阈值即进入"蓄力"，此时近战扇形暂停自动触发；
         * 松开鼠标的那一刻倾泻一次放大版重击。
         * 用 warriorCharging 记录"上一帧是否处于蓄力"，从而在松开帧识别 release，
         * 并把松开那一帧记下的 charge 值（warriorChargePower）交给本次重击使用。
         */
        int heroId = firstWizard();
        boolean warriorHeld = manual && (in.buttons & InputCommand.BUTTON_FIRE) != 0
                && heroId >= 0 && classOf(heroId) == HeroClass.WARRIOR;
        boolean warriorNowCharging = warriorHeld && in.charge >= Balance.WARRIOR_CHARGE_MIN;
        boolean warriorRelease = warriorCharging && !warriorNowCharging;
        if (warriorNowCharging) {
            warriorChargePower = in.charge;     // 持续刷新，松开那一刻即为最终蓄力值
        }
        warriorCharging = warriorNowCharging;

        for (int n = 0; n < wizards.size(); n++) {
            int id = wizards.get(n);
            if (!alive[id]) {
                continue;
            }
            Loadout lo = loadout[id];
            if (lo == null) {
                continue;
            }
            boolean warrior = lo.classKind == HeroClass.WARRIOR;
            for (int s = 0; s < Loadout.SLOTS; s++) {
                int raw = lo.spells[s];
                if (raw == Spells.NONE) {
                    continue;
                }
                SpellDef def = Spells.get(lo.resolvedSpell(s));
                if (def == null) {
                    continue;
                }
                lo.cd[s] -= dt;
                if (lo.cd[s] > 0f) {
                    continue;
                }
                // 战士的近战扇形受蓄力接管：蓄力中不放，松开帧放一次重击（并顺带放掉其他槽位）
                boolean meleeArc = def.form == SpellDef.Form.MELEE_ARC;
                float chargeOverride = 0f;
                boolean fired;
                if (manual) {
                    boolean fireHeld = (in.buttons & InputCommand.BUTTON_FIRE) != 0;
                    if (warrior && meleeArc && warriorCharging) {
                        continue;       // 蓄力中：近战扇形暂停自动挥砍，等松开
                    }
                    boolean chargedRelease = warrior && meleeArc && warriorRelease;
                    // 松开蓄力的那一帧开火键已经抬起，必须放行这次"重击"；
                    // 其余情况仍要求按住开火键（没按住就不放，也不进冷却）。
                    if (!fireHeld && !chargedRelease) {
                        continue;
                    }
                    if (chargedRelease) {
                        chargeOverride = warriorChargePower;   // 松开：释放放大版重击
                    }
                    float facing = (float) Math.atan2(in.aimY - y[id], in.aimX - x[id]);
                    fired = castOne(def, raw, id, s, true, facing, chargeOverride);
                } else {
                    fired = castOne(def, raw, id, s, false, 0f, 0f);
                }
                if (fired) {
                    float cdTime = def.cooldown / Math.max(0.1f, lo.stats.atkSpeed);
                    lo.cd[s] = cdTime;
                }
            }
        }
        if (warriorRelease) {
            warriorChargePower = 0f;    // 重击已倾泻，清空蓄力
        }
    }

    /**
     * 计算一次施法的最终伤害。集中在这里，所有修正只算一次：
     *   1) 全伤害倍率（被动 + 质变）
     *   2) 元素亲和（秘法额外基础 20% + 秘法亲和倍率；火球亲和只影响后续 DoT）
     *   3) 暴击掷骰
     * 调用方：castOne（投射物 / 扇形 / 连锁都从这里拿初值）。
     */
    private float computePower(SpellDef def, Loadout lo) {
        Stats st = lo.stats;
        float p = def.damage * st.dmgMul;
        if (def.element == Element.ARCANE) {
            p *= (1f + Balance.ARCANE_DAMAGE_BONUS) * st.arcaneMul;
        }
        if (st.lastStandActive) {
            p *= 1f + Balance.LAST_STAND_DAMAGE;
        }
        if (st.critChance > 0f && rng.nextFloat() < st.critChance) {
            p *= st.critDmg;
        }
        return p;
    }

    /** 施放一个法术。返回是否成功出手（自动模式没找到目标就不进冷却，手动模式朝 aim 方向必出手） */
    private boolean castOne(SpellDef def, int rawSpellId, int caster, int slot, boolean manualAim, float facing) {
        return castOne(def, rawSpellId, caster, slot, manualAim, facing, 0f);
    }

    /**
     * 施放一个法术。charge 为这次出手的蓄力进度 0..1（仅近战扇形消费，0 即普通出手）。
     * 返回是否成功出手（自动模式没找到目标就不进冷却，手动模式朝 aim 方向必出手）。
     */
    private boolean castOne(SpellDef def, int rawSpellId, int caster, int slot, boolean manualAim, float facing, float charge) {
        Loadout lo = loadout[caster];
        Stats st = lo.stats;
        if (!manualAim) {
            float seekRange = (def.form == SpellDef.Form.MELEE_ARC) ? def.arcRadius : def.range;
            int target = nearestEnemy(x[caster], y[caster], seekRange);
            if (target < 0) {
                return false;
            }
            facing = (float) Math.atan2(y[target] - y[caster], x[target] - x[caster]);
        }
        float power = computePower(def, lo);
        // 蓄力重击：范围/张角/伤害按 charge 线性放大（0 → 原值，1 → 满蓄力倍率）
        float arcRadiusMul = 1f;
        float arcAngleMul  = 1f;
        float knockbackMul = 1f;
        if (charge > 0f) {
            float c = Math.min(1f, charge);
            arcRadiusMul = 1f + (Balance.WARRIOR_CHARGE_RADIUS - 1f) * c;
            arcAngleMul  = 1f + (Balance.WARRIOR_CHARGE_ANGLE  - 1f) * c;
            power *= 1f + (Balance.WARRIOR_CHARGE_DAMAGE - 1f) * c;
            // 击退也随蓄力放大——战士蓄满的重击是全游戏击退最强的一击
            knockbackMul = 1f + (Balance.WARRIOR_CHARGE_KNOCKBACK_MUL - 1f) * c;
        }

        switch (def.form) {
            case PROJECTILE -> {
                int n = Math.max(1, def.count);
                int pierce = Math.max(0, def.pierce + st.pierceAdd);
                float aoe = def.aoeRadius * st.areaMul;
                int totalChain = def.chain + (def.element == Element.SHOCK ? st.shockChainAdd : 0);
                int bounce = Math.max(0, def.bounce + st.bounceAdd);
                for (int i = 0; i < n; i++) {
                    // 单发时无偏移；多发时均匀铺满扇形
                    float off = (n == 1) ? 0f
                            : -def.spread * 0.5f + def.spread * i / (n - 1);
                    float a = facing + off;
                    spawnProjectile(def, rawSpellId, x[caster], y[caster],
                            (float) Math.cos(a), (float) Math.sin(a), caster,
                            power, pierce, aoe, totalChain, bounce);
                }
            }
            case MELEE_ARC -> arcHit(def, caster, facing, power, lo.stats, arcRadiusMul, arcAngleMul, knockbackMul);
        }
        return true;
    }

    /** 近战扇形：命中范围内所有敌人，不需要单体弹道判定 */
    private void arcHit(SpellDef def, int caster, float facing, float power, Stats st,
                        float radiusMul, float angleMul, float knockbackMul) {
        float arcRadius = def.arcRadius * radiusMul;
        float arcAngle  = def.arcAngle * angleMul;
        // 近战击退：法术自带 knockback（盾击/裂地）+ 基准小幅击退，再乘蓄力倍率
        float knockSpeed = (def.knockback + Balance.HIT_KNOCKBACK) * knockbackMul;
        int fx = spawnFx(FX_ARC, x[caster], y[caster], 0f, 0f, arcRadius, 0.15f, def.element);
        if (fx >= 0) {
            dmg[fx] = facing;                       // FX 不用 dmg，借来存扇形朝向
            vx[fx] = arcAngle * 0.5f;               // 借 vx 存半张角（弧度）
        }
        float cosF = (float) Math.cos(facing);
        float sinF = (float) Math.sin(facing);
        float cosHalf = (float) Math.cos(arcAngle * 0.5f);

        // 元素参数：DoT 时长和强度都要走被动乘算
        int ele = def.element;
        float elePot = def.elemPotency;
        float eleDur = def.elemDuration * st.dotDurMul;
        if (ele == Element.FIRE) {
            elePot *= st.fireMul;
        } else if (ele == Element.FROST) {
            eleDur *= st.frostDurMul;
        }

        enemyHash.query(x[caster], y[caster], arcRadius + Balance.MAX_TARGET_RADIUS, scratch);
        for (int n = 0; n < scratch.size(); n++) {
            int e = scratch.get(n);
            if (!alive[e] || kind[e] != KIND_ENEMY) {
                continue;
            }
            float dx = x[e] - x[caster];
            float dy = y[e] - y[caster];
            float d2 = dx * dx + dy * dy;
            float reach = arcRadius + r[e];
            if (d2 > reach * reach) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            if (d > 1e-3f && (dx * cosF + dy * sinF) / d < cosHalf) {
                continue;
            }
            damage(e, power);
            if (alive[e]) {
                applyElement(e, ele, elePot, eleDur, power);
                knockbackEnemy(e, x[caster], y[caster], knockSpeed);
            }
        }
    }

    private void updateProjectiles(float dt) {
        for (int i = 0; i < high; i++) {
            if (kind[i] != KIND_PROJECTILE || !alive[i]) {
                continue;
            }
            // 追踪箭：飞行中微调朝向最近敌人（仅玩家弹幕）
            if (team[i] == TEAM_PLAYER) {
                SpellDef pd = Spells.get(meta[i]);
                if (pd != null && pd.homing) {
                    int tgt = nearestEnemyExcluding(x[i], y[i], Balance.HOMING_RANGE, null, -1);
                    if (tgt >= 0) {
                        float desired = (float) Math.atan2(y[tgt] - y[i], x[tgt] - x[i]);
                        float cur = (float) Math.atan2(vy[i], vx[i]);
                        float diff = desired - cur;
                        while (diff > Math.PI) diff -= Math.PI * 2f;
                        while (diff < -Math.PI) diff += Math.PI * 2f;
                        float turn = Math.max(-Balance.HOMING_TURN, Math.min(Balance.HOMING_TURN, diff));
                        float na = cur + turn;
                        float sp = (float) Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i]);
                        vx[i] = (float) Math.cos(na) * sp;
                        vy[i] = (float) Math.sin(na) * sp;
                    }
                }
            } else if (projTarget[i] >= 0) {
                // 国王魔弹：限速转向锁定目标（projTarget）。速度恒定、转向不快，
                // 玩家靠走位能把它蹚在身后，等它 6 秒寿命耗尽自消。
                int tg = projTarget[i];
                if (tg < 0 || tg >= high || !alive[tg] || kind[tg] != KIND_WIZARD) {
                    projTarget[i] = -1;            // 目标阵亡/失效：转为直线飞行
                } else {
                    float desired = (float) Math.atan2(y[tg] - y[i], x[tg] - x[i]);
                    float cur = (float) Math.atan2(vy[i], vx[i]);
                    float diff = desired - cur;
                    while (diff > Math.PI) diff -= (float) (Math.PI * 2);
                    while (diff < -Math.PI) diff += (float) (Math.PI * 2);
                    float turn = Math.max(-Balance.KING2_BOLT_TURN * dt,
                            Math.min(Balance.KING2_BOLT_TURN * dt, diff));
                    float na = cur + turn;
                    float sp = (float) Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i]);
                    vx[i] = (float) Math.cos(na) * sp;
                    vy[i] = (float) Math.sin(na) * sp;
                }
            }
            // 飞碟 Boss 的追踪弹（meta == -1 的敌方弹幕）：每帧朝最近的玩家缓慢转向
            if (team[i] == TEAM_ENEMY && meta[i] == -1) {
                int tgt = nearestWizard(x[i], y[i]);
                if (tgt >= 0) {
                    float d2 = (x[tgt] - x[i]) * (x[tgt] - x[i])
                            + (y[tgt] - y[i]) * (y[tgt] - y[i]);
                    // 超出索敌半径就放弃这帧的追踪，保持当前弹道自顾自地飞
                    if (d2 <= Balance.BOSS_HOMING_SEEK * Balance.BOSS_HOMING_SEEK) {
                        float desired = (float) Math.atan2(y[tgt] - y[i], x[tgt] - x[i]);
                        float cur = (float) Math.atan2(vy[i], vx[i]);
                        float diff = desired - cur;
                        while (diff > Math.PI) diff -= Math.PI * 2f;
                        while (diff < -Math.PI) diff += Math.PI * 2f;
                        float turn = Math.max(-Balance.BOSS_HOMING_TURN,
                                Math.min(Balance.BOSS_HOMING_TURN, diff));
                        float na = cur + turn;
                        float sp = Balance.BOSS_HOMING_SPD;
                        vx[i] = (float) Math.cos(na) * sp;
                        vy[i] = (float) Math.sin(na) * sp;
                    }
                }
            }
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            life[i] -= dt;
            // 飞碟 Boss 的追踪弹（meta == -1）：寿命到了没人接着，就原地自爆（AOE 砸玩家）
            if (meta[i] == -1 && life[i] <= 0f) {
                detonateBossHoming(i);
                continue;
            }
            if (life[i] <= 0f) {
                despawn(i);
                continue;
            }
            // 障碍拦截：弹幕（无论敌我）碰到障碍物直接消失
            obstacleHash.query(x[i], y[i], r[i] + Balance.OBSTACLE_MAX_R, scratch2);
            boolean blocked = false;
            for (int n = 0; n < scratch2.size(); n++) {
                int o = scratch2.get(n);
                if (!alive[o] || kind[o] != KIND_OBSTACLE || !obstacleBlocksProjectiles[o]) {
                    continue;
                }
                if (overlapsObstacle(o, x[i], y[i], r[i])) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) {
                kill(i);
                continue;
            }

            if (team[i] == TEAM_PLAYER) {
                // 玩家弹幕：只打敌人
                enemyHash.query(x[i], y[i], r[i] + Balance.MAX_TARGET_RADIUS, scratch);
                for (int n = 0; n < scratch.size(); n++) {
                    int e = scratch.get(n);
                    if (!alive[e] || kind[e] != KIND_ENEMY || e == lastHit[i]) {
                        continue;
                    }
                    float dx = x[e] - x[i];
                    float dy = y[e] - y[i];
                    float rr = r[e] + r[i];
                    if (dx * dx + dy * dy > rr * rr) {
                        continue;
                    }
                    onProjectileHit(i, e);
                    if (pierce[i] > 0) {
                        pierce[i]--;
                        lastHit[i] = e;
                    } else if (projBounce[i] > 0) {
                        // 跳弹：转向下一个目标，不消耗穿透
                        redirectToNearest(i, e);
                        lastHit[i] = e;
                    } else {
                        kill(i);
                        break;
                    }
                }
            } else {
                // 敌方弹幕：打玩家本体，也会被宠物挡下（宠物护主的一部分）
                if (enemyBoltHit(i)) {
                    // 飞碟 Boss 追踪弹命中即炸（不靠直接撞的伤害，而是 AOE）
                    if (meta[i] == -1) {
                        detonateBossHoming(i);
                    } else {
                        kill(i);
                    }
                    continue;
                }
            }
        }
    }

    /** 敌方弹幕命中判定：先判玩家本体，再判宠物。返回是否命中（命中后弹幕自行销毁） */
    private boolean enemyBoltHit(int p) {
        for (int n = 0; n < wizards.size(); n++) {
            int wz = wizards.get(n);
            if (!alive[wz]) {
                continue;
            }
            float dx = x[wz] - x[p];
            float dy = y[wz] - y[p];
            float rr = r[wz] + r[p];
            if (dx * dx + dy * dy <= rr * rr) {
                if (iframe[wz] <= 0f) {
                    damage(wz, dmg[p]);
                    iframe[wz] = heroIframe(wz);
                }
                return true;
            }
        }
        for (int n = 0; n < minions.size(); n++) {
            int m = minions.get(n);
            if (!alive[m]) {
                continue;
            }
            float dx = x[m] - x[p];
            float dy = y[m] - y[p];
            float rr = r[m] + r[p];
            if (dx * dx + dy * dy <= rr * rr) {
                if (iframe[m] <= 0f) {
                    damage(m, dmg[p]);
                    iframe[m] = Balance.MINION_IFRAME;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 飞碟 Boss 追踪弹的爆炸结算：原地放一个 FX_BLAST + 半径内对玩家/宠物造成伤害与击退。
     * 命中玩家/宠物时由 updateProjectiles 在 enemyBoltHit 之后调用，自爆时由寿命耗尽分支调用。
     */
    private void detonateBossHoming(int p) {
        if (!alive[p]) {
            return;
        }
        float ex = x[p], ey = y[p];
        spawnFx(FX_BLAST, ex, ey, 0f, 0f, Balance.BOSS_HOMING_BLAST_R, 0.25f, Element.NONE);
        float r = Balance.BOSS_HOMING_BLAST_R;
        float kb = Balance.BOSS_HOMING_BLAST_KB;
        // 玩家本体：按距离衰减的中心点爆炸（越靠近弹心越痛）
        for (int n = 0; n < wizards.size(); n++) {
            int w = wizards.get(n);
            if (!alive[w]) {
                continue;
            }
            float dx = x[w] - ex, dy = y[w] - ey;
            float d2 = dx * dx + dy * dy;
            if (d2 > r * r) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            float ratio = 1f - d / r;          // 0..1 线性衰减（中心满伤）
            if (iframe[w] <= 0f) {
                damage(w, Balance.BOSS_HOMING_DMG * ratio);
                iframe[w] = heroIframe(w);
            }
            // 击退：远离弹心推开，方向沿玩家-弹心；零向量兜底随机方向
            if (d > 1e-3f) {
                kx[w] += dx / d * kb * ratio;
                ky[w] += dy / d * kb * ratio;
            } else {
                float a = rng.nextFloat() * (float) (Math.PI * 2);
                kx[w] += (float) Math.cos(a) * kb;
                ky[w] += (float) Math.sin(a) * kb;
            }
        }
        // 宠物（召唤师）：同样吃 AOE 与击退。无敌帧用宠物专属
        for (int n = 0; n < minions.size(); n++) {
            int m = minions.get(n);
            if (!alive[m]) {
                continue;
            }
            float dx = x[m] - ex, dy = y[m] - ey;
            float d2 = dx * dx + dy * dy;
            if (d2 > r * r) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            float ratio = 1f - d / r;
            if (iframe[m] <= 0f) {
                damage(m, Balance.BOSS_HOMING_DMG * 0.7f * ratio);   // 宠物吃 70% 伤害
                iframe[m] = Balance.MINION_IFRAME;
            }
            if (d > 1e-3f) {
                kx[m] += dx / d * kb * ratio;
                ky[m] += dy / d * kb * ratio;
            } else {
                float a = rng.nextFloat() * (float) (Math.PI * 2);
                kx[m] += (float) Math.cos(a) * kb;
                ky[m] += (float) Math.sin(a) * kb;
            }
        }
        despawn(p);
    }

    /**
     * 跳弹：找最近还没被命中的敌人，弹过去。
     * 距离用 RICOCHET_RANGE 限制，避免穿屏追人。
     */
    private void redirectToNearest(int p, int exclude) {
        chainUsed.clear();
        chainUsed.add(exclude);
        int next = nearestEnemyExcluding(x[p], y[p], Balance.RICOCHET_RANGE, chainUsed, -1);
        if (next < 0) {
            // 没目标就消失
            kill(p);
            return;
        }
        float dx = x[next] - x[p];
        float dy = y[next] - y[p];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            kill(p);
            return;
        }
        SpellDef def = Spells.get(meta[p]);
        float speedMag = (def != null) ? def.speed : 470f;
        vx[p] = dx / len * speedMag;
        vy[p] = dy / len * speedMag;
        lastHit[p] = -1;             // 重新开始计数
        projBounce[p]--;
    }

    private void onProjectileHit(int p, int e) {
        // meta 存的是基础形态 id，要用 resolved 来取 def（进化版的字段）
        int baseId = meta[p];
        int ownerId = owner[p];
        Loadout olo = loadout[ownerId];
        int resolvedId = (olo != null) ? Spells.resolve(baseId, olo.evolvedMask) : baseId;
        SpellDef def = Spells.get(resolvedId);
        float power = dmg[p];
        // 命中点先存下来：目标可能在这次伤害里死掉并被复用，之后就不能再读它的坐标
        float hx = x[e];
        float hy = y[e];

        damage(e, power);

        if (def == null) {
            return;
        }
        // 弹幕命中击退：沿弹道方向小幅推挤（比近战轻，避免把怪推出自己的弹道）
        if (alive[e]) {
            float sp = (float) Math.sqrt(vx[p] * vx[p] + vy[p] * vy[p]);
            float kbSrcX = (sp > 1e-3f) ? hx - vx[p] / sp : hx - 1f;
            float kbSrcY = (sp > 1e-3f) ? hy - vy[p] / sp : hy;
            knockbackEnemy(e, kbSrcX, kbSrcY, Balance.HIT_KNOCKBACK * Balance.HIT_KNOCKBACK_RANGED_MUL);
        }
        if (alive[e]) {
            // 元素参数按施法者 Stats 修正：DoT 时长走 dotDurMul，火焰伤害走 fireMul，冰霜时长走 frostDurMul
            float eleDur = def.elemDuration * (olo != null ? olo.stats.dotDurMul : 1f);
            float elePot = def.elemPotency;
            if (def.element == Element.FIRE && olo != null) {
                elePot *= olo.stats.fireMul;
            } else if (def.element == Element.FROST && olo != null) {
                eleDur *= olo.stats.frostDurMul;
            }
            applyElement(e, def.element, elePot, eleDur, power);
        }
        if (def.freeze > 0f) {
            stunT[e] = Math.max(stunT[e], def.freeze * ccMul(e));
        }
        if (projAoe[p] > 0f) {
            float aoeDmg = power * 0.6f;
            aoeDmg *= (olo != null ? olo.stats.reactionDmgMul : 1f);
            explode(x[p], y[p], projAoe[p], aoeDmg, def.element, 0f);
        }
        if (projChain[p] > 0) {
            chainFrom(hx, hy, e, def, power, projChain[p], projChain[p] * 0, olo);
        }
        // 烈焰新星：命中后留下燃烧区域
        if (def.zoneRadius > 0f) {
            float zoneDps = def.zoneDps * (olo != null ? olo.stats.fireMul : 1f);
            spawnZone(ZONE_FIRE, hx, hy, def.zoneRadius, def.zoneDuration, zoneDps,
                    def.zoneElement, ownerId);
        }
        // 冰锥分裂：穿透够阈值后命中分裂 3 枚碎片
        if (def.id == Spells.ICE_SHARD || def.id == Spells.ABSOLUTE_ZERO) {
            int basePierce = def.pierce + (olo != null ? olo.stats.pierceAdd : 0);
            if (basePierce >= Balance.ICE_SPLIT_PIERCE) {
                int cnt = Balance.ICE_SPLIT_COUNT;
                for (int k = 0; k < cnt; k++) {
                    float off = -Balance.ICE_SPLIT_SPREAD * 0.5f
                            + Balance.ICE_SPLIT_SPREAD * k / Math.max(1, cnt - 1);
                    float a = (float) Math.atan2(hy - y[p], hx - x[p]) + off;
                    // 分裂的碎片不继承穿透、伤害略低
                    spawnProjectile(def, baseId, hx, hy,
                            (float) Math.cos(a), (float) Math.sin(a),
                            ownerId, power * 0.7f, 0, 0f, 0, 0);
                }
            }
        }
    }

    /**
     * 连锁闪电：从命中点向外弹射，每跳衰减。用 scratch2，绝不能碰 scratch。
     *
     * chainRange 与 chainFalloff 改成由投射物传入：Stats 修正过的值（D3）。
     * 如果达到电网阈值，最后一跳位置留一个 ZONE_CHAIN。
     */
    private void chainFrom(float sx, float sy, int first, SpellDef def, float baseDamage,
                           int totalChain, int chainUsedCount, Loadout olo) {
        chainUsed.clear();
        chainUsed.add(first);
        float curX = sx;
        float curY = sy;
        float d = baseDamage;
        float falloff = def.chainFalloff;
        for (int j = 0; j < totalChain; j++) {
            d *= 1f - falloff;
            int next = nearestEnemyExcluding(curX, curY, def.chainRange, chainUsed, -1);
            if (next < 0) {
                break;
            }
            spawnFx(FX_BEAM, curX, curY, x[next], y[next], 0f, 0.12f, def.element);
            damage(next, d);
            if (alive[next]) {
                float eleDur = def.elemDuration * (olo != null ? olo.stats.dotDurMul : 1f);
                applyElement(next, def.element, def.elemPotency, eleDur, d);
            }
            chainUsed.add(next);
            curX = x[next];
            curY = y[next];
        }
        // 电网：弹射数达到阈值
        if (totalChain >= Balance.CHAIN_NET_CHAIN) {
            float netDps = Balance.CHAIN_NET_DPS * (olo != null ? olo.stats.fireMul : 1f);
            spawnZone(ZONE_CHAIN, curX, curY, Balance.CHAIN_NET_RADIUS,
                    Balance.CHAIN_NET_DURATION, netDps, Element.SHOCK,
                    olo != null ? loadoutToId(olo) : -1);
        }
    }

    private int loadoutToId(Loadout lo) {
        for (int i = 0; i < wizards.size(); i++) {
            int id = wizards.get(i);
            if (loadout[id] == lo) {
                return id;
            }
        }
        return -1;
    }

    private void updateFx(float dt) {
        for (int i = 0; i < high; i++) {
            if (kind[i] != KIND_FX || !alive[i]) {
                continue;
            }
            life[i] -= dt;
            if (life[i] <= 0f) {
                despawn(i);
            }
        }
    }

    // ------------------------------------------------------------------
    // 拾取物 / 区域 / 被动 tick
    // ------------------------------------------------------------------

    /**
     * 经验宝石与宝箱：吸附 + 拾取。
     *
     * 性能：宝石同屏上限 600，玩家最多 4 人，600×4 = 2400 次距离判定远低于空间哈希的代价，
     * 直接线性遍历。距离简单又正确——以后要省的话改用 SpatialHash 即可。
     */
    private void updatePickups(float dt) {
        for (int i = 0; i < high; i++) {
            if (kind[i] != KIND_PICKUP || !alive[i]) {
                continue;
            }
            life[i] -= dt;
            if (life[i] <= 0f) {
                despawn(i);
                continue;
            }
            int nearest = -1;
            float bestD2 = Float.MAX_VALUE;
            for (int n = 0; n < wizards.size(); n++) {
                int w = wizards.get(n);
                if (!alive[w]) {
                    continue;
                }
                float dx = x[w] - x[i];
                float dy = y[w] - y[i];
                float d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    nearest = w;
                }
            }
            if (nearest < 0) {
                continue;
            }
            Loadout lo = loadout[nearest];
            // 蘑菇的吸附范围单独放宽（任务道具，不该考验走位精度），其余拾取物沿用宝石半径
            boolean isMushroom = (meta[i] == PICKUP_MUSHROOM);
            float base = isMushroom ? Balance.MUSHROOM_PICKUP_RADIUS : Balance.PICKUP_RADIUS;
            float radius = base * (lo != null ? lo.stats.pickupMul : 1f);
            if (bestD2 <= radius * radius) {
                // 进入拾取范围：吸附
                float d = (float) Math.sqrt(bestD2);
                if (d > 1e-3f) {
                    float inv = 1f / d;
                    float dx = x[nearest] - x[i];
                    float dy = y[nearest] - y[i];
                    vx[i] = dx * inv * Balance.GEM_MAGNET_SPEED;
                    vy[i] = dy * inv * Balance.GEM_MAGNET_SPEED;
                }
            }
            // 移动（吸附时才有速度，否则为 0）
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            // 拾取判定：足够近就吸收
            if (bestD2 <= (r[i] + Balance.WIZARD_RADIUS) * (r[i] + Balance.WIZARD_RADIUS)) {
                if (meta[i] == PICKUP_CHEST) {
                    // 宝箱：直接给一次升级
                    if (lo != null) {
                        lo.gainXp(Loadout.xpForLevel(lo.level) - lo.xp + 1);
                    }
                } else if (meta[i] == PICKUP_MUSHROOM) {
                    // 蘑菇：给少量经验并推进「采集蘑菇」事件进度
                    if (lo != null) {
                        lo.gainXp(dmg[i]);
                    }
                    takeMushroom();
                } else if (lo != null) {
                    lo.gainXp(dmg[i]);
                }
                despawn(i);
            }
        }
    }

    /**
     * 地面区域：毒雾 / 火场 / 钉刺陷阱 / 电网。
     * 每 0.5 秒一个 tick；陷阱是触发即爆所以单独处理。
     */
    private void updateZones(float dt) {
        for (int i = 0; i < high; i++) {
            if (kind[i] != KIND_ZONE || !alive[i]) {
                continue;
            }
            int sub = meta[i];
            // 飞龙灼烧带：单独走 updateDragonBurns，不打敌人、不叠加元素
            if (sub == ZONE_DRAGON_BURN) {
                continue;
            }
            life[i] -= dt;
            if (life[i] <= 0f) {
                if (sub == ZONE_WARNING) {
                    // 预警圈到期：对玩家与敌人同时爆炸并击退。
                    // 伤害取创建时写入的 dmg——Boss 的 spawnWarning 与国王技能共用此通道，
                    // 两者半径与伤害不同，写死 WARNING_DAMAGE 会把国王技能打回 38。
                    // skipKing：这是国王自己的技能，王座系（本体与三阶段分身）不吃自伤、也不被震走。
                    float wd = (dmg[i] > 0f) ? dmg[i] : Balance.WARNING_DAMAGE;
                    explode(x[i], y[i], r[i], wd, Element.NONE, Balance.WARNING_KNOCKBACK, true);
                    int whits = damagePlayersInRadius(x[i], y[i], r[i], wd);
                    if (whits > 0) {
                        kingDrain(whits);   // 三阶段被动：技能命中玩家 → 汲取生命（其他模式内部自行忽略）
                    }
                } else if (sub == ZONE_KING_SPIKE_TELE) {
                    // 三阶段地刺前摇到期：刺出结算 30 伤害，原地留一丛刺身视觉
                    int shits = damagePlayersInRadius(x[i], y[i], r[i], dmg[i]);
                    if (shits > 0) {
                        kingDrain(shits);
                    }
                    spawnZone(ZONE_KING_SPIKE, x[i], y[i], r[i], Balance.KING3_SPIKE_FX_TTL,
                            0f, Element.NONE, -1);
                }
                despawn(i);
                continue;
            }
            if (sub == ZONE_KING_SPIKE_TELE || sub == ZONE_KING_SPIKE) {
                // 地刺的两种形态都不走敌人周期 tick：警示只等倒计时，刺身纯视觉
                continue;
            }
            if (sub == ZONE_TRAP) {
                // 钉刺陷阱：敌人进入即触发，伤害 + 眩晕 + 自毁
                if (iframe[i] == 0f) {
                    enemyHash.query(x[i], y[i], Balance.SPIKE_RADIUS + Balance.MAX_TARGET_RADIUS, scratch2);
                    for (int n = 0; n < scratch2.size(); n++) {
                        int e = scratch2.get(n);
                        if (!alive[e] || kind[e] != KIND_ENEMY) {
                            continue;
                        }
                        float dx = x[e] - x[i];
                        float dy = y[e] - y[i];
                        if (dx * dx + dy * dy <= Balance.SPIKE_RADIUS * Balance.SPIKE_RADIUS) {
                            damage(e, Balance.SPIKE_DAMAGE);
                            if (alive[e]) {
                                stunT[e] = Math.max(stunT[e], Balance.SPIKE_STUN * ccMul(e));
                            }
                            iframe[i] = 1f;   // 标记已触发
                            break;
                        }
                    }
                }
                if (iframe[i] == 1f) {
                    despawn(i);
                }
                continue;
            }
            // 其他区域：周期 tick
            cd[i] += dt;
            if (cd[i] < 0.5f) {
                continue;
            }
            cd[i] = 0f;
            enemyHash.query(x[i], y[i], r[i] + Balance.MAX_TARGET_RADIUS, scratch2);
            int ownerId = owner[i];
            Loadout olo = (ownerId >= 0) ? loadout[ownerId] : null;
            float dps = dmg[i];
            int ele = elem[i];
            for (int n = 0; n < scratch2.size(); n++) {
                int e = scratch2.get(n);
                if (!alive[e] || kind[e] != KIND_ENEMY) {
                    continue;
                }
                // 预警圈只是前摇视觉，不该当持续伤害场：0.5 秒一跳会误伤王座系
                //（与到期 explode 的 skipKing 同语义——“王座（含分身）不吃自己放的圈”）
                if (sub == ZONE_WARNING && isKingKind(e)) {
                    continue;
                }
                float dx = x[e] - x[i];
                float dy = y[e] - y[i];
                if (dx * dx + dy * dy > r[e] * r[e]) {
                    continue;
                }
                damage(e, dps * 0.5f);
                if (alive[e] && ele > Element.NONE) {
                    float dur = 0.5f * (olo != null ? olo.stats.dotDurMul : 1f);
                    float pot = dps;
                    if (ele == Element.FIRE && olo != null) {
                        pot *= olo.stats.fireMul;
                    } else if (ele == Element.POISON) {
                        pot = Math.min(pot, Balance.ELEM_POISON_MAX_DPS);
                    }
                    applyElement(e, ele, pot, dur, dps * 0.5f);
                }
            }
        }
    }

    /**
     * 4 个"由主动改造而来"的被动 + 周期资源的计时器。
     * 全部按"是否有该被动" + 计时器双条件触发，避免一直占用计算。
     */
    private void specialPassiveTick(float dt) {
        for (int n = 0; n < wizards.size(); n++) {
            int id = wizards.get(n);
            if (!alive[id]) {
                continue;
            }
            Loadout lo = loadout[id];
            if (lo == null) {
                continue;
            }
            Stats st = lo.stats;

            // 屏障：每 20 秒 15 点护盾
            if (st.barrier) {
                lo.barrierTimer -= dt;
                if (lo.barrierTimer <= 0f) {
                    lo.shield = Math.max(lo.shield, Balance.BARRIER_SHIELD);
                    lo.barrierTimer = Balance.BARRIER_INTERVAL;
                }
            }
            // 宝箱：每 60 秒在玩家位置生成
            if (st.chest) {
                lo.chestTimer -= dt;
                if (lo.chestTimer <= 0f) {
                    spawnChest(x[id], y[id]);
                    lo.chestTimer = Balance.CHEST_INTERVAL;
                }
            }
            // 重抽：每 20 秒 +1 免费重抽
            if (st.reroll) {
                lo.rerollTimer -= dt;
                if (lo.rerollTimer <= 0f) {
                    lo.rerolls++;
                    lo.rerollTimer = Balance.REROLL_INTERVAL;
                }
            }
            // 战吼护盾：每 25 秒 +30 护盾 + AOE 击退
            if (st.warCryShield) {
                lo.warCryTimer -= dt;
                if (lo.warCryTimer <= 0f) {
                    lo.shield = Math.max(lo.shield, Balance.WARCRY_SHIELD);
                    enemyHash.query(x[id], y[id], Balance.WARCRY_RADIUS + Balance.MAX_TARGET_RADIUS, scratch2);
                    for (int k = 0; k < scratch2.size(); k++) {
                        int e = scratch2.get(k);
                        if (!alive[e] || kind[e] != KIND_ENEMY) {
                            continue;
                        }
                        float dx = x[e] - x[id];
                        float dy = y[e] - y[id];
                        float d = (float) Math.sqrt(dx * dx + dy * dy);
                        if (d > 1e-3f && d <= Balance.WARCRY_RADIUS) {
                            float kbw = Balance.WARCRY_KNOCKBACK * ccMul(e);
                            kx[e] += dx / d * kbw;
                            ky[e] += dy / d * kbw;
                        }
                    }
                    lo.warCryTimer = Balance.WARCRY_INTERVAL;
                }
            }
            // 环绕旋风：每 0.4 秒对范围内敌人 8 伤害
            if (st.orbitingStorm) {
                lo.orbitingTimer -= dt;
                if (lo.orbitingTimer <= 0f) {
                    float dps = Balance.ORBITING_DAMAGE * 2.5f;   // 8 / 0.4s = 20 dps
                    enemyHash.query(x[id], y[id], Balance.ORBITING_RADIUS + Balance.MAX_TARGET_RADIUS, scratch2);
                    for (int k = 0; k < scratch2.size(); k++) {
                        int e = scratch2.get(k);
                        if (!alive[e] || kind[e] != KIND_ENEMY) {
                            continue;
                        }
                        float dx = x[e] - x[id];
                        float dy = y[e] - y[id];
                        if (dx * dx + dy * dy > Balance.ORBITING_RADIUS * Balance.ORBITING_RADIUS) {
                            continue;
                        }
                        damage(e, Balance.ORBITING_DAMAGE);
                    }
                    lo.orbitingTimer = Balance.ORBITING_INTERVAL;
                }
            }
            // 毒雾轨迹：移动时在身后铺毒
            if (st.poisonTrail) {
                lo.poisonTimer -= dt;
                if (lo.poisonTimer <= 0f) {
                    spawnZone(ZONE_POISON, x[id], y[id],
                            Balance.POISON_TRAIL_RADIUS, Balance.POISON_TRAIL_DURATION,
                            Balance.POISON_TRAIL_DPS, Element.POISON, id);
                    lo.poisonTimer = Balance.POISON_TRAIL_INTERVAL;
                }
            }
            // 钉刺陷阱：累计位移
            if (st.spikeTrap) {
                float ddx = x[id] - px[id];
                float ddy = y[id] - py[id];
                float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                lo.trapDistance += dist;
                if (lo.trapDistance >= Balance.SPIKE_DISTANCE) {
                    lo.trapDistance = 0f;
                    spawnZone(ZONE_TRAP, x[id], y[id], Balance.SPIKE_RADIUS, Balance.SPIKE_LIFE,
                            0f, Element.NONE, id);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 元素与反应
    // ------------------------------------------------------------------

    /**
     * 给目标附着元素。已附着不同元素时触发反应。
     *
     * @param incoming 触发这次附着的伤害数值，超导按它的百分比追加伤害
     */
    public void applyElement(int target, int element, float potency, float duration, float incoming) {
        if (!alive[target] || element <= Element.NONE) {
            return;
        }
        // 秘法不附着、不参与反应：它的价值就是命中瞬间的加伤，见 castOne / spawnProjectile
        if (element == Element.ARCANE) {
            return;
        }

        int cur = elem[target];
        if (cur == element) {
            // 同种元素续时间、叠强度（叠加速度减半，避免两个冰锥直接冻住不动）
            elemT[target] = Math.max(elemT[target], duration);
            elemP[target] = Math.min(potency + elemP[target] * 0.5f, elementCap(element));
            return;
        }
        if (cur != Element.NONE) {
            int reaction = Element.reaction(cur, element);
            if (reaction != Element.R_NONE) {
                // 触发反应前先把旧元素状态快照给 reaction 用（引爆要算剩余毒伤）
                float snapPot = elemP[target];
                float snapDur = elemT[target];
                elem[target] = Element.NONE;
                elemT[target] = 0f;
                elemP[target] = 0f;
                triggerReaction(reaction, target, incoming, snapPot, snapDur);
                return;
            }
        }
        elem[target] = element;
        elemT[target] = duration;
        elemP[target] = Math.min(potency, elementCap(element));
    }

    private static float elementCap(int element) {
        return switch (element) {
            case Element.FROST -> Balance.ELEM_FROST_MAX_SLOW;
            case Element.FIRE -> 40f;    // 燃烧每秒伤害上限
            case Element.POISON -> Balance.ELEM_POISON_MAX_DPS;
            case Element.SHOCK -> 0.60f; // 受伤增伤上限
            default -> Float.MAX_VALUE;
        };
    }

    private void triggerReaction(int reaction, int target, float incoming,
                                float oldPot, float oldDur) {
        reactionCounts[reaction]++;
        switch (reaction) {
            case Element.R_STEAM -> {
                float r = Balance.REACTION_STEAM_RADIUS * statsMulForReaction(target);
                float dmg = Balance.REACTION_STEAM_DAMAGE * statsMulForReactionDmg(target);
                explode(x[target], y[target], r, dmg, Element.FIRE, 0f);
                if (alive[target]) {
                    stunT[target] = Math.max(stunT[target], Balance.REACTION_STEAM_STUN * ccMul(target));
                }
            }
            case Element.R_SUPERCONDUCT -> {
                float ratio = Balance.REACTION_SUPERCONDUCT_RATIO
                        * statsMulForReactionDmg(target);
                damage(target, incoming * ratio);
                if (alive[target]) {
                    stunT[target] = Math.max(stunT[target], Balance.REACTION_SUPERCONDUCT_STUN * ccMul(target));
                }
            }
            case Element.R_OVERLOAD -> {
                float r = Balance.REACTION_OVERLOAD_RADIUS * statsMulForReaction(target);
                float dmg = Balance.REACTION_OVERLOAD_DAMAGE * statsMulForReactionDmg(target);
                explode(x[target], y[target], r, dmg, Element.SHOCK,
                        Balance.REACTION_OVERLOAD_KNOCKBACK);
            }
            case Element.R_DETONATE -> {
                // 立即结算剩余毒伤：剩余时间 × 当前 dps。值由 applyElement 快照传入
                float remaining = oldPot * Math.max(0f, oldDur) * Balance.DETONATE_RATIO
                        * statsMulForReactionDmg(target);
                if (remaining > 0f) {
                    damage(target, remaining);
                }
            }
            default -> { }
        }
    }

    /** 找造成这次反应的攻击者所属玩家的 stats（用于反应乘算）。
     *  当前用最近玩家简化处理，后续可改成按 owner 找 */
    private float statsMulForReaction(int target) {
        int w = firstWizard();
        if (w < 0 || loadout[w] == null) {
            return 1f;
        }
        return loadout[w].stats.reactionRadiusMul;
    }

    private float statsMulForReactionDmg(int target) {
        int w = firstWizard();
        if (w < 0 || loadout[w] == null) {
            return 1f;
        }
        return loadout[w].stats.reactionDmgMul;
    }

    /** 国王系实体（本体 / 三阶段分身）：王座自己的技能不伤、不推它们 */
    private boolean isKingKind(int e) {
        return e == kingId || e == kingTwinId;
    }

    /** 国王系“生根”判定：本体 / 分身处于施法或传送前摇时不吃爆炸击退（用户要求站定蓄力） */
    private boolean kingRooted(int e) {
        if (e == kingId) {
            return kingTelegraphT > 0f || kingTeleT > 0f;
        }
        return e == kingTwinId && kingTwinTeleT > 0f;
    }

    /** 范围伤害 + 可选击退。用 scratch2，调用点都在 scratch 的遍历里 */
    private void explode(float ex, float ey, float radius, float damage, int element, float knockback) {
        explode(ex, ey, radius, damage, element, knockback, false);
    }

    /**
     * 同上；skipKing=true 时王座系（本体与三阶段分身）都不吃这次爆炸——国王自己的预警圈专用：
     * 王座不该被自己放的圈震伤（也不该在传送前摇里被推走）。
     */
    private void explode(float ex, float ey, float radius, float damage, int element, float knockback, boolean skipKing) {
        spawnFx(FX_BLAST, ex, ey, 0f, 0f, radius, 0.25f, element);
        enemyHash.query(ex, ey, radius + Balance.MAX_TARGET_RADIUS, scratch2);
        for (int n = 0; n < scratch2.size(); n++) {
            int e = scratch2.get(n);
            if (!alive[e] || kind[e] != KIND_ENEMY || (skipKing && isKingKind(e))) {
                continue;
            }
            float dx = x[e] - ex;
            float dy = y[e] - ey;
            float d2 = dx * dx + dy * dy;
            float reach = radius + r[e];
            if (d2 > reach * reach) {
                continue;
            }
            damage(e, damage);
            // 国王系施法/传送前摇中脚下生根：不结算击退，保证“站定蓄力”不被爆炸余波推着走
            //（一阶段技能 / 三阶段传送，本体与分身同规则，用户要求）
            if (knockback > 0f && alive[e] && !kingRooted(e)) {
                float d = (float) Math.sqrt(d2);
                float kb = knockback * ccMul(e);   // Boss 抗性：击退被削弱
                if (d > 1e-3f) {
                    kx[e] += dx / d * kb;
                    ky[e] += dy / d * kb;
                } else {
                    kx[e] += kb;
                }
            }
        }
    }

    /**
     * 对范围内所有玩家（含宠物）造成伤害（预警圈 / 地刺爆炸用，友军伤害 D6 再做）。
     * 返回实际命中玩家的数量：三阶段王座按"造成伤害次数"汲取生命，调用方靠它计数。
     */
    private int damagePlayersInRadius(float ex, float ey, float radius, float dmg) {
        int hits = 0;
        for (int n = 0; n < wizards.size(); n++) {
            int wz = wizards.get(n);
            if (!alive[wz]) {
                continue;
            }
            float dx = x[wz] - ex;
            float dy = y[wz] - ey;
            float rr = radius + r[wz];
            if (dx * dx + dy * dy <= rr * rr) {
                if (iframe[wz] <= 0f) {
                    damage(wz, dmg);
                    iframe[wz] = heroIframe(wz);
                    hits++;
                }
            }
        }
        // 宠物同样吃 Boss 的范围技：站得近就得跟着挨打，否则召唤师等于白嫖一个免伤盾
        for (int n = 0; n < minions.size(); n++) {
            int m = minions.get(n);
            if (!alive[m]) {
                continue;
            }
            float dx = x[m] - ex;
            float dy = y[m] - ey;
            float rr = radius + r[m];
            if (dx * dx + dy * dy <= rr * rr) {
                if (iframe[m] <= 0f) {
                    damage(m, dmg);
                    iframe[m] = Balance.MINION_IFRAME;
                }
            }
        }
        return hits;
    }

    private void cullDistant() {
        int w = firstWizard();
        if (w < 0) {
            return;
        }
        float wx = x[w];
        float wy = y[w];
        float lim2 = Balance.DESPAWN_RANGE * Balance.DESPAWN_RANGE;
        for (int i = 0; i < high; i++) {
            if (kind[i] != KIND_ENEMY || !alive[i]) {
                continue;
            }
            // Boss 不回收：它移速（52）远低于玩家（195），跑远了就被删掉的话
            // Boss 战会莫名其妙自己结束。由 updateBossPhase 负责它的生命周期。
            if (i == bossId || i == milkyId) {
                continue;
            }
            // 雕像事件靶子不回收：玩家跑远了任务就永远完不成
            if (variant[i] == V_STATUE) {
                continue;
            }
            // 骨蛇的每一节都不回收：尾巴被判出局的话，蛇会自己掉尾巴
            if (variant[i] == V_SERPENT) {
                continue;
            }
            float dx = x[i] - wx;
            float dy = y[i] - wy;
            if (dx * dx + dy * dy > lim2) {
                despawn(i);
            }
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public int nearestEnemy(float sx, float sy, float maxRange) {
        return nearestEnemyExcluding(sx, sy, maxRange, null, -1);
    }

    /** 索敌。exclude 用于连锁闪电跳过已命中目标。固定用 scratch2，避免与调用点的 scratch 冲突 */
    private int nearestEnemyExcluding(float sx, float sy, float maxRange, IntList exclude, int skipId) {
        enemyHash.query(sx, sy, maxRange, scratch2);
        int best = -1;
        float bestD2 = maxRange * maxRange;
        for (int n = 0; n < scratch2.size(); n++) {
            int e = scratch2.get(n);
            if (!alive[e] || kind[e] != KIND_ENEMY || e == skipId) {
                continue;
            }
            if (exclude != null && exclude.contains(e)) {
                continue;
            }
            float dx = x[e] - sx;
            float dy = y[e] - sy;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = e;
            }
        }
        return best;
    }

    private int nearestWizard(float sx, float sy) {
        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        for (int n = 0; n < wizards.size(); n++) {
            int w = wizards.get(n);
            if (!alive[w]) {
                continue;
            }
            float dx = x[w] - sx;
            float dy = y[w] - sy;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = w;
            }
        }
        return best;
    }

    /** 第一个活着玩家的 id。WaveDirector 与客户端冒烟参数注入都要用，提升为 public */
    public int firstWizard() {
        for (int n = 0; n < wizards.size(); n++) {
            int w = wizards.get(n);
            if (alive[w]) {
                return w;
            }
        }
        return -1;
    }

    /** 该玩家实体的职业（找不到返回法师） */
    private int classOf(int id) {
        Loadout lo = loadout[id];
        return (lo != null) ? lo.classKind : HeroClass.WIZARD;
    }

    /** 战士职业特性：每击杀一个敌人，所有存活战士各回 2 HP（设计文档第 2 节） */
    private void healWarriorsOnKill() {
        for (int n = 0; n < wizards.size(); n++) {
            int id = wizards.get(n);
            if (!alive[id]) {
                continue;
            }
            Loadout lo = loadout[id];
            if (lo != null && lo.classKind == HeroClass.WARRIOR && lo.stats.healOnKill > 0f) {
                hp[id] = Math.min(maxHp[id], hp[id] + lo.stats.healOnKill);
            }
        }
    }

    // ------------------------------------------------------------------
    // 伤害与访问器
    // ------------------------------------------------------------------

    /**
     * 对目标造成伤害。集中所有"加伤/减伤"逻辑，避免散落。
     *
     * 顺序：
     *   1) 元素增伤（雷系：被电的目标更脆）
     *   2) 玩家身上：先扣护盾再扣血（护盾来自屏障/战吼被动）
     *   3) 减伤（来自"坚壁"等被动 + 绝境爆发反向补偿）
     *   4) 扣血
     *
     * crit 已经在调用方（castOne）掷过了，这里只处理减伤。
     */
    /** 被控抗性系数：Boss（含奶蛙）与骨蛇只吃 (1 - BOSS_CC_RESIST) 的眩晕/冰冻/减速/击退 */
    private float ccMul(int id) {
        return (variant[id] == V_BOSS || variant[id] == V_SERPENT)
                ? 1f - Balance.BOSS_CC_RESIST : 1f;
    }

    public void damage(int id, float amount) {
        if (id < 0 || !alive[id] || amount <= 0f) {
            return;
        }
        // 骨蛇：所有节共享一个血池，统一扣在头上。身体挨打也算血
        if (kind[id] == KIND_ENEMY && variant[id] == V_SERPENT) {
            int h = serpent[id];
            if (h < 0 || !alive[h]) {
                return;
            }
            // 同一帧内只结算一次：骨蛇有 12 个实体，一发火球的爆炸半径里
            // 往往同时罩住好几节，逐节转发会让这一发打出 12 倍伤害
            // （实测能把本该撑一分钟的小 Boss 压到 4 秒内被秒）。连锁闪电
            // 在它自己身上来回弹也是同一个问题，一并被这行挡掉。
            // 跨帧的持续输出不受影响，所以单发 DPS 手感不变。
            if (serpentHitT == time) {
                return;
            }
            serpentHitT = time;
            id = h;
        }
        // 三阶段分裂的王座分身：与本体共享一个血池，伤害统一记在本体上。
        // （分身的 hp 每帧从本体同步，所以它永远不会在这里被打到 0。）
        if (id == kingTwinId && kingId >= 0 && alive[kingId]) {
            id = kingId;
        }
        // 附着雷电的目标更脆
        float amt = (elem[id] == Element.SHOCK)
                ? amount * (1f + Balance.ELEM_SHOCK_DMG_AMP) : amount;

        // 三阶段王座本体：常驻 90% 减伤；传送前摇期间降到 20%（玩家的输出窗口）
        if (id == kingId && kingPhase >= 3) {
            amt *= 1f - (kingTeleT > 0f ? Balance.KING3_DR_CAST : Balance.KING3_DR);
        } else if (id == kingId && kingPhase == 2) {
            // 二阶段国王：常驻 20% 减伤（用户给定）
            amt *= 1f - Balance.KING2_DR;
        }

        if (kind[id] == KIND_WIZARD) {
            Loadout lo = loadout[id];
            if (lo != null) {
                // 1) 护盾
                if (lo.shield > 0f) {
                    float absorbed = Math.min(lo.shield, amt);
                    lo.shield -= absorbed;
                    amt -= absorbed;
                }
                // 2) 减伤
                amt *= 1f - lo.stats.dr;
            }
        } else if (kind[id] == KIND_ENEMY && enemyShield[id] > 0f) {
            // 敌人护盾（精英 / Boss）：先扣盾再扣血
            float absorbed = Math.min(enemyShield[id], amt);
            enemyShield[id] -= absorbed;
            amt -= absorbed;
        }
        hp[id] -= amt;
        if (hp[id] <= 0f) {
            kill(id);
        }
    }

    /**
     * 给敌人施加一次击退，方向为 (srcX,srcY) → 敌人。
     * 只对"普通敌怪"生效：玩家、宠物、Boss、事件雕像都免疫（否则会把 Boss 推走或让雕像飘起来）。
     * 已有击退会被叠加而不是覆盖，所以连续命中会持续把怪往外顶。
     */
    private void knockbackEnemy(int e, float srcX, float srcY, float speed) {
        if (speed <= 0f || !alive[e] || kind[e] != KIND_ENEMY) {
            return;
        }
        if (variant[e] == V_BOSS || variant[e] == V_STATUE || variant[e] == V_SERPENT) {
            return;     // Boss / 雕像 / 骨蛇：站桩不动，不吃击退
        }
        float dx = x[e] - srcX;
        float dy = y[e] - srcY;
        float d2 = dx * dx + dy * dy;
        if (d2 < 1e-6f) {
            // 完全重合时给一个随机方向，避免零向量导致无位移
            float a = rng.nextFloat() * (float) (Math.PI * 2);
            kx[e] += (float) Math.cos(a) * speed;
            ky[e] += (float) Math.sin(a) * speed;
            return;
        }
        float d = (float) Math.sqrt(d2);
        kx[e] += dx / d * speed;
        ky[e] += dy / d * speed;
    }

    /** 装配法术到指定槽。槽位越界或无此角色则忽略 */
    public void setSpell(int wizardId, int slot, int spellId) {
        Loadout lo = loadout(wizardId);
        if (lo != null && slot >= 0 && slot < Loadout.SLOTS) {
            lo.set(slot, spellId);
        }
    }

    /** 装到第一个空槽，满了返回 false */
    public boolean giveSpell(int wizardId, int spellId) {
        Loadout lo = loadout(wizardId);
        return lo != null && lo.add(spellId);
    }

    /** 击杀 Boss 奖励：让升级面板弹出一组仅主动技的三选一（技能卡） */
    public void grantBossCard(int wizardId) {
        Loadout lo = loadout(wizardId);
        if (lo == null) {
            return;
        }
        lo.pendingUps++;
        if (lo.pendingChoices == null) {
            lo.pendingChoices = Upgrades.rollSpell(lo, rng);
        }
    }

    /** 从职业池里随机挑一个尚未拥有的主动技；都满则返回 NONE */
    private int pickNewSpell(Loadout lo) {
        int[] pool = Spells.poolForClass(lo.classKind);
        List<Integer> avail = new ArrayList<>();
        for (int sid : pool) {
            if (!lo.contains(sid)) {
                avail.add(sid);
            }
        }
        if (avail.isEmpty()) {
            return Spells.NONE;
        }
        return avail.get(rng.nextInt(avail.size()));
    }

    public Loadout loadout(int id) {
        return (id >= 0 && id < MAX) ? loadout[id] : null;
    }

    public int reactionCount(int reaction) {
        return (reaction >= 0 && reaction < reactionCounts.length) ? reactionCounts[reaction] : 0;
    }

    public void setKillListener(KillListener l) {
        this.killListener = l;
    }

    /** 召唤师的下一次召唤倒计时（秒）。非召唤师恒为 0 */
    public float summonTimer(int wizardId) {
        return (wizardId >= 0 && wizardId < MAX) ? summonT[wizardId] : 0f;
    }

    /** 某个玩家当前存活的宠物数（HUD 显示用） */
    public int minionCount(int ownerId) {
        int n = 0;
        for (int k = 0; k < minions.size(); k++) {
            int m = minions.get(k);
            if (alive[m] && owner[m] == ownerId) {
                n++;
            }
        }
        return n;
    }

    public float time() {
        return time;
    }

    /** 本局选中的关卡；客户端只读它来画同一套障碍物与机关预警。 */
    public ArenaMap arenaMap() {
        return arenaMap;
    }

    /** HUD 用的当前推进节点；位于连接段时明确提示玩家仍在前进而非回到固定房间。 */
    public ArenaMap.ExpeditionNode currentExpeditionNode() {
        int hero = firstWizard();
        return hero < 0 ? null : arenaMap.nodeAt(x[hero], y[hero]);
    }

    public int trapCount() {
        return arenaMap.traps().length;
    }

    public ArenaMap.Trap trap(int index) {
        ArenaMap.Trap[] traps = arenaMap.traps();
        return (index >= 0 && index < traps.length) ? traps[index] : null;
    }

    public boolean trapTelegraphing(int index) {
        ArenaMap.Trap trap = trap(index);
        return trap != null && trap.telegraphing(time);
    }

    public boolean trapArming(int index) {
        ArenaMap.Trap trap = trap(index);
        return trap != null && trap.arming(time);
    }

    public boolean trapActive(int index) {
        ArenaMap.Trap trap = trap(index);
        return trap != null && trap.active(time);
    }

    // ---- 5 关 Boss 奶蛙（客户端渲染 / 音乐用） ----

    /** 调试用：关闭/开启普通刷怪（关闭后只保留 Boss 与事件） */
    public void setSpawningEnabled(boolean enabled) {
        this.spawningEnabled = enabled;
    }

    /** 冒烟 / 调试用：立即刷新奶蛙（忽略等级条件） */
    public void forceSpawnMilky() {
        if (!milkySpawned) {
            spawnMilky();
        }
    }

    /** 本局角色是否被奶蛙技能击败（阵亡画面据此显示专属图 + 「压力！」） */
    public boolean killedByMilky() {
        return killedByMilky;
    }

    /** 奶蛙实体 id；-1 表示不在场 */
    public int milkyId() {
        return milkyId;
    }

    /** 奶蛙是否在场且存活 */
    public boolean milkyAlive() {
        return milkyId >= 0 && alive[milkyId];
    }

    /** 奶蛙施法状态：0=移动/待机，1=蓄力踩地，2=捧腹大笑 */
    public int milkyCast() {
        return milkyCast;
    }

    /** 当前施法已进行时间 / 总时长（渲染动画进度用） */
    public float milkyCastT() {
        return milkyCastT;
    }

    /** 当前技能的实际施法时长（客户端渲染动画进度也用它） */
    public float milkyCastDur() {
        return (milkyCast == 2) ? milkyLaughCastDur : milkyStompCastDur;
    }

    /** 由客户端按施法时长归一化播放动画：这里不再受 GIF 总时长牵制 */
    /** true=奶蛙朝右（决定用哪套行走动画） */
    public boolean milkyFaceRight() {
        return milkyFaceRight;
    }

    /** 踩地动画是否用镜像版（玩家在左侧时为 true） */
    public boolean milkyMirror() {
        return milkyMirror;
    }

    public float milkyX() {
        return milkyId >= 0 ? x[milkyId] : 0f;
    }

    public float milkyY() {
        return milkyId >= 0 ? y[milkyId] : 0f;
    }

    /** 奶蛙是否已被击败倒地（触发剧情 CG 用；与 victory 无关，不再直接通关） */
    public boolean milkyFallen() {
        return milkyFallen;
    }

    /** 奶蛙倒下的位置（CG 镜头与消散特效锚点），未倒地时为 0 */
    public float milkyDownX() {
        return milkyDownX;
    }

    public float milkyDownY() {
        return milkyDownY;
    }

    // ---- 王宫最终决战（客户端渲染 / 音效 / 冒烟用） ----

    /** 是否已进入王宫决战场景（竞技场） */
    public boolean kingArena() {
        return kingArena;
    }

    /** 国王实体 id；-1 表示不在场 */
    public int kingId() {
        return kingId;
    }

    /** 三阶段第二管血分裂出的分身实体 id；-1 表示未分裂 */
    public int kingTwinId() {
        return kingTwinId;
    }

    /** 分身是否朝向右侧（渲染选动画用；本体朝向见 kingFaceRight） */
    public boolean kingTwinFaceRight() {
        return kingTwinFaceRight;
    }

    /** 分身当前是否处于传送前摇（渲染蓄力提示用） */
    public boolean kingTwinCasting() {
        return kingTwinTeleT > 0f;
    }

    /** 分身传送前摇剩余秒数（冒烟验证用；本体见 kingTeleT） */
    public float kingTwinTeleT() {
        return kingTwinTeleT;
    }

    /** 国王阶段：0=未开战，1/2/3=对应阶段 */
    public int kingPhase() {
        return kingPhase;
    }

    /** 国王是否朝向右侧（渲染选向左/向右动画） */
    public boolean kingFaceRight() {
        return kingFaceRight;
    }

    /** 国王当前是否处于技能前摇（渲染蓄力提示用；三阶段含传送前摇） */
    public boolean kingCasting() {
        return kingTelegraphT > 0f || kingTeleT > 0f;
    }

    /** 一阶段国王是否已被击破（客户端据此弹决裂对白；beginKingPhase2 后复位） */
    public boolean kingFallen() {
        return kingFallen;
    }

    /** 一阶段国王倒下的位置（对白演出：倒地剪影锚点） */
    public float kingDownX() {
        return kingDownX;
    }

    public float kingDownY() {
        return kingDownY;
    }

    /** 二阶段国王是否已被击破（客户端据此弹「王座本体」过渡剧情；beginKingPhase3 后复位） */
    public boolean kingFallen2() {
        return kingFallen2;
    }

    /** 三阶段传送前摇剩余秒数（>0：渲染落点预警圈 + 20% 减伤输出窗口） */
    public float kingTeleT() {
        return kingTeleT;
    }

    /** 深渊牵引剩余秒数（>0：渲染王座→玩家的牵引流束） */
    public float kingPullT() {
        return kingPullT;
    }

    public int enemyCount() {
        return enemiesAlive;
    }

    public int killCount() {
        return kills;
    }

    public int liveCount() {
        return liveCount;
    }

    public int highWater() {
        return high;
    }

    public int wizardCount() {
        return wizards.size();
    }

    public int wizard(int index) {
        return wizards.get(index);
    }

    /** 当前阶段索引 0..STAGE_COUNT-1，渲染层用来切背景与障碍主题 */
    public int stage() {
        return stage;
    }

    /**
     * 主控玩家的等级。WaveDirector 用它决定 Boss 何时登场。
     * 没有玩家（还没生成 / 全灭）时返回 0，这样不会误触发刷 Boss。
     */
    public int playerLevel() {
        int w = firstWizard();
        if (w < 0) {
            return 0;
        }
        Loadout lo = loadout[w];
        return (lo == null) ? 0 : lo.level;
    }

    /** 当前 Boss 实体 id，-1 表示没有 Boss 在场 */
    public int bossId() {
        return bossId;
    }

    /** 是否已击败奶蛙 Boss（胜利判定）。一旦置位不会复位 */
    public boolean victory() {
        return victory;
    }

    /** 开火模式开关。手动模式下玩家按住鼠标左键朝鼠标方向开火 */
    public void setAutoFire(boolean auto) {
        this.autoFire = auto;
    }

    /** 当前是否自动开火 */
    public boolean isAutoFire() {
        return autoFire;
    }

    /**
     * 战士当前是否正处于蓄力状态（供 HUD 画蓄力反馈）。
     * charge >= WARRIOR_CHARGE_MIN 起为 true，松开后立刻转 false。
     */
    public boolean warriorCharging() {
        return warriorCharging;
    }

    /** 当前蓄力进度 0..1（0 表示未蓄力）。供 HUD 画蓄力环/条 */
    public float warriorChargeProgress() {
        return warriorCharging ? warriorChargePower : 0f;
    }

    /**
     * 玩家当前可用的冲刺发数。弓箭手 0..ARCHER_DASH_MAX；其他职业恒为 0（HUD 不画）。
     * 仅做读访问器，调用方只用来画 UI，不要据此修改世界状态。
     */
    public int dashChargesOf(int wizardId) {
        if (wizardId < 0 || wizardId >= MAX || !alive[wizardId] || kind[wizardId] != KIND_WIZARD) {
            return 0;
        }
        return dashCharges[wizardId];
    }

    /**
     * 玩家当前冲刺单发充能进度 0..1。1 表示刚发出去（刚开始冷却）；0 表示已就绪。
     * 弓箭手：当前缺弹药时返回"下一发的充能进度"，满发时返回 1。
     * 战士：返回当前 dashCd 的归一化进度。
     * 非冲刺职业恒返回 1（HUD 不画时无意义）。
     */
    public float dashCdFraction(int wizardId) {
        if (wizardId < 0 || wizardId >= MAX || !alive[wizardId] || kind[wizardId] != KIND_WIZARD) {
            return 1f;
        }
        int ch = dashCharges[wizardId];
        if (ch <= 0) {
            return 0f;   // 弹药耗尽：HUD 显示"全暗"
        }
        Loadout lo = loadout[wizardId];
        if (lo == null || lo.classKind != HeroClass.ARCHER) {
            return 1f;
        }
        // 弓箭手：满发返回 1（就绪），否则是下一发的充能进度（0=刚发、1=就绪）
        if (ch >= Balance.ARCHER_DASH_MAX) {
            return 1f;
        }
        float cdTotal = Balance.ARCHER_DASH_CD;
        return cdTotal > 0f ? Math.max(0f, Math.min(1f, 1f - dashCd[wizardId] / cdTotal)) : 1f;
    }

    /** 当前 Boss 档位（BOSS_NAMES 下标），没有 Boss 时返回 -1 */
    public int bossTier() {
        return bossId >= 0 ? bossTier : -1;
    }

    /** 当前 Boss 名字，HUD 血条标题用。没有 Boss 返回空串 */
    public String bossName() {
        if (bossId < 0) {
            return "";
        }
        // 骨蛇（小 Boss）不在 Balance.BOSS_NAMES 里——它用 bossTier = -2 做标记，
        // 不单独取名的话血条会显示兜底的 "Boss"
        if (variant[bossId] == V_SERPENT) {
            return EnemyStats.SERPENT_NAME;
        }
        return (bossTier >= 0 && bossTier < Balance.BOSS_NAMES.length)
                ? Balance.BOSS_NAMES[bossTier] : "Boss";
    }

    /** 场上障碍物数量，冒烟测试验证场景生成用 */
    public int obstacleCount() {
        int n = 0;
        for (int i = 0; i < high; i++) {
            if (alive[i] && kind[i] == KIND_OBSTACLE) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 升级三选一（D3）
    // ------------------------------------------------------------------

    /** 玩家有多少次升级待处理。>0 时客户端应暂停并显示三选一 */
    public int pendingChoices(int wizardId) {
        Loadout lo = loadout(wizardId);
        return lo != null ? lo.pendingUps : 0;
    }

    /**
     * 取出或生成该玩家当前待选的三组选项。
     * 第一次调用会生成，之后在同一轮升级里会复用——直到应用完一组。
     */
    public Upgrades.Choice[] peekChoices(int wizardId) {
        Loadout lo = loadout(wizardId);
        if (lo == null) {
            return new Upgrades.Choice[0];
        }
        if (lo.pendingChoices == null) {
            lo.pendingChoices = Upgrades.roll(lo, rng);
        }
        return lo.pendingChoices;
    }

    /** 玩家确认选择：应用升级，pendingUps--，若还有升级则生成下一组 */
    public void applyChoice(int wizardId, int index) {
        Loadout lo = loadout(wizardId);
        if (lo == null || lo.pendingChoices == null) {
            return;
        }
        Upgrades.Choice c = lo.pendingChoices[index];
        if (c != null) {
            applyUpgrade(wizardId, lo, c);
        }
        lo.pendingUps--;
        lo.pendingChoices = (lo.pendingUps > 0) ? Upgrades.roll(lo, rng) : null;
    }

    /** 免费重抽当前三选一 */
    public void rerollChoices(int wizardId) {
        Loadout lo = loadout(wizardId);
        if (lo == null || lo.pendingChoices == null || lo.rerolls <= 0) {
            return;
        }
        lo.rerolls--;
        lo.pendingChoices = Upgrades.roll(lo, rng);
    }

    private void applyUpgrade(int wizardId, Loadout lo, Upgrades.Choice c) {
        switch (c.kind) {
            case Upgrades.KIND_SPELL -> lo.add(c.id);
            case Upgrades.KIND_PASSIVE -> lo.addPassive(c.id);
            case Upgrades.KIND_FILLER -> {
                if (c.id == Upgrades.FILLER_HEAL) {
                    hp[wizardId] = Math.min(maxHp[wizardId], hp[wizardId] + maxHp[wizardId] * 0.4f);
                } else if (c.id == Upgrades.FILLER_VITALITY) {
                    // FILLER_VITALITY: 加 10 HP 并回满 —— 通过加一个空"hp 加值"再回血实现
                    // 这里直接给个"0 成本"的临时加成：加血 + 立即回满
                    lo.stats.maxHpAdd += 10f;
                    maxHp[wizardId] += 10f;
                    hp[wizardId] = maxHp[wizardId];
                }
            }
        }
        // 重新同步 maxHp（避免 FILLER_VITALITY 与被动叠加遗漏）
        if (lo != null) {
            float newMax = HeroClass.baseHp(classOf(wizardId)) + lo.stats.maxHpAdd;
            if (Math.abs(maxHp[wizardId] - newMax) > 0.5f) {
                maxHp[wizardId] = newMax;
                if (hp[wizardId] > newMax) {
                    hp[wizardId] = newMax;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 终局战报
    // ------------------------------------------------------------------

    /**
     * 一局结束（胜利或阵亡）时的战报快照。
     *
     * 之所以要在结束那一刻冻结一份：结算画面还在持续渲染，而世界里的计数器
     * 可能被残留的宠物击杀、延迟结算继续改动，直接读实时值会让面板上的数字自己跳动。
     */
    public static final class Summary {
        public final boolean victory;
        /** true = 玩家从暂停菜单「退出结算」主动结束（标题与阵亡区分） */
        public final boolean abandoned;
        public final float time;
        public final int level;
        /** 小怪击杀数（已扣除 Boss） */
        public final int minionKills;
        public final int bossKills;
        /** 主动技能数（Loadout.SLOTS 上限） */
        public final int spells;
        /** 被动总层数 */
        public final int passives;
        /** 本局完成的小任务数（共 3） */
        public final int events;

        Summary(boolean victory, boolean abandoned, float time, int level, int minionKills,
                int bossKills, int spells, int passives, int events) {
            this.victory = victory;
            this.abandoned = abandoned;
            this.time = time;
            this.level = level;
            this.minionKills = minionKills;
            this.bossKills = bossKills;
            this.spells = spells;
            this.passives = passives;
            this.events = events;
        }
    }

    /**
     * 冻结一份当前战报。victory=true 表示通关，false 表示阵亡。
     * wid 必须显式传入：阵亡快照是在 kill() 里取的，那时 alive 已置 false，
     * firstWizard() 会返回 -1，拿不到 Loadout。
     */
    private Summary snapshot(boolean won, boolean aband, int wid) {
        Loadout lo = (wid >= 0) ? loadout[wid] : null;
        int lv = (lo != null) ? lo.level : 0;
        int sp = (lo != null) ? lo.activeCount() : 0;
        int pas = 0;
        if (lo != null) {
            for (int i = 0; i < lo.pstacks.size(); i++) {
                pas += lo.pstacks.get(i);
            }
        }
        return new Summary(won, aband, time, lv, kills - bossKills, bossKills, sp, pas,
                eventCompleted);
    }

    /** 主控玩家是否已阵亡。客户端据此冻结模拟并弹结算画面 */
    public boolean defeat() {
        return defeat;
    }

    /** 玩家是否从暂停菜单主动退出（「退出结算」）。与阵亡同屏展示战报，但标题不同 */
    public boolean abandoned() {
        return abandoned;
    }

    // ---- 战斗事件（小任务）只读访问器 ----
    /** 当前事件类型：Balance.EVENT_RIFT / EVENT_STATUE / EVENT_MUSHROOM，0=无 */
    public int eventType() {
        return eventType;
    }

    /** 当前事件进度（裂隙=已坚持秒；雕像=已摧毁数；蘑菇=已采集数） */
    public float eventProgress() {
        return eventProgress;
    }

    /** 当前事件目标值 */
    public float eventGoal() {
        return eventGoal;
    }

    /** 事件区域中心坐标（裂隙圈圆心 / 雕像与蘑菇散布中心） */
    public float eventX() {
        return eventX;
    }

    public float eventY() {
        return eventY;
    }

    /** 「任务完成 +经验」横幅剩余显示秒数（>0 显示） */
    public float eventBannerT() {
        return eventBannerT;
    }

    /** 本局已完成的小任务数 */
    public int eventCompletedCount() {
        return eventCompleted;
    }

    /** 当前事件名字（无事件时返回空串） */
    public String eventName() {
        if (eventType == Balance.EVENT_RIFT) {
            return Balance.EVENT_NAMES[0];
        }
        if (eventType == Balance.EVENT_STATUE) {
            return Balance.EVENT_NAMES[1];
        }
        if (eventType == Balance.EVENT_MUSHROOM) {
            return Balance.EVENT_NAMES[2];
        }
        return "";
    }

    /** 本局击败的 Boss 数量 */
    public int bossKills() {
        return bossKills;
    }

    /** 本局击败的小怪数量（总击杀扣除 Boss） */
    public int minionKills() {
        return kills - bossKills;
    }

    /**
     * 终局战报快照。对局尚未结束时返回 null——结算画面只在结束后才画，
     * 调用方（客户端）应当先判 defeat()/victory() 再取。
     */
    public Summary summary() {
        return summary;
    }
}
