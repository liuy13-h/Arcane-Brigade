package com.arcanebrigade.core;

import java.util.Random;

/**
 * 世界模拟。这是整个游戏的权威状态，不依赖任何 UI 框架。
 *
 * 存储采用 SoA（Structure of Arrays）+ 空闲链表复用，目的是：
 *   1. 上千实体时避免逐对象 GC；
 *   2. 快照序列化时可以直接按数组批量打包（D5 联机会用到）。
 *
 * 固定步长推进，dt 恒为 Balance.FIXED_STEP，保证可重放、可联机。
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

    /** 敌人变体。普通怪不做特殊行为，其余按类型分派 */
    public static final int V_NORMAL = 0;
    public static final int V_ELITE  = 1;   // 精英：大血厚甲，带护盾
    public static final int V_THIEF  = 2;   // 小偷：偷地上宝石，不攻击
    public static final int V_RANGED = 3;   // 远程：保持距离发射弹幕
    public static final int V_SPLIT  = 4;   // 分裂：死亡裂成数只
    public static final int V_BOSS   = 5;   // Boss：多阶段
    public static final int V_STATUE = 6;   // 战斗事件「摧毁雕像」：不移动不攻击的静态靶子

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
    public final float[] hp = new float[MAX];
    public final float[] maxHp = new float[MAX];
    public final float[] speed = new float[MAX];
    public final float[] dmg = new float[MAX];
    public final float[] life = new float[MAX];
    public final float[] cd = new float[MAX];
    /** 受击无敌剩余时间，&lt;=0 才能再次受伤 */
    public final float[] iframe = new float[MAX];

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

    private float time;
    private final Random rng;
    /** 本局锁定的单屏关卡；所有碰撞与陷阱由这一份关卡数据驱动。 */
    private final ArenaMap arenaMap;
    private final SpatialHash enemyHash = new SpatialHash(64f);
    /** 障碍物空间哈希。障碍是静态的，只在场景切换时整体重建，所以每帧只查询不重建 */
    private final SpatialHash obstacleHash = new SpatialHash(64f);
    private final WaveDirector director = new WaveDirector();

    // ---- 场景 / 阶段 ----
    private int stage;
    private float stageTimer;
    private boolean obstaclesGenerated;
    private int bossId = -1;
    /** 当前 Boss 是第几只（BOSS_LEVELS 的下标），HUD 显示名字用 */
    private int bossTier;
    /** 击败最后一只 Boss 后置位，客户端据此暂停并弹胜利画面 */
    private boolean victory;
    /** 主控玩家阵亡后置位，客户端据此冻结并弹结算画面 */
    private boolean defeat;
    /** 本局击败的 Boss 数量（kills 含 Boss，结算要分开显示） */
    private int bossKills;
    /** 终局战报快照：胜利或阵亡时冻结一份，避免结算画面上的数字继续跳动 */
    private Summary summary;
    /** 主动退出（暂停菜单「退出结算」）：与阵亡同屏展示战报，但标题不同 */
    private boolean abandoned;
    /** 开火模式：true=自动索敌开火，false=手动（朝鼠标方向，按住开火） */
    private boolean autoFire = true;
    private float bossWarningTimer;
    private float bossSummonTimer;

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
        if (id == bossId) {
            // 击败最后一只 Boss = 通关。前几只倒下只清标记，不打断对局。
            if (bossTier == Balance.BOSS_LEVELS.length - 1) {
                victory = true;
                summary = snapshot(true, false, firstWizard());
            }
            bossId = -1;   // Boss 倒下：清掉阶段技能标记，下一帧 updateBossPhase 也会兜底
        }
        int k = kind[id];
        if (k == KIND_ENEMY) {
            enemiesAlive--;
            kills++;
            if (variant[id] == V_BOSS) {
                bossKills++;
            }
            healWarriorsOnKill();
            if (killListener != null) {
                killListener.onKill(id, x[id], y[id], meta[id]);
            }
            int v = variant[id];
            if (v == V_THIEF) {
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
     * 按职业生成玩家。设置基础 HP / 移速、起手主动技能、起手被动（巫师带"重抽"），
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
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        float dist = Balance.SPAWN_RING_IN
                + rng.nextFloat() * (Balance.SPAWN_RING_OUT - Balance.SPAWN_RING_IN);
        float sx = x[w] + (float) Math.cos(ang) * dist;
        float sy = y[w] + (float) Math.sin(ang) * dist;

        int id = spawnEnemy(sx, sy);
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
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        float dist = Balance.SPAWN_RING_IN
                + rng.nextFloat() * (Balance.SPAWN_RING_OUT - Balance.SPAWN_RING_IN);
        float sx = clampX(x[w] + (float) Math.cos(ang) * dist);
        float sy = clampY(y[w] + (float) Math.sin(ang) * dist);
        // 变体数值（精英×6 / 小偷 / 远程）与时间成长都在 spawnEnemy 里定好
        spawnEnemy(sx, sy, rng.nextInt(3), variant);
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
        float ang = rng.nextFloat() * (float) (Math.PI * 2);
        float sx = clampX(tx + (float) Math.cos(ang) * Balance.BOSS_SPAWN_DIST);
        float sy = clampY(ty + (float) Math.sin(ang) * Balance.BOSS_SPAWN_DIST);
        int id = spawnEnemy(sx, sy, rng.nextInt(3), V_BOSS);
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

        updateStage(dt);          // 阶段推进 + 首次障碍生成
        rebuildEnemyHash();
        updateOrder(dt, in);      // 鼠标指挥指令的有效期
        updateStatus(dt);
        updateWizards(dt, in);
        updateSummoners(dt);      // 召唤师：到点召唤一批宠物
        updateEvents(dt);         // 战斗事件：到点触发 + 进度推进 + 完成发经验
        specialPassiveTick(dt);
        director.update(this, dt);
        updateEnemies(dt);
        updateMinions(dt);        // 宠物 AI：护主 / 听指挥 / 拴绳
        if (bossId >= 0) {
            updateBossPhase(dt);  // Boss 阶段技能（预警圈 / 召唤）
        }
        castSpells(dt, in);
        updateProjectiles(dt);
        updatePickups(dt);
        updateZones(dt);
        updateArenaTraps();
        updateFx(dt);
        cullDistant();
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

    /** 机关的四拍循环：预警 → 伤害窗口 → 恢复。只对玩家/宠物结算，避免环境自行清场。 */
    private void updateArenaTraps() {
        for (ArenaMap.Trap trap : arenaMap.traps()) {
            if (!trap.active(time)) {
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
            for (int m = 0; m < Balance.MUSHROOM_COUNT; m++) {
                float a = rng.nextFloat() * (float) (Math.PI * 2);
                float d = 60f + rng.nextFloat() * 360f;
                int id = spawnMushroom(clampX(eventX + (float) Math.cos(a) * d),
                        clampY(eventY + (float) Math.sin(a) * d));
                if (id >= 0) {
                    eventIds.add(id);
                }
            }
        }
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

            float baseSpeed = HeroClass.baseSpeed(ck) * arenaMap.movementMultiplierAt(x[id], y[id]);
            x[id] += in.dx * baseSpeed * moveMul * dt;
            y[id] += in.dy * baseSpeed * moveMul * dt;
            resolveObstacles(id);
            clampToWorld(id);   // 玩家也被棕色城墙（边界）挡在内侧
            if (iframe[id] > 0f) {
                iframe[id] -= dt;
            }
            // 回血：基础 + 被动 + 绝境爆发
            float regen = Balance.WIZARD_REGEN
                    + (st != null ? st.regenAdd : 0f)
                    + (st != null && st.lastStandActive ? Balance.LAST_STAND_DAMAGE * 4f : 0f);
            if (hp[id] < maxHp[id]) {
                hp[id] = Math.min(maxHp[id], hp[id] + regen * dt);
            }
        }
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
            // 其余（普通 / 精英 / 分裂 / Boss）走下方通用追击 + 接触伤害

            // 追击目标含宠物：宠物挡在怪和玩家之间时，怪会先啃宠物——这就是"护主"的实质
            int target = nearestPlayerUnit(x[i], y[i]);
            if (target < 0) {
                continue;
            }
            // 冰霜减速
            float slow = (elem[i] == Element.FROST)
                    ? Math.min(elemP[i], Balance.ELEM_FROST_MAX_SLOW) : 0f;
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
            clampToWorld(i);       // 敌人同样被棕色城墙挡在内侧，不会被挤飞出去

            // 接触伤害
            float ndx = x[target] - x[i];
            float ndy = y[target] - y[i];
            float nlen = (float) Math.sqrt(ndx * ndx + ndy * ndy);
            if (nlen < r[i] + r[target]) {
                cd[i] -= dt;
                if (cd[i] <= 0f) {
                    if (iframe[target] <= 0f) {
                        damage(target, dmg[i]);
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

    /** Boss 阶段技能：预警圈 + 召唤。Boss 的追击与接触伤害由 updateEnemies 通用逻辑驱动 */
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
    }

    // ------------------------------------------------------------------
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
    private int nearestPlayerUnit(float sx, float sy) {
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
    private void resolveObstacles(int id) {
        obstacleHash.query(x[id], y[id], r[id] + Balance.OBSTACLE_MAX_R, scratch2);
        for (int n = 0; n < scratch2.size(); n++) {
            int o = scratch2.get(n);
            if (!alive[o] || kind[o] != KIND_OBSTACLE) {
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

    /** 把实体钳制在可玩区内（城墙内侧边缘，玩家 / 敌人共用） */
    private void clampToWorld(int id) {
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

    /** 玩家受击无敌帧：按职业取基础值 + 灵巧被动加成 */
    private float heroIframe(int wid) {
        Loadout lo = loadout[wid];
        int ck = (lo != null) ? lo.classKind : HeroClass.WIZARD;
        float add = (lo != null && lo.stats != null) ? lo.stats.iframeAdd : 0f;
        return HeroClass.baseIframe(ck) + add;
    }

    /** 可滚动地图的生成点钳制，保证单位与事件始终落在作者设计的场地内。 */
    private float clampX(float v) {
        return Math.max(-arenaMap.halfWidth(), Math.min(arenaMap.halfWidth(), v));
    }

    private float clampY(float v) {
        return Math.max(-arenaMap.halfHeight(), Math.min(arenaMap.halfHeight(), v));
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
        for (int n = 0; n < wizards.size(); n++) {
            int id = wizards.get(n);
            if (!alive[id]) {
                continue;
            }
            Loadout lo = loadout[id];
            if (lo == null) {
                continue;
            }
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
                boolean fired;
                if (manual) {
                    // 手动：没按住开火键就不放，也不进冷却
                    if ((in.buttons & InputCommand.BUTTON_FIRE) == 0) {
                        continue;
                    }
                    float facing = (float) Math.atan2(in.aimY - y[id], in.aimX - x[id]);
                    fired = castOne(def, raw, id, s, true, facing);
                } else {
                    fired = castOne(def, raw, id, s, false, 0f);
                }
                if (fired) {
                    float cdTime = def.cooldown / Math.max(0.1f, lo.stats.atkSpeed);
                    lo.cd[s] = cdTime;
                }
            }
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
            case MELEE_ARC -> arcHit(def, caster, facing, power, lo.stats);
        }
        return true;
    }

    /** 近战扇形：命中范围内所有敌人，不需要单体弹道判定 */
    private void arcHit(SpellDef def, int caster, float facing, float power, Stats st) {
        int fx = spawnFx(FX_ARC, x[caster], y[caster], 0f, 0f, def.arcRadius, 0.15f, def.element);
        if (fx >= 0) {
            dmg[fx] = facing;                       // FX 不用 dmg，借来存扇形朝向
            vx[fx] = def.arcAngle * 0.5f;           // 借 vx 存半张角（弧度）
        }
        float cosF = (float) Math.cos(facing);
        float sinF = (float) Math.sin(facing);
        float cosHalf = (float) Math.cos(def.arcAngle * 0.5f);

        // 元素参数：DoT 时长和强度都要走被动乘算
        int ele = def.element;
        float elePot = def.elemPotency;
        float eleDur = def.elemDuration * st.dotDurMul;
        if (ele == Element.FIRE) {
            elePot *= st.fireMul;
        } else if (ele == Element.FROST) {
            eleDur *= st.frostDurMul;
        }

        enemyHash.query(x[caster], y[caster], def.arcRadius + Balance.MAX_TARGET_RADIUS, scratch);
        for (int n = 0; n < scratch.size(); n++) {
            int e = scratch.get(n);
            if (!alive[e] || kind[e] != KIND_ENEMY) {
                continue;
            }
            float dx = x[e] - x[caster];
            float dy = y[e] - y[caster];
            float d2 = dx * dx + dy * dy;
            float reach = def.arcRadius + r[e];
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
            }
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            life[i] -= dt;
            if (life[i] <= 0f) {
                despawn(i);
                continue;
            }
            // 障碍拦截：弹幕（无论敌我）碰到障碍物直接消失
            obstacleHash.query(x[i], y[i], r[i] + Balance.OBSTACLE_MAX_R, scratch2);
            boolean blocked = false;
            for (int n = 0; n < scratch2.size(); n++) {
                int o = scratch2.get(n);
                if (!alive[o] || kind[o] != KIND_OBSTACLE) {
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
                    kill(i);
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
            stunT[e] = Math.max(stunT[e], def.freeze);
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
            float radius = Balance.PICKUP_RADIUS * (lo != null ? lo.stats.pickupMul : 1f);
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
            life[i] -= dt;
            if (life[i] <= 0f) {
                if (sub == ZONE_WARNING) {
                    // 预警圈到期：对玩家与敌人同时爆炸并击退（Boss 阶段技能）
                    explode(x[i], y[i], r[i], Balance.WARNING_DAMAGE, Element.NONE,
                            Balance.WARNING_KNOCKBACK);
                    damagePlayersInRadius(x[i], y[i], r[i], Balance.WARNING_DAMAGE);
                }
                despawn(i);
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
                                stunT[e] = Math.max(stunT[e], Balance.SPIKE_STUN);
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
                            kx[e] += dx / d * Balance.WARCRY_KNOCKBACK;
                            ky[e] += dy / d * Balance.WARCRY_KNOCKBACK;
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
                    stunT[target] = Math.max(stunT[target], Balance.REACTION_STEAM_STUN);
                }
            }
            case Element.R_SUPERCONDUCT -> {
                float ratio = Balance.REACTION_SUPERCONDUCT_RATIO
                        * statsMulForReactionDmg(target);
                damage(target, incoming * ratio);
                if (alive[target]) {
                    stunT[target] = Math.max(stunT[target], Balance.REACTION_SUPERCONDUCT_STUN);
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
     *  当前用最近玩家简化处理，D6 联机时改成按 owner 找 */
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

    /** 范围伤害 + 可选击退。用 scratch2，调用点都在 scratch 的遍历里 */
    private void explode(float ex, float ey, float radius, float damage, int element, float knockback) {        spawnFx(FX_BLAST, ex, ey, 0f, 0f, radius, 0.25f, element);
        enemyHash.query(ex, ey, radius + Balance.MAX_TARGET_RADIUS, scratch2);
        for (int n = 0; n < scratch2.size(); n++) {
            int e = scratch2.get(n);
            if (!alive[e] || kind[e] != KIND_ENEMY) {
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
            if (knockback > 0f && alive[e]) {
                float d = (float) Math.sqrt(d2);
                if (d > 1e-3f) {
                    kx[e] += dx / d * knockback;
                    ky[e] += dy / d * knockback;
                } else {
                    kx[e] += knockback;
                }
            }
        }
    }

    /** 对范围内所有玩家造成伤害（预警圈爆炸用，友军伤害 D6 再做） */
    private void damagePlayersInRadius(float ex, float ey, float radius, float dmg) {
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
            if (i == bossId) {
                continue;
            }
            // 雕像事件靶子不回收：玩家跑远了任务就永远完不成
            if (variant[i] == V_STATUE) {
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

    /** 第一个活着玩家的 id。WaveDirector 也要用，做成包级可见 */
    int firstWizard() {
        for (int n = 0; n < wizards.size(); n++) {
            int w = wizards.get(n);
            if (alive[w]) {
                return w;
            }
        }
        return -1;
    }

    /** 该玩家实体的职业（找不到返回巫师） */
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
    public void damage(int id, float amount) {
        if (id < 0 || !alive[id] || amount <= 0f) {
            return;
        }
        // 附着雷电的目标更脆
        float amt = (elem[id] == Element.SHOCK)
                ? amount * (1f + Balance.ELEM_SHOCK_DMG_AMP) : amount;

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

    public boolean trapActive(int index) {
        ArenaMap.Trap trap = trap(index);
        return trap != null && trap.active(time);
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

    /** 是否已击败最终 Boss（胜利判定）。一旦置位不会复位 */
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

    /** 当前 Boss 档位（BOSS_NAMES 下标），没有 Boss 时返回 -1 */
    public int bossTier() {
        return bossId >= 0 ? bossTier : -1;
    }

    /** 当前 Boss 名字，HUD 血条标题用。没有 Boss 返回空串 */
    public String bossName() {
        if (bossId < 0) {
            return "";
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
