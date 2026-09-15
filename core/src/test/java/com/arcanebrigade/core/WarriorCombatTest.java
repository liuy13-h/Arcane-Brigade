package com.arcanebrigade.core;

/**
 * 战士战斗逻辑的零依赖单元测试（与 ArenaMapTest 同风格：main + check，不需要 JUnit）。
 *
 * 覆盖战士在战斗画面里会走到的每一条逻辑分支：
 *   1. 职业基础数值（HP / 移速 / 无敌帧）与起手技能「挥砍」的伤害
 *   2. 挥砍是 120° 扇形：正前方命中、背后不命中、扇形内多个敌人一起命中
 *   3. 长按左键的蓄力重击：松手释放时伤害显著高于普通挥砍
 *   4. 空格冲刺：位移距离、冲刺无敌帧、冷却内不能二次冲刺
 *   5. 职业特性：受伤减免 15%、击杀回血 +2
 *
 * 用法：
 *   java -cp core/target/classes:core/target/test-classes com.arcanebrigade.core.WarriorCombatTest
 *
 * 注意：断言单次伤害的用例会先把测试敌人的血量抬到 DUMMY_HP(1000)。默认小怪血量(52)
 * 正好等于挥砍伤害，一刀就死 → 实体槽会被掉落的经验宝石复用，之后的断言就会读到别的实体。
 */
public final class WarriorCombatTest {

    private WarriorCombatTest() {}

    private static final float EPS = 0.01f;
    /** 测试用敌人的血量：远大于一刀伤害，保证挨打后仍存活、可供断言 */
    private static final float DUMMY_HP = 1000f;
    private static int failures;

    public static void main(String[] args) {
        basics();
        slashHitsFrontOnly();
        slashHitsEveryEnemyInArc();
        chargedHeavyAttackHitsHarder();
        dashMovesAndGrantsIFrame();
        dashBlockedDuringCooldown();
        warriorTakesReducedDamage();
        killHealsWarrior();

        System.out.println(failures == 0
                ? "OK：战士战斗逻辑全部检查通过"
                : "FAIL：" + failures + " 项检查未通过");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---------------- 1. 基础数值 ----------------

    private static void basics() {
        check(HeroClass.baseHp(HeroClass.WARRIOR) == 280f,
                "战士基础 HP 应为 280，实际 " + HeroClass.baseHp(HeroClass.WARRIOR));
        check(HeroClass.baseSpeed(HeroClass.WARRIOR) == 190f,
                "战士基础移速应为 190，实际 " + HeroClass.baseSpeed(HeroClass.WARRIOR));
        check(Math.abs(HeroClass.baseIframe(HeroClass.WARRIOR) - 0.12f) < EPS,
                "战士无敌帧应为 0.12s");

        int start = HeroClass.startSpell(HeroClass.WARRIOR);
        check(start == Spells.WARRIOR_SLASH, "战士起手技能应为「挥砍」");
        SpellDef slash = Spells.get(Spells.WARRIOR_SLASH);
        check(slash != null && Math.abs(slash.damage - 52f) < EPS, "挥砍基础伤害应为 52");
        check(slash != null && slash.form == SpellDef.Form.MELEE_ARC,
                "挥砍应为近战扇形（MELEE_ARC）");
    }

    // ---------------- 2. 扇形命中 ----------------

    private static void slashHitsFrontOnly() {
        World w = battleWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.WARRIOR);
        int front = dummy(w, 70f, 0f);
        int back = dummy(w, -70f, 0f);

        swingOnce(w, hero, 1f, 0f);

        check(w.hp[front] < DUMMY_HP, "正前方的敌人应被挥砍命中");
        check(Math.abs(w.hp[back] - DUMMY_HP) < EPS,
                "背后的敌人不应被 120° 扇形命中（实际掉血 " + (DUMMY_HP - w.hp[back]) + "）");
    }

    private static void slashHitsEveryEnemyInArc() {
        World w = battleWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.WARRIOR);
        int a = dummy(w, 70f, 25f);
        int b = dummy(w, 70f, -25f);

        swingOnce(w, hero, 1f, 0f);

        check(w.hp[a] < DUMMY_HP && w.hp[b] < DUMMY_HP,
                "扇形内所有敌人应同时命中（近战无需单体弹道判定）"
                        + " a:掉" + (DUMMY_HP - w.hp[a]) + " / b:掉" + (DUMMY_HP - w.hp[b]));
    }

    // ---------------- 3. 蓄力重击 ----------------

    private static void chargedHeavyAttackHitsHarder() {
        // 普通挥砍
        World normal = battleWorld();
        int heroN = normal.spawnWizard(0f, 0f, HeroClass.WARRIOR);
        int foeN = dummy(normal, 70f, 0f);
        swingOnce(normal, heroN, 1f, 0f);
        float plainDmg = DUMMY_HP - normal.hp[foeN];

        // 蓄力重击：第一帧按住左键并把 charge 拉到 1（进入蓄力、不出手），第二帧松手释放
        World charged = battleWorld();
        int heroC = charged.spawnWizard(0f, 0f, HeroClass.WARRIOR);
        int foeC = dummy(charged, 70f, 0f);
        InputCommand hold = input();
        hold.buttons = InputCommand.BUTTON_FIRE;
        hold.charge = 1f;
        hold.aimX = 100f;
        hold.aimY = 0f;
        charged.step(Balance.FIXED_STEP, hold);
        check(Math.abs(charged.hp[foeC] - DUMMY_HP) < EPS,
                "蓄力期间不应出手（只在松开那一刻释放重击）");

        InputCommand release = input();
        release.aimX = 100f;
        release.aimY = 0f;              // charge=0：松开
        charged.step(Balance.FIXED_STEP, release);
        float chargedDmg = DUMMY_HP - charged.hp[foeC];

        check(plainDmg > 0f, "普通挥砍应造成伤害，实际 " + plainDmg);
        // 满蓄力伤害倍率 2.6；即使普通挥砍暴击(×1.5)而重击未暴击，比值也 > 1.5
        check(chargedDmg > plainDmg * 1.5f,
                "满蓄力重击伤害应显著高于普通挥砍（普通 " + plainDmg + " / 重击 " + chargedDmg + "）");
    }

    // ---------------- 4. 冲刺 ----------------

    private static void dashMovesAndGrantsIFrame() {
        World w = battleWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.WARRIOR);
        float x0 = w.x[hero];

        InputCommand dash = input();
        dash.buttons = InputCommand.BUTTON_DASH;
        dash.aimX = 1000f;              // 冲刺朝鼠标方向
        dash.aimY = 0f;
        w.step(Balance.FIXED_STEP, dash);

        check(w.dashTime[hero] > 0f, "冲刺应进入位移持续状态");
        check(w.iframe[hero] > 0f, "冲刺期间应有无敌帧");
        check(w.dashCd[hero] > 0f, "冲刺后应进入冷却");

        for (int i = 0; i < 20 && w.dashTime[hero] > 0f; i++) {
            w.step(Balance.FIXED_STEP, input());
        }
        float moved = w.x[hero] - x0;
        check(moved > Balance.WARRIOR_DASH_DIST * 0.6f,
                "冲刺位移应接近 " + Balance.WARRIOR_DASH_DIST + "（实际 " + moved + "）");
    }

    private static void dashBlockedDuringCooldown() {
        World w = battleWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.WARRIOR);
        InputCommand dash = input();
        dash.buttons = InputCommand.BUTTON_DASH;
        dash.aimX = 1000f;
        w.step(Balance.FIXED_STEP, dash);
        for (int i = 0; i < 20 && w.dashTime[hero] > 0f; i++) {
            w.step(Balance.FIXED_STEP, input());
        }
        check(w.dashCd[hero] > 0f, "此时应仍在冲刺冷却中");

        float x0 = w.x[hero];
        InputCommand again = input();
        again.buttons = InputCommand.BUTTON_DASH;
        again.aimX = 1000f;
        w.step(Balance.FIXED_STEP, again);
        for (int i = 0; i < 20; i++) {
            w.step(Balance.FIXED_STEP, input());
        }
        check(w.x[hero] - x0 < 5f,
                "冷却未好时再次按空格不应产生位移（实际 " + (w.x[hero] - x0) + "）");
    }

    // ---------------- 5. 职业特性 ----------------

    private static void warriorTakesReducedDamage() {
        World w = battleWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.WARRIOR);
        w.hp[hero] = w.maxHp[hero];
        w.iframe[hero] = 0f;

        w.damage(hero, 100f);

        float taken = w.maxHp[hero] - w.hp[hero];
        // 战士减伤 15%：100 点伤害只吃 85
        check(Math.abs(taken - 85f) < 0.5f,
                "战士应有 15% 减伤（100 伤害实际承受 " + taken + "）");
    }

    private static void killHealsWarrior() {
        World w = battleWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.WARRIOR);
        w.hp[hero] = w.maxHp[hero] - 20f;      // 先掉点血，便于观察回血
        float before = w.hp[hero];

        int foe = w.spawnEnemy(70f, 0f, 0, World.V_NORMAL);
        w.hp[foe] = 1f;                        // 一刀可杀
        swingOnce(w, hero, 1f, 0f);

        check(!w.alive[foe], "残血敌人应被击杀");
        check(w.hp[hero] >= before + Balance.WARRIOR_LIFESTEAL - 0.05f,
                "击杀应回 " + Balance.WARRIOR_LIFESTEAL + " 点血（实际 +"
                        + (w.hp[hero] - before) + "）");
    }

    // ---------------- 工具 ----------------

    /** 关闭自动开火、种子固定的战斗世界：随机（暴击等）在同一 seed 下可复现 */
    private static World battleWorld() {
        World w = new World(20260914L);
        w.setAutoFire(false);
        return w;
    }

    /** 在指定位置放一个"血厚到打不死"的测试敌人，便于观察单次伤害 */
    private static int dummy(World w, float x, float y) {
        int id = w.spawnEnemy(x, y, 0, World.V_NORMAL);
        w.maxHp[id] = DUMMY_HP;
        w.hp[id] = DUMMY_HP;
        return id;
    }

    private static InputCommand input() {
        return new InputCommand();
    }

    /** 手动朝 +x 方向挥砍一次（战士只有手动开火才消费蓄力/定向） */
    private static void swingOnce(World w, int hero, float aimDx, float aimDy) {
        InputCommand in = input();
        in.buttons = InputCommand.BUTTON_FIRE;
        in.aimX = w.x[hero] + aimDx * 100f;
        in.aimY = w.y[hero] + aimDy * 100f;
        w.step(Balance.FIXED_STEP, in);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
