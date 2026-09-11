package com.arcanebrigade.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 三选一升级选项生成。
 *
 * 设计要求（DESIGN.md 第 4 节）：
 *   - 主动技能只在主动槽未满 3 时出现
 *   - 被动为主来源（34 个池 + 8 槽）
 *   - 候选不足用通用填充（急救 / 强身）
 *   - 稀有度权重 60/30/10
 *   - 幸运 +1 选项
 *   - **协同提示** —— 选法术时若与已有法术形成反应要明示；
 *     选被动时若是某主动的催化剂，要提示"可进化：XX → XX"。
 *     这是 DESIGN.md 明确点名的"游戏灵魂"。
 */
public final class Upgrades {

    private Upgrades() {}

    public static final int KIND_SPELL  = 0;
    public static final int KIND_PASSIVE = 1;
    public static final int KIND_FILLER = 2;

    public static final int FILLER_HEAL      = 1;
    public static final int FILLER_VITALITY  = 2;

    public static final class Choice {
        public final int kind;
        public final int id;
        public final String name;
        public final String desc;
        public final String label;
        public final int rarity;
        public final String rarityName;
        public final String synergy;
        public final boolean evolution;

        Choice(int kind, int id, String name, String desc, String label,
               int rarity, String synergy, boolean evolution) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.label = label;
            this.rarity = rarity;
            this.rarityName = rarityName(rarity);
            this.synergy = synergy;
            this.evolution = evolution;
        }

        private static String rarityName(int r) {
            return switch (r) {
                case PassiveDef.RARE -> "稀有";
                case PassiveDef.EPIC -> "史诗";
                default -> "普通";
            };
        }
    }

    /**
     * 投一次三选一。
     *
     * @param lo   玩家当前构筑
     * @param rng  随机源（让压测也能复现）
     * @return     3（或 3+optionAdd）个不重复的选项
     */
    public static Choice[] roll(Loadout lo, Random rng) {
        List<Cand> pool = new ArrayList<>();
        addSpellCandidates(lo, pool);
        addPassiveCandidates(lo, pool);

        int n = 3 + lo.stats.optionAdd;
        Choice[] out = new Choice[n];
        int filled = 0;

        while (filled < n && !pool.isEmpty()) {
            Cand c = weightedPick(pool, rng);
            pool.remove(c);
            out[filled++] = makeChoice(c, lo);
        }
        // 候选不足：补通用
        while (filled < n) {
            out[filled++] = makeFiller(filled, rng);
        }
        return out;
    }

    private static void addSpellCandidates(Loadout lo, List<Cand> pool) {
        if (lo.firstEmpty() < 0) {
            return;
        }
        // 只从该职业的专属技能池抽，保证三职业各玩各的构筑
        for (int sid : Spells.poolForClass(lo.classKind)) {
            if (lo.contains(sid)) {
                continue;
            }
            pool.add(new Cand(KIND_SPELL, sid, Spells.rarityOf(sid)));
        }
    }

    private static void addPassiveCandidates(Loadout lo, List<Cand> pool) {
        // 被动无上限：任何未满层的被动都可入池，不再受槽位约束
        for (int pid : Passives.all()) {
            PassiveDef d = Passives.get(pid);
            if (d == null) {
                continue;
            }
            if (lo.passiveStacks(pid) >= d.maxStacks) {
                continue;
            }
            pool.add(new Cand(KIND_PASSIVE, pid, d.rarity));
        }
    }

    /** 按稀有度权重加权抽一个，不放回 */
    private static Cand weightedPick(List<Cand> pool, Random rng) {
        int total = 0;
        for (Cand c : pool) {
            total += weight(c.rarity);
        }
        int roll = rng.nextInt(Math.max(1, total));
        int acc = 0;
        for (Cand c : pool) {
            acc += weight(c.rarity);
            if (roll < acc) {
                return c;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private static int weight(int rarity) {
        return switch (rarity) {
            case PassiveDef.RARE -> 30;
            case PassiveDef.EPIC -> 10;
            default -> 60;
        };
    }

    private static Choice makeChoice(Cand c, Loadout lo) {
        if (c.kind == KIND_SPELL) {
            SpellDef def = Spells.get(c.id);
            String syn = spellSynergy(c.id, lo);
            return new Choice(KIND_SPELL, c.id, def != null ? def.name : "?",
                    spellDesc(def), "主动", c.rarity, syn, false);
        } else {
            PassiveDef d = Passives.get(c.id);
            String syn = passiveSynergy(c.id, lo);
            boolean evo = Spells.catalyzedBy(c.id) != 0 && lo.contains(Spells.catalyzedBy(c.id));
            return new Choice(KIND_PASSIVE, c.id, d != null ? d.name : "?",
                    d != null ? d.desc : "", "被动", c.rarity, syn, evo);
        }
    }

    /** 法术协同：与已装备法术的元素形成反应 */
    private static String spellSynergy(int spellId, Loadout lo) {
        SpellDef def = Spells.get(spellId);
        if (def == null || def.element <= Element.NONE) {
            return "";
        }
        for (int s = 0; s < Loadout.SLOTS; s++) {
            int equipped = lo.resolvedSpell(s);
            if (equipped == Spells.NONE || equipped == spellId) {
                continue;
            }
            SpellDef e = Spells.get(equipped);
            if (e == null || e.element <= Element.NONE) {
                continue;
            }
            int r = Element.reaction(def.element, e.element);
            if (r != Element.R_NONE) {
                return "与「" + e.name + "」形成「" + Element.reactionName(r) + "」";
            }
        }
        return "";
    }

    /** 被动协同：是某已装备主动的催化剂 → 提示可进化 */
    private static String passiveSynergy(int passiveId, Loadout lo) {
        int baseSpell = Spells.catalyzedBy(passiveId);
        if (baseSpell == 0 || !lo.contains(baseSpell)) {
            return "";
        }
        SpellDef base = Spells.get(baseSpell);
        SpellDef evolved = Spells.get(Spells.evolvedOf(baseSpell));
        if (base == null || evolved == null) {
            return "";
        }
        return "催化：「" + base.name + "」→「" + evolved.name + "」";
    }

    private static Choice makeFiller(int seed, Random rng) {
        if ((seed + rng.nextInt(2)) % 2 == 0) {
            return new Choice(KIND_FILLER, FILLER_HEAL, "急救",
                    "恢复 40% 最大生命", "补给", PassiveDef.COMMON, "", false);
        }
        return new Choice(KIND_FILLER, FILLER_VITALITY, "强身",
                "最大生命 +10 并回满该部分", "补给", PassiveDef.RARE, "", false);
    }

    private static String spellDesc(SpellDef d) {
        if (d == null) {
            return "";
        }
        String elem = Element.name(d.element);
        if (elem.equals("无")) {
            elem = "无附魔";
        }
        return String.format("%.0f 伤害 / %.2fs CD · %s", d.damage, d.cooldown, elem);
    }

    private static final class Cand {
        final int kind;
        final int id;
        final int rarity;
        Cand(int kind, int id, int rarity) {
            this.kind = kind;
            this.id = id;
            this.rarity = rarity;
        }
    }

    /**
     * 仅从该职业的主动技池中抽 3 张不重复的技能卡（Boss 击杀奖励用）。
     * 与 roll() 的区别：不含被动 / 填充，保证"拿到的是一张技能卡"。
     * 候选不足 3 个（池子已被抽空）时，用通用填充补齐。
     */
    public static Choice[] rollSpell(Loadout lo, Random rng) {
        int[] pool = Spells.poolForClass(lo.classKind);
        List<Integer> avail = new ArrayList<>();
        for (int sid : pool) {
            if (!lo.contains(sid)) {
                avail.add(sid);
            }
        }
        java.util.Collections.shuffle(avail, rng);
        int n = 3;
        Choice[] out = new Choice[n];
        int filled = 0;
        for (int sid : avail) {
            if (filled >= n) {
                break;
            }
            out[filled++] = makeChoice(new Cand(KIND_SPELL, sid, Spells.rarityOf(sid)), lo);
        }
        while (filled < n) {
            out[filled++] = makeFiller(filled, rng);
        }
        return out;
    }
}
