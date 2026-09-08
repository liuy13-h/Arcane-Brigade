package com.arcanebrigade.core;

/**
 * 法术定义。纯数据，没有行为。
 *
 * 存在的意义：新增法术只需要在 Spells 表里加一行，不用碰 World 的任何逻辑。
 * 反过来说，如果哪天发现"为了加一个法术要去改 World"，说明抽象漏了。
 *
 * form 是攻击形态的抽象点：
 *   PROJECTILE —— 飞行弹道，走碰撞检测，可穿透/爆炸/连锁
 *   MELEE_ARC  —— 以自身为中心的扇形瞬间判定，命中扇形内所有敌人（战士用）
 * 两者共用同一套冷却、索敌、元素附着与反应逻辑，只有"怎么把伤害送出去"不同。
 */
public final class SpellDef {

    public enum Form {
        PROJECTILE,
        MELEE_ARC
    }

    public final int id;
    public final String name;
    public final Form form;

    // ---- 通用 ----
    public final float cooldown;
    public final float damage;
    /** 索敌距离。弹道法术同时用来换算寿命：life = range / speed */
    public final float range;
    public final int element;
    /** 元素强度：火焰=每秒伤害，冰霜=减速比例，雷电=受伤增伤比例 */
    public final float elemPotency;
    public final float elemDuration;

    // ---- PROJECTILE ----
    public final float speed;
    public final float boltRadius;
    /** 一次发射几发 */
    public final int count;
    /** 多发时的扇形总张角（弧度） */
    public final float spread;
    /** 可穿透几个额外目标，0 = 命中即消失 */
    public final int pierce;
    /** 命中后爆炸半径，0 = 无爆炸 */
    public final float aoeRadius;
    /** 连锁：额外弹射几个目标，0 = 不弹射 */
    public final int chain;
    public final float chainRange;
    /** 每次弹射的伤害衰减比例 */
    public final float chainFalloff;
    /** 命中后跳弹次数，0 = 不跳。和"跳弹"被动叠加 */
    public final int bounce;
    /** 是否追踪：飞行中每帧微调朝向最近敌人（弓箭手追踪箭） */
    public final boolean homing;

    // ---- 进化形态专用 ----
    /** 命中后留下地面区域的半径，0 = 不留（烈焰新星） */
    public final float zoneRadius;
    /** 地面区域持续时间 */
    public final float zoneDuration;
    /** 地面区域每秒伤害 */
    public final float zoneDps;
    /** 地面区域附着的元素 */
    public final int zoneElement;
    /** 命中冻结时长，0 = 不冻结（绝对零度） */
    public final float freeze;

    // ---- MELEE_ARC ----
    /** 扇形半径 */
    public final float arcRadius;
    /** 扇形张角（弧度） */
    public final float arcAngle;
    /** 近战命中把敌人推开的速度（盾击/裂地用），0 = 不击退 */
    public final float knockback;

    private SpellDef(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.form = b.form;
        this.cooldown = b.cooldown;
        this.damage = b.damage;
        this.range = b.range;
        this.element = b.element;
        this.elemPotency = b.elemPotency;
        this.elemDuration = b.elemDuration;
        this.speed = b.speed;
        this.boltRadius = b.boltRadius;
        this.count = b.count;
        this.spread = b.spread;
        this.pierce = b.pierce;
        this.aoeRadius = b.aoeRadius;
        this.chain = b.chain;
        this.chainRange = b.chainRange;
        this.chainFalloff = b.chainFalloff;
        this.bounce = b.bounce;
        this.homing = b.homing;
        this.zoneRadius = b.zoneRadius;
        this.zoneDuration = b.zoneDuration;
        this.zoneDps = b.zoneDps;
        this.zoneElement = b.zoneElement;
        this.freeze = b.freeze;
        this.arcRadius = b.arcRadius;
        this.arcAngle = b.arcAngle;
        this.knockback = b.knockback;
    }

    public static Builder builder(int id, String name, Form form) {
        return new Builder(id, name, form);
    }

    /** 弹道法术的寿命由射程和速度推出，避免手填一个对不上的数字 */
    public float boltLife() {
        return speed > 0f ? range / speed : 1f;
    }

    public static final class Builder {
        private final int id;
        private final String name;
        private final Form form;

        private float cooldown = 1f;
        private float damage = 10f;
        private float range = 600f;
        private int element = Element.NONE;
        private float elemPotency = 0f;
        private float elemDuration = 0f;

        private float speed = 470f;
        private float boltRadius = 6f;
        private int count = 1;
        private float spread = 0f;
        private int pierce = 0;
        private float aoeRadius = 0f;
        private int chain = 0;
        private float chainRange = 220f;
        private float chainFalloff = 0.15f;
        private int bounce = 0;

        private float arcRadius = 90f;
        private float arcAngle = (float) Math.toRadians(120);
        private float knockback = 0f;
        private boolean homing = false;

        private float zoneRadius = 0f;
        private float zoneDuration = 0f;
        private float zoneDps = 0f;
        private int zoneElement = Element.NONE;
        private float freeze = 0f;

        private Builder(int id, String name, Form form) {
            this.id = id;
            this.name = name;
            this.form = form;
        }

        public Builder cooldown(float v) { this.cooldown = v; return this; }
        public Builder damage(float v) { this.damage = v; return this; }
        public Builder range(float v) { this.range = v; return this; }
        public Builder element(int v) { this.element = v; return this; }
        public Builder elemPotency(float v) { this.elemPotency = v; return this; }
        public Builder elemDuration(float v) { this.elemDuration = v; return this; }
        public Builder speed(float v) { this.speed = v; return this; }
        public Builder boltRadius(float v) { this.boltRadius = v; return this; }
        public Builder count(int v) { this.count = v; return this; }
        public Builder spread(float v) { this.spread = v; return this; }
        public Builder pierce(int v) { this.pierce = v; return this; }
        public Builder aoeRadius(float v) { this.aoeRadius = v; return this; }
        public Builder chain(int v) { this.chain = v; return this; }
        public Builder chainRange(float v) { this.chainRange = v; return this; }
        public Builder chainFalloff(float v) { this.chainFalloff = v; return this; }
        public Builder bounce(int v) { this.bounce = v; return this; }
        public Builder arcRadius(float v) { this.arcRadius = v; return this; }
        public Builder arcAngle(float v) { this.arcAngle = v; return this; }
        public Builder knockback(float v) { this.knockback = v; return this; }
        public Builder homing() { this.homing = true; return this; }

        public Builder zone(float radius, float duration, float dps, int element) {
            this.zoneRadius = radius;
            this.zoneDuration = duration;
            this.zoneDps = dps;
            this.zoneElement = element;
            return this;
        }
        public Builder freeze(float v) { this.freeze = v; return this; }

        public SpellDef build() {
            return new SpellDef(this);
        }
    }
}
