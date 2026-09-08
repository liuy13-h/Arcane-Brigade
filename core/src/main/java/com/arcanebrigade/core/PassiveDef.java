package com.arcanebrigade.core;

/**
 * 被动定义。纯数据。
 *
 * 设计要点：被动**不直接改 World 的行为**，而是携带数值增量（delta）与开关标志，
 * 由 Stats 汇总成一份属性，World 只在施法/受伤/结算时读 Stats。
 *
 * 这么做的原因很现实：34 个被动如果写成 34 段 if-else 散落在 World 里，
 * 调平衡会变成噩梦，而且漏判一个就静默失效。数据驱动下，
 * 加一个被动 = Passives 表里加一行。
 *
 * 数值语义：所有 float 增量都是"每层叠加多少"，最终值 = 1 + Σ(delta × stacks)
 * （除非注释里写明是加值而非乘率）。
 */
public final class PassiveDef {

    public static final int COMMON = 0;
    public static final int RARE   = 1;
    public static final int EPIC   = 2;

    /** 分类只用于三选一时的权重与 UI 分组，不影响逻辑 */
    public enum Kind {
        OUTPUT, SURVIVAL, RESOURCE, ELEMENT, MUTATION, CONVERTED
    }

    public final int id;
    public final String name;
    public final String desc;
    public final int rarity;
    public final int maxStacks;
    public final Kind kind;

    // ---- 数值增量（每层）----
    public final float dmgMul;
    public final float critChance;
    public final float critDmg;
    public final float atkSpeed;
    public final float moveMul;
    public final float maxHpAdd;
    public final float dr;
    public final float regenAdd;
    public final float pickupMul;
    public final float xpMul;
    public final int pierceAdd;
    public final int bounceAdd;
    public final float areaMul;
    public final float dotDurMul;
    public final float fireMul;
    public final float frostDurMul;
    public final int shockChainAdd;
    public final float arcaneMul;
    public final float iframeAdd;
    public final int optionAdd;

    // ---- 开关标志（这些是"有或没有"，叠加层数只能是 1）----
    /** 孤注一掷：只带 1 个主动时，其伤害 +80%、冷却 −20% */
    public final boolean loneWolf;
    /** 连锁反应：元素反应范围 +50% */
    public final boolean chainReaction;
    /** 临界质量：穿透 ≥ 阈值时全伤害 +50% */
    public final boolean criticalMass;
    /** 弹幕之王：每秒投射物数 ≥ 阈值时攻速 +25% */
    public final boolean barrageKing;

    /** 环绕旋风（原"旋风"法术改造） */
    public final boolean orbitingStorm;
    /** 毒雾轨迹（原"毒云"法术改造） */
    public final boolean poisonTrail;
    /** 战吼护盾（原主动技能改造） */
    public final boolean warCryShield;
    /** 钉刺陷阱（原主动技能改造） */
    public final boolean spikeTrap;

    /** 屏障：每 N 秒获得护盾 */
    public final boolean barrier;
    /** 绝境爆发：低血量时移速与伤害提升 */
    public final boolean lastStand;
    /** 宝箱：每 N 秒掉一个宝箱 */
    public final boolean chest;
    /** 元素共鸣：每装备一种元素，全伤害 +5% */
    public final boolean resonance;
    /** 元素过载：触发元素反应时伤害 +30% */
    public final boolean elemOverload;
    /** 重抽：每 N 秒获得一次免费重抽 */
    public final boolean reroll;

    private PassiveDef(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.desc = b.desc;
        this.rarity = b.rarity;
        this.maxStacks = b.maxStacks;
        this.kind = b.kind;
        this.dmgMul = b.dmgMul;
        this.critChance = b.critChance;
        this.critDmg = b.critDmg;
        this.atkSpeed = b.atkSpeed;
        this.moveMul = b.moveMul;
        this.maxHpAdd = b.maxHpAdd;
        this.dr = b.dr;
        this.regenAdd = b.regenAdd;
        this.pickupMul = b.pickupMul;
        this.xpMul = b.xpMul;
        this.pierceAdd = b.pierceAdd;
        this.bounceAdd = b.bounceAdd;
        this.areaMul = b.areaMul;
        this.dotDurMul = b.dotDurMul;
        this.fireMul = b.fireMul;
        this.frostDurMul = b.frostDurMul;
        this.shockChainAdd = b.shockChainAdd;
        this.arcaneMul = b.arcaneMul;
        this.iframeAdd = b.iframeAdd;
        this.optionAdd = b.optionAdd;
        this.loneWolf = b.loneWolf;
        this.chainReaction = b.chainReaction;
        this.criticalMass = b.criticalMass;
        this.barrageKing = b.barrageKing;
        this.orbitingStorm = b.orbitingStorm;
        this.poisonTrail = b.poisonTrail;
        this.warCryShield = b.warCryShield;
        this.spikeTrap = b.spikeTrap;
        this.barrier = b.barrier;
        this.lastStand = b.lastStand;
        this.chest = b.chest;
        this.resonance = b.resonance;
        this.elemOverload = b.elemOverload;
        this.reroll = b.reroll;
    }

    public static Builder builder(int id, String name, Kind kind, int rarity) {
        return new Builder(id, name, kind, rarity);
    }

    public String rarityName() {
        return switch (rarity) {
            case RARE -> "稀有";
            case EPIC -> "史诗";
            default -> "普通";
        };
    }

    public static final class Builder {
        private final int id;
        private final String name;
        private final Kind kind;
        private final int rarity;
        private String desc = "";
        private int maxStacks = 1;

        private float dmgMul = 0f;
        private float critChance = 0f;
        private float critDmg = 0f;
        private float atkSpeed = 0f;
        private float moveMul = 0f;
        private float maxHpAdd = 0f;
        private float dr = 0f;
        private float regenAdd = 0f;
        private float pickupMul = 0f;
        private float xpMul = 0f;
        private int pierceAdd = 0;
        private int bounceAdd = 0;
        private float areaMul = 0f;
        private float dotDurMul = 0f;
        private float fireMul = 0f;
        private float frostDurMul = 0f;
        private int shockChainAdd = 0;
        private float arcaneMul = 0f;
        private float iframeAdd = 0f;
        private int optionAdd = 0;

        private boolean loneWolf = false;
        private boolean chainReaction = false;
        private boolean criticalMass = false;
        private boolean barrageKing = false;
        private boolean orbitingStorm = false;
        private boolean poisonTrail = false;
        private boolean warCryShield = false;
        private boolean spikeTrap = false;
        private boolean barrier = false;
        private boolean lastStand = false;
        private boolean chest = false;
        private boolean resonance = false;
        private boolean elemOverload = false;
        private boolean reroll = false;

        private Builder(int id, String name, Kind kind, int rarity) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.rarity = rarity;
        }

        public Builder desc(String v) { this.desc = v; return this; }
        public Builder stacks(int v) { this.maxStacks = v; return this; }

        public Builder dmg(float v) { this.dmgMul = v; return this; }
        public Builder crit(float v) { this.critChance = v; return this; }
        public Builder critDmg(float v) { this.critDmg = v; return this; }
        public Builder atkSpeed(float v) { this.atkSpeed = v; return this; }
        public Builder move(float v) { this.moveMul = v; return this; }
        public Builder hp(float v) { this.maxHpAdd = v; return this; }
        public Builder dr(float v) { this.dr = v; return this; }
        public Builder regen(float v) { this.regenAdd = v; return this; }
        public Builder pickup(float v) { this.pickupMul = v; return this; }
        public Builder xp(float v) { this.xpMul = v; return this; }
        public Builder pierce(int v) { this.pierceAdd = v; return this; }
        public Builder bounce(int v) { this.bounceAdd = v; return this; }
        public Builder area(float v) { this.areaMul = v; return this; }
        public Builder dotDur(float v) { this.dotDurMul = v; return this; }
        public Builder fire(float v) { this.fireMul = v; return this; }
        public Builder frostDur(float v) { this.frostDurMul = v; return this; }
        public Builder shockChain(int v) { this.shockChainAdd = v; return this; }
        public Builder arcane(float v) { this.arcaneMul = v; return this; }
        public Builder iframe(float v) { this.iframeAdd = v; return this; }
        public Builder option(int v) { this.optionAdd = v; return this; }

        public Builder loneWolf() { this.loneWolf = true; return this; }
        public Builder chainReaction() { this.chainReaction = true; return this; }
        public Builder criticalMass() { this.criticalMass = true; return this; }
        public Builder barrageKing() { this.barrageKing = true; return this; }
        public Builder orbitingStorm() { this.orbitingStorm = true; return this; }
        public Builder poisonTrail() { this.poisonTrail = true; return this; }
        public Builder warCryShield() { this.warCryShield = true; return this; }
        public Builder spikeTrap() { this.spikeTrap = true; return this; }
        public Builder barrier() { this.barrier = true; return this; }
        public Builder lastStand() { this.lastStand = true; return this; }
        public Builder chest() { this.chest = true; return this; }
        public Builder resonance() { this.resonance = true; return this; }
        public Builder elemOverload() { this.elemOverload = true; return this; }
        public Builder reroll() { this.reroll = true; return this; }

        public PassiveDef build() {
            return new PassiveDef(this);
        }
    }
}
