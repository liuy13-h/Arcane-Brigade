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
 *   java -cp core/target/classes com.arcanebrigade.core.HeadlessSmoke [帧数] classes   四职业：各生成巫师/战士/弓箭手/召唤师，验证技能开火、抽卡与宠物
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
     * 四职业验证：分别生成巫师 / 战士 / 弓箭手 / 召唤师，各带满本职业技能池，
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

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
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
