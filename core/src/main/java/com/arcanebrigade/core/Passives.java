package com.arcanebrigade.core;

import com.arcanebrigade.core.PassiveDef.Kind;

/**
 * 被动注册表。34 个：30 个池内被动 + 4 个由主动技能改造而来。
 *
 * 数值全部对照 DESIGN.md 第 4 节。改平衡只改这张表。
 *
 * maxStacks 的分配是有讲究的：
 *   - 纯数值被动允许叠加，否则后期升级没东西可选；
 *   - 质变类与转换类强制唯一，它们是"有没有"而不是"多强"，
 *     允许叠加会让孤注一掷这种设计锚点失去意义。
 *   - 穿透弹给 3 层，是为了让"冰锥穿透 ≥ 5 → 命中分裂"这个质变阈值可达
 *     （冰锥基础 2 + 3 = 5），阈值写死了却够不到是最糟的设计失误。
 */
public final class Passives {

    private Passives() {}

    public static final int NONE = 0;

    // ---- 输出类（8）----
    public static final int POWER_TRAINING = 1;   // 力量训练
    public static final int PRECISION      = 2;   // 精准
    public static final int DEADLY_STRIKE  = 3;   // 致命一击
    public static final int ALACRITY       = 4;   // 疾速
    public static final int PIERCING_SHOT  = 5;   // 穿透弹
    public static final int RICOCHET       = 6;   // 跳弹
    public static final int MAGNIFY        = 7;   // 巨化
    public static final int LINGERING_BURN = 8;   // 延烧

    // ---- 生存类（7）----
    public static final int VIGOR          = 9;   // 强健
    public static final int BULWARK        = 10;  // 坚壁
    public static final int REGENERATION   = 11;  // 再生
    public static final int SWIFTNESS      = 12;  // 迅捷
    public static final int NIMBLE         = 13;  // 灵巧
    public static final int BARRIER        = 14;  // 屏障
    public static final int LAST_STAND     = 15;  // 绝境爆发

    // ---- 资源类（5）----
    public static final int GREED          = 16;  // 贪婪
    public static final int WISDOM         = 17;  // 智慧
    public static final int REROLL         = 18;  // 重抽
    public static final int TREASURE       = 19;  // 宝箱
    public static final int FORTUNE        = 20;  // 幸运

    // ---- 元素类（6）----
    public static final int FIRE_AFFINITY  = 21;  // 火焰亲和
    public static final int FROST_AFFINITY = 22;  // 冰霜亲和
    public static final int SHOCK_AFFINITY = 23;  // 雷电亲和
    public static final int ARCANE_AFFINITY = 24; // 秘法亲和
    public static final int RESONANCE      = 25;  // 元素共鸣
    public static final int ELEM_OVERLOAD  = 26;  // 元素过载

    // ---- 质变类（4，设计核心）----
    public static final int ALL_IN         = 27;  // 孤注一掷
    public static final int CHAIN_REACTION = 28;  // 连锁反应
    public static final int CRITICAL_MASS  = 29;  // 临界质量
    public static final int BARRAGE_KING   = 30;  // 弹幕之王

    // ---- 由主动改造而来（4）----
    public static final int ORBITING_STORM = 31;  // 环绕旋风
    public static final int POISON_TRAIL   = 32;  // 毒雾轨迹
    public static final int WAR_CRY_SHIELD = 33;  // 战吼护盾
    public static final int SPIKE_TRAP     = 34;  // 钉刺陷阱

    private static final PassiveDef[] TABLE = new PassiveDef[64];

    static {
        // ============ 输出类（8）============
        TABLE[POWER_TRAINING] = PassiveDef.builder(POWER_TRAINING, "力量训练", Kind.OUTPUT, PassiveDef.COMMON)
                .desc("全伤害 +12%").dmg(0.12f).stacks(5).build();

        TABLE[PRECISION] = PassiveDef.builder(PRECISION, "精准", Kind.OUTPUT, PassiveDef.COMMON)
                .desc("暴击率 +8%").crit(0.08f).stacks(5).build();

        TABLE[DEADLY_STRIKE] = PassiveDef.builder(DEADLY_STRIKE, "致命一击", Kind.OUTPUT, PassiveDef.RARE)
                .desc("暴击伤害 +50%").critDmg(0.50f).stacks(3).build();

        TABLE[ALACRITY] = PassiveDef.builder(ALACRITY, "疾速", Kind.OUTPUT, PassiveDef.COMMON)
                .desc("攻击速度 +14%").atkSpeed(0.14f).stacks(5).build();

        TABLE[PIERCING_SHOT] = PassiveDef.builder(PIERCING_SHOT, "穿透弹", Kind.OUTPUT, PassiveDef.RARE)
                .desc("所有投射物 +1 穿透").pierce(1).stacks(3).build();

        TABLE[RICOCHET] = PassiveDef.builder(RICOCHET, "跳弹", Kind.OUTPUT, PassiveDef.RARE)
                .desc("投射物命中后额外弹射 1 次").bounce(1).stacks(2).build();

        TABLE[MAGNIFY] = PassiveDef.builder(MAGNIFY, "巨化", Kind.OUTPUT, PassiveDef.COMMON)
                .desc("所有范围效果 +20%").area(0.20f).stacks(4).build();

        TABLE[LINGERING_BURN] = PassiveDef.builder(LINGERING_BURN, "延烧", Kind.OUTPUT, PassiveDef.COMMON)
                .desc("持续伤害时长 +40%").dotDur(0.40f).stacks(3).build();

        // ============ 生存类（7）============
        TABLE[VIGOR] = PassiveDef.builder(VIGOR, "强健", Kind.SURVIVAL, PassiveDef.COMMON)
                .desc("最大生命 +25").hp(25f).stacks(5).build();

        TABLE[BULWARK] = PassiveDef.builder(BULWARK, "坚壁", Kind.SURVIVAL, PassiveDef.COMMON)
                .desc("受伤减免 8%").dr(0.08f).stacks(4).build();

        TABLE[REGENERATION] = PassiveDef.builder(REGENERATION, "再生", Kind.SURVIVAL, PassiveDef.COMMON)
                .desc("每秒回血 +1.0").regen(1.0f).stacks(4).build();

        TABLE[SWIFTNESS] = PassiveDef.builder(SWIFTNESS, "迅捷", Kind.SURVIVAL, PassiveDef.COMMON)
                .desc("移动速度 +10%").move(0.10f).stacks(5).build();

        TABLE[NIMBLE] = PassiveDef.builder(NIMBLE, "灵巧", Kind.SURVIVAL, PassiveDef.RARE)
                .desc("受击无敌帧 +0.15 秒").iframe(0.15f).stacks(2).build();

        TABLE[BARRIER] = PassiveDef.builder(BARRIER, "屏障", Kind.SURVIVAL, PassiveDef.RARE)
                .desc("每 20 秒获得 15 点护盾").barrier().build();

        TABLE[LAST_STAND] = PassiveDef.builder(LAST_STAND, "绝境爆发", Kind.SURVIVAL, PassiveDef.EPIC)
                .desc("生命低于 30% 时，移速 +20%、伤害 +15%").lastStand().build();

        // ============ 资源类（5）============
        TABLE[GREED] = PassiveDef.builder(GREED, "贪婪", Kind.RESOURCE, PassiveDef.COMMON)
                .desc("拾取范围 +50%").pickup(0.50f).stacks(3).build();

        TABLE[WISDOM] = PassiveDef.builder(WISDOM, "智慧", Kind.RESOURCE, PassiveDef.COMMON)
                .desc("经验获取 +20%").xp(0.20f).stacks(4).build();

        TABLE[REROLL] = PassiveDef.builder(REROLL, "重抽", Kind.RESOURCE, PassiveDef.RARE)
                .desc("每 20 秒获得一次免费重抽").reroll().build();

        TABLE[TREASURE] = PassiveDef.builder(TREASURE, "宝箱", Kind.RESOURCE, PassiveDef.RARE)
                .desc("每 60 秒掉落一个宝箱").chest().build();

        TABLE[FORTUNE] = PassiveDef.builder(FORTUNE, "幸运", Kind.RESOURCE, PassiveDef.EPIC)
                .desc("升级时多一个选项").option(1).build();

        // ============ 元素类（6）============
        TABLE[FIRE_AFFINITY] = PassiveDef.builder(FIRE_AFFINITY, "火焰亲和", Kind.ELEMENT, PassiveDef.COMMON)
                .desc("火焰伤害 +35%").fire(0.35f).stacks(3).build();

        TABLE[FROST_AFFINITY] = PassiveDef.builder(FROST_AFFINITY, "冰霜亲和", Kind.ELEMENT, PassiveDef.COMMON)
                .desc("冰霜效果时长 +50%").frostDur(0.50f).stacks(3).build();

        TABLE[SHOCK_AFFINITY] = PassiveDef.builder(SHOCK_AFFINITY, "雷电亲和", Kind.ELEMENT, PassiveDef.RARE)
                .desc("雷电弹射次数 +1").shockChain(1).stacks(3).build();

        TABLE[ARCANE_AFFINITY] = PassiveDef.builder(ARCANE_AFFINITY, "秘法亲和", Kind.ELEMENT, PassiveDef.COMMON)
                .desc("秘法伤害 +30%").arcane(0.30f).stacks(3).build();

        TABLE[RESONANCE] = PassiveDef.builder(RESONANCE, "元素共鸣", Kind.ELEMENT, PassiveDef.RARE)
                .desc("每装备一种元素，全伤害 +5%").resonance().build();

        TABLE[ELEM_OVERLOAD] = PassiveDef.builder(ELEM_OVERLOAD, "元素过载", Kind.ELEMENT, PassiveDef.EPIC)
                .desc("触发元素反应时伤害 +30%").elemOverload().build();

        // ============ 质变类（4）============
        TABLE[ALL_IN] = PassiveDef.builder(ALL_IN, "孤注一掷", Kind.MUTATION, PassiveDef.EPIC)
                .desc("只装备 1 个主动技能时，其伤害 +80%、冷却 −20%").loneWolf().build();

        TABLE[CHAIN_REACTION] = PassiveDef.builder(CHAIN_REACTION, "连锁反应", Kind.MUTATION, PassiveDef.EPIC)
                .desc("元素反应范围 +50%").chainReaction().build();

        TABLE[CRITICAL_MASS] = PassiveDef.builder(CRITICAL_MASS, "临界质量", Kind.MUTATION, PassiveDef.EPIC)
                .desc("穿透 ≥ 5 时全伤害 +50%").criticalMass().build();

        TABLE[BARRAGE_KING] = PassiveDef.builder(BARRAGE_KING, "弹幕之王", Kind.MUTATION, PassiveDef.EPIC)
                .desc("每秒投射物数 ≥ 8 时攻速 +25%").barrageKing().build();

        // ============ 由主动改造而来（4）============
        TABLE[ORBITING_STORM] = PassiveDef.builder(ORBITING_STORM, "环绕旋风", Kind.CONVERTED, PassiveDef.RARE)
                .desc("身边持续旋转伤害场：8 伤害 / 0.4 秒 / 半径 90").orbitingStorm().build();

        TABLE[POISON_TRAIL] = PassiveDef.builder(POISON_TRAIL, "毒雾轨迹", Kind.CONVERTED, PassiveDef.RARE)
                .desc("移动时留下毒雾：5 伤害每 0.5 秒，持续 4 秒").poisonTrail().build();

        TABLE[WAR_CRY_SHIELD] = PassiveDef.builder(WAR_CRY_SHIELD, "战吼护盾", Kind.CONVERTED, PassiveDef.RARE)
                .desc("每 25 秒自动获得 30 点护盾并击退周围敌人").warCryShield().build();

        TABLE[SPIKE_TRAP] = PassiveDef.builder(SPIKE_TRAP, "钉刺陷阱", Kind.CONVERTED, PassiveDef.RARE)
                .desc("每移动一段距离布下陷阱，触发造成 30 伤害并定身 1 秒").spikeTrap().build();
    }

    public static PassiveDef get(int id) {
        if (id <= NONE || id >= TABLE.length) {
            return null;
        }
        return TABLE[id];
    }

    public static int capacity() {
        return TABLE.length;
    }

    /** 所有有效被动 id，三选一抽卡用 */
    public static int[] all() {
        int n = 0;
        for (int i = 1; i < TABLE.length; i++) {
            if (TABLE[i] != null) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 1; i < TABLE.length; i++) {
            if (TABLE[i] != null) {
                out[k++] = i;
            }
        }
        return out;
    }
}
