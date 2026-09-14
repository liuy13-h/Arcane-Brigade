package com.arcanebrigade.core;

/**
 * 无界面冒烟测试 + 性能压测 + 元素反应验证 + D3 升级链路验证。
 *
 * 这个 main 能跑起来，就证明 core 层没有偷偷依赖任何 UI 框架——
 * 这是 core 层保持 UI 无关的前提。同时给出每帧模拟耗时，用来盯性能回退。
 *
 * 用法：
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数]            默认对局（三系法术）
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数] stress     满载压力
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数] melee      近战扇形分支
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数] react      元素反应定向验证
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数] upgrade    D3 升级链路：自动选升级跑 N 秒
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数] stage      D4 场景/障碍/阶段/Boss：越阶段边界 + 显式刷 Boss
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数] classes   四职业：各生成法师/战士/弓箭手/召唤师，验证技能开火、抽卡与宠物
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke 0 king           王宫决战：一/二/三阶段全链（转场/走位/魔弹/AOE/裂隙→减伤/传送/地刺/牵引/召唤，时序固定）
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数] debug      逐步诊断
 */
public final class HeadlessSmoke {

    private HeadlessSmoke() {}

    /**
     * 压测用的敌人数量。刻意与 Balance.MAX_ENEMIES 解耦：
     * MAX_ENEMIES 是平衡数值（会随玩法调小），压测量要保证足够盯住性能回退。
     */
    private static final int STRESS_ENEMIES = 900;

    public static void main(String[] args) {
        String mode = args.length > 1 ? args[1] : "play";
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : 3600;
        // 场景模式默认跑够越过至少一次阶段边界（300s = 18000 帧）
        if ("stage".equalsIgnoreCase(mode) && frames < 18060) {
            frames = 18060;
        }

        if ("react".equalsIgnoreCase(mode)) {
            verifyReactions();
            return;
        }

        World w = new World(12345L);
        int wid = w.spawnWizard(0f, 0f);

        if ("stage".equalsIgnoreCase(mode)) {
            runStage(w, frames, wid);
            return;
        }

        if ("upgrade".equalsIgnoreCase(mode)) {
            runUpgradePath(frames, wid, w);
            return;
        }

        if ("classes".equalsIgnoreCase(mode)) {
            runClasses(frames);
            return;
        }

        if ("king".equalsIgnoreCase(mode)) {
            runKing();
            return;
        }
        // 压测专用：血池拉到极大，避免玩家阵亡打断观察（真实对局的生死交给 iframe + 复活令牌）
        w.maxHp[wid] = 1_000_000f;
        w.hp[wid] = 1_000_000f;
        InputCommand in = new InputCommand();

        if ("melee".equalsIgnoreCase(mode)) {
            // 只带近战，验证 MELEE_ARC 这条分支（战士 D4 正式接入）
            w.setSpell(wid, 0, Spells.WARRIOR_SLASH);
            w.setSpell(wid, 1, Spells.NONE);
            w.setSpell(wid, 2, Spells.NONE);
        } else {
            // 火 + 冰 + 雷：三种反应都能在实战里自然触发
            w.setSpell(wid, 0, Spells.FIREBALL);
            w.setSpell(wid, 1, Spells.ICE_SHARD);
            w.setSpell(wid, 2, Spells.CHAIN_LIGHTNING);
        }

        if ("stress".equalsIgnoreCase(mode)) {
            fill(w, STRESS_ENEMIES);
            // 关键：满载压测必须让这 900 只活着。否则法术几秒就把它们清光，
            // 测出来的是"空场跑循环"的假数据，完全盯不住性能回退。
            // 压测量与玩法上限解耦：MAX_ENEMIES 是平衡数值，不该因为它调小
            // 就把性能回归测试也一起削弱。
            makeUndying(w);
        }

        if ("debug".equalsIgnoreCase(mode)) {
            for (int i = 0; i < frames; i++) {
                in.set(1f, 0f);
                w.hp[wid] = w.maxHp[wid];
                w.step(Balance.FIXED_STEP, in);
                if (i % 30 == 0) {
                    int t = w.nearestEnemy(w.x[wid], w.y[wid], 5000f);
                    float near = t >= 0
                            ? (float) Math.hypot(w.x[t] - w.x[wid], w.y[t] - w.y[wid]) : -1f;
                    System.out.printf("  f=%4d t=%5.2fs 玩家(%.0f,%.0f) hp=%.0f 最近敌人=%6.0f 投射物=%d 敌人=%d 击杀=%d%n",
                            i, w.time(), w.x[wid], w.y[wid], w.hp[wid], near,
                            countKind(w, World.KIND_PROJECTILE), w.enemyCount(), w.killCount());
                }
            }
            return;
        }

        // 预热，让 JIT 和对象池进入稳态
        for (int i = 0; i < 600; i++) {
            in.set(1f, 0f);
            w.step(Balance.FIXED_STEP, in);
        }
        long warmEnemies = w.enemyCount();

        System.out.println("stress".equalsIgnoreCase(mode) ? "时间轴（满载压力）：" : "时间轴（每 10 秒一行）：");
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            // 模拟被围时的走位：基准方向缓慢旋转 + 每半秒折返，净位移接近 0。
            // 别写成匀速绕大圈——那样敌人永远追不上，测出来的是假数据。
            double base = i * 0.004;
            double flip = ((i / 30) & 1) == 0 ? 1.0 : -1.0;
            in.set((float) (Math.cos(base) * flip), (float) (Math.sin(base) * flip));
            // 无敌：压测只关心刷怪节奏与模拟开销，不想被"玩家死亡"提前打断
            w.hp[wid] = w.maxHp[wid];
            w.step(Balance.FIXED_STEP, in);
            if ((i + 1) % 600 == 0) {
                System.out.printf("  t=%5.1fs  场上敌人 %4d  投射物 %3d  特效 %3d  累计击杀 %5d  存活实体 %4d%n",
                        w.time(), w.enemyCount(), countKind(w, World.KIND_PROJECTILE),
                        countKind(w, World.KIND_FX), w.killCount(), w.liveCount());
            }
        }
        long t1 = System.nanoTime();

        double totalMs = (t1 - t0) / 1e6;
        double perFrame = totalMs / frames;
        double budget = Balance.FIXED_STEP * 1000.0;

        System.out.println("=== core headless 模拟压测 ===");
        System.out.printf("模式：%s，模拟 %d 帧 = %.1f 秒游戏时间%n",
                mode, frames, frames * Balance.FIXED_STEP);
        System.out.printf("耗时 %.0f ms，平均每帧 %.3f ms（帧预算 %.2f ms，占比 %.1f%%）%n",
                totalMs, perFrame, budget, perFrame / budget * 100);
        System.out.printf("预热后敌人 %d，结束敌人 %d（压测量 %d，玩法上限 %d）%n",
                warmEnemies, w.enemyCount(), STRESS_ENEMIES, Balance.MAX_ENEMIES);
        System.out.printf("存活实体 %d，累计击杀 %d，池高水位 %d / %d%n",
                w.liveCount(), w.killCount(), w.highWater(), World.MAX);

        printReactions(w);

        if (perFrame > budget * 0.35) {
            System.out.println("!! 警告：模拟已吃掉超过 35% 的帧预算，留给渲染的不多了");
        } else {
            System.out.println("OK：模拟开销健康");
        }
    }

    private static void printReactions(World w) {
        System.out.println("元素反应触发次数：");
        System.out.printf("  蒸汽爆发（燃烧+冰霜） %d%n", w.reactionCount(Element.R_STEAM));
        System.out.printf("  超导    （冰冻+雷电） %d%n", w.reactionCount(Element.R_SUPERCONDUCT));
        System.out.printf("  过载    （燃烧+雷电） %d%n", w.reactionCount(Element.R_OVERLOAD));
    }

    /**
     * 定向验证三种元素反应。不依赖随机战斗——直接构造元素附着，
     * 这样"反应到底有没有接线"这件事是确定的，而不是"打了 60 秒好像没看到"。
     */
    private static void verifyReactions() {
        World w = new World(777L);
        int caster = w.spawnWizard(0f, 0f);

        int[] victims = new int[4];
        for (int i = 0; i < victims.length; i++) {
            int e = w.spawnEnemy(200f + i * 40f, 0f);
            w.maxHp[e] = 100_000f;
            w.hp[e] = 100_000f;
            victims[i] = e;
        }

        // 先让世界推进一帧，把敌人塞进空间哈希，否则下面的定向施法找不到目标
        w.step(Balance.FIXED_STEP, new InputCommand());

        int steam = victims[0];
        int superC = victims[1];
        int over = victims[2];
        int deto = victims[3];

        // 燃烧 + 冰霜 -> 蒸汽爆发
        w.applyElement(steam, Element.FIRE, 6f, 3f, 20f);
        w.applyElement(steam, Element.FROST, 0.35f, 2f, 20f);

        // 冰冻 + 雷电 -> 超导
        w.applyElement(superC, Element.FROST, 0.35f, 2f, 20f);
        w.applyElement(superC, Element.SHOCK, 0.20f, 3f, 50f);

        // 燃烧 + 雷电 -> 过载
        w.applyElement(over, Element.FIRE, 6f, 3f, 20f);
        w.applyElement(over, Element.SHOCK, 0.20f, 3f, 20f);

        // 中毒 + 燃烧 -> 引爆
        w.applyElement(deto, Element.POISON, 10f, 1.5f, 20f);
        float hpBeforeDeto = w.hp[deto];
        w.applyElement(deto, Element.FIRE, 6f, 3f, 20f);
        float hpAfterDeto = w.hp[deto];
        // 期望：10 dps × 1.5s = 15 伤害，元素应该被清
        float detoDamage = hpBeforeDeto - hpAfterDeto;
        boolean detoFired = w.reactionCount(Element.R_DETONATE) == 1
                && w.elem[deto] == Element.NONE && detoDamage >= 10f;

        int nSteam = w.reactionCount(Element.R_STEAM);
        int nSuper = w.reactionCount(Element.R_SUPERCONDUCT);
        int nOver = w.reactionCount(Element.R_OVERLOAD);

        System.out.println("=== 元素反应定向验证 ===");
        System.out.printf("  蒸汽爆发 触发 %d 次  %s%n", nSteam, nSteam == 1 ? "OK" : "!! 失败");
        System.out.printf("  超导     触发 %d 次  %s%n", nSuper, nSuper == 1 ? "OK" : "!! 失败");
        System.out.printf("  过载     触发 %d 次  %s%n", nOver, nOver == 1 ? "OK" : "!! 失败");
        System.out.printf("  引爆     触发 %d 次  伤害 %.0f  %s%n",
                w.reactionCount(Element.R_DETONATE), detoDamage, detoFired ? "OK" : "!! 失败");

        // 反应之后元素应该被清掉，而不是继续挂着
        boolean cleared = w.elem[steam] == Element.NONE
                && w.elem[superC] == Element.NONE
                && w.elem[over] == Element.NONE;
        System.out.printf("  反应后元素已清除：%s%n", cleared ? "OK" : "!! 失败");

        // 同种元素应该叠加而不是互相反应
        int same = w.spawnEnemy(400f, 0f);
        w.maxHp[same] = 100_000f;
        w.hp[same] = 100_000f;
        w.applyElement(same, Element.FROST, 0.35f, 2f, 10f);
        w.applyElement(same, Element.FROST, 0.35f, 2f, 10f);
        boolean stacked = w.elem[same] == Element.FROST && w.elemP[same] > 0.35f;
        System.out.printf("  同种元素叠加而非反应：%s（强度 %.2f）%n",
                stacked ? "OK" : "!! 失败", w.elemP[same]);

        // 秘法不附着、不反应
        int arcane = w.spawnEnemy(500f, 0f);
        w.applyElement(arcane, Element.ARCANE, 0f, 0f, 10f);
        System.out.printf("  秘法不附着状态：%s%n",
                w.elem[arcane] == Element.NONE ? "OK" : "!! 失败");

        boolean allOk = nSteam == 1 && nSuper == 1 && nOver == 1 && detoFired && cleared && stacked
                && w.elem[arcane] == Element.NONE;
        System.out.println(allOk ? "全部通过" : "存在失败项，见上");
        if (!allOk) {
            System.exit(1);
        }
    }

    /**
     * D3 升级链路：自动选第一个升级跑 N 秒，验证：
     *   - 升级次数 / 等级 / 被动数
     *   - 技能进化（哪些主动被进化了）
     *   - 引爆反应需要毒雾轨迹被动后才能触发
     *   - Stats 真的会改变输出（DPS 提升）
     */
    private static void runUpgradePath(int frames, int wid, World w) {
        InputCommand in = new InputCommand();
        // 装备三个法术，让升级选被动为主
        w.setSpell(wid, 0, Spells.FIREBALL);
        w.setSpell(wid, 1, Spells.ICE_SHARD);
        w.setSpell(wid, 2, Spells.CHAIN_LIGHTNING);

        // 持续给经验：跳过前 60 秒刷怪，直接灌满经验让升级尽快发生
        Loadout lo = w.loadout(wid);
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            // 自动拾取：作弊用，直接给经验
            lo.gainXp(0.8f);
            // 处理待选升级：选第一个
            while (lo.pendingUps > 0) {
                Upgrades.Choice[] cs = w.peekChoices(wid);
                if (cs == null || cs.length == 0) {
                    break;
                }
                w.applyChoice(wid, 0);
            }
            // 走位
            double base = i * 0.004;
            double flip = ((i / 30) & 1) == 0 ? 1.0 : -1.0;
            in.set((float) (Math.cos(base) * flip), (float) (Math.sin(base) * flip));
            w.hp[wid] = w.maxHp[wid];
            w.step(Balance.FIXED_STEP, in);
        }
        long t1 = System.nanoTime();

        System.out.println("=== D3 升级链路验证 ===");
        System.out.printf("模拟 %d 帧 = %.1f 秒，耗时 %.0f ms，平均 %.3f ms/帧%n",
                frames, frames * Balance.FIXED_STEP, (t1 - t0) / 1e6,
                (t1 - t0) / 1e6 / frames);
        System.out.printf("等级：%d，xpRatio=%.2f，被动数 %d（无上限）%n",
                lo.level, lo.xpRatio(), lo.passiveCount());
        System.out.printf("主动槽：%d 个，%s | %s | %s%n",
                lo.activeCount(),
                spellLabel(lo.spells[0], lo.evolvedMask),
                spellLabel(lo.spells[1], lo.evolvedMask),
                spellLabel(lo.spells[2], lo.evolvedMask));
        System.out.printf("已进化：");
        for (int s = 0; s < Loadout.SLOTS; s++) {
            int raw = lo.spells[s];
            int resolved = lo.resolvedSpell(s);
            if (raw != Spells.NONE && resolved != raw) {
                System.out.printf("[%s→%s] ", Spells.get(raw).name, Spells.get(resolved).name);
            }
        }
        System.out.println();
        System.out.printf("质变激活：孤注一掷=%s，临界质量=%s，弹幕之王=%s%n",
                lo.stats.loneWolfActive, lo.stats.criticalMassActive, lo.stats.barrageActive);
        System.out.printf("元素数=%d, 全伤=%.2f, 暴击率=%.0f%%, 攻速=%.2f, 移速=%.2f, 最大 HP=%.0f, 减伤=%.0f%%%n",
                lo.stats.elementKinds, lo.stats.dmgMul, lo.stats.critChance * 100,
                lo.stats.atkSpeed, lo.stats.moveMul, Balance.WIZARD_HP + lo.stats.maxHpAdd,
                lo.stats.dr * 100);
        System.out.printf("累计击杀：%d  元素反应：蒸汽=%d 超导=%d 过载=%d 引爆=%d%n",
                w.killCount(),
                w.reactionCount(Element.R_STEAM), w.reactionCount(Element.R_SUPERCONDUCT),
                w.reactionCount(Element.R_OVERLOAD), w.reactionCount(Element.R_DETONATE));

        // 关键断言：D3 的核心是 "Stats 影响实际伤害" 和 "升级能多次发生"
        boolean ok = lo.level > 1 && lo.passiveCount() > 0 && lo.stats.dmgMul > 1.0f;
        System.out.println(ok ? "OK：升级链路工作正常" : "!! 失败：升级或被动未生效");
        if (!ok) {
            System.exit(1);
        }
    }

    /**
     * D4 场景 / 障碍 / 阶段 / Boss 验证。
     *
     * 1) 跑够越过至少一个阶段边界，确认阶段切换触发、障碍物随之重建；
     * 2) 显式刷一个 Boss，再跑一段，确认 updateBossPhase（预警圈 / 召唤）不抛异常。
     *
     * 不必真等 20 分钟——阶段与 Boss 的代码路径都能在这两套短循环里被覆盖。
     */
    private static void runStage(World w, int frames, int wid) {
        w.maxHp[wid] = 1_000_000f;
        w.hp[wid] = 1_000_000f;
        w.setSpell(wid, 0, Spells.FIREBALL);
        w.setSpell(wid, 1, Spells.ICE_SHARD);
        w.setSpell(wid, 2, Spells.CHAIN_LIGHTNING);
        InputCommand in = new InputCommand();

        int lastStage = 0;
        int maxObstacles = 0;
        int maxEnemies = 0;
        int lastBoss = -1;
        int bossSeen = 0;
        int lvlIdx = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            double base = i * 0.004;
            double flip = ((i / 30) & 1) == 0 ? 1.0 : -1.0;
            in.set((float) (Math.cos(base) * flip), (float) (Math.sin(base) * flip));
            w.hp[wid] = w.maxHp[wid];
            w.step(Balance.FIXED_STEP, in);
            int s = w.stage();
            if (s != lastStage) {
                lastStage = s;
                System.out.printf("  阶段切换 -> stage %d  t=%.1fs  障碍物 %d  场上敌人 %d%n",
                        s, w.time(), w.obstacleCount(), w.enemyCount());
            }
            // Boss 登场/倒下：验证 BOSS_LEVELS 等级表真的把 Boss 送进场
            int b = w.bossId();
            if (b != lastBoss) {
                if (b >= 0) {
                    bossSeen++;
                    System.out.printf("  Boss 登场：%s  Lv.%d  t=%.1fs  HP=%.0f  场上敌人 %d%n",
                            w.bossName(), w.playerLevel(), w.time(), w.maxHp[b], w.enemyCount());
                } else {
                    System.out.printf("  Boss 被击杀  Lv.%d  t=%.1fs%n", w.playerLevel(), w.time());
                }
                lastBoss = b;
            }
            // 记录玩家升到每个 Boss 触发等级的时间点，用来评估等级曲线节奏
            if (lvlIdx < Balance.BOSS_LEVELS.length
                    && w.playerLevel() >= Balance.BOSS_LEVELS[lvlIdx]) {
                System.out.printf("  达到 Lv.%d  t=%.1fs  场上敌人 %d%n",
                        Balance.BOSS_LEVELS[lvlIdx], w.time(), w.enemyCount());
                lvlIdx++;
            }
            maxObstacles = Math.max(maxObstacles, w.obstacleCount());
            maxEnemies = Math.max(maxEnemies, w.enemyCount());
        }
        long t1 = System.nanoTime();

        // 显式刷 Boss，跑 30 秒，确认预警圈 / 召唤路径稳定
        if (w.bossId() < 0) {
            w.spawnBoss(Balance.BOSS_HP_TIERS.length - 1);
        }
        int bossIdAtSpawn = w.bossId();
        for (int i = 0; i < 1800; i++) {
            in.set(1f, 0f);
            w.hp[wid] = w.maxHp[wid];
            w.step(Balance.FIXED_STEP, in);
        }
        boolean bossGone = w.bossId() < 0;   // 可能被玩家弹幕打死，属正常

        System.out.println("=== D4 场景 / 障碍 / 阶段 / Boss 验证 ===");
        System.out.printf("时间轴模拟 %d 帧 = %.1f 秒，耗时 %.0f ms%n",
                frames, frames * Balance.FIXED_STEP, (t1 - t0) / 1e6);
        System.out.printf("到达阶段 %d，最大障碍数 %d（安全圈外生成），峰值敌人 %d，结束敌人 %d%n",
                lastStage, maxObstacles, maxEnemies, w.enemyCount());
        System.out.printf("等级表 Boss 登场次数 %d / %d，结束时 Lv.%d，血量成长系数 t=%.0fs 时 %.1fx%n",
                bossSeen, Balance.BOSS_LEVELS.length, w.playerLevel(),
                w.time(), Balance.enemyHpScale(w.time()));
        System.out.printf("显式 Boss id=%d，30 秒后 %s%n",
                bossIdAtSpawn, bossGone ? "已被击杀（阶段技能路径已覆盖）" : "仍存活");
        System.out.println("OK：场景 / 障碍 / 阶段 / Boss 代码路径无异常");
    }

    /**
     * 四职业验证：分别生成法师 / 战士 / 弓箭手 / 召唤师，各带满本职业技能池，
     * 跑一段时间后确认各系技能都能正常开火、击杀，且按职业抽卡不抛异常。
     * 召唤师额外验证：宠物按节奏成批刷新、数量封顶、不会跑出拴绳范围。
     */
    private static void runClasses(int frames) {
        int n = HeroClass.COUNT - 1;
        World w = new World(20260908L);
        int[] ids = new int[n];
        int[] cls = new int[n];
        for (int i = 0; i < n; i++) {
            cls[i] = i + 1;
        }
        for (int i = 0; i < n; i++) {
            int id = w.spawnWizard((i - 1) * 140f, 0f, cls[i]);
            ids[i] = id;
            int[] pool = Spells.poolForClass(cls[i]);
            if (pool.length == 0) {
                System.out.println("!! 失败：职业 " + HeroClass.name(cls[i]) + " 技能池为空");
                System.exit(1);
            }
            Loadout lo = w.loadout(id);
            int slots = Math.min(Loadout.SLOTS, pool.length);
            for (int s = 0; s < slots; s++) {
                lo.set(s, pool[s]);
            }
        }
        int summonerIdx = -1;
        for (int i = 0; i < n; i++) {
            if (cls[i] == HeroClass.SUMMONER) {
                summonerIdx = i;
            }
        }

        InputCommand in = new InputCommand();
        int maxMinions = 0;
        float maxLeash = 0f;
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            double base = i * 0.004;
            double flip = ((i / 30) & 1) == 0 ? 1.0 : -1.0;
            in.set((float) (Math.cos(base) * flip), (float) (Math.sin(base) * flip));
            for (int k = 0; k < n; k++) {
                w.hp[ids[k]] = w.maxHp[ids[k]];
            }
            // 每隔一阵点一次鼠标：走"宠物按点击方向进攻"这条分支
            in.buttons |= ((i % 90) < 45) ? InputCommand.BUTTON_ORDER : 0;
            in.aimX = (float) (Math.cos(base) * 400f);
            in.aimY = (float) (Math.sin(base) * 400f);
            w.step(Balance.FIXED_STEP, in);

            if (summonerIdx >= 0) {
                int owner = ids[summonerIdx];
                maxMinions = Math.max(maxMinions, w.minionCount(owner));
                float ox = w.x[owner];
                float oy = w.y[owner];
                for (int e = 0; e < w.highWater(); e++) {
                    if (w.alive[e] && w.kind[e] == World.KIND_MINION) {
                        maxLeash = Math.max(maxLeash, dist(ox, oy, w.x[e], w.y[e]));
                    }
                }
            }
            // 周期性灌经验并应用升级，验证各职业专属抽卡路径
            if (i % 120 == 0) {
                for (int k = 0; k < n; k++) {
                    Loadout lo = w.loadout(ids[k]);
                    lo.gainXp(2f);
                    while (lo.pendingUps > 0) {
                        Upgrades.Choice[] cs = w.peekChoices(ids[k]);
                        if (cs == null || cs.length == 0) {
                            break;
                        }
                        w.applyChoice(ids[k], 0);
                    }
                }
            }
        }
        long t1 = System.nanoTime();

        System.out.println("=== 四职业技能 / 抽卡验证 ===");
        System.out.printf("模拟 %d 帧 = %.1f 秒，耗时 %.0f ms，平均 %.3f ms/帧%n",
                frames, frames * Balance.FIXED_STEP, (t1 - t0) / 1e6, (t1 - t0) / 1e6 / frames);
        for (int k = 0; k < n; k++) {
            Loadout lo = w.loadout(ids[k]);
            System.out.printf("  [%s] 等级 %d  被动 %d  最大HP %.0f  减伤 %.0f%%  暴击 %.0f%%%n",
                    HeroClass.name(cls[k]), lo.level, lo.passiveCount(),
                    w.maxHp[ids[k]], lo.stats.dr * 100, lo.stats.critChance * 100);
        }
        System.out.printf("累计击杀：%d  存活实体 %d%n", w.killCount(), w.liveCount());
        boolean ok = w.killCount() > 0;
        System.out.println(ok ? "OK：四职业技能均正常开火并击杀" : "!! 失败：未产生击杀");

        if (summonerIdx >= 0) {
            int owner = ids[summonerIdx];
            System.out.printf("召唤师：峰值宠物 %d（上限 %d）  最远拴绳距离 %.0f（上限 %.0f）%n",
                    maxMinions, Balance.SUMMON_COUNT, maxLeash, Balance.MINION_LEASH);
            if (maxMinions < Balance.SUMMON_COUNT) {
                System.out.println("!! 失败：宠物从未成批召唤");
                ok = false;
            }
            if (maxMinions > Balance.SUMMON_COUNT) {
                System.out.println("!! 失败：宠物数量超出上限（旧批次未解散）");
                ok = false;
            }
            // 拴绳留 40px 容差：分离推挤与追击刹不住车会有少量越界
            if (maxLeash > Balance.MINION_LEASH + 40f) {
                System.out.println("!! 失败：宠物跑出了拴绳范围");
                ok = false;
            }
            if (ok) {
                System.out.println("OK：宠物成批召唤、数量封顶且未脱离拴绳");
            }
        }
        if (!ok) {
            System.exit(1);
        }
    }

    /**
     * 王宫决战 · 国王行为验证（时序固定在约 26 秒游戏时间内，忽略帧数参数）。
     *
     * 玩家全程站桩、无敌，只盯国王：
     *   1) 8 秒技能 CD 内常态站桩：一步不动；
     *   2) 技能前摇 1.4 秒随机走位（躲弹幕）：速率 ≈ 175、中途会拐弯，
     *      且位移方向不持续指向玩家（真追击会一直指向玩家）；
     *   3) 唯一技能与默认属性：前摇起点生成 180/50/1.4s 预警圈，9.4 秒爆炸扣 50 血；
     *      第 15 秒按获得顺序失去一个被动并扣 10 血。
     * 为让爆炸伤害可确定判定，开战前把国王传送到场地右侧顶角：
     * 1.4 秒随机走位也够不到玩家（不产生无敌帧），玩家必吃这一发 50。
     *
     * 场次 3（二阶段）：一阶段击破只发决裂信号（不结算）→ beginKingPhase2 王座重生
     *   （6000 血 / 玩家移速 -20 / 开场裂隙）；随后 42 秒长跑观测：
     *   纯追击走位三档（175 / 155 / 140，不再保持距离/绕行；AOE 前摇期间站定）、
     *   魔弹（130 / 扇形 3 连发 / 10 秒一轮）、地面提示（10 秒 / 1.5 秒前摇 / 150）、
     *   裂隙刷怪（5 秒一道 / 每道 2 只 / 同屏封顶），
     *   最后击破二阶段 → 只发「王座本体」过渡信号（等对白播完进三阶段）。
     *
     * 场次 4（三阶段 · 王座本体）：beginKingPhase3 觉醒（12000 血 / 常驻 80% 减伤 / 前摇窗口 20%），
     *   随后 30 秒长跑观测：传送（3 秒周期 / 落点 ≤50 / 1 秒前摇 / 落点 100 范围 30 爆发）、
     *   地刺（5 秒 / 1 秒警示 / 30）、深渊牵引（5 秒 / 1.5 秒窗口 / 30 速拽向王座）、
     *   裂隙召唤 Boss（25 秒 / 非奶蛙档位）、造成伤害回 200，最后击破 → 真通关。
     */
    private static void runKing() {
        InputCommand in = new InputCommand();
        boolean ok = true;

        // ================= 场次 1：常态站桩 + 前摇随机走位 + 技能 =================
        World w = new World(20260912L);
        int wid = w.spawnWizard(0f, 0f);
        w.maxHp[wid] = 1_000_000f;
        w.hp[wid] = 1_000_000f;
        Loadout lo = w.loadout(wid);
        lo.clear(0);
        lo.clear(1);
        lo.clear(2);   // 清光法术：不让子弹干扰对国王的观测
        w.enterKingArena();
        int k = w.kingId();
        if (k < 0) {
            System.out.println("!! 失败：进入王宫决战但国王未生成");
            System.exit(1);
        }

        // ---- 阶段 1：技能 CD 内的常态（0 ~ 7.9s）：站桩，一步不动 ----
        int standFrames = (int) (Balance.KING1_SKILL_CD * 60) - 6;
        float path1 = 0f;
        int castingEarly = 0;
        for (int i = 0; i < standFrames; i++) {
            float bx = w.x[k];
            float by = w.y[k];
            w.hp[wid] = w.maxHp[wid];
            w.step(Balance.FIXED_STEP, in);
            if (w.kingCasting()) {
                castingEarly++;
            }
            path1 += dist(bx, by, w.x[k], w.y[k]);
            if (i == standFrames - 6) {
                // 传送国王到右侧顶角，为下面这一发技能建立确定性的几何：距离 ~720，
                // 1.4 秒随机走位也够不到玩家，爆炸伤害必落在玩家身上且国王不吃击退
                w.x[k] = Balance.ARENA_HALF_X - 80f;
                w.y[k] = Balance.ARENA_TOP;
            }
        }

        // ---- 阶段 2：8s 起手 → 1.4s 前摇随机走位 → 回到站桩（跑到 ~10.4s）----
        boolean castSeen = false;
        boolean castingEnded = false;
        boolean zoneSeen = false;
        float castT = 0f;
        float dodgePath = 0f;
        int dodgeFrames = 0;
        int dodgeAligned = 0;      // 位移持续指向玩家的帧数（随机走位应远小于总帧数）
        int dodgeTurns = 0;        // 相邻帧方向夹角 > 30° 的次数（中途拐弯 / 撞墙反弹）
        int faceViolations = 0;
        float tailPath = 0f;
        int tailFrames = 0;
        float aoeT = -1f;
        float zoneR = 0f;
        float zoneDmg = 0f;
        float zoneX = 0f;
        float zoneY = 0f;
        float prevDx = 0f;
        float prevDy = 0f;
        for (int i = 0; i < 150; i++) {
            float bx = w.x[k];
            float by = w.y[k];
            boolean casting = w.kingCasting();
            w.hp[wid] = w.maxHp[wid];
            float beforeHp = w.hp[wid];
            w.step(Balance.FIXED_STEP, in);
            float hpDelta = w.hp[wid] - beforeHp;
            if (aoeT < 0f && hpDelta < -45f && hpDelta > -55f) {
                aoeT = w.time();
            }
            float dx = w.x[k] - bx;
            float dy = w.y[k] - by;
            float stp = (float) Math.hypot(dx, dy);
            if (casting) {
                if (!castSeen) {
                    castSeen = true;
                    castT = w.time();
                }
                dodgePath += stp;
                if (stp > 0.5f) {
                    dodgeFrames++;
                    // 走位方向 vs 玩家方向：随机走位不该持续指向玩家（那是追击的特征）
                    float tdx = w.x[wid] - bx;
                    float tdy = w.y[wid] - by;
                    float tl = (float) Math.hypot(tdx, tdy);
                    float dot = tl > 1e-3f ? (dx * tdx + dy * tdy) / (stp * tl) : 0f;
                    if (dot > 0.95f) {
                        dodgeAligned++;
                    }
                    if (prevDx != 0f || prevDy != 0f) {
                        float pl = (float) Math.hypot(prevDx, prevDy);
                        float pd = (dx * prevDx + dy * prevDy) / (stp * pl);
                        if (pd < 0.866f) {   // 夹角 > 30° = 拐弯
                            dodgeTurns++;
                        }
                    }
                    prevDx = dx;
                    prevDy = dy;
                    // 朝向跟走位方向：横向位移足够大时朝向必须与之一致
                    if (Math.abs(dx) > 1f && w.kingFaceRight() != (dx > 0f)) {
                        faceViolations++;
                    }
                }
            } else if (castSeen) {
                castingEnded = true;
            }
            if (castingEnded) {
                tailPath += stp;
                tailFrames++;
            }
            // 预警圈扫描：验证技能真的落了 180 / 50 / 圈心在玩家身上
            for (int e = 0; e < w.highWater(); e++) {
                if (w.alive[e] && w.kind[e] == World.KIND_ZONE && w.life[e] > 0f) {
                    zoneSeen = true;
                    zoneR = w.r[e];
                    zoneDmg = w.dmg[e];
                    zoneX = w.x[e];
                    zoneY = w.y[e];
                }
            }
        }
        float dodgeSpeed = dodgeFrames > 0 ? dodgePath / (dodgeFrames * Balance.FIXED_STEP) : 0f;

        System.out.println("=== 王宫决战 · 国王行为验证 ===");
        System.out.printf("阶段1 常态站桩：%d 帧内总位移 %.2f px（期间提前施法 %d 帧）%n",
                standFrames, path1, castingEarly);
        System.out.printf("阶段2 技能：起手 t=%.2fs，前摇走位 %d 帧 / 拐弯 %d 次 / 指向玩家 %d 帧，速率 %.1f px/s%n",
                castT, dodgeFrames, dodgeTurns, dodgeAligned, dodgeSpeed);
        System.out.printf("  前摇结束后回站桩：%d 帧位移 %.2f px，朝向违例 %d%n",
                tailFrames, tailPath, faceViolations);
        System.out.printf("  预警圈：%s r=%.0f dmg=%.0f 圈心(%.0f,%.0f)，爆炸 t=%s%n",
                zoneSeen ? "已生成" : "未生成", zoneR, zoneDmg, zoneX, zoneY,
                aoeT > 0f ? String.format("%.2fs", aoeT) : "未检测到扣血");

        if (castingEarly != 0) {
            System.out.println("!! 失败：8 秒 CD 内提前出现技能前摇");
            ok = false;
        }
        if (path1 > 2f) {
            System.out.println("!! 失败：常态下国王移动了（应站桩不动）");
            ok = false;
        }
        if (!castSeen || Math.abs(castT - Balance.KING1_SKILL_CD) > 0.12f) {
            System.out.println("!! 失败：技能起手时间不在 8 秒节拍上");
            ok = false;
        }
        if (dodgeFrames < 78 || dodgeFrames > 90) {
            System.out.println("!! 失败：前摇走位帧数不在 1.4 秒的预期内");
            ok = false;
        }
        if (dodgeSpeed <= 165f || dodgeSpeed >= 181f) {
            System.out.println("!! 失败：前摇走位速率偏离 175");
            ok = false;
        }
        if (dodgeAligned > dodgeFrames * 45 / 100) {
            System.out.println("!! 失败：前摇走位持续指向玩家（随机走位变成了追击）");
            ok = false;
        }
        if (dodgeTurns < 1) {
            System.out.println("!! 失败：前摇走位没有拐弯（不像随机走位）");
            ok = false;
        }
        if (tailFrames < 30 || tailPath > 2f) {
            System.out.println("!! 失败：前摇结束后没有回到站桩");
            ok = false;
        }
        if (!zoneSeen || Math.abs(zoneR - 180f) > 1f
                || Math.abs(zoneDmg - 50f) > 0.5f
                || Math.abs(zoneX) > 3f || Math.abs(zoneY - Balance.ARENA_ENTER_Y) > 3f) {
            System.out.println("!! 失败：预警圈参数或圈心位置不对（应为 180/50，圈心在玩家身上）");
            ok = false;
        }
        if (aoeT < 0f || Math.abs(aoeT - 9.4f) > 0.15f) {
            System.out.println("!! 失败：预警圈没有在 1.4 秒前摇后对玩家爆炸（-50）");
            ok = false;
        }
        if (faceViolations > 2) {
            System.out.println("!! 失败：前摇走位朝向与位移方向不一致");
            ok = false;
        }

        // ================= 场次 2：默认属性（每 15 秒失去一个被动 + 扣 10 血）=================
        World w2 = new World(4242L);
        int wid2 = w2.spawnWizard(0f, 0f);
        w2.maxHp[wid2] = 1_000_000f;
        w2.hp[wid2] = 1_000_000f;
        Loadout lo2 = w2.loadout(wid2);
        lo2.clear(0);
        lo2.clear(1);
        lo2.clear(2);
        boolean gavePassive = lo2.addPassive(Passives.POWER_TRAINING);
        w2.enterKingArena();
        float attrT = -1f;
        int passiveBefore = lo2.passiveCount();   // 法师起手自带"重抽"，这里按实际数量记
        int passiveAfterAttr = -1;
        int frames2 = (int) ((Balance.KING_ATTRITION_INTERVAL + 0.4f) * 60);
        for (int i = 0; i < frames2; i++) {
            float beforeHp = w2.hp[wid2];
            w2.step(Balance.FIXED_STEP, in);
            float d2 = w2.hp[wid2] - beforeHp;
            if (attrT < 0f && d2 < -9f && d2 > -11f) {
                attrT = w2.time();
                passiveAfterAttr = lo2.passiveCount();
            }
        }
        System.out.printf("默认属性：扣 10 血事件 t=%s，被动 %d → %d%n",
                attrT > 0f ? String.format("%.2fs", attrT) : "未检测到",
                passiveBefore, passiveAfterAttr);
        if (!gavePassive) {
            System.out.println("!! 失败：未能预置被动，无法验证流失");
            ok = false;
        }
        if (attrT < 0f || Math.abs(attrT - Balance.KING_ATTRITION_INTERVAL) > 0.15f) {
            System.out.println("!! 失败：15 秒未按节拍扣 10 血");
            ok = false;
        }
        if (gavePassive && passiveAfterAttr != passiveBefore - 1) {
            System.out.println("!! 失败：扣血时没有按获得顺序失去一个被动");
            ok = false;
        }

        // ================= 场次 3：二阶段（击破一阶段 → 王座重生 → 走位 / 魔弹 / AOE / 裂隙） =================
        World w3 = new World(9090L);
        int wid3 = w3.spawnWizard(0f, 0f);
        w3.maxHp[wid3] = 1_000_000f;
        w3.hp[wid3] = 1_000_000f;
        Loadout lo3 = w3.loadout(wid3);
        lo3.clear(0);
        lo3.clear(1);
        lo3.clear(2);   // 清光法术：玩家弹幕不干扰魔弹观测
        w3.enterKingArena();
        int k1 = w3.kingId();

        // ---- 3a 一阶段击破：不再直接结算，转为决裂信号（客户端据此弹对白） ----
        float downX = w3.x[k1];
        float downY = w3.y[k1];
        w3.kill(k1);
        boolean fallenSig = w3.kingFallen();
        boolean kingGone = w3.kingId() < 0;
        boolean notVictoryYet = !w3.victory();
        boolean downAnchor = Math.abs(w3.kingDownX() - downX) < 0.5f
                && Math.abs(w3.kingDownY() - downY) < 0.5f;

        // ---- 3b 转场：国王在王座上以二阶段重生；玩家回殿中入场位，移速 -20 ----
        float tPhase2 = w3.time();
        w3.beginKingPhase2();
        int k2 = w3.kingId();
        boolean phase2Now = w3.kingPhase() == 2;
        float king2X = k2 >= 0 ? w3.x[k2] : 0f;
        float king2Y = k2 >= 0 ? w3.y[k2] : 0f;
        boolean respawnPos = k2 >= 0
                && Math.abs(king2X - Balance.ARENA_KING_X) < 0.5f
                && Math.abs(king2Y - Balance.ARENA_KING_Y) < 0.5f;
        boolean hp2 = k2 >= 0 && Math.abs(w3.maxHp[k2] - Balance.KING2_HP) < 0.5f;
        float hp2Val = k2 >= 0 ? w3.maxHp[k2] : -1f;   // 当场捕获：k2 槽位会在三阶段被复用
        boolean reflagged = !w3.kingFallen();
        boolean playerBack = Math.abs(w3.x[wid3] - Balance.ARENA_ENTER_X) < 0.5f
                && Math.abs(w3.y[wid3] - Balance.ARENA_ENTER_Y) < 0.5f;
        int openerEnemies = countKind(w3, World.KIND_ENEMY) - (k2 >= 0 ? 1 : 0);

        // ---- 3c 玩家移速：法师基础移速 - 二阶段惩罚，0.5 秒右移 ----
        InputCommand run3 = new InputCommand();
        clearNonBoss(w3);    // 清掉开场裂隙的魔物，隔离档位走位观测
        run3.set(1f, 0f);
        float mx0 = w3.x[wid3];
        for (int i = 0; i < 30; i++) {
            w3.hp[wid3] = w3.maxHp[wid3];
            w3.step(Balance.FIXED_STEP, run3);
        }
        float moved = w3.x[wid3] - mx0;
        // 从 Balance 推导而非写死：数值平衡调整后这里不会失配
        float expectMovePx = (Balance.WIZARD_SPEED - Balance.KING2_SPEED_PENALTY) * 30f * Balance.FIXED_STEP;
        run3.reset();
        w3.x[wid3] = Balance.ARENA_ENTER_X;   // 归位殿中：档位测试以 (0,220) 为圆心
        w3.y[wid3] = Balance.ARENA_ENTER_Y;

        // ---- 3d 追击走位三档（玩家固定在 (0,220)）：全程追人，不再保距/绕行 ----
        w3.x[k2] = 0f;   w3.y[k2] = -250f;    // 距离 470 → 175 直线追击
        float farStep = oneStep(w3, k2, run3);
        w3.x[k2] = 0f;   w3.y[k2] = -50f;     // 距离 270 → 155 追击
        float midStep = oneStep(w3, k2, run3);
        w3.x[k2] = 0f;   w3.y[k2] = 0f;       // 距离 220 → 140 贴身追击（距离应缩短）
        float nearDist0 = dist(w3.x[wid3], w3.y[wid3], w3.x[k2], w3.y[k2]);
        float nearStep = oneStep(w3, k2, run3);
        float nearDy = w3.y[k2];
        float nearDist1 = dist(w3.x[wid3], w3.y[wid3], w3.x[k2], w3.y[k2]);
        w3.x[k2] = 0f;   w3.y[k2] = 120f;     // 距离 100 → 仍按 140 追击（贴脸不后退）
        float closeDist0 = dist(w3.x[wid3], w3.y[wid3], w3.x[k2], w3.y[k2]);
        float closeStep = oneStep(w3, k2, run3);
        float closeDist1 = dist(w3.x[wid3], w3.y[wid3], w3.x[k2], w3.y[k2]);

        // ---- 3d+ 贴身接触伤害：国王压在玩家身上一帧，应掉血 25 ----
        w3.x[k2] = w3.x[wid3];
        w3.y[k2] = w3.y[wid3];
        w3.hp[wid3] = w3.maxHp[wid3];
        w3.iframe[wid3] = 0f;
        float contactBefore = w3.hp[wid3];
        oneStep(w3, k2, run3);
        float contactDelta = w3.hp[wid3] - contactBefore;
        w3.x[k2] = 0f;   w3.y[k2] = -170f;    // 归位王座方向，进入 3e 长跑

        // ---- 3e 42 秒长跑：魔弹 / 地面提示 / 裂隙的节拍与参数 ----
        int zoneRounds = 0;
        int zoneHits = 0;
        int boltRounds = 0;
        int boltImpacts = 0;
        int maxBoltsAtOnce = 0;
        int boltWorstFlight = 0;
        int boltFlightFrames = 0;
        int boltTargetFrames = 0;
        int riftCount = 0;
        int maxEnemyTotal = 0;
        float firstZoneT = -1f;
        float firstBoltT = -1f;
        float firstRiftT = -1f;
        float zoneParamT = -1f;
        float zoneParamR = 0f;
        float zoneParamLife = 0f;
        float zoneParamDmg = 0f;
        float zoneParamOff = 0f;
        float boltSpeedMin = 1e9f;
        float boltSpeedMax = 0f;
        float boltLifeAtBirth = -1f;
        float lastBoltDist = -1f;
        boolean hadBolt = false;
        boolean hadZone = false;
        boolean hadRift = false;
        int castFrames3 = 0;      // AOE 前摇总帧数（前摇站定验证）
        float castMove3 = 0f;     // 前摇期间国王累计位移（站定应为 0）
        int frames3 = (int) (42f / Balance.FIXED_STEP);
        for (int i = 0; i < frames3; i++) {
            w3.hp[wid3] = w3.maxHp[wid3];
            w3.iframe[wid3] = 0f;   // 排除魔物啃咬刷新的受击无敌帧：站桩必吃预警圈爆炸
            float beforeHp3 = w3.hp[wid3];
            // 前摇站定：红光亮起（kingCasting）的帧里国王本帧位移必须为 0
            boolean castNow = w3.kingCasting();
            float kx0 = w3.x[k2];
            float ky0 = w3.y[k2];
            w3.step(Balance.FIXED_STEP, run3);   // run3 已 reset：玩家站桩
            if (castNow) {
                castFrames3++;
                castMove3 += dist(kx0, ky0, w3.x[k2], w3.y[k2]);
            }
            float d3 = w3.hp[wid3] - beforeHp3;
            float tRel = w3.time() - tPhase2;

            // 预警圈：出现轮次 + 参数 + 圈心贴在玩家身上
            boolean zoneNow = false;
            for (int e = 0; e < w3.highWater(); e++) {
                if (w3.alive[e] && w3.kind[e] == World.KIND_ZONE && w3.life[e] > 0f) {
                    zoneNow = true;
                    if (zoneParamT < 0f) {
                        zoneParamT = tRel;
                        zoneParamR = w3.r[e];
                        zoneParamLife = w3.life[e];
                        zoneParamDmg = w3.dmg[e];
                        zoneParamOff = dist(w3.x[wid3], w3.y[wid3], w3.x[e], w3.y[e]);
                    }
                }
            }
            if (zoneNow && !hadZone) {
                zoneRounds++;
                if (firstZoneT < 0f) {
                    firstZoneT = tRel;
                }
            } else if (!zoneNow && hadZone) {
                // 圈到期消失帧 = 爆炸帧：站桩玩家应吃到 -50（允许叠加魔物啃咬）
                if (d3 <= -42f) {
                    zoneHits++;
                }
            }
            hadZone = zoneNow;

            // 魔弹：出现轮次 / 单轮颗数 / 速度恒 130 / 追踪锁定 / 生命周期（命中或 6 秒寿终）
            int boltIdx = -1;
            int boltsNow = 0;
            float boltDistNow = -1f;
            for (int e = 0; e < w3.highWater(); e++) {
                if (w3.alive[e] && w3.kind[e] == World.KIND_PROJECTILE) {
                    boltIdx = e;
                    boltsNow++;
                    boltDistNow = dist(w3.x[wid3], w3.y[wid3], w3.x[e], w3.y[e]);
                    float sp = (float) Math.hypot(w3.vx[e], w3.vy[e]);
                    boltSpeedMin = Math.min(boltSpeedMin, sp);
                    boltSpeedMax = Math.max(boltSpeedMax, sp);
                    if (w3.projTarget[e] == wid3) {
                        boltTargetFrames++;
                    }
                }
            }
            maxBoltsAtOnce = Math.max(maxBoltsAtOnce, boltsNow);
            if (boltIdx >= 0 && !hadBolt) {
                boltRounds++;
                boltFlightFrames = 0;
                if (firstBoltT < 0f) {
                    firstBoltT = tRel;
                    boltLifeAtBirth = w3.life[boltIdx];
                }
            } else if (boltIdx < 0 && hadBolt) {
                boltWorstFlight = Math.max(boltWorstFlight, boltFlightFrames);
                if (lastBoltDist >= 0f && lastBoltDist < 40f) {
                    boltImpacts++;   // 消失前已贴到玩家身上：物理命中
                }
            }
            if (boltIdx >= 0) {
                boltFlightFrames++;
                lastBoltDist = boltDistNow;
            }
            hadBolt = boltIdx >= 0;

            // 裂隙：视觉出现轮次（tRel>2 跳过转场时的开场裂隙）
            boolean riftNow = false;
            for (int e = 0; e < w3.highWater(); e++) {
                if (w3.alive[e] && w3.kind[e] == World.KIND_FX
                        && w3.meta[e] == World.FX_RIFT && w3.life[e] > 0f) {
                    riftNow = true;
                }
            }
            if (tRel > 2f && riftNow && !hadRift) {
                riftCount++;
                if (firstRiftT < 0f) {
                    firstRiftT = tRel;
                }
            }
            hadRift = riftNow;

            maxEnemyTotal = Math.max(maxEnemyTotal, countKind(w3, World.KIND_ENEMY));
        }

        // ---- 3f 二阶段击破：只发「王座本体」过渡信号（不结算，等对白播完进三阶段） ----
        float down2X = w3.x[k2];
        float down2Y = w3.y[k2];
        w3.kill(k2);
        boolean fallenSig2 = w3.kingFallen2();
        boolean kingGone2 = w3.kingId() < 0;
        boolean notVictory2 = !w3.victory();
        boolean downAnchor2 = Math.abs(w3.kingDownX() - down2X) < 0.5f
                && Math.abs(w3.kingDownY() - down2Y) < 0.5f;
        boolean noFallenAgain = !w3.kingFallen();

        // ---- 3g 三阶段（王座本体）：80% 减伤 / 贴身 15 / 3 秒传送爆发 / 5 秒地刺 / 5 秒牵引 / 25 秒召唤 / 命中回 200 ----
        float tPhase3 = w3.time();
        int enemiesBefore3 = countKind(w3, World.KIND_ENEMY);
        w3.beginKingPhase3();
        int k3 = w3.kingId();
        // 开场裂隙净增的魔物数（差值法：排除二阶段残留魔物的干扰；-1 扣掉新国王）
        int openerMobs3 = countKind(w3, World.KIND_ENEMY) - enemiesBefore3 - 1;
        int phaseAt3 = w3.kingPhase();
        float hp3Val = k3 >= 0 ? w3.maxHp[k3] : -1f;
        boolean phase3Now = phaseAt3 == 3;
        boolean hp3 = k3 >= 0 && Math.abs(hp3Val - Balance.KING3_HP) < 0.5f;
        boolean spawnPos3 = k3 >= 0
                && Math.abs(w3.x[k3] - Balance.ARENA_KING_X) < 0.5f
                && Math.abs(w3.y[k3] - Balance.ARENA_KING_Y) < 0.5f;
        boolean playerBack3 = Math.abs(w3.x[wid3] - Balance.ARENA_ENTER_X) < 0.5f
                && Math.abs(w3.y[wid3] - Balance.ARENA_ENTER_Y) < 0.5f;

        // 常态减伤：直喂 1000 应只掉 200（80% 减伤）；前摇窗口 20% 减伤在首轮传送里补测
        w3.hp[k3] = w3.maxHp[k3];
        float drBefore = w3.hp[k3];
        w3.damage(k3, 1000f);
        float drIdleLoss = drBefore - w3.hp[k3];
        float drCastLoss = -1f;
        boolean drCastDone = false;

        InputCommand stay3 = new InputCommand();   // 玩家全程站桩（不主动移动）

        // ---- 3g+ 贴身接触伤害（三阶段定制 15）：王座压在玩家身上一帧，应掉血 15 ----
        clearNonBoss(w3);
        w3.x[k3] = w3.x[wid3];
        w3.y[k3] = w3.y[wid3];
        w3.hp[wid3] = w3.maxHp[wid3];
        w3.iframe[wid3] = 0f;
        float contactBefore3 = w3.hp[wid3];
        w3.step(Balance.FIXED_STEP, stay3);
        float contactDelta3 = w3.hp[wid3] - contactBefore3;
        w3.x[k3] = Balance.ARENA_KING_X;   // 归位王座，进入传送观测
        w3.y[k3] = Balance.ARENA_KING_Y;

        int teleJumps = 0;
        int teleLandingBad = 0;
        int teleExplodeHit = 0;
        int teleExplodeExact = 0;
        float firstTeleT = -1f;
        float lastJumpT = -1f;
        float minJumpGap = 1e9f;
        float maxJumpGap = 0f;
        int telegraphFrames = 0;
        float kingStrayPath = 0f;      // 非传送帧王座自身位移（三阶段不走路，应恒 0）
        int spikeRounds = 0;
        int spikeHits = 0;
        int spikeExact = 0;
        boolean spikeFxOk = false;
        float firstSpikeT = -1f;
        float spikeParamR = 0f;
        float spikeParamLife = 0f;
        float spikeParamDmg = 0f;
        boolean hadSpikeTele = false;
        int pullRounds = 0;
        int pullFrames = 0;
        int pullAligned = 0;
        int pullCleanWindows = 0;
        int pullWinFrames = 0;
        float firstPullT = -1f;
        int summonRounds = 0;
        boolean summonTierBad = false;
        float firstSummonT = -1f;
        boolean hadSummon = false;
        int riftRounds3 = 0;
        int boltRounds3 = 0;
        int aoeRounds3 = 0;
        int maxBolts3 = 0;             // 三阶段单帧同屏魔弹峰值（应等于齐射数量）
        boolean hadRift3 = false;
        boolean hadBolt3 = false;
        boolean hadAoe3 = false;
        int healEvents = 0;
        int healExact200 = 0;
        float healTotal = 0f;
        boolean healNotMultiple = false;
        float healOddDelta = 0f;       // healNotMultiple 触发时的异常增量（失败信息用）
        int frames3b = (int) (30f / Balance.FIXED_STEP);
        for (int i = 0; i < frames3b; i++) {
            float tRel3 = w3.time() - tPhase3;
            // 血拉满 + 无敌帧清零：王座各通道的每一发都必中，逐帧只观测这一帧吃了什么
            w3.hp[wid3] = w3.maxHp[wid3];
            w3.iframe[wid3] = 0f;
            // 地刺钉子：警示圈在场时把玩家按在刺尖中心，到期必定吃这一发
            for (int e = 0; e < w3.highWater(); e++) {
                if (w3.alive[e] && w3.kind[e] == World.KIND_ZONE
                        && w3.meta[e] == World.ZONE_KING_SPIKE_TELE) {
                    w3.x[wid3] = w3.x[e];
                    w3.y[wid3] = w3.y[e];
                }
            }

            float kx0 = w3.x[k3];
            float ky0 = w3.y[k3];
            float ppx0 = w3.x[wid3];
            float ppy0 = w3.y[wid3];
            float php0 = w3.hp[wid3];
            float khp0 = w3.hp[k3];
            float telePre = w3.kingTeleT();
            float pullPre = w3.kingPullT();

            w3.step(Balance.FIXED_STEP, stay3);

            // 非国王的普通魔物（裂隙小怪）当帧清掉：不清会持续啃咬污染逐帧判伤；召唤 Boss 保留
            for (int e = 0; e < w3.highWater(); e++) {
                if (w3.alive[e] && w3.kind[e] == World.KIND_ENEMY
                        && e != k3 && w3.variant[e] != World.V_BOSS) {
                    w3.despawn(e);
                }
            }

            float deltaP = w3.hp[wid3] - php0;
            float deltaK = w3.hp[k3] - khp0;

            // 传送：起跳帧（前摇由 0 变正）查落点距 / 节拍间隔；起爆帧查爆发伤害
            boolean teleBegun = telePre <= 0f && w3.kingTeleT() > 0f;
            boolean teleExploded = telePre > 0f && w3.kingTeleT() <= 0f;
            if (telePre > 0f) {
                telegraphFrames++;
            }
            if (teleBegun) {
                teleJumps++;
                float landDist = dist(w3.x[k3], w3.y[k3], w3.x[wid3], w3.y[wid3]);
                if (landDist > Balance.KING3_TELE_RANGE + 2f) {
                    teleLandingBad++;
                }
                if (firstTeleT < 0f) {
                    firstTeleT = tRel3;
                }
                if (lastJumpT > 0f) {
                    minJumpGap = Math.min(minJumpGap, tRel3 - lastJumpT);
                    maxJumpGap = Math.max(maxJumpGap, tRel3 - lastJumpT);
                }
                lastJumpT = tRel3;
                if (!drCastDone) {
                    // 首轮前摇里补测「前摇期间只有 20% 减伤」：直喂 1000 应掉 800
                    drCastDone = true;
                    w3.hp[k3] = w3.maxHp[k3];
                    float castBefore = w3.hp[k3];
                    w3.damage(k3, 1000f);
                    drCastLoss = castBefore - w3.hp[k3];
                    w3.hp[k3] = w3.maxHp[k3] - 6000f;   // 立刻铺回血亏，供回血观测
                }
            } else {
                kingStrayPath += dist(kx0, ky0, w3.x[k3], w3.y[k3]);
            }
            if (teleExploded) {
                if (deltaP <= -29f) {
                    teleExplodeHit++;   // -30 命中；同帧贴身 -25 叠加也会到 -55，同样算爆发落地
                }
                if (Math.abs(deltaP + Balance.KING3_TELE_DAMAGE) < 1.5f) {
                    teleExplodeExact++;
                }
            }

            // 地刺：警示圈（meta 5）在场 → 到期刺出（meta 6 同帧补视觉），玩家钉在圈心
            boolean spikeTeleNow = false;
            boolean spikeFxNow = false;
            float spikeFxX = 0f;
            float spikeFxY = 0f;
            for (int e = 0; e < w3.highWater(); e++) {
                if (w3.alive[e] && w3.kind[e] == World.KIND_ZONE && w3.life[e] > 0f) {
                    if (w3.meta[e] == World.ZONE_KING_SPIKE_TELE) {
                        spikeTeleNow = true;
                        if (!hadSpikeTele) {
                            spikeRounds++;
                            if (firstSpikeT < 0f) {
                                firstSpikeT = tRel3;
                                spikeParamR = w3.r[e];
                                spikeParamLife = w3.life[e];
                                spikeParamDmg = w3.dmg[e];
                            }
                        }
                    } else if (w3.meta[e] == World.ZONE_KING_SPIKE) {
                        spikeFxNow = true;
                        spikeFxX = w3.x[e];
                        spikeFxY = w3.y[e];
                    }
                }
            }
            if (hadSpikeTele && !spikeTeleNow) {
                if (deltaP <= -29f) {
                    spikeHits++;
                }
                if (Math.abs(deltaP + Balance.KING3_SPIKE_DAMAGE) < 1.5f) {
                    spikeExact++;
                }
                if (spikeFxNow && Math.abs(spikeFxX - ppx0) < 45f
                        && Math.abs(spikeFxY - ppy0) < 45f) {
                    spikeFxOk = true;   // 刺身视觉在同一落点补出
                }
            }
            hadSpikeTele = spikeTeleNow;

            // 牵引：窗口帧数（1.5 秒 ≈ 90 帧）+ 位移方向（王座 = 深渊）与 30 速校验
            boolean pullBegun = pullPre <= 0f && w3.kingPullT() > 0f;
            boolean pullEnded = pullPre > 0f && w3.kingPullT() <= 0f;
            if (pullBegun) {
                pullRounds++;
                pullWinFrames = 0;
                if (firstPullT < 0f) {
                    firstPullT = tRel3;
                }
            }
            if (pullPre > 0f || w3.kingPullT() > 0f) {
                pullFrames++;
                pullWinFrames++;
                float mvx = w3.x[wid3] - ppx0;
                float mvy = w3.y[wid3] - ppy0;
                float mv = (float) Math.hypot(mvx, mvy);
                float tdx = kx0 - ppx0;
                float tdy = ky0 - ppy0;
                float tl = (float) Math.hypot(tdx, tdy);
                if (mv > 1e-3f && tl > 1e-3f) {
                    float dot = (mvx * tdx + mvy * tdy) / (mv * tl);
                    float pxps = mv / Balance.FIXED_STEP;
                    if (dot > 0.98f && pxps > 25f && pxps < 35f) {
                        pullAligned++;
                    }
                }
            }
            if (pullEnded && pullWinFrames >= 88 && pullWinFrames <= 91) {
                pullCleanWindows++;
            }

            // 召唤：场上出现非国王的 V_BOSS；档位（carry）必须落在 0~3 的 Boss 池内
            boolean summonNow = false;
            for (int e = 0; e < w3.highWater(); e++) {
                if (w3.alive[e] && w3.kind[e] == World.KIND_ENEMY
                        && w3.variant[e] == World.V_BOSS && e != k3) {
                    summonNow = true;
                    if (w3.carry[e] < 0f || w3.carry[e] > Balance.BOSS_HP_TIERS.length - 0.1f) {
                        summonTierBad = true;
                    }
                }
            }
            if (summonNow && !hadSummon) {
                summonRounds++;
                if (firstSummonT < 0f) {
                    firstSummonT = tRel3;
                }
            }
            hadSummon = summonNow;

            // 保留机制：裂隙 / 魔弹 / 预警圈轮次（tRel>2 跳过开场裂隙）
            boolean riftNow = false;
            boolean boltNow = false;
            boolean aoeNow = false;
            int boltsNow3 = 0;
            for (int e = 0; e < w3.highWater(); e++) {
                if (!w3.alive[e]) {
                    continue;
                }
                if (w3.kind[e] == World.KIND_FX && w3.meta[e] == World.FX_RIFT
                        && w3.life[e] > 0f) {
                    riftNow = true;
                } else if (w3.kind[e] == World.KIND_PROJECTILE) {
                    boltNow = true;
                    boltsNow3++;
                } else if (w3.kind[e] == World.KIND_ZONE
                        && w3.meta[e] == World.ZONE_WARNING && w3.life[e] > 0f) {
                    aoeNow = true;
                }
            }
            if (tRel3 > 2f && riftNow && !hadRift3) {
                riftRounds3++;
            }
            if (boltNow && !hadBolt3) {
                boltRounds3++;
            }
            if (aoeNow && !hadAoe3) {
                aoeRounds3++;
            }
            hadRift3 = riftNow;
            hadBolt3 = boltNow;
            hadAoe3 = aoeNow;
            maxBolts3 = Math.max(maxBolts3, boltsNow3);

            // 回血：每次造成伤害 +200；每 5 秒把血亏重铺回 6000，
            // 避免长时间摩擦后顶到满血，把 +200 的增量截断成非 200 的碎片
            if (deltaK > 1f) {
                healEvents++;
                healTotal += deltaK;
                if (Math.abs(deltaK - Balance.KING3_HEAL_HIT) < 1.5f) {
                    healExact200++;
                }
                float q = deltaK / Balance.KING3_HEAL_HIT;
                if (Math.abs(q - Math.round(q)) > 0.02f) {
                    healNotMultiple = true;
                    healOddDelta = deltaK;   // 存下异常增量，失败信息里打出来便于定位
                }
            }
            if (i % 300 == 0 && w3.hp[k3] > w3.maxHp[k3] - 5990f) {
                w3.hp[k3] = w3.maxHp[k3] - 6000f;   // 重铺血亏（向下调整，不算回血）
            }
        }

        // ---- 3h 终局：击破王座本体 → 真通关（不误发王座过渡信号） ----
        w3.kill(k3);
        boolean victory3 = w3.victory();
        boolean kingGone3 = w3.kingId() < 0;
        boolean noFallenAgain3 = !w3.kingFallen2();
        boolean phase0After = w3.kingPhase() == 0;

        System.out.println("=== 王宫二阶段 · 转场 / 追击走位 / 3 连发魔弹验证 ===");
        System.out.printf("击破一阶段：决裂信号=%s 国王退场=%s 未结算=%s 倒地锚点=%s%n",
                fallenSig, kingGone, notVictoryYet, downAnchor);
        System.out.printf("王座重生：位置(%.0f,%.0f) 血 %.0f 玩家回位=%s 开场裂隙魔物 %d；玩家 0.5 秒右移 %.1f px（期望 %.1f）%n",
                king2X, king2Y, hp2Val, playerBack, openerEnemies, moved, expectMovePx);
        System.out.printf("追击走位：远档 %.2f px/帧（期望 2.83）中档 %.2f（2.42）近档 %.2f（2.08，dy=%.2f）贴脸仍追击 %.2f px%n",
                farStep, midStep, nearStep, nearDy, closeDist0 - closeDist1);
        System.out.printf("贴身碰撞：国王压身一帧掉血 %.1f（期望 -25）%n", contactDelta);
        System.out.printf("魔弹：%d 轮齐射（首轮 t=%.2fs）单轮峰值 %d 颗 速度 %.0f~%.0f 追踪锁定 %d 帧；出生寿命 %.2fs，齐射最长飞行 %.2fs，命中 %d 次%n",
                boltRounds, firstBoltT, maxBoltsAtOnce, boltSpeedMin, boltSpeedMax, boltTargetFrames,
                boltLifeAtBirth, boltWorstFlight / 60f, boltImpacts);
        System.out.printf("地面提示：%d 轮（首轮 t=%.2fs）r=%.0f life=%.2fs dmg=%.0f 圈心偏移 %.1f px；-50 爆炸命中 %d 次%n",
                zoneRounds, firstZoneT, zoneParamR, zoneParamLife, zoneParamDmg,
                zoneParamOff, zoneHits);
        System.out.printf("裂隙：%d 道（首道 t=%.2fs）魔物峰值 %d（含国王；AOE 会波及清怪）%n",
                riftCount, firstRiftT, maxEnemyTotal);
        System.out.printf("前摇站定：前摇共 %d 帧，国王位移 %.2f px（期望 0）%n", castFrames3, castMove3);
        System.out.printf("击破二阶段：王座信号=%s 国王退场=%s 未结算=%s 倒地锚点=%s 无重复决裂=%s%n",
                fallenSig2, kingGone2, notVictory2, downAnchor2, noFallenAgain);
        System.out.println("=== 王宫三阶段 · 王座本体（减伤 / 传送 / 地刺 / 牵引 / 召唤 / 回血）验证 ===");
        System.out.printf("觉醒：阶段=%d 血 %.0f 出生位=%s 玩家回位=%s；常态 1000 → -%.0f（期望 -200）前摇 1000 → -%.0f（期望 -800）%n",
                phaseAt3, hp3Val, spawnPos3, playerBack3, drIdleLoss, drCastLoss);
        System.out.printf("贴身碰撞（三阶段）：王座压身一帧掉血 %.1f（期望 -15）%n", contactDelta3);
        System.out.printf("传送：%d 次（首次 t=%.2fs）落点超距 %d 次 间隔 %.2f~%.2fs 前摇共 %d 帧；爆发命中 %d 次（干净 -30：%d）%n",
                teleJumps, firstTeleT, teleLandingBad, minJumpGap, maxJumpGap, telegraphFrames,
                teleExplodeHit, teleExplodeExact);
        System.out.printf("地刺：%d 批（首批 t=%.2fs）r=%.0f life=%.2fs dmg=%.0f；钉位命中 %d 次（干净 -30：%d）刺身补帧=%s%n",
                spikeRounds, firstSpikeT, spikeParamR, spikeParamLife, spikeParamDmg,
                spikeHits, spikeExact, spikeFxOk);
        System.out.printf("牵引：%d 轮（首轮 t=%.2fs）观测 %d 帧 方向对齐 %d 帧 完整 1.5s 窗口 %d 个%n",
                pullRounds, firstPullT, pullFrames, pullAligned, pullCleanWindows);
        System.out.printf("召唤：%d 只（首只 t=%.2fs）档位异常=%s；保留：裂隙 %d 道（开场 %d 只）/ 魔弹 %d 轮（峰值 %d 颗）/ 预警圈 %d 轮%n",
                summonRounds, firstSummonT, summonTierBad, riftRounds3, openerMobs3, boltRounds3, maxBolts3, aoeRounds3);
        System.out.printf("回血：%d 次 合计 +%.0f（干净 +200：%d 次 非 200 进位：%s）；非传送漂移 %.2f px%n",
                healEvents, healTotal, healExact200, healNotMultiple, kingStrayPath);
        System.out.printf("击破王座本体：通关=%s 国王退场=%s 无重复王座信号=%s 阶段复位=%s%n",
                victory3, kingGone3, noFallenAgain3, phase0After);

        if (!fallenSig || !kingGone || !notVictoryYet || !downAnchor) {
            System.out.println("!! 失败：一阶段击破应该只发决裂信号（不结算、记录倒地锚点）");
            ok = false;
        }
        if (k2 < 0 || !phase2Now || !respawnPos || !hp2 || !reflagged
                || !playerBack || openerEnemies != Balance.KING2_RIFT_COUNT) {
            System.out.println("!! 失败：二阶段重生（王座位 / 6000 血 / 玩家回位 / 开场裂隙）不正确");
            ok = false;
        }
        if (Math.abs(moved - expectMovePx) > 1.5f) {
            System.out.println("!! 失败：二阶段玩家移速不等于 (法师基础移速 - 二阶段惩罚)");
            ok = false;
        }
        if (Math.abs(farStep - 2.83f) > 0.12f) {
            System.out.println("!! 失败：>400 距离档没有按 170 直线追击");
            ok = false;
        }
        if (Math.abs(midStep - 2.42f) > 0.12f) {
            System.out.println("!! 失败：250~400 距离档没有按 145 追击");
            ok = false;
        }
        if (Math.abs(nearStep - 2.08f) > 0.12f || nearDist0 - nearDist1 < 2.0f) {
            System.out.println("!! 失败：<250 距离档没有按 125 贴身追击");
            ok = false;
        }
        if (Math.abs(closeStep - 2.08f) > 0.12f || closeDist0 - closeDist1 < 2.0f) {
            System.out.println("!! 失败：贴脸时没有继续追击（不应后退拉开）");
            ok = false;
        }
        if (Math.abs(contactDelta + Balance.KING2_CONTACT_DAMAGE) > 0.5f) {
            System.out.println("!! 失败：贴身接触伤害不是 25");
            ok = false;
        }
        if (maxBoltsAtOnce != Balance.KING2_BOLT_COUNT) {
            System.out.println("!! 失败：魔弹没有按每轮 3 颗齐射");
            ok = false;
        }
        if (boltRounds < 3 || firstBoltT < 0f || Math.abs(firstBoltT - 10f) > 0.3f) {
            System.out.println("!! 失败：魔弹没有按 10 秒节拍齐射");
            ok = false;
        }
        if (boltLifeAtBirth < 5.9f || boltLifeAtBirth > 6.01f
                || boltSpeedMin < 128f || boltSpeedMax > 132f) {
            System.out.println("!! 失败：魔弹寿命（6 秒）或速度（130）不正确");
            ok = false;
        }
        if (boltTargetFrames <= 0 || boltWorstFlight > 380) {
            System.out.println("!! 失败：魔弹没有追踪锁定或单发寿命超过 6 秒");
            ok = false;
        }
        if (boltImpacts < 2) {
            System.out.println("!! 失败：魔弹没有追到站桩的玩家（追踪形同虚设）");
            ok = false;
        }
        if (zoneRounds < 3 || firstZoneT < 0f || Math.abs(firstZoneT - 10f) > 0.3f) {
            System.out.println("!! 失败：地面提示没有按 10 秒节拍出现");
            ok = false;
        }
        if (Math.abs(zoneParamR - Balance.KING2_AOE_RADIUS) > 0.5f
                || Math.abs(zoneParamDmg - Balance.KING2_AOE_DAMAGE) > 0.5f
                || zoneParamLife < 1.35f || zoneParamLife > 1.51f
                || zoneParamOff > 3f) {
            System.out.println("!! 失败：地面提示参数（150 / 1.5s 前摇 / 圈心在玩家）不正确");
            ok = false;
        }
        if (zoneHits < 2) {
            System.out.println("!! 失败：地面提示爆炸没有对玩家生效（-50）");
            ok = false;
        }
        if (riftCount < 7 || firstRiftT < 0f || Math.abs(firstRiftT - 5f) > 0.3f) {
            System.out.println("!! 失败：裂隙没有按 5 秒节拍展开");
            ok = false;
        }
        if (maxEnemyTotal < 4 || maxEnemyTotal > Balance.KING2_MAX_ENEMIES + 1) {
            // 下限放宽：国王 AOE 的爆炸通道会同帧波及清怪（explode），同屏数量随 AOE 节奏浮动
            System.out.println("!! 失败：裂隙魔物没有持续积累（同屏应 ≥3 只且不超封顶）");
            ok = false;
        }
        if (castFrames3 < 300 || castMove3 > 1f) {
            System.out.println("!! 失败：AOE 前摇期间国王没有站定蓄力");
            ok = false;
        }
        if (!fallenSig2 || !kingGone2 || !notVictory2 || !downAnchor2 || !noFallenAgain) {
            System.out.println("!! 失败：二阶段击破应该只发「王座本体」过渡信号（不结算、记录倒地锚点）");
            ok = false;
        }
        if (k3 < 0 || !phase3Now || !hp3 || !spawnPos3 || !playerBack3) {
            System.out.println("!! 失败：三阶段觉醒（王座位 / 12000 血 / 玩家回位）不正确");
            ok = false;
        }
        if (Math.abs(drIdleLoss - 200f) > 1.5f || Math.abs(drCastLoss - 800f) > 1.5f) {
            System.out.println("!! 失败：王座减伤不是常态 80% / 前摇窗口 20%");
            ok = false;
        }
        if (Math.abs(contactDelta3 + Balance.KING3_CONTACT_DAMAGE) > 0.5f) {
            System.out.println("!! 失败：三阶段贴身接触伤害不是 15");
            ok = false;
        }
        if (teleJumps < 8 || firstTeleT < 0f || Math.abs(firstTeleT - Balance.KING3_TELE_CD) > 0.15f) {
            System.out.println("!! 失败：传送没有按 3 秒节拍起跳（首轮 3s）");
            ok = false;
        }
        if (minJumpGap < 2.9f || maxJumpGap > 3.1f) {
            System.out.println("!! 失败：传送间隔不是 3 秒周期（1 秒前摇应含在周期内）");
            ok = false;
        }
        if (teleLandingBad > 0) {
            System.out.println("!! 失败：传送落点超出距玩家 50 的范围");
            ok = false;
        }
        if (teleExplodeHit < 4 || teleExplodeExact < 3 || telegraphFrames < 480) {
            System.out.println("!! 失败：传送爆发没有按 1 秒前摇 / 100 范围 / 30 伤害落地");
            ok = false;
        }
        if (spikeRounds < 4 || firstSpikeT < 0f || Math.abs(firstSpikeT - Balance.KING3_SPIKE_CD) > 0.2f) {
            System.out.println("!! 失败：地刺没有按 5 秒节拍出现");
            ok = false;
        }
        if (Math.abs(spikeParamR - Balance.KING3_SPIKE_RADIUS) > 0.5f
                || Math.abs(spikeParamDmg - Balance.KING3_SPIKE_DAMAGE) > 0.5f
                || spikeParamLife < 0.9f || spikeParamLife > 1.05f) {
            System.out.println("!! 失败：地刺参数（40 / 1 秒前摇 / 30）不正确");
            ok = false;
        }
        if (spikeHits < 4 || spikeExact < 2 || !spikeFxOk) {
            System.out.println("!! 失败：地刺到期没有对圈心玩家生效（-30）或刺身视觉缺失");
            ok = false;
        }
        if (pullRounds < 4 || firstPullT < 0f || Math.abs(firstPullT - Balance.KING3_PULL_CD) > 0.25f) {
            System.out.println("!! 失败：深渊牵引没有按 5 秒节拍起手");
            ok = false;
        }
        if (pullCleanWindows < 3) {
            System.out.println("!! 失败：牵引窗口不是 1.5 秒（完整窗口数不足）");
            ok = false;
        }
        if (pullFrames < 300 || pullAligned < pullFrames * 60 / 100) {
            System.out.println("!! 失败：牵引位移没有按 30 速指向王座（「王座视为深渊」）");
            ok = false;
        }
        if (summonRounds < 1 || firstSummonT < 0f || Math.abs(firstSummonT - Balance.KING3_SUMMON_CD) > 0.3f
                || summonTierBad) {
            System.out.println("!! 失败：裂隙召唤 Boss（25 秒 / 非奶蛙档位）不正确");
            ok = false;
        }
        if (riftRounds3 < 4 || boltRounds3 < 2 || aoeRounds3 < 2) {
            System.out.println("!! 失败：三阶段没有保留二阶段的裂隙 / 魔弹 / 预警圈");
            ok = false;
        }
        if (openerMobs3 != Balance.KING3_RIFT_COUNT) {
            System.out.println("!! 失败：三阶段开场裂隙没有按每次 4 只爬出魔物");
            ok = false;
        }
        if (maxBolts3 != Balance.KING3_BOLT_COUNT) {
            System.out.println("!! 失败：三阶段魔弹没有按每轮 5 颗齐射");
            ok = false;
        }
        if (healEvents < 8 || healExact200 < 3 || healTotal < 2000f || healNotMultiple) {
            System.out.println("!! 失败：王座造成伤害没有按每次 +200 汲取生命（异常增量 " + healOddDelta + "）");
            ok = false;
        }
        if (kingStrayPath > 150f) {
            System.out.println("!! 失败：三阶段王座出现了非传送位移（不应走路）");
            ok = false;
        }
        if (!victory3 || !kingGone3 || !noFallenAgain3 || !phase0After) {
            System.out.println("!! 失败：击破王座本体没有按真通关结算（或误发过渡信号）");
            ok = false;
        }

        System.out.println(ok ? "OK：国王行为（一阶段站桩 / 走动 / 技能 + 二阶段重生 / 追击走位 / 贴身碰撞 / 前摇站定 / 3 连发魔弹 / AOE / 裂隙 + 三阶段减伤 / 贴身 15 / 传送爆发 / 地刺 / 牵引 / 召唤 / 回血 / 5 连发魔弹 / 每次 4 只裂隙魔物）全部符合预期"
                : "!! 存在失败项，见上");
        if (!ok) {
            System.exit(1);
        }
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 只留国王：把场上其余敌人（裂隙魔物）移出战场，隔离档位走位观测 */
    private static void clearNonBoss(World w) {
        int king = w.kingId();
        for (int i = 0; i < w.highWater(); i++) {
            if (w.alive[i] && w.kind[i] == World.KIND_ENEMY && i != king) {
                w.despawn(i);
            }
        }
    }

    /** 步进一帧并返回某实体的位移像素（距离带速率观测用） */
    private static float oneStep(World w, int id, InputCommand in) {
        float bx = w.x[id];
        float by = w.y[id];
        w.step(Balance.FIXED_STEP, in);
        return dist(bx, by, w.x[id], w.y[id]);
    }

    private static String spellLabel(int raw, int evolvedMask) {
        if (raw == Spells.NONE) {
            return "空";
        }
        int resolved = Spells.resolve(raw, evolvedMask);
        if (resolved != raw) {
            return Spells.get(resolved).name + "★";
        }
        return Spells.get(raw).name;
    }

    private static int countKind(World w, int kind) {
        int n = 0;
        for (int i = 0; i < w.highWater(); i++) {
            if (w.alive[i] && w.kind[i] == kind) {
                n++;
            }
        }
        return n;
    }

    /** 把场上所有敌人的血池拉到极大，用于满载压测 */
    private static void makeUndying(World w) {
        for (int i = 0; i < w.highWater(); i++) {
            if (w.alive[i] && w.kind[i] == World.KIND_ENEMY) {
                w.maxHp[i] = 1_000_000f;
                w.hp[i] = 1_000_000f;
            }
        }
    }

    /** 在玩家周围铺满敌人，制造最坏情况：全部挤成一团，分离力计算量最大 */
    private static void fill(World w, int count) {
        int side = (int) Math.ceil(Math.sqrt(count));
        float gap = 26f;
        float origin = -side * gap / 2f;
        for (int i = 0; i < count; i++) {
            int id = w.spawnEnemy(origin + (i % side) * gap, origin + (i / side) * gap);
            if (id < 0) {
                break;
            }
        }
    }
}
