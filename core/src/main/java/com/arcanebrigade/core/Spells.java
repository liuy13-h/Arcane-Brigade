package com.arcanebrigade.core;

/**
 * 法术注册表。所有主动技能都写在这里。
 *
 * D2 范围：巫师 4 个（魔弹 / 火球 / 冰锥 / 连锁闪电），
 * 外加战士挥砍与弓箭手箭矢各 1 个——后两个不是为了做职业，
 * 而是为了**现在就把 MELEE_ARC 这条攻击形态分支验证掉**，
 * 否则 D4 加战士时会发现抽象不对，返工成本高。
 */
public final class Spells {

    private Spells() {}

    public static final int NONE            = 0;
    public static final int MAGIC_MISSILE   = 1;
    public static final int FIREBALL        = 2;
    public static final int ICE_SHARD       = 3;
    public static final int CHAIN_LIGHTNING = 4;
    public static final int WARRIOR_SLASH   = 5;
    public static final int ARCHER_ARROW    = 6;

    // ---- 战士技能池（D4 接入职业）----
    public static final int WHIRLWIND       = 7;   // 回旋斩
    public static final int GROUND_SLAM     = 8;   // 裂地
    public static final int SHIELD_BASH     = 9;   // 盾击

    // ---- 弓箭手技能池（D4 接入职业）----
    public static final int ARCHER_MULTISHOT = 10; // 多重箭

    // ---- 进化形态（主动 + 催化剂被动 → 质变）----
    public static final int MISSILE_STORM  = 11;  // 魔弹   + 穿透弹   → 齐射 5 发且必定穿透
    public static final int FLAME_NOVA     = 12;  // 火球   + 延烧     → 爆炸后留下燃烧区域
    public static final int ABSOLUTE_ZERO  = 13;  // 冰锥   + 冰霜亲和 → 命中冻结 1 秒
    public static final int THUNDERSTORM   = 14;  // 连锁闪电 + 雷电亲和 → 弹射不衰减，范围 +30%

    public static final int ARCHER_HOMING   = 15;  // 追踪箭
    public static final int ARCHER_RAIN     = 16;  // 箭雨

    // ---- 召唤师技能池（本体输出平庸，强度在宠物身上）----
    public static final int SUMMONER_BOLT    = 17;  // 秘能弹：起手，远程单体
    public static final int SUMMONER_ORB     = 18;  // 秘能球：慢速大范围
    public static final int SUMMONER_SPIRITS = 19;  // 灵体箭幕：穿透扇形
    public static final int SUMMONER_PULSE   = 20;  // 秘法脉冲：近身爆发 + 击退

    // ---- 进化形态（战士 / 弓箭手）----
    public static final int WARRIOR_SLASH_EVO = 21; // 巨力斩（挥砍 + 力量训练）
    public static final int WHIRLWIND_EVO     = 22; // 龙卷斩（回旋斩 + 疾速）
    public static final int ARCHER_ARROW_EVO  = 23; // 穿心箭（箭矢 + 致命一击）
    public static final int ARCHER_MULTI_EVO  = 24; // 箭幕（多重箭 + 穿透弹）

    private static final SpellDef[] TABLE = new SpellDef[32];

    /** 基础形态 → 进化形态 */
    private static final int[] EVOLVED_OF = new int[32];
    /** 基础形态 → 催化剂被动 id */
    private static final int[] CATALYST_OF = new int[32];

    static {
        // 巫师：四种形态各一个——单体 / 范围 / 穿透 / 弹射
        TABLE[MAGIC_MISSILE] = SpellDef.builder(MAGIC_MISSILE, "魔弹", SpellDef.Form.PROJECTILE)
                .cooldown(0.30f).damage(14f).range(620f)
                .speed(470f).boltRadius(6f)
                .element(Element.ARCANE)
                .build();

        TABLE[FIREBALL] = SpellDef.builder(FIREBALL, "火球", SpellDef.Form.PROJECTILE)
                .cooldown(1.10f).damage(22f).range(620f)
                .speed(380f).boltRadius(9f)
                .aoeRadius(60f)
                .element(Element.FIRE).elemPotency(6f).elemDuration(3f)
                .build();

        TABLE[ICE_SHARD] = SpellDef.builder(ICE_SHARD, "冰锥", SpellDef.Form.PROJECTILE)
                .cooldown(0.80f).damage(16f).range(700f)
                .speed(620f).boltRadius(7f)
                .pierce(2)
                .element(Element.FROST).elemPotency(0.35f).elemDuration(2f)
                .build();

        TABLE[CHAIN_LIGHTNING] = SpellDef.builder(CHAIN_LIGHTNING, "连锁闪电", SpellDef.Form.PROJECTILE)
                .cooldown(0.90f).damage(12f).range(560f)
                .speed(900f).boltRadius(7f)
                .chain(3).chainRange(220f).chainFalloff(0.15f)
                .element(Element.SHOCK).elemPotency(0.20f).elemDuration(3f)
                .build();

        // 战士：近战扇形，命中范围内所有敌人（D4 正式接入职业，这里先验证形态）
        TABLE[WARRIOR_SLASH] = SpellDef.builder(WARRIOR_SLASH, "挥砍", SpellDef.Form.MELEE_ARC)
                .cooldown(0.55f).damage(26f)
                .arcRadius(90f).arcAngle((float) Math.toRadians(120))
                .build();

        // 弓箭手：高速穿透箭（D4）
        TABLE[ARCHER_ARROW] = SpellDef.builder(ARCHER_ARROW, "箭矢", SpellDef.Form.PROJECTILE)
                .cooldown(0.45f).damage(18f).range(760f)
                .speed(700f).boltRadius(5f)
                .pierce(1)
                .build();

        // ---- 战士技能池（近战弧形，往怪堆里扎）----
        TABLE[WHIRLWIND] = SpellDef.builder(WHIRLWIND, "回旋斩", SpellDef.Form.MELEE_ARC)
                .cooldown(0.85f).damage(20f)
                .arcRadius(105f).arcAngle((float) Math.toRadians(360))
                .build();

        TABLE[GROUND_SLAM] = SpellDef.builder(GROUND_SLAM, "裂地", SpellDef.Form.MELEE_ARC)
                .cooldown(1.30f).damage(30f).knockback(150f)
                .arcRadius(135f).arcAngle((float) Math.toRadians(360))
                .build();

        TABLE[SHIELD_BASH] = SpellDef.builder(SHIELD_BASH, "盾击", SpellDef.Form.MELEE_ARC)
                .cooldown(0.90f).damage(34f).knockback(230f)
                .arcRadius(82f).arcAngle((float) Math.toRadians(95))
                .build();

        // ---- 弓箭手技能池（远程直线，天生穿透）----
        TABLE[ARCHER_MULTISHOT] = SpellDef.builder(ARCHER_MULTISHOT, "多重箭", SpellDef.Form.PROJECTILE)
                .cooldown(0.70f).damage(16f).range(720f)
                .speed(680f).boltRadius(5f)
                .count(4).spread((float) Math.toRadians(34)).pierce(1)
                .build();

        TABLE[ARCHER_HOMING] = SpellDef.builder(ARCHER_HOMING, "追踪箭", SpellDef.Form.PROJECTILE)
                .cooldown(0.80f).damage(24f).range(820f)
                .speed(760f).boltRadius(6f)
                .pierce(2).homing()
                .build();

        TABLE[ARCHER_RAIN] = SpellDef.builder(ARCHER_RAIN, "箭雨", SpellDef.Form.PROJECTILE)
                .cooldown(1.40f).damage(12f).range(560f)
                .speed(620f).boltRadius(5f)
                .count(10).spread((float) Math.toRadians(80)).pierce(1)
                .build();

        // ---- 召唤师技能池（远程弹幕为主，靠宠物扛住近身）----
        TABLE[SUMMONER_BOLT] = SpellDef.builder(SUMMONER_BOLT, "秘能弹", SpellDef.Form.PROJECTILE)
                .cooldown(0.42f).damage(17f).range(640f)
                .speed(500f).boltRadius(7f)
                .element(Element.ARCANE)
                .build();

        TABLE[SUMMONER_ORB] = SpellDef.builder(SUMMONER_ORB, "秘能球", SpellDef.Form.PROJECTILE)
                .cooldown(1.20f).damage(20f).range(600f)
                .speed(300f).boltRadius(11f)
                .aoeRadius(58f)
                .element(Element.ARCANE)
                .build();

        TABLE[SUMMONER_SPIRITS] = SpellDef.builder(SUMMONER_SPIRITS, "灵体箭幕", SpellDef.Form.PROJECTILE)
                .cooldown(0.95f).damage(13f).range(680f)
                .speed(560f).boltRadius(6f)
                .count(3).spread((float) Math.toRadians(30)).pierce(2)
                .element(Element.ARCANE)
                .build();

        TABLE[SUMMONER_PULSE] = SpellDef.builder(SUMMONER_PULSE, "秘法脉冲", SpellDef.Form.MELEE_ARC)
                .cooldown(1.10f).damage(24f).knockback(180f)
                .arcRadius(120f).arcAngle((float) Math.toRadians(360))
                .element(Element.ARCANE)
                .build();

        // ---- 进化形态 ----
        // 魔弹风暴：从"单体点射"变成"扇形弹幕"，且必定穿透——质变要看得见
        TABLE[MISSILE_STORM] = SpellDef.builder(MISSILE_STORM, "魔弹风暴", SpellDef.Form.PROJECTILE)
                .cooldown(0.30f).damage(14f).range(620f)
                .speed(470f).boltRadius(6f)
                .count(5).spread((float) Math.toRadians(36))
                .pierce(1)
                .element(Element.ARCANE)
                .build();

        // 烈焰新星：爆炸后留下持续燃烧区域，把一次性伤害变成持续控场
        TABLE[FLAME_NOVA] = SpellDef.builder(FLAME_NOVA, "烈焰新星", SpellDef.Form.PROJECTILE)
                .cooldown(1.10f).damage(22f).range(620f)
                .speed(380f).boltRadius(10f)
                .aoeRadius(72f)
                .zone(90f, 4f, 10f, Element.FIRE)
                .element(Element.FIRE).elemPotency(6f).elemDuration(3f)
                .build();

        // 绝对零度：命中冻结，把冰霜从"减速"升级成"硬控"
        TABLE[ABSOLUTE_ZERO] = SpellDef.builder(ABSOLUTE_ZERO, "绝对零度", SpellDef.Form.PROJECTILE)
                .cooldown(0.80f).damage(16f).range(700f)
                .speed(620f).boltRadius(7f)
                .pierce(2).freeze(1f)
                .element(Element.FROST).elemPotency(0.35f).elemDuration(2f)
                .build();

        // 雷霆风暴：弹射不再衰减 + 范围 +30%，从"打三个"变成"清一片"
        TABLE[THUNDERSTORM] = SpellDef.builder(THUNDERSTORM, "雷霆风暴", SpellDef.Form.PROJECTILE)
                .cooldown(0.90f).damage(12f).range(560f)
                .speed(900f).boltRadius(7f)
                .chain(3).chainRange(286f).chainFalloff(0f)
                .element(Element.SHOCK).elemPotency(0.20f).elemDuration(3f)
                .build();

        // ---- 进化形态（战士 / 弓箭手）----
        // 巨力斩：挥砍伤害暴涨，配合近战站桩
        TABLE[WARRIOR_SLASH_EVO] = SpellDef.builder(WARRIOR_SLASH_EVO, "巨力斩", SpellDef.Form.MELEE_ARC)
                .cooldown(0.55f).damage(42f)
                .arcRadius(90f).arcAngle((float) Math.toRadians(120))
                .build();

        // 龙卷斩：回旋斩冷却大幅压缩，转得更快
        TABLE[WHIRLWIND_EVO] = SpellDef.builder(WHIRLWIND_EVO, "龙卷斩", SpellDef.Form.MELEE_ARC)
                .cooldown(0.50f).damage(20f)
                .arcRadius(112f).arcAngle((float) Math.toRadians(360))
                .build();

        // 穿心箭：箭矢伤害暴涨且穿透更深
        TABLE[ARCHER_ARROW_EVO] = SpellDef.builder(ARCHER_ARROW_EVO, "穿心箭", SpellDef.Form.PROJECTILE)
                .cooldown(0.45f).damage(30f).range(760f)
                .speed(700f).boltRadius(5f)
                .pierce(2)
                .build();

        // 箭幕：多重箭齐射数量翻倍，铺满扇形
        TABLE[ARCHER_MULTI_EVO] = SpellDef.builder(ARCHER_MULTI_EVO, "箭幕", SpellDef.Form.PROJECTILE)
                .cooldown(0.70f).damage(16f).range(720f)
                .speed(680f).boltRadius(5f)
                .count(7).spread((float) Math.toRadians(46)).pierce(1)
                .build();

        evolution(MAGIC_MISSILE, MISSILE_STORM, Passives.PIERCING_SHOT);
        evolution(FIREBALL, FLAME_NOVA, Passives.LINGERING_BURN);
        evolution(ICE_SHARD, ABSOLUTE_ZERO, Passives.FROST_AFFINITY);
        evolution(CHAIN_LIGHTNING, THUNDERSTORM, Passives.SHOCK_AFFINITY);
        // 战士 / 弓箭手进化：催化剂复用现有被动，凑齐即质变
        evolution(WARRIOR_SLASH, WARRIOR_SLASH_EVO, Passives.POWER_TRAINING);
        evolution(WHIRLWIND, WHIRLWIND_EVO, Passives.ALACRITY);
        evolution(ARCHER_ARROW, ARCHER_ARROW_EVO, Passives.DEADLY_STRIKE);
        evolution(ARCHER_MULTISHOT, ARCHER_MULTI_EVO, Passives.PIERCING_SHOT);
    }

    private static void evolution(int base, int evolved, int catalyst) {
        EVOLVED_OF[base] = evolved;
        CATALYST_OF[base] = catalyst;
    }

    public static SpellDef get(int id) {
        if (id <= NONE || id >= TABLE.length) {
            return null;
        }
        return TABLE[id];
    }

    /** 注册表大小，UI 遍历用 */
    public static int capacity() {
        return TABLE.length;
    }

    /** 巫师可用池，D3 三选一从这里抽 */
    public static int[] wizardPool() {
        return new int[] { MAGIC_MISSILE, FIREBALL, ICE_SHARD, CHAIN_LIGHTNING };
    }

    /** 战士可用池 */
    public static int[] warriorPool() {
        return new int[] { WARRIOR_SLASH, WHIRLWIND, GROUND_SLAM, SHIELD_BASH };
    }

    /** 弓箭手可用池 */
    public static int[] archerPool() {
        return new int[] { ARCHER_ARROW, ARCHER_MULTISHOT, ARCHER_HOMING, ARCHER_RAIN };
    }

    /** 召唤师可用池 */
    public static int[] summonerPool() {
        return new int[] { SUMMONER_BOLT, SUMMONER_ORB, SUMMONER_SPIRITS, SUMMONER_PULSE };
    }

    /** 按职业取技能池，三选一抽卡用 */
    public static int[] poolForClass(int classKind) {
        return switch (classKind) {
            case HeroClass.WARRIOR  -> warriorPool();
            case HeroClass.ARCHER   -> archerPool();
            case HeroClass.SUMMONER -> summonerPool();
            default                 -> wizardPool();
        };
    }

    /** 三选一时的稀有度。基础法术多为普通，弹射类/强力技能给稀有，箭雨史诗 */
    public static int rarityOf(int id) {
        return switch (id) {
            case CHAIN_LIGHTNING, ARCHER_MULTISHOT, ARCHER_HOMING, WHIRLWIND, GROUND_SLAM, SHIELD_BASH ->
                    PassiveDef.RARE;
            case ARCHER_RAIN -> PassiveDef.EPIC;
            case FIREBALL -> PassiveDef.RARE;
            case SUMMONER_ORB, SUMMONER_SPIRITS, SUMMONER_PULSE -> PassiveDef.RARE;
            default -> PassiveDef.COMMON;
        };
    }

    /** 该形态是否是某个基础的进化版 */
    public static boolean isEvolved(int spellId) {
        for (int i = 0; i < EVOLVED_OF.length; i++) {
            if (EVOLVED_OF[i] == spellId) {
                return true;
            }
        }
        return false;
    }

    /** 进化后的形态；没有进化路线就返回自己 */
    public static int evolvedOf(int baseId) {
        if (baseId <= NONE || baseId >= EVOLVED_OF.length) {
            return baseId;
        }
        int e = EVOLVED_OF[baseId];
        return e == 0 ? baseId : e;
    }

    /** 该主动的催化剂被动 id，0 = 没有进化路线 */
    public static int catalystOf(int baseId) {
        if (baseId <= NONE || baseId >= CATALYST_OF.length) {
            return 0;
        }
        return CATALYST_OF[baseId];
    }

    /** 该催化剂对应哪个主动，0 = 不是任何技能的催化剂 */
    public static int catalyzedBy(int passiveId) {
        if (passiveId <= 0) {
            return 0;
        }
        for (int i = 1; i < CATALYST_OF.length; i++) {
            if (CATALYST_OF[i] == passiveId) {
                return i;
            }
        }
        return 0;
    }

    /** 按进化掩码把基础形态解析成实际形态 */
    public static int resolve(int spellId, int evolvedMask) {
        if ((evolvedMask & (1 << spellId)) != 0) {
            return evolvedOf(spellId);
        }
        return spellId;
    }
}
