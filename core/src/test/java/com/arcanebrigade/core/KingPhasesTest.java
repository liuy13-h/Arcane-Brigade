package com.arcanebrigade.core;

import java.util.ArrayList;

/**
 * 国王 Boss 三阶段单元测试（零依赖 / 无框架 / 直跑，风格与 ArenaMapTest 一致）。
 *
 * 编译运行（项目根 D:/text1/untitled 下执行）：
 *   javac -encoding UTF-8 -cp core\target\classes -d core\target\test-classes core\src\test\java\com\arcanebrigade\core\KingPhasesTest.java
 *   java -cp "core\target\classes;core\target\test-classes" com.arcanebrigade.core.KingPhasesTest
 * 全部通过退出码 0；存在失败项退出码 1。
 *
 * 覆盖：
 *  一阶段——进场清场/默认属性/站桩；8s 技能（1s 前摇随机走位 / 180 预警圈 / -80 爆炸）；
 *          15s 消耗机制（-10 血 + 失 1 被动）；击破只发决裂信号不结算。
 *  二阶段——转场重生（60000 血 / 回位 / 开场裂隙）；移速惩罚 20；三档追击 250·205·170；
 *          常驻 20% 减伤；贴身接触 40 + 受击无敌帧；30s 长跑（魔弹 5 连 / AOE 圈 / 裂隙刷怪）；
 *          击破只发王座过渡信号。
 *  三阶段——觉醒（120000 血池 / 90% 常态减伤 / 前摇窗口 20%）；传送（3s 节拍 / 落点 ≤50 /
 *          爆发 -30 / 不走路 / 命中回血 200）；地刺与深渊牵引；25s 召唤 Boss；
 *          第二管血分裂双子（共享血池 / 伤害转发 / 传送错开 2s / 分裂仅一次）；清池终局通关。
 */
public final class KingPhasesTest {

    private static final float STEP = Balance.FIXED_STEP;
    /** 全程站桩输入（无移动、无开火） */
    private static final InputCommand STAY = new InputCommand();

    private static int passed = 0;
    private static int failed = 0;

    private KingPhasesTest() {}

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        System.out.println("=== 国王 Boss 三阶段单元测试 ===");

        System.out.println("--- 阶段一 ---");
        phase1EntryStandAndSkill();
        phase1Attrition();
        phase1FallenSignal();

        System.out.println("--- 阶段二 ---");
        phase2TransitionChaseAndPenalty();
        phase2DamageReductionAndContact();
        phase2BoltsZoneRiftAndFallen();

        System.out.println("--- 阶段三 ---");
        phase3AwakenAndDR();
        phase3TeleportAndHeal();
        phase3SpikeAndPull();
        phase3SummonBoss();
        phase3TwinSplitAndOffset();
        phase3FinalKill();

        long ms = System.currentTimeMillis() - t0;
        System.out.println("----------------------------------------------");
        System.out.printf("结果：通过 %d 项，失败 %d 项，耗时 %d ms%n", passed, failed, ms);
        if (failed > 0) {
            System.out.println("存在失败项！");
            System.exit(1);
        }
        System.out.println("全部通过");
    }

    // ------------------------------------------------------------------
    // 阶段一
    // ------------------------------------------------------------------

    /** 进场清场 + 常态站桩 + 8s 技能（1s 前摇走位 / 180 圈 / -80 爆炸）+ 贴身不咬人 */
    private static void phase1EntryStandAndSkill() {
        int[] out = new int[1];
        World w = enterArena(101L, out);
        int wid = out[0];
        int mob = w.spawnEnemy(300f, 0f);
        w.enterKingArena();
        int k = w.kingId();

        check("进场：kingArena / 阶段 1 / 清场（场上仅剩国王，杂兵槽位被复用）/ 不结算",
                w.kingArena() && w.kingPhase() == 1
                && countKind(w, World.KIND_ENEMY) == 1 && w.alive[mob] && !w.victory());
        check("国王生成：30000 血 / 王座前 / r=46 / 无接触伤害", k >= 0
                && Math.abs(w.maxHp[k] - Balance.KING1_HP) < 0.5f
                && dist(w.x[k], w.y[k], Balance.ARENA_KING_X, Balance.ARENA_KING_Y) < 0.5f
                && Math.abs(w.r[k] - Balance.KING1_RADIUS) < 0.5f
                && Math.abs(w.dmg[k]) < 0.01f);
        checkNear("玩家归位殿中 (0,220)",
                dist(w.x[wid], w.y[wid], Balance.ARENA_ENTER_X, Balance.ARENA_ENTER_Y), 0f, 0.5f);

        // 常态贴身一帧：无接触伤害
        w.x[k] = w.x[wid];
        w.y[k] = w.y[wid];
        w.iframe[wid] = 0f;
        float hp0 = w.hp[wid];
        w.step(STEP, STAY);
        checkNear("贴身一帧零掉血（一阶段无接触伤害）", w.hp[wid] - hp0, 0f, 0.5f);
        w.x[k] = Balance.ARENA_KING_X;
        w.y[k] = Balance.ARENA_KING_Y;

        // 站桩期（0~7.9s）：一步不动、无提前施法
        int standFrames = (int) (Balance.KING1_SKILL_CD * 60f) - 8;
        float path1 = 0f;
        int castingEarly = 0;
        for (int i = 0; i < standFrames; i++) {
            float bx = w.x[k];
            float by = w.y[k];
            w.hp[wid] = w.maxHp[wid];
            w.step(STEP, STAY);
            if (w.kingCasting()) {
                castingEarly++;
            }
            path1 += dist(bx, by, w.x[k], w.y[k]);
            if (i == standFrames - 6) {
                // 挪去右上角：前摇走位够不到玩家，爆炸几何确定
                w.x[k] = Balance.ARENA_HALF_X - 80f;
                w.y[k] = Balance.ARENA_TOP;
            }
        }
        check("常态站桩：位移 < 2px 且无提前施法", path1 < 2f && castingEarly == 0);

        // 起手 → 前摇走位 → 回站桩（观测 2.6s）
        boolean castSeen = false;
        boolean castingEnded = false;
        boolean zoneSeen = false;
        float castT = -1f;
        float dodgePath = 0f;
        int dodgeFrames = 0;
        float tailPath = 0f;
        int tailFrames = 0;
        float aoeT = -1f;
        float aoeDelta = 0f;
        float zoneR = 0f;
        float zoneDmg = 0f;
        float zoneX = 0f;
        float zoneY = 0f;
        boolean hadZone = false;
        for (int i = 0; i < 156; i++) {
            float bx = w.x[k];
            float by = w.y[k];
            boolean casting = w.kingCasting();
            w.hp[wid] = w.maxHp[wid];
            float beforeHp = w.hp[wid];
            w.step(STEP, STAY);
            float hpDelta = w.hp[wid] - beforeHp;
            float stp = dist(bx, by, w.x[k], w.y[k]);
            if (casting) {
                if (!castSeen) {
                    castSeen = true;
                    castT = w.time();
                }
                dodgePath += stp;
                if (stp > 0.5f) {
                    dodgeFrames++;
                }
            } else if (castSeen) {
                castingEnded = true;
            }
            if (castingEnded) {
                tailPath += stp;
                tailFrames++;
            }
            boolean zoneNow = false;
            for (int e = 0; e < w.highWater(); e++) {
                if (w.alive[e] && w.kind[e] == World.KIND_ZONE && w.life[e] > 0f) {
                    zoneNow = true;
                    if (!zoneSeen) {
                        zoneSeen = true;
                        zoneR = w.r[e];
                        zoneDmg = w.dmg[e];
                        zoneX = w.x[e];
                        zoneY = w.y[e];
                    }
                }
            }
            if (hadZone && !zoneNow && aoeT < 0f) {
                aoeT = w.time();
                aoeDelta = hpDelta;   // 圈到期帧的掉血 = 爆炸伤害
            }
            hadZone = zoneNow;
        }
        float dodgeSpeed = dodgeFrames > 0 ? dodgePath / (dodgeFrames * STEP) : 0f;

        check("8 秒起手（±0.15s）", castSeen && Math.abs(castT - Balance.KING1_SKILL_CD) < 0.15f);
        checkNear("前摇走位速率 = 175", dodgeSpeed, Balance.KING1_SPEED, 8f);
        check("前摇走位帧数 ≈ 60 且结束后回站桩", dodgeFrames >= 54 && dodgeFrames <= 66
                && tailFrames >= 30 && tailPath < 2f);
        check("预警圈：r=180 / dmg=80 / 圈心在玩家", zoneSeen
                && Math.abs(zoneR - Balance.KING1_SKILL_RADIUS) < 1f
                && Math.abs(zoneDmg - Balance.KING1_SKILL_DAMAGE) < 0.5f
                && dist(zoneX, zoneY, Balance.ARENA_ENTER_X, Balance.ARENA_ENTER_Y) < 3f);
        check("1 秒前摇后 t≈9s 爆炸且 -80", aoeT > 0f
                && Math.abs(aoeT - (Balance.KING1_SKILL_CD + Balance.KING1_SKILL_TELEGRAPH)) < 0.15f
                && Math.abs(aoeDelta + Balance.KING1_SKILL_DAMAGE) < 1.5f);
    }

    /** 默认属性：每 15 秒失去一个被动并损失 10 点生命 */
    private static void phase1Attrition() {
        int[] out = new int[1];
        World w = enterArena(104L, out);
        int wid = out[0];
        Loadout lo = w.loadout(wid);
        boolean gave = lo.addPassive(Passives.POWER_TRAINING);
        w.enterKingArena();
        int passiveBefore = lo.passiveCount();

        float attrT = -1f;
        int passiveAfterAttr = -1;
        int frames = (int) ((Balance.KING_ATTRITION_INTERVAL + 0.4f) * 60f);
        for (int i = 0; i < frames; i++) {
            float beforeHp = w.hp[wid];
            w.step(STEP, STAY);
            float d = w.hp[wid] - beforeHp;
            if (attrT < 0f && d < -9f && d > -11f) {
                attrT = w.time();
                passiveAfterAttr = lo.passiveCount();
            }
        }
        check("15 秒扣 10 血并按顺序失 1 被动", gave
                && attrT > 0f && Math.abs(attrT - Balance.KING_ATTRITION_INTERVAL) < 0.15f
                && passiveAfterAttr == passiveBefore - 1);
    }

    /** 一阶段击破：只发决裂信号（不结算、记录倒地锚点） */
    private static void phase1FallenSignal() {
        int[] out = new int[1];
        World w = enterArena(105L, out);
        w.enterKingArena();
        int k = w.kingId();
        float dx = w.x[k];
        float dy = w.y[k];

        w.kill(k);
        check("击破：决裂信号 / 国王退场 / 不结算 / 倒地锚点", w.kingFallen()
                && w.kingId() < 0 && !w.victory()
                && Math.abs(w.kingDownX() - dx) < 0.5f && Math.abs(w.kingDownY() - dy) < 0.5f);
    }

    // ------------------------------------------------------------------
    // 阶段二
    // ------------------------------------------------------------------

    /** 转场重生（60000 血 / 回位 / 开场裂隙）+ 移速惩罚 20 + 三档追击 250·205·170 */
    private static void phase2TransitionChaseAndPenalty() {
        int[] out = new int[1];
        World w = enterArena(201L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        int k2 = w.kingId();

        boolean riftFx = false;
        for (int e = 0; e < w.highWater(); e++) {
            if (w.alive[e] && w.kind[e] == World.KIND_FX && w.meta[e] == World.FX_RIFT && w.life[e] > 0f) {
                riftFx = true;
            }
        }
        check("转场：阶段 2 / 王座重生(0,-170) / 60000 血 / 决裂信号复位", w.kingPhase() == 2 && k2 >= 0
                && dist(w.x[k2], w.y[k2], Balance.ARENA_KING_X, Balance.ARENA_KING_Y) < 0.5f
                && Math.abs(w.maxHp[k2] - Balance.KING2_HP) < 0.5f
                && !w.kingFallen());
        check("转场：玩家回位 + 开场裂隙 2 只 + 裂隙视觉",
                dist(w.x[wid], w.y[wid], Balance.ARENA_ENTER_X, Balance.ARENA_ENTER_Y) < 0.5f
                && countKind(w, World.KIND_ENEMY) == 1 + Balance.KING2_RIFT_COUNT && riftFx);

        // 移速惩罚：200 - 20 = 180，0.5 秒右移 ≈ 90px
        clearMobs(w);
        InputCommand run = new InputCommand();
        run.set(1f, 0f);
        float x0 = w.x[wid];
        for (int i = 0; i < 30; i++) {
            w.hp[wid] = w.maxHp[wid];
            w.step(STEP, run);
        }
        checkNear("移速惩罚：0.5s 右移 = (200-20)×0.5", w.x[wid] - x0,
                (Balance.WIZARD_SPEED - Balance.KING2_SPEED_PENALTY) * 0.5f, 1.5f);

        // 三档追击（玩家固定殿中）：470 → 250；270 → 205；220 → 170；贴脸仍追
        w.x[wid] = Balance.ARENA_ENTER_X;
        w.y[wid] = Balance.ARENA_ENTER_Y;
        w.x[k2] = 0f; w.y[k2] = -250f;
        float far = oneStep(w, k2);
        w.x[k2] = 0f; w.y[k2] = -50f;
        float mid = oneStep(w, k2);
        w.x[k2] = 0f; w.y[k2] = 0f;
        float near0 = dist(w.x[wid], w.y[wid], w.x[k2], w.y[k2]);
        float near = oneStep(w, k2);
        float near1 = dist(w.x[wid], w.y[wid], w.x[k2], w.y[k2]);
        w.x[k2] = 0f; w.y[k2] = 120f;
        float close0 = dist(w.x[wid], w.y[wid], w.x[k2], w.y[k2]);
        float close = oneStep(w, k2);
        float close1 = dist(w.x[wid], w.y[wid], w.x[k2], w.y[k2]);

        check("追击三档：250 / 205 / 170（px/帧）",
                Math.abs(far - Balance.KING2_SPEED_FAR / 60f) < 0.12f
                && Math.abs(mid - Balance.KING2_SPEED_MID / 60f) < 0.12f
                && Math.abs(near - Balance.KING2_SPEED_NEAR / 60f) < 0.12f);
        check("追击方向朝玩家：近档与贴脸距离均缩短",
                near0 - near1 > 2f && close0 - close1 > 2f
                && Math.abs(close - Balance.KING2_SPEED_NEAR / 60f) < 0.12f);
    }

    /** 20% 减伤 + 贴身接触 40 + 受击无敌帧 */
    private static void phase2DamageReductionAndContact() {
        int[] out = new int[1];
        World w = enterArena(203L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        int k2 = w.kingId();
        clearMobs(w);

        w.hp[k2] = w.maxHp[k2];
        float b0 = w.hp[k2];
        w.damage(k2, 1000f);
        checkNear("二阶段常驻 20% 减伤（1000 → -800）", b0 - w.hp[k2], 1000f * (1f - Balance.KING2_DR), 0.5f);

        w.x[k2] = w.x[wid];
        w.y[k2] = w.y[wid];
        w.iframe[wid] = 0f;
        float c0 = w.hp[wid];
        w.step(STEP, STAY);
        checkNear("贴身接触伤害 = 40", w.hp[wid] - c0, -Balance.KING2_CONTACT_DAMAGE, 0.5f);
        float c1 = w.hp[wid];
        w.step(STEP, STAY);
        checkNear("受击无敌帧内不重复掉血", w.hp[wid] - c1, 0f, 0.5f);
    }

    /** 30 秒长跑：魔弹 5 连（130 速·4s·追踪）/ AOE 圈（150·80·前摇站定）/ 裂隙 5s 刷 2 只；击破 → 王座过渡信号 */
    private static void phase2BoltsZoneRiftAndFallen() {
        int[] out = new int[1];
        World w = enterArena(204L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        int k2 = w.kingId();
        float t0 = w.time();

        int zoneRounds = 0, zoneHits = 0, boltRounds = 0, boltImpacts = 0;
        int maxBolts = 0, boltFlight = 0, riftCount = 0, maxEnemies = 0, castFrames = 0;
        float firstZoneT = -1f, firstBoltT = -1f;
        float zR = 0f, zLife = 0f, zDmg = 0f, zOff = 0f;
        float boltLifeBirth = -1f, boltSpdMin = 1e9f, boltSpdMax = 0f;
        float lastBoltDist = -1f, worstFlight = 0f, castMove = 0f;
        boolean hadZone = false, hadBolt = false, hadRift = false;

        int frames = (int) (30f / STEP);
        for (int i = 0; i < frames; i++) {
            w.hp[wid] = w.maxHp[wid];
            w.iframe[wid] = 0f;
            float beforeHp = w.hp[wid];
            boolean castNow = w.kingCasting();
            float kx0 = w.x[k2], ky0 = w.y[k2];
            w.step(STEP, STAY);
            float d = w.hp[wid] - beforeHp;
            float tRel = w.time() - t0;
            if (castNow) {
                castFrames++;
                castMove += dist(kx0, ky0, w.x[k2], w.y[k2]);
            }

            boolean zoneNow = false;
            for (int e = 0; e < w.highWater(); e++) {
                if (w.alive[e] && w.kind[e] == World.KIND_ZONE && w.life[e] > 0f) {
                    zoneNow = true;
                    if (firstZoneT < 0f) {
                        zR = w.r[e]; zLife = w.life[e]; zDmg = w.dmg[e];
                        zOff = dist(w.x[wid], w.y[wid], w.x[e], w.y[e]);
                    }
                }
            }
            if (zoneNow && !hadZone) {
                zoneRounds++;
                if (firstZoneT < 0f) {
                    firstZoneT = tRel;
                }
            } else if (!zoneNow && hadZone && d <= -42f) {
                zoneHits++;   // 圈到期爆炸（允许叠加贴身啃咬）
            }
            hadZone = zoneNow;

            int boltIdx = -1, boltsNow = 0;
            float boltDist = -1f;
            for (int e = 0; e < w.highWater(); e++) {
                if (w.alive[e] && w.kind[e] == World.KIND_PROJECTILE
                        && w.team[e] == World.TEAM_ENEMY) {   // 只统计敌方弹体（国王魔弹），排除玩家弹幕
                    boltIdx = e;
                    boltsNow++;
                    boltDist = dist(w.x[wid], w.y[wid], w.x[e], w.y[e]);
                    float sp = (float) Math.hypot(w.vx[e], w.vy[e]);
                    boltSpdMin = Math.min(boltSpdMin, sp);
                    boltSpdMax = Math.max(boltSpdMax, sp);
                }
            }
            if (boltsNow > maxBolts) {
                maxBolts = boltsNow;
            }
            if (boltIdx >= 0 && !hadBolt) {
                boltRounds++;
                boltFlight = 0;
                if (firstBoltT < 0f) {
                    firstBoltT = tRel;
                    boltLifeBirth = w.life[boltIdx];
                }
            } else if (boltIdx < 0 && hadBolt) {
                if (boltFlight * STEP > worstFlight) {
                    worstFlight = boltFlight * STEP;
                }
                if (lastBoltDist >= 0f && lastBoltDist < 40f) {
                    boltImpacts++;   // 消失前已贴到玩家：物理命中
                }
            }
            if (boltIdx >= 0) {
                boltFlight++;
                lastBoltDist = boltDist;
            }
            hadBolt = boltIdx >= 0;

            boolean riftNow = false;
            for (int e = 0; e < w.highWater(); e++) {
                if (w.alive[e] && w.kind[e] == World.KIND_FX && w.meta[e] == World.FX_RIFT && w.life[e] > 0f) {
                    riftNow = true;
                }
            }
            if (tRel > 2f && riftNow && !hadRift) {
                riftCount++;
            }
            hadRift = riftNow;
            maxEnemies = Math.max(maxEnemies, countKind(w, World.KIND_ENEMY));
        }

        check("魔弹：首轮 t≈5s / 单轮 5 颗 / 命中 ≥1 / ≥4 轮",
                Math.abs(firstBoltT - (Balance.KING2_BOLT_TRACK + Balance.KING2_BOLT_REST)) < 0.2f
                && maxBolts == Balance.KING2_BOLT_COUNT && boltImpacts >= 1 && boltRounds >= 4);
        check("魔弹：速度 130 / 出生寿命 4s / 最长飞行 ≤ 4s",
                boltSpdMin >= Balance.KING2_BOLT_SPEED - 2f
                && boltSpdMax <= Balance.KING2_BOLT_SPEED + 2f
                && Math.abs(boltLifeBirth - Balance.KING2_BOLT_TRACK) < 0.03f
                && worstFlight <= Balance.KING2_BOLT_TRACK + 0.1f);
        check("AOE 圈：首轮 t≈6s / 150·80·1.5s / 圈心在玩家",
                Math.abs(firstZoneT - Balance.KING2_AOE_CD) < 0.2f
                && Math.abs(zR - Balance.KING2_AOE_RADIUS) < 1f
                && Math.abs(zLife - Balance.KING2_AOE_TELEGRAPH) < 0.05f
                && Math.abs(zDmg - Balance.KING2_AOE_DAMAGE) < 0.5f && zOff < 3f);
        check("AOE 圈：≥4 轮 / 爆炸命中 ≥2 / 前摇站定", zoneRounds >= 4 && zoneHits >= 2
                && castFrames >= 80 && castMove < 0.01f);
        check("裂隙：≥4 道 / 同屏封顶 ≤ 12（10 只 + 国王）",
                riftCount >= 4 && maxEnemies <= Balance.KING2_MAX_ENEMIES + 2);

        // 击破二阶段：只发王座过渡信号
        float dx = w.x[k2], dy = w.y[k2];
        w.kill(k2);
        check("击破二阶段：王座信号 / 退场 / 不结算 / 锚点 / 无重复决裂", w.kingFallen2()
                && w.kingId() < 0 && !w.victory() && !w.kingFallen()
                && Math.abs(w.kingDownX() - dx) < 0.5f && Math.abs(w.kingDownY() - dy) < 0.5f);
    }

    // ------------------------------------------------------------------
    // 阶段三
    // ------------------------------------------------------------------

    /** 觉醒（120000 血池 / 回位 / 开场裂隙 4 只）+ 90% 常态减伤与 20% 前摇窗口 */
    private static void phase3AwakenAndDR() {
        int[] out = new int[1];
        World w = enterArena(301L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        w.kill(w.kingId());
        int before = countKind(w, World.KIND_ENEMY);
        w.beginKingPhase3();
        int k3 = w.kingId();

        check("觉醒：阶段 3 / 王座 (0,-170) / 120000 血池满血", w.kingPhase() == 3 && k3 >= 0
                && dist(w.x[k3], w.y[k3], Balance.ARENA_KING_X, Balance.ARENA_KING_Y) < 0.5f
                && Math.abs(w.maxHp[k3] - Balance.KING3_HP) < 0.5f
                && Math.abs(w.hp[k3] - Balance.KING3_HP) < 0.5f);
        check("觉醒：玩家回位 / 无分身 / 王座信号复位 / 开场裂隙 4 只", w.kingTwinId() < 0
                && !w.kingFallen2()
                && dist(w.x[wid], w.y[wid], Balance.ARENA_ENTER_X, Balance.ARENA_ENTER_Y) < 0.5f
                && countKind(w, World.KIND_ENEMY) - before == 1 + Balance.KING3_RIFT_COUNT);

        // 常态 90% 减伤
        w.hp[k3] = w.maxHp[k3];
        float b0 = w.hp[k3];
        w.damage(k3, 1000f);
        checkNear("常态 90% 减伤（1000 → -100）", b0 - w.hp[k3], 1000f * (1f - Balance.KING3_DR), 0.5f);

        // 等首个传送前摇窗口（t≈3s）：20% 减伤（输出窗口）
        int guard = 400;
        while (guard-- > 0 && w.kingTeleT() <= 0f) {
            w.hp[wid] = w.maxHp[wid];
            w.step(STEP, STAY);
        }
        boolean inCast = w.kingTeleT() > 0f;
        w.hp[k3] = w.maxHp[k3];
        float b1 = w.hp[k3];
        w.damage(k3, 1000f);
        check("前摇窗口出现且 kingCasting 置位", inCast && w.kingCasting());
        checkNear("前摇窗口 20% 减伤（1000 → -800）", b1 - w.hp[k3], 1000f * (1f - Balance.KING3_DR_CAST), 0.5f);

        // 前摇窗口内贴身豁免（用户规则：释放技能时取消碰撞伤害）——
        // 清场 + 接触 cd 清零 + 无敌帧清零：若未豁免，本帧必然咬出 -30
        clearMobs(w);
        w.x[k3] = w.x[wid];
        w.y[k3] = w.y[wid];
        w.cd[k3] = 0f;
        w.iframe[wid] = 0f;
        w.hp[wid] = w.maxHp[wid];
        float c0 = w.hp[wid];
        w.step(STEP, STAY);
        boolean stillCasting = w.kingCasting();
        check("前摇窗口内贴身不咬（碰撞伤害豁免）", stillCasting && w.hp[wid] - c0 > -0.5f);
        w.x[k3] = Balance.ARENA_KING_X;     // 归位王座，继续后续前摇/减伤观测
        w.y[k3] = Balance.ARENA_KING_Y;

        // 前摇结束恢复 90%
        guard = 120;
        while (guard-- > 0 && w.kingTeleT() > 0f) {
            w.hp[wid] = w.maxHp[wid];
            w.step(STEP, STAY);
        }
        w.hp[k3] = w.maxHp[k3];
        float b2 = w.hp[k3];
        w.damage(k3, 1000f);
        checkNear("前摇结束后恢复 90%（1000 → -100）", b2 - w.hp[k3], 1000f * (1f - Balance.KING3_DR), 0.5f);
    }

    /** 传送：3s 节拍 / 落点 ≤50 / 爆发 -30 / 不走路 / 命中回血 200 */
    private static void phase3TeleportAndHeal() {
        int[] out = new int[1];
        World w = enterArena(303L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        w.kill(w.kingId());
        w.beginKingPhase3();
        int k3 = w.kingId();
        w.setAutoFire(false);   // 关掉自动开火：国王血量变化只来自深渊汲取，判伤才干净
        float t0 = w.time();

        int jumps = 0, landingBad = 0, explodeHits = 0, explodeExact = 0;
        int healEvents = 0, healExact = 0, telegraphFrames = 0;
        float firstJumpT = -1f, lastJumpT = -1f, minGap = 1e9f, maxGap = 0f, stray = 0f;
        boolean healBadMultiple = false;

        int frames = (int) (10.5f / STEP);
        for (int i = 0; i < frames; i++) {
            w.hp[wid] = w.maxHp[wid];
            w.iframe[wid] = 0f;
            if (w.hp[k3] > w.maxHp[k3] - 6000f) {
                w.hp[k3] = w.maxHp[k3] - 6000f;   // 血亏重铺：保回血增量干净（向下调整不算回血）
            }
            float kx0 = w.x[k3], ky0 = w.y[k3];
            float php0 = w.hp[wid], khp0 = w.hp[k3];
            float telePre = w.kingTeleT();
            w.step(STEP, STAY);
            clearMobs(w);   // 清小怪保逐帧判伤干净（召唤 Boss 在 25s 后，不在本窗口）
            float deltaP = w.hp[wid] - php0;
            float deltaK = w.hp[k3] - khp0;
            float tRel = w.time() - t0;

            boolean begun = telePre <= 0f && w.kingTeleT() > 0f;
            boolean exploded = telePre > 0f && w.kingTeleT() <= 0f;
            if (telePre > 0f) {
                telegraphFrames++;
            }
            if (begun) {
                jumps++;
                if (firstJumpT < 0f) {
                    firstJumpT = tRel;
                }
                if (lastJumpT > 0f) {
                    minGap = Math.min(minGap, tRel - lastJumpT);
                    maxGap = Math.max(maxGap, tRel - lastJumpT);
                }
                lastJumpT = tRel;
                if (dist(w.x[k3], w.y[k3], w.x[wid], w.y[wid]) > Balance.KING3_TELE_RANGE + 2f) {
                    landingBad++;
                }
            } else {
                stray += dist(kx0, ky0, w.x[k3], w.y[k3]);
            }
            if (exploded) {
                if (deltaP <= -29f) {
                    explodeHits++;
                }
                if (Math.abs(deltaP + Balance.KING3_TELE_DAMAGE) < 1.5f) {
                    explodeExact++;
                }
            }
            if (deltaK > 1f) {
                healEvents++;
                if (Math.abs(deltaK - Balance.KING3_HEAL_HIT) < 1.5f) {
                    healExact++;
                }
                float q = deltaK / Balance.KING3_HEAL_HIT;
                if (Math.abs(q - Math.round(q)) > 0.02f) {
                    healBadMultiple = true;
                }
            }
        }
        check("传送：≥3 次 / 首轮 t≈3s", jumps >= 3 && firstJumpT > 0f
                && Math.abs(firstJumpT - Balance.KING3_TELE_CD) < 0.2f);
        check("传送：落点全在玩家 50 以内", landingBad == 0);
        check("传送：节拍 ≈3s / 前摇共 ≥150 帧", minGap >= Balance.KING3_TELE_CD - 0.15f
                && maxGap <= Balance.KING3_TELE_CD + 0.15f && telegraphFrames >= 150);
        check("传送：不走路（非传送帧零漂移）", stray < 1f);
        check("传送爆发：命中 ≥1 / 干净 -30 ≥1", explodeHits >= 1 && explodeExact >= 1);
        check("命中回血：事件 ≥1 / 增量为 200 整数倍 / 含干净 +200",
                healEvents >= 1 && !healBadMultiple && healExact >= 1);
    }

    /** 地刺（5s 一批：150 内随机落点 / 40 半径 / 1s 前摇 / 30 伤害 / 刺身同位）+ 深渊牵引（1.5s 拉 45px） */
    private static void phase3SpikeAndPull() {
        int[] out = new int[1];
        World w = enterArena(304L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        w.kill(w.kingId());
        w.beginKingPhase3();
        int k3 = w.kingId();
        w.setAutoFire(false);
        clearMobs(w);
        float t0 = w.time();
        float throneX = Balance.ARENA_KING_X, throneY = Balance.ARENA_KING_Y;

        float firstSpikeT = -1f, sTeleR = 0f, sTeleDmg = 0f, sTeleLife = 0f;
        float sTeleX = 0f, sTeleY = 0f, sOff = 1e9f;
        boolean spikeBodySeen = false;
        float bodyGap = 1e9f;
        int pullRounds = 0, pullFrames = 0;
        float firstPullT = -1f, pullDist = 0f;
        boolean pullTowardThrone = true;
        boolean hadPull = false;
        float pullStartX = 0f, pullStartY = 0f, pullEndX = 0f, pullEndY = 0f;

        int frames = (int) (11.5f / STEP);
        for (int i = 0; i < frames; i++) {
            w.hp[wid] = w.maxHp[wid];
            w.iframe[wid] = 0f;
            boolean pullBefore = w.kingPullT() > 0f;
            float px0 = w.x[wid], py0 = w.y[wid];
            w.step(STEP, STAY);
            clearMobs(w);
            float tRel = w.time() - t0;

            // ---- 牵引统计 ----
            if (w.kingPullT() > 0f) {
                pullFrames++;
                float mv = dist(px0, py0, w.x[wid], w.y[wid]);
                pullDist += mv;
                if (!pullBefore) {
                    pullRounds++;
                    if (firstPullT < 0f) {
                        firstPullT = tRel;
                    }
                    pullStartX = px0;
                    pullStartY = py0;
                }
                pullEndX = w.x[wid];
                pullEndY = w.y[wid];
            } else if (pullBefore && !hadPull) {
                // 首轮牵引结束：净位移应朝王座
                float toThrone = (throneX - pullStartX) * (pullEndX - pullStartX)
                        + (throneY - pullStartY) * (pullEndY - pullStartY);
                if (toThrone <= 0f) {
                    pullTowardThrone = false;
                }
            }
            hadPull = w.kingPullT() > 0f;

            // ---- 地刺统计 ----
            for (int e = 0; e < w.highWater(); e++) {
                if (!w.alive[e] || w.kind[e] != World.KIND_ZONE) {
                    continue;
                }
                if (w.meta[e] == World.ZONE_KING_SPIKE_TELE && w.life[e] > 0f) {
                    if (firstSpikeT < 0f) {
                        firstSpikeT = tRel;
                        sTeleR = w.r[e];
                        sTeleDmg = w.dmg[e];
                        sTeleLife = w.life[e];
                        sTeleX = w.x[e];
                        sTeleY = w.y[e];
                        sOff = dist(w.x[wid], w.y[wid], sTeleX, sTeleY);
                    }
                } else if (w.meta[e] == World.ZONE_KING_SPIKE && w.life[e] > 0f && !spikeBodySeen
                        && firstSpikeT > 0f) {
                    spikeBodySeen = true;
                    bodyGap = dist(w.x[e], w.y[e], sTeleX, sTeleY);
                }
            }
        }

        check("地刺：首批 t≈5s / r=40 / dmg=30 / 前摇 1s / 落点在玩家 150 内",
                firstSpikeT > 0f
                && Math.abs(firstSpikeT - Balance.KING3_SPIKE_CD) < 0.25f
                && Math.abs(sTeleR - Balance.KING3_SPIKE_RADIUS) < 1f
                && Math.abs(sTeleDmg - Balance.KING3_SPIKE_DAMAGE) < 0.5f
                && Math.abs(sTeleLife - Balance.KING3_SPIKE_TELEGRAPH) < 0.05f
                && sOff <= Balance.KING3_SPIKE_RANGE + 1f);
        check("地刺：前摇到期原地刺出（刺身与警示同位）",
                spikeBodySeen && bodyGap < 0.5f);
        check("牵引：首轮 t≈5s / ≥2 轮 / 单轮 ≈1.5s（90 帧）",
                firstPullT > 0f && Math.abs(firstPullT - Balance.KING3_PULL_CD) < 0.25f
                && pullRounds >= 2
                && pullFrames / (float) pullRounds >= 80f
                && pullFrames / (float) pullRounds <= 100f);
        check("牵引：单轮净位移 ≈45px（30 速 × 1.5s）且方向朝王座",
                pullTowardThrone
                && pullDist / (float) Math.max(1, pullRounds) >= Balance.KING3_PULL_SPEED * Balance.KING3_PULL_DUR - 7f
                && pullDist / (float) Math.max(1, pullRounds) <= Balance.KING3_PULL_SPEED * Balance.KING3_PULL_DUR + 7f);
    }

    /** 25s 召唤：裂隙爬出 Boss（档位血量 / 52 速 / r=46 / 精英盾 / carry 档位）+ 召唤裂隙视觉 */
    private static void phase3SummonBoss() {
        int[] out = new int[1];
        World w = enterArena(305L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        w.kill(w.kingId());
        w.beginKingPhase3();
        int k3 = w.kingId();
        w.setAutoFire(false);
        float t0 = w.time();

        float firstBossT = -1f;
        boolean bossStatsOk = false;
        boolean bossDetailOk = false;
        boolean riftFxAtSummon = false;
        int bossId = -1;

        int frames = (int) (26.5f / STEP);
        for (int i = 0; i < frames && firstBossT < 0f; i++) {
            w.hp[wid] = w.maxHp[wid];
            w.iframe[wid] = 0f;
            w.step(STEP, STAY);
            float tRel = w.time() - t0;

            // 找召唤出的 Boss（V_BOSS 且非王座系）
            for (int e = 0; e < w.highWater(); e++) {
                if (!w.alive[e] || w.kind[e] != World.KIND_ENEMY || w.variant[e] != World.V_BOSS
                        || e == k3 || e == w.kingTwinId()) {
                    continue;
                }
                firstBossT = tRel;
                bossId = e;
                break;
            }
            if (firstBossT >= 0f) {
                // 召唤瞬间应有裂隙视觉
                for (int e = 0; e < w.highWater(); e++) {
                    if (w.alive[e] && w.kind[e] == World.KIND_FX
                            && w.meta[e] == World.FX_RIFT && w.life[e] > 0f) {
                        riftFxAtSummon = true;
                        break;
                    }
                }
                // 档位数值核对
                int tier = -1;
                for (int t = 0; t < Balance.BOSS_HP_TIERS.length; t++) {
                    if (Math.abs(w.maxHp[bossId] - Balance.BOSS_HP_TIERS[t]) < 0.5f) {
                        tier = t;
                        break;
                    }
                }
                bossStatsOk = tier >= 0
                        && Math.abs(w.speed[bossId] - Balance.BOSS_SPEED) < 0.5f
                        && Math.abs(w.r[bossId] - Balance.BOSS_RADIUS) < 0.5f;
                bossDetailOk = tier >= 0
                        && Math.abs(w.dmg[bossId] - Balance.BOSS_DMG_TIERS[tier]) < 0.5f
                        && w.carry[bossId] == tier
                        && Math.abs(w.enemyShield[bossId] - Balance.ELITE_SHIELD * (1f + tier * 0.6f)) < 0.5f;
            } else {
                clearMobs(w);   // 尚未召唤：清杂兵，保证召唤时同屏不封顶
            }
        }

        check("召唤：25s 首只 Boss 落位（±0.3s）且带裂隙视觉",
                firstBossT > 0f && Math.abs(firstBossT - Balance.KING3_SUMMON_CD) < 0.3f
                && riftFxAtSummon);
        check("召唤：Boss 数值 = 档位血池 / 52 速 / r=46", bossStatsOk);
        check("召唤：Boss 细节 = 档位伤害 / carry 档位 / 精英盾 120×(1+0.6×tier)", bossDetailOk);
    }

    /** 第二管血分裂：双子共享血池 / 伤害转发 / 传送错开 2s / 分裂仅一次 */
    private static void phase3TwinSplitAndOffset() {
        int[] out = new int[1];
        World w = enterArena(306L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        w.kill(w.kingId());
        w.beginKingPhase3();
        int k3 = w.kingId();
        w.setAutoFire(false);
        clearMobs(w);

        // 打空第一管血 → 触发分裂
        w.hp[k3] = Balance.KING3_HP_PER_BAR - 100f;
        w.step(STEP, STAY);
        int twin = w.kingTwinId();
        check("分裂：血池 ≤ 单管即裂出分身（76px 处 / 满血池上限 / 出生同血）", twin >= 0
                && Math.abs(w.maxHp[twin] - Balance.KING3_HP) < 0.5f
                && Math.abs(w.hp[twin] - w.hp[k3]) < 0.5f
                && Math.abs(dist(w.x[twin], w.y[twin], w.x[k3], w.y[k3]) - 76f) < 3f);

        // 伤害转发：打分身 → 本体血池按三阶段减伤承伤；下一步分身血与本体同步
        w.hp[k3] = w.maxHp[k3];
        w.hp[twin] = w.hp[k3];
        float before = w.hp[k3];
        w.damage(twin, 1000f);
        checkNear("共享血池：打分身 1000 → 本体 -100（90% 减伤）", before - w.hp[k3],
                1000f * (1f - Balance.KING3_DR), 0.5f);
        w.step(STEP, STAY);
        checkNear("血池同步：一帧后分身血 = 本体血", w.hp[twin] - w.hp[k3], 0f, 0.5f);

        // 传送错开：观测 8s，收集本体 / 分身各自的前摇起点
        ArrayList<Float> bodyStarts = new ArrayList<Float>();
        ArrayList<Float> twinStarts = new ArrayList<Float>();
        float t0 = w.time();
        int frames = (int) (11.5f / STEP);
        for (int i = 0; i < frames; i++) {
            w.hp[wid] = w.maxHp[wid];   // 保活：玩家被磨死会让传送/分身行为全部停摆
            w.iframe[wid] = 0f;
            boolean bodyBefore = w.kingTeleT() > 0f;
            boolean twinBefore = w.kingTwinTeleT() > 0f;
            w.step(STEP, STAY);
            float tRel = w.time() - t0;
            if (!bodyBefore && w.kingTeleT() > 0f) {
                bodyStarts.add(tRel);
            }
            if (!twinBefore && w.kingTwinTeleT() > 0f) {
                twinStarts.add(tRel);
                // 分身落点承诺：玩家 50 以内
                if (dist(w.x[w.kingTwinId()], w.y[w.kingTwinId()], w.x[wid], w.y[wid])
                        > Balance.KING3_TELE_RANGE + 2f) {
                    twinStarts.add(-1f);   // 落点违规标记
                }
            }
        }
        boolean twinJumps = !twinStarts.isEmpty() && twinStarts.get(0) > 0f;
        boolean offsetOk = twinJumps;
        for (float tt : twinStarts) {
            if (tt < 0f) {
                offsetOk = false;   // 落点违规
                continue;
            }
            float lastBody = -1f;
            for (float tb : bodyStarts) {
                if (tb <= tt) {
                    lastBody = tb;
                }
            }
            if (lastBody < 0f || Math.abs(tt - lastBody - Balance.KING3_TWIN_TELE_DELAY) > 0.4f) {
                offsetOk = false;
            }
        }
        // 任意两次起跳（本体或分身）间隔 ≥ 0.9s：两尊从不同时瞬移
        ArrayList<Float> all = new ArrayList<Float>();
        all.addAll(bodyStarts);
        all.addAll(twinStarts);
        all.sort(null);
        float minGap = 1e9f;
        for (int i = 1; i < all.size(); i++) {
            minGap = Math.min(minGap, all.get(i) - all.get(i - 1));
        }
        check("双子传送：分身起跳 ≥2 次且恒在本体起跳后 ≈2s / 落点 ≤50", twinJumps
                && twinStarts.size() >= 2 && offsetOk);
        if (!twinJumps || twinStarts.size() < 2 || !offsetOk) {
            System.out.println("  [诊断] bodyStarts=" + bodyStarts + " twinStarts=" + twinStarts);
        }
        check("双子传送：起跳节拍错开（任意两次起跳间隔 ≥0.9s）", all.size() >= 3 && minGap >= 0.9f);

        // 分裂仅一次：杀掉分身后不再重生
        int twin2 = w.kingTwinId();
        w.kill(twin2);
        for (int i = 0; i < 60; i++) {
            w.step(STEP, STAY);
        }
        check("分裂仅一次：分身被杀后血池再低也不重生", w.kingTwinId() < 0 && w.kingId() >= 0);
    }

    /** 清池终局：先杀分身不结算 → 血池彻底打空 → 通关 */
    private static void phase3FinalKill() {
        int[] out = new int[1];
        World w = enterArena(307L, out);
        int wid = out[0];
        w.enterKingArena();
        w.kill(w.kingId());
        w.beginKingPhase2();
        w.kill(w.kingId());
        w.beginKingPhase3();
        int k3 = w.kingId();
        clearMobs(w);

        w.hp[k3] = Balance.KING3_HP_PER_BAR - 100f;
        w.step(STEP, STAY);
        int twin = w.kingTwinId();
        check("终局前置：分裂已发生", twin >= 0);

        w.kill(twin);   // 先杀分身：共享血池还在，不结算
        check("先杀分身：不结算 / 分身清位 / 本体仍在",
                !w.victory() && w.kingTwinId() < 0 && w.kingId() >= 0);
        for (int i = 0; i < 60; i++) {
            w.hp[wid] = w.maxHp[wid];
            w.step(STEP, STAY);
        }
        check("分裂守卫：分身不重生", w.kingTwinId() < 0 && w.kingId() >= 0);

        w.kill(w.kingId());   // 血池彻底清空：真通关
        check("清池终局：victory 置位 / 国王与分身均退场",
                w.victory() && w.kingId() < 0 && w.kingTwinId() < 0);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 建世界 + 生成一名法师玩家，返回世界；out[0] = 玩家实体 id */
    private static World enterArena(long seed, int[] out) {
        World w = new World(seed);
        out[0] = w.spawnWizard(0f, 0f, HeroClass.WIZARD);
        return w;
    }

    /** 统计场上某类实体的存活数 */
    private static int countKind(World w, int kind) {
        int c = 0;
        for (int e = 0; e < w.highWater(); e++) {
            if (w.alive[e] && w.kind[e] == kind) {
                c++;
            }
        }
        return c;
    }

    /** 清掉所有非王座系魔物（走 kill 正常通道，计数一致） */
    private static void clearMobs(World w) {
        int k = w.kingId();
        int t = w.kingTwinId();
        for (int e = 0; e < w.highWater(); e++) {
            if (w.alive[e] && w.kind[e] == World.KIND_ENEMY && e != k && e != t) {
                w.kill(e);
            }
        }
    }

    /** 推进一帧并返回实体 id 的位移距离 */
    private static float oneStep(World w, int id) {
        float bx = w.x[id];
        float by = w.y[id];
        w.step(STEP, STAY);
        return dist(bx, by, w.x[id], w.y[id]);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[通过] " + name);
        } else {
            failed++;
            System.out.println("[失败] " + name);
        }
    }

    private static void checkNear(String name, float actual, float expected, float tol) {
        check(name + "（实际 " + actual + "，期望 " + expected + "±" + tol + "）",
                Math.abs(actual - expected) <= tol);
    }
}
