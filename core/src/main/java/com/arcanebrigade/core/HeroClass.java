package com.arcanebrigade.core;

/**
 * 职业注册表。四职业（巫师 / 战士 / 弓箭手 / 召唤师）的基础属性、起手武器、特性与专属技能池。
 *
 * 设计文档第 2 节：各职业共用同一套技能系统（独立冷却 + 强化路径 + 元素注入），
 * 差别在攻击形态、属性、专属技能池。这里把所有"职业相关"的常量集中，
 * World / Stats / Upgrades / 客户端都只查这张表，不在逻辑里散落魔法数字。
 *
 * 攻击形态差异是根本：巫师远程弹幕、战士近战扇形、弓箭手远程穿透箭——
 * 它直接决定你怎么站位，所以是职业的第一区分度。
 * 召唤师是第四个形态：本体输出平庸，靠周期性召唤宠物替他扛与打。
 */
public final class HeroClass {

    private HeroClass() {}

    public static final int WIZARD   = 1;
    public static final int WARRIOR  = 2;
    public static final int ARCHER   = 3;
    public static final int SUMMONER = 4;

    /** 职业数量（数组尺寸用） */
    public static final int COUNT = 5;

    private static final String[] NAMES = new String[COUNT];
    private static final float[] BASE_HP = new float[COUNT];
    private static final float[] BASE_SPEED = new float[COUNT];

    static {
        NAMES[WIZARD]  = "巫师";
        BASE_HP[WIZARD]  = Balance.WIZARD_HP;
        BASE_SPEED[WIZARD]  = Balance.WIZARD_SPEED;

        NAMES[WARRIOR] = "战士";
        BASE_HP[WARRIOR] = Balance.WARRIOR_HP;
        BASE_SPEED[WARRIOR] = Balance.WARRIOR_SPEED;

        NAMES[ARCHER]  = "弓箭手";
        BASE_HP[ARCHER]  = Balance.ARCHER_HP;
        BASE_SPEED[ARCHER]  = Balance.ARCHER_SPEED;

        NAMES[SUMMONER] = "召唤师";
        BASE_HP[SUMMONER] = Balance.SUMMONER_HP;
        BASE_SPEED[SUMMONER] = Balance.SUMMONER_SPEED;
    }

    private static boolean valid(int classKind) {
        return classKind >= 1 && classKind < COUNT;
    }

    public static String name(int classKind) {
        return valid(classKind) ? NAMES[classKind] : "?";
    }

    public static float baseHp(int classKind) {
        return valid(classKind) ? BASE_HP[classKind] : Balance.WIZARD_HP;
    }

    public static float baseSpeed(int classKind) {
        return valid(classKind) ? BASE_SPEED[classKind] : Balance.WIZARD_SPEED;
    }

    /** 各职业的基础受击无敌帧（战士被明显削弱，见 Balance） */
    public static float baseIframe(int classKind) {
        return switch (classKind) {
            case WARRIOR  -> Balance.WARRIOR_IFRAME;
            case ARCHER   -> Balance.ARCHER_IFRAME;
            case SUMMONER -> Balance.SUMMONER_IFRAME;
            default       -> Balance.WIZARD_IFRAME;
        };
    }

    /** 职业特性文本，选职业界面用 */
    public static String trait(int classKind) {
        return switch (classKind) {
            case WARRIOR  -> "受伤减免 15% · 每次击杀回 2 HP";
            case ARCHER   -> "暴击率 +10% · 移速最快";
            case SUMMONER -> String.format("每 %.0f 秒召唤 %d 只宠物 · 宠物护主",
                    Balance.SUMMON_INTERVAL, Balance.SUMMON_COUNT);
            default       -> "法术伤害 +10% · 每 30 秒免费重抽";
        };
    }

    /** 该职业的起手主动技能（占第 0 槽） */
    public static int startSpell(int classKind) {
        return switch (classKind) {
            case WARRIOR  -> Spells.WARRIOR_SLASH;
            case ARCHER   -> Spells.ARCHER_ARROW;
            case SUMMONER -> Spells.SUMMONER_BOLT;
            default       -> Spells.MAGIC_MISSILE;
        };
    }

    /** 起手即拥有的被动（巫师带"重抽"，其余职业特性已并入 Stats 职业加成） */
    public static int[] startPassives(int classKind) {
        if (classKind == WIZARD) {
            return new int[] { Passives.REROLL };
        }
        return new int[0];
    }

    /** 该职业专属技能池，三选一抽卡用 */
    public static int[] pool(int classKind) {
        return Spells.poolForClass(classKind);
    }
}
