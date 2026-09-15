package com.arcanebrigade.core;

/**
 * 弓箭手职业回归测试（无外部依赖，与 ArenaMapTest 同风格）。
 *
 * 覆盖：
 *   1. 基础属性：HP / 移速最快 / 暴击率 +10% / 起手武器为穿透箭
 *   2. 自动开火：范围内有目标才开火，箭矢造成伤害；无目标不出手
 *   3. 穿透：单支箭命中直线上两个目标造成相同伤害，第三个目标不受影响
 *   4. 冲刺：3 发充能、每发消耗 1 颗、耗尽后空按无效、按 6.5s/发 回充
 *   5. 冲刺位移：朝瞄准方向位移约 160px，位移期间带无敌帧
 *   6. 职业边界：法师 / 召唤师没有冲刺
 *
 * 所有战斗都摆在核心出生点正北（+Y 轴）直线上：该轴线上没有障碍物、
 * 陷阱与减速地形（掩体在 (-190,25) 与 (205,-100)，陷阱在 (-210,40) / (260,-5)）。
 */
public final class ArcherTest {

    public static void main(String[] args) {
        testBaseStats();
        testAutoFire();
        testArrowPierce();
        testDashChargesAndCooldown();
        testDashMovementAndIframe();
        testNoDashForOtherClasses();
        System.out.println("OK: ArcherTest");
    }

    // ------------------------------------------------------------------
    // 1. 基础属性
    // ------------------------------------------------------------------
    private static void testBaseStats() {
        World w = newWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.ARCHER);

        check(w.maxHp[hero] == Balance.ARCHER_HP,
                "弓箭手基础血量必须是 " + Balance.ARCHER_HP + "，实际 " + w.maxHp[hero]);
        check(w.hp[hero] == w.maxHp[hero], "出生时必须是满血");

        check(HeroClass.baseSpeed(HeroClass.ARCHER) == Balance.ARCHER_SPEED,
                "弓箭手移速必须读 Balance.ARCHER_SPEED");
        float archerSpeed = HeroClass.baseSpeed(HeroClass.ARCHER);
        for (int cls = 1; cls < HeroClass.COUNT; cls++) {
            if (cls == HeroClass.ARCHER) continue;
            check(archerSpeed > HeroClass.baseSpeed(cls),
                    "弓箭手必须移速最快（" + archerSpeed + " vs 职业 " + cls
                            + " 的 " + HeroClass.baseSpeed(cls) + "）");
        }

        check(Math.abs(w.loadout(hero).stats.critChance - Balance.ARCHER_CRIT) < 1e-4f,
                "弓箭手基础暴击率必须是 " + Balance.ARCHER_CRIT + "，实际 "
                        + w.loadout(hero).stats.critChance);

        Loadout lo = w.loadout(hero);
        check(lo.resolvedSpell(0) == Spells.ARCHER_ARROW,
                "弓箭手起手武器必须是穿透箭（第 0 槽）");
        SpellDef arrow = Spells.get(lo.resolvedSpell(0));
        check(Math.abs(arrow.cooldown - 0.45f) < 1e-4f && arrow.damage == 50f,
                "箭矢应为 0.45s CD / 50 伤害，实际 " + arrow.cooldown + "s / " + arrow.damage);
        check(arrow.pierce == 1, "箭矢必须天生穿透 1 个目标，实际 " + arrow.pierce);
        check(arrow.speed == 700f && arrow.range == 760f,
                "箭矢应为 700 速 / 760 射程，实际 " + arrow.speed + " / " + arrow.range);

        check(HeroClass.baseIframe(HeroClass.ARCHER) == Balance.ARCHER_IFRAME,
                "弓箭手受击无敌帧必须读 Balance.ARCHER_IFRAME");
    }

    // ------------------------------------------------------------------
    // 2. 自动开火
    // ------------------------------------------------------------------
    private static void testAutoFire() {
        // 2a. 范围内有目标：自动开火并造成伤害
        World w = newWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.ARCHER);
        int statue = w.spawnEnemy(0f, 200f, 0, World.V_STATUE);
        for (int i = 0; i < 30; i++) w.step(Balance.FIXED_STEP, new InputCommand());
        check(w.hp[statue] < w.maxHp[statue],
                "范围内有目标时弓箭手必须自动开火并造成伤害（目标血量未下降）");
        check(w.alive[hero], "站桩输出期间玩家不应受伤死亡（雕像是静态靶）");

        // 2b. 无目标：不出手、不产生投射物
        World empty = newWorld();
        empty.spawnWizard(0f, 0f, HeroClass.ARCHER);
        for (int i = 0; i < 30; i++) empty.step(Balance.FIXED_STEP, new InputCommand());
        check(countProjectiles(empty) == 0, "范围内无目标时弓箭手不得出手");
    }

    // ------------------------------------------------------------------
    // 3. 穿透：一支箭打穿直线上的两个目标
    // ------------------------------------------------------------------
    private static void testArrowPierce() {
        World w = newWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.ARCHER);
        w.setAutoFire(false);     // 手动模式：只放一箭，排除后续箭矢干扰
        int s1 = w.spawnEnemy(0f, 200f, 0, World.V_STATUE);
        int s2 = w.spawnEnemy(0f, 420f, 0, World.V_STATUE);
        int s3 = w.spawnEnemy(0f, 640f, 0, World.V_STATUE);

        InputCommand fire = new InputCommand();
        fire.aimX = 0f;
        fire.aimY = 1000f;        // 朝正北瞄准
        fire.buttons |= InputCommand.BUTTON_FIRE;
        w.step(Balance.FIXED_STEP, fire);          // 单帧扣扳机：只出手一次
        for (int i = 0; i < 90; i++) w.step(Balance.FIXED_STEP, new InputCommand());

        float loss1 = w.maxHp[s1] - w.hp[s1];
        float loss2 = w.maxHp[s2] - w.hp[s2];
        float loss3 = w.maxHp[s3] - w.hp[s3];
        check(loss1 > 0f, "第一目标必须被箭矢命中（掉血 " + loss1 + "）");
        check(Math.abs(loss1 - loss2) < 0.01f,
                "同一支穿透箭必须对两个目标造成相同伤害（" + loss1 + " vs " + loss2 + "）");
        check(loss3 == 0f,
                "穿透 1 次后箭矢必须消失，第三目标不得掉血（实际掉血 " + loss3 + "）");
        check(loss1 > 40f, "命中伤害应接近基础 50（暴击更高），实际 " + loss1);
    }

    // ------------------------------------------------------------------
    // 4. 冲刺充能与冷却回充
    // ------------------------------------------------------------------
    private static void testDashChargesAndCooldown() {
        World w = newWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.ARCHER);

        check(w.dashChargesOf(hero) == Balance.ARCHER_DASH_MAX,
                "弓箭手出生必须满 3 发冲刺充能");
        check(w.dashCdRemain(hero) == 0f && w.dashCdTotal(hero) == Balance.ARCHER_DASH_CD,
                "就绪时 dashCdRemain=0，dashCdTotal 必须是 " + Balance.ARCHER_DASH_CD + "s");

        // 连冲三发，每次等位移结束后再冲
        for (int n = 0; n < 3; n++) {
            dashNorth(w);
            check(w.dashChargesOf(hero) == Balance.ARCHER_DASH_MAX - 1 - n,
                    "第 " + (n + 1) + " 次冲刺后剩余充能应为 " + (Balance.ARCHER_DASH_MAX - 1 - n)
                            + "，实际 " + w.dashChargesOf(hero));
            for (int i = 0; i < 12; i++) w.step(Balance.FIXED_STEP, new InputCommand());
        }
        check(w.dashCdRemain(hero) > 0f && w.dashCdRemain(hero) <= Balance.ARCHER_DASH_CD,
                "耗尽后 dashCdRemain 必须在 (0, " + Balance.ARCHER_DASH_CD + "] 区间");

        // 第 4 次：无充能不得位移
        float xBefore = w.x[hero], yBefore = w.y[hero];
        InputCommand spam = new InputCommand();
        spam.aimX = 0f; spam.aimY = 1000f;
        spam.buttons |= InputCommand.BUTTON_DASH;
        for (int i = 0; i < 30; i++) w.step(Balance.FIXED_STEP, spam);
        check(Math.abs(w.x[hero] - xBefore) < 0.5f && Math.abs(w.y[hero] - yBefore) < 0.5f,
                "充能耗尽后按冲刺不得位移");

        // 回充：每 6.5s 回 1 发，直到满 3
        for (int expect = 1; expect <= Balance.ARCHER_DASH_MAX; expect++) {
            stepFrames(w, (int) Math.ceil(Balance.ARCHER_DASH_CD / Balance.FIXED_STEP) + 5);
            check(w.dashChargesOf(hero) == expect,
                    "回充后充能应为 " + expect + "，实际 " + w.dashChargesOf(hero));
            if (expect < Balance.ARCHER_DASH_MAX) {
                float remain = w.dashCdRemain(hero);
                check(remain > 0f && remain < Balance.ARCHER_DASH_CD,
                        "未满发时下一发必须仍在充能（remain=" + remain + "）");
            }
        }
        check(w.dashCdRemain(hero) == 0f, "满发时 dashCdRemain 必须归 0");

        // 充能中途的剩余秒数应随时间递减
        dashNorth(w);
        float remainNow = w.dashCdRemain(hero);
        check(remainNow > Balance.ARCHER_DASH_CD - 0.5f,
                "刚冲刺完 dashCdRemain 应接近满值 " + Balance.ARCHER_DASH_CD + "，实际 " + remainNow);
        stepFrames(w, 60);
        check(w.dashCdRemain(hero) < remainNow - 0.5f,
                "充能剩余秒数必须随时间下降（" + remainNow + " → " + w.dashCdRemain(hero) + "）");
    }

    // ------------------------------------------------------------------
    // 5. 冲刺位移与无敌帧
    // ------------------------------------------------------------------
    private static void testDashMovementAndIframe() {
        World w = newWorld();
        int hero = w.spawnWizard(0f, 0f, HeroClass.ARCHER);

        InputCommand dash = new InputCommand();
        dash.aimX = 0f; dash.aimY = 1000f;
        dash.buttons |= InputCommand.BUTTON_DASH;
        w.step(Balance.FIXED_STEP, dash);

        check(w.dashTime[hero] > 0f, "冲刺当帧必须进入位移状态");
        check(w.iframe[hero] > 0.1f,
                "冲刺必须附带约 " + Balance.ARCHER_DASH_IFRAME + "s 无敌帧，实际 " + w.iframe[hero]);

        // 位移结束后应沿瞄准方向移动约 ARCHER_DASH_DIST（北向轴线上无障碍）。
        // 位移按 60Hz 离散帧积分：0.16s ≈ 10 帧结束，实际约 166px，容差取 10。
        for (int i = 0; i < 15; i++) w.step(Balance.FIXED_STEP, new InputCommand());
        float moved = Math.abs(w.y[hero]);
        check(Math.abs(moved - Balance.ARCHER_DASH_DIST) < 10f,
                "冲刺位移必须约 " + Balance.ARCHER_DASH_DIST + "px，实际 " + moved);
        check(Math.abs(w.x[hero]) < 1f, "朝正北冲刺不得产生横向位移");
    }

    // ------------------------------------------------------------------
    // 6. 职业边界：法师 / 召唤师没有冲刺
    // ------------------------------------------------------------------
    private static void testNoDashForOtherClasses() {
        for (int cls : new int[]{HeroClass.WIZARD, HeroClass.SUMMONER}) {
            World w = newWorld();
            int hero = w.spawnWizard(0f, 0f, cls);
            InputCommand dash = new InputCommand();
            dash.aimX = 0f; dash.aimY = 1000f;
            dash.buttons |= InputCommand.BUTTON_DASH;
            for (int i = 0; i < 10; i++) w.step(Balance.FIXED_STEP, dash);
            check(Math.abs(w.y[hero]) < 1f,
                    "职业 " + cls + " 不应有冲刺位移（实际 y=" + w.y[hero] + "）");
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private static World newWorld() {
        World w = new World(2026_0914L, ArenaMap.DESERT_RUINS);
        w.setSpawningEnabled(false);   // 关闭世界刷怪，保证战斗场景完全确定
        return w;
    }

    /** 朝正北空格冲刺一帧（瞄准点压在正北远处） */
    private static void dashNorth(World w) {
        InputCommand in = new InputCommand();
        in.aimX = 0f;
        in.aimY = 1000f;
        in.buttons |= InputCommand.BUTTON_DASH;
        w.step(Balance.FIXED_STEP, in);
    }

    private static void stepFrames(World w, int n) {
        for (int i = 0; i < n; i++) w.step(Balance.FIXED_STEP, new InputCommand());
    }

    private static int countProjectiles(World w) {
        int n = 0;
        for (int id = 0; id < w.highWater(); id++) {
            if (w.alive[id] && w.kind[id] == World.KIND_PROJECTILE) n++;
        }
        return n;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
