package com.arcanebrigade.core;

/**
 * 一个角色的状态：构筑（3 主动 + 无上限被动）+ 成长（等级经验）+ 各类计时器。
 *
 * 主动刻意只给 3 个——类幸存者里主动技能是自动释放的，堆多了玩家根本感知不到，
 * 反而稀释构筑焦点。深度交给无上限的被动（见设计文档"孤注一掷"）。
 *
 * 冷却是每个槽独立的，和实体上的 cd[] 分开存：
 * 实体 cd[] 只给敌人接触伤害用，混在一起会让"法术冷却"和"攻击间隔"互相踩。
 *
 * 计时器（护盾 / 宝箱 / 战吼 / 环绕旋风 / 陷阱）放在这里而不是 World 的数组里，
 * 是因为它们只对玩家有意义，塞进 8192 长度的 SoA 数组纯属浪费。
 */
public final class Loadout {

    /** 主动槽上限。这是硬设计，不要随手调大 */
    public static final int SLOTS = 3;

    public final int[] spells = new int[SLOTS];
    public final float[] cd = new float[SLOTS];

    /** 被动：无上限。passives[i] 是第 i 种被动，pstacks[i] 是它叠的层数（直到 PassiveDef.maxStacks） */
    public final IntList passives = new IntList(16);
    public final IntList pstacks = new IntList(16);

    /** 哪些主动已进化。位 n 为 1 表示 spells 里的基础形态 n 已进化 */
    public int evolvedMask;

    public final Stats stats = new Stats();

    // ---- 成长 ----
    public int level = 1;
    public float xp;
    public float xpNext;
    /** 待处理的升级次数。>0 时客户端暂停并弹三选一 */
    public int pendingUps;
    /** 免费重抽次数（"重抽"被动提供） */
    public int rerolls;
    public float rerollTimer;

    /** 护盾：先扣护盾再扣血 */
    public float shield;

    // ---- 周期计时器 ----
    public float barrierTimer;
    public float chestTimer;
    public float warCryTimer;
    public float orbitingTimer;
    public float poisonTimer;
    /** 累计位移，达到阈值布一个陷阱（"钉刺陷阱"） */
    public float trapDistance;

    /** 三选一缓存。null 表示当前没有待选选项（pendingUps==0） */
    public Upgrades.Choice[] pendingChoices;

    /** 职业。决定基础属性、特性与技能池。默认巫师，由 World.spawnWizard 设置 */
    public int classKind = HeroClass.WIZARD;

    public Loadout() {
        xpNext = xpForLevel(1);
    }

    /** 升级所需经验。二次曲线：前期飞快，后期放缓，20 分钟约 30 级 */
    public static float xpForLevel(int level) {
        return 5f + level * 4f + level * level * 0.55f;
    }

    // ------------------------------------------------------------------
    // 主动
    // ------------------------------------------------------------------

    public void set(int slot, int spellId) {
        spells[slot] = spellId;
        cd[slot] = 0f;
        refresh();
    }

    public void clear(int slot) {
        spells[slot] = Spells.NONE;
        cd[slot] = 0f;
        refresh();
    }

    public int firstEmpty() {
        for (int i = 0; i < SLOTS; i++) {
            if (spells[i] == Spells.NONE) {
                return i;
            }
        }
        return -1;
    }

    /** 装到第一个空槽；满了返回 false（三选一 UI 满槽时不该再给主动） */
    public boolean add(int spellId) {
        int slot = firstEmpty();
        if (slot < 0) {
            return false;
        }
        set(slot, spellId);
        return true;
    }

    public boolean contains(int spellId) {
        for (int i = 0; i < SLOTS; i++) {
            if (spells[i] == spellId) {
                return true;
            }
        }
        return false;
    }

    public int activeCount() {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (spells[i] != Spells.NONE) {
                n++;
            }
        }
        return n;
    }

    /** 实际形态（已进化的返回进化版） */
    public int resolvedSpell(int slot) {
        int sid = spells[slot];
        return sid == Spells.NONE ? Spells.NONE : Spells.resolve(sid, evolvedMask);
    }

    // ------------------------------------------------------------------
    // 被动
    // ------------------------------------------------------------------

    /**
     * 获得一个被动。已拥有且未满层则叠层，否则开一个新槽（无上限，永远成功）。
     *
     * 注意：这里不直接改任何数值，只改构筑然后重算 Stats——
     * "数值只在 Stats 里算一次"是 D3 最重要的纪律。
     */
    public boolean addPassive(int passiveId) {
        PassiveDef def = Passives.get(passiveId);
        if (def == null) {
            return false;
        }
        int n = passives.size();
        for (int i = 0; i < n; i++) {
            if (passives.get(i) == passiveId && pstacks.get(i) < def.maxStacks) {
                pstacks.set(i, pstacks.get(i) + 1);
                refresh();
                return true;
            }
        }
        // 无上限：未满层的被动开新槽，空间永远够
        passives.add(passiveId);
        pstacks.add(1);
        refresh();
        return true;
    }

    public boolean hasPassive(int passiveId) {
        return passiveStacks(passiveId) > 0;
    }

    public int passiveStacks(int passiveId) {
        int n = passives.size();
        for (int i = 0; i < n; i++) {
            if (passives.get(i) == passiveId) {
                return pstacks.get(i);
            }
        }
        return 0;
    }

    public int passiveCount() {
        return passives.size();
    }

    // ------------------------------------------------------------------
    // 重算
    // ------------------------------------------------------------------

    /**
     * 构筑变化后必须调用。做两件事：重算 Stats，然后检查技能进化。
     *
     * 进化检查放在 Stats 之后，因为 Stats 的质变判定要读 resolvedSpell，
     * 必须先用最新的 evolvedMask。
     */
    public void refresh() {
        checkEvolutions();
        stats.recompute(this);
    }

    private void checkEvolutions() {
        for (int s = 0; s < SLOTS; s++) {
            int sid = spells[s];
            if (sid == Spells.NONE) {
                continue;
            }
            int catalyst = Spells.catalystOf(sid);
            if (catalyst != 0 && hasPassive(catalyst)) {
                evolvedMask |= 1 << sid;
            }
        }
    }

    // ------------------------------------------------------------------
    // 经验与升级
    // ------------------------------------------------------------------

    /** 加经验，返回本次升了几级 */
    public int gainXp(float amount) {
        xp += amount * stats.xpMul;
        int ups = 0;
        while (xp >= xpNext) {
            xp -= xpNext;
            level++;
            xpNext = xpForLevel(level);
            ups++;
        }
        pendingUps += ups;
        return ups;
    }

    public float xpRatio() {
        return xpNext > 0f ? Math.min(1f, xp / xpNext) : 0f;
    }
}
