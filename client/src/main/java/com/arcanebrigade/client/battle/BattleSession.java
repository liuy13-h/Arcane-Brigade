package com.arcanebrigade.client.battle;

import com.arcanebrigade.core.Balance;
import com.arcanebrigade.core.InputCommand;
import com.arcanebrigade.core.Loadout;
import com.arcanebrigade.core.Upgrades;
import com.arcanebrigade.core.World;

/**
 * 客户端战斗编排器。
 *
 * <p>它是 UI 和 {@link World} 之间唯一的对局控制边界：界面层只收集输入、请求
 * 暂停或选择升级；本类负责世界创建、固定步长推进和升级暂停。渲染器可以只读
 * {@link #world()}，但不得自行调用 {@link World#step(float, InputCommand)}。</p>
 */
public final class BattleSession {

    private static final double STEP = Balance.FIXED_STEP;
    /** 单帧补帧上限，避免卡顿后的死亡螺旋。 */
    private static final int MAX_STEPS = 5;

    private World world;
    private double accumulator;
    private boolean manualPaused;

    public BattleSession(long seed) {
        world = new World(seed);
    }

    /** 当前战斗的权威状态；仅供渲染和只读查询使用。 */
    public World world() {
        return world;
    }

    /** 以指定职业创建本局玩家。 */
    public void start(int heroClass) {
        if (world.wizardCount() == 0) {
            world.spawnWizard(0f, 0f, heroClass);
        }
    }

    /** 仅供启动冒烟测试快速覆盖 Boss 渲染和阶段逻辑。 */
    public void spawnBossForSmoke(int tier) {
        world.spawnBoss(tier);
    }

    /** 丢弃当前对局，准备一局新的战斗。 */
    public void reset(long seed) {
        boolean autoFire = world.isAutoFire();
        world = new World(seed);
        world.setAutoFire(autoFire);
        accumulator = 0.0;
        manualPaused = false;
    }

    /** 由 UI 逐帧调用。返回可传给渲染器的位置插值系数。 */
    public float advance(double elapsedSeconds, InputCommand input) {
        if (world.victory() || world.defeat() || isPaused()) {
            accumulator = 0.0;
            return 0f;
        }

        accumulator += elapsedSeconds;
        int steps = 0;
        while (accumulator >= STEP && steps < MAX_STEPS) {
            world.step((float) STEP, input);
            accumulator -= STEP;
            steps++;
        }
        if (steps == MAX_STEPS) {
            accumulator = 0.0;
        }
        return (float) (accumulator / STEP);
    }

    public void toggleManualPause() {
        if (!world.victory() && !world.defeat()) {
            manualPaused = !manualPaused;
        }
    }

    public boolean isManualPaused() {
        return manualPaused;
    }

    /** 升级三选一出现时自动暂停；与手动暂停共享同一个战斗边界。 */
    public boolean hasPendingUpgrade() {
        return world.wizardCount() > 0
                && world.pendingChoices(world.wizard(0)) > 0;
    }

    public boolean isPaused() {
        return manualPaused || hasPendingUpgrade();
    }

    public Upgrades.Choice[] pendingChoices() {
        if (!hasPendingUpgrade()) {
            return null;
        }
        return world.peekChoices(world.wizard(0));
    }

    public Loadout playerLoadout() {
        return world.wizardCount() == 0 ? null : world.loadout(world.wizard(0));
    }

    public void chooseUpgrade(int index) {
        if (hasPendingUpgrade()) {
            world.applyChoice(world.wizard(0), index);
        }
    }

    public void rerollUpgradeChoices() {
        if (hasPendingUpgrade()) {
            world.rerollChoices(world.wizard(0));
        }
    }
}
