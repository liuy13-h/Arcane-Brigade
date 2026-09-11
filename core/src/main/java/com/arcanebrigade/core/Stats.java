package com.arcanebrigade.core;

/**
 * 由被动汇总出的一份属性。World 只在施法 / 受伤 / 结算时读它，不认识任何具体被动。
 *
 * 这是 D3 的核心抽象。没有这一层，34 个被动会变成 34 段 if (hasPassive(X)) 散落在
 * World 各处——调平衡时改一处漏三处，而且漏掉的会静默失效（最难查的 bug）。
 *
 * 重算时机：只在构筑变化时（获得被动 / 换技能）。不要每帧算，8 个槽 × 层数足够便宜，
 * 但每帧算纯属浪费，而且会掩盖"忘记重算"的 bug。
 */
public final class Stats {

    // ---- 加算类：最终值 = 1 + Σ(delta × 层数) ----
    public float dmgMul        = 1f;
    public float atkSpeed      = 1f;
    public float moveMul       = 1f;
    public float pickupMul     = 1f;
    public float xpMul         = 1f;
    public float areaMul       = 1f;
    public float dotDurMul     = 1f;
    public float fireMul       = 1f;
    public float frostDurMul   = 1f;
    public float arcaneMul     = 1f;

    // ---- 加值类 ----
    public float critChance    = 0f;
    public float critDmg       = Balance.BASE_CRIT_DMG;
    public float maxHpAdd      = 0f;
    public float dr            = 0f;
    public float regenAdd      = 0f;
    public int   pierceAdd     = 0;
    public int   bounceAdd     = 0;
    public int   shockChainAdd = 0;
    public float iframeAdd     = 0f;
    public int   optionAdd     = 0;
    /** 每次击杀回复的生命（战士职业特性），0 = 无 */
    public float healOnKill    = 0f;
    public float reactionDmgMul    = 1f;
    public float reactionRadiusMul = 1f;

    // ---- 开关 ----
    public boolean orbitingStorm;
    public boolean poisonTrail;
    public boolean warCryShield;
    public boolean spikeTrap;
    public boolean barrier;
    public boolean lastStand;
    public boolean chest;
    public boolean reroll;

    /** 质变是否已经达成。UI 要显示"已激活"，否则玩家不知道自己凑齐了 */
    public boolean loneWolfActive;
    public boolean criticalMassActive;
    public boolean barrageActive;

    /** 绝境爆发：World 在 updateWizards 里根据血量设。无独立乘算——World 直接在 castOne 里读 */
    public boolean lastStandActive;

    /** 已装备主动技能覆盖到的不同元素数量（元素共鸣的输入，也用于三选一提示） */
    public int elementKinds;

    public void reset() {
        dmgMul = 1f;
        atkSpeed = 1f;
        moveMul = 1f;
        pickupMul = 1f;
        xpMul = 1f;
        areaMul = 1f;
        dotDurMul = 1f;
        fireMul = 1f;
        frostDurMul = 1f;
        arcaneMul = 1f;

        critChance = 0f;
        critDmg = Balance.BASE_CRIT_DMG;
        maxHpAdd = 0f;
        dr = 0f;
        regenAdd = 0f;
        pierceAdd = 0;
        bounceAdd = 0;
        shockChainAdd = 0;
        iframeAdd = 0f;
        optionAdd = 0;
        healOnKill = 0f;
        reactionDmgMul = 1f;
        reactionRadiusMul = 1f;

        orbitingStorm = false;
        poisonTrail = false;
        warCryShield = false;
        spikeTrap = false;
        barrier = false;
        lastStand = false;
        chest = false;
        reroll = false;

        loneWolfActive = false;
        criticalMassActive = false;
        barrageActive = false;
        lastStandActive = false;
        elementKinds = 0;
    }

    /**
     * 按当前构筑重算全部属性。
     *
     * 顺序有讲究：先累加所有被动的基础增量，再套用质变条件——
     * 因为"临界质量"和"弹幕之王"的判定依赖前面算出来的攻速与穿透。
     */
    public void recompute(Loadout lo) {
        reset();

        // 1) 累加被动
        boolean loneWolf = false;
        boolean criticalMass = false;
        boolean barrageKing = false;
        boolean chainReaction = false;
        boolean elemOverload = false;
        boolean resonance = false;

        for (int s = 0; s < lo.passives.size(); s++) {
            int pid = lo.passives.get(s);
            if (pid == Passives.NONE) {
                continue;
            }
            PassiveDef d = Passives.get(pid);
            if (d == null) {
                continue;
            }
            int n = lo.pstacks.get(s);

            dmgMul      += d.dmgMul * n;
            atkSpeed    += d.atkSpeed * n;
            moveMul     += d.moveMul * n;
            pickupMul   += d.pickupMul * n;
            xpMul       += d.xpMul * n;
            areaMul     += d.areaMul * n;
            dotDurMul   += d.dotDurMul * n;
            fireMul     += d.fireMul * n;
            frostDurMul += d.frostDurMul * n;
            arcaneMul   += d.arcaneMul * n;

            critChance    += d.critChance * n;
            critDmg       += d.critDmg * n;
            maxHpAdd      += d.maxHpAdd * n;
            dr            += d.dr * n;
            regenAdd      += d.regenAdd * n;
            pierceAdd     += d.pierceAdd * n;
            bounceAdd     += d.bounceAdd * n;
            shockChainAdd += d.shockChainAdd * n;
            iframeAdd     += d.iframeAdd * n;
            optionAdd     += d.optionAdd * n;

            orbitingStorm |= d.orbitingStorm;
            poisonTrail   |= d.poisonTrail;
            warCryShield  |= d.warCryShield;
            spikeTrap     |= d.spikeTrap;
            barrier       |= d.barrier;
            lastStand     |= d.lastStand;
            chest         |= d.chest;
            reroll        |= d.reroll;

            loneWolf      |= d.loneWolf;
            criticalMass  |= d.criticalMass;
            barrageKing   |= d.barrageKing;
            chainReaction |= d.chainReaction;
            elemOverload  |= d.elemOverload;
            resonance     |= d.resonance;
        }

        // 职业特性：巫师法术+10% / 战士减伤15%+击杀回血 / 弓箭手暴击+10%
        // 放在被动累加之后、封顶之前，保证职业加成也受上限约束
        applyClassTraits(lo);

        // 减伤必须封顶：坚壁 4 层是 32%，再叠加职业减伤还有空间，
        // 但不封顶的话后期能堆到免疫，游戏就没了
        dr = Math.min(dr, Balance.MAX_DAMAGE_REDUCTION);
        critChance = Math.min(critChance, Balance.MAX_CRIT_CHANCE);

        // 2) 统计已装备的元素种类 + 最大穿透 + 每秒投射物数
        int maxPierce = pierceAdd;
        float barrageRate = 0f;
        int elemMask = 0;
        int actives = lo.activeCount();

        for (int s = 0; s < Loadout.SLOTS; s++) {
            int sid = lo.spells[s];
            if (sid == Spells.NONE) {
                continue;
            }
            SpellDef def = Spells.get(Spells.resolve(sid, lo.evolvedMask));
            if (def == null) {
                continue;
            }
            maxPierce = Math.max(maxPierce, def.pierce + pierceAdd);
            if (def.element > Element.NONE && def.element != Element.ARCANE) {
                elemMask |= 1 << def.element;
            } else if (def.element == Element.ARCANE) {
                elemMask |= 1 << Element.ARCANE;
            }
            barrageRate += def.count * atkSpeed / Math.max(def.cooldown, 1e-3f);
        }
        elementKinds = Integer.bitCount(elemMask);

        // 3) 质变判定
        if (resonance) {
            dmgMul += Balance.RESONANCE_PER_ELEMENT * elementKinds;
        }
        if (loneWolf && actives == 1) {
            loneWolfActive = true;
            dmgMul *= 1f + Balance.ALL_IN_DAMAGE;
        }
        if (criticalMass && maxPierce >= Balance.CRITICAL_MASS_PIERCE) {
            criticalMassActive = true;
            dmgMul *= 1f + Balance.CRITICAL_MASS_DAMAGE;
        }
        if (barrageKing && barrageRate >= Balance.BARRAGE_RATE_THRESHOLD) {
            barrageActive = true;
            atkSpeed *= 1f + Balance.BARRAGE_ATTACK_SPEED;
        }
        if (chainReaction) {
            reactionRadiusMul *= 1f + Balance.CHAIN_REACTION_RADIUS;
        }
        if (elemOverload) {
            reactionDmgMul *= 1f + Balance.ELEM_OVERLOAD_DAMAGE;
        }
    }

    /** 职业特性：在被动累加之后套用，全部走 Balance 常量 */
    private void applyClassTraits(Loadout lo) {
        switch (lo.classKind) {
            case HeroClass.WARRIOR -> {
                dr += Balance.WARRIOR_DR;
                healOnKill += Balance.WARRIOR_LIFESTEAL;
            }
            case HeroClass.ARCHER -> critChance += Balance.ARCHER_CRIT;
            default -> dmgMul += Balance.WIZARD_SPELL_DMG;
        }
    }

    /** 每秒投射物数，UI 显示"弹幕之王"进度用 */
    public float barrageRate(Loadout lo) {
        float rate = 0f;
        for (int s = 0; s < Loadout.SLOTS; s++) {
            int sid = lo.spells[s];
            if (sid == Spells.NONE) {
                continue;
            }
            SpellDef def = Spells.get(Spells.resolve(sid, lo.evolvedMask));
            if (def != null) {
                rate += def.count * atkSpeed / Math.max(def.cooldown, 1e-3f);
            }
        }
        return rate;
    }

    public int maxPierce(Loadout lo) {
        int m = pierceAdd;
        for (int s = 0; s < Loadout.SLOTS; s++) {
            int sid = lo.spells[s];
            if (sid == Spells.NONE) {
                continue;
            }
            SpellDef def = Spells.get(Spells.resolve(sid, lo.evolvedMask));
            if (def != null) {
                m = Math.max(m, def.pierce + pierceAdd);
            }
        }
        return m;
    }
}
