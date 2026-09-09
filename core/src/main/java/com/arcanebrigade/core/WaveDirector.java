package com.arcanebrigade.core;

import java.util.Random;

/**
 * 刷怪节奏控制器（D4 内容阶段）。
 *
 * 三件事：
 *   1) 基础刷怪：速率随时间爬升，但封顶；场上数量受"软上限"约束，
 *      所以玩家看到的怪明显变少，难度改由敌人血量成长曲线承担。
 *   2) 变体怪：随时间解锁精英 / 小偷 / 远程，按概率混入普通刷怪。
 *   3) Boss：玩家升到 Balance.BOSS_LEVELS 里的等级时登场，一局共 4 只。
 *      Boss 在场时普通刷怪降速，把舞台让给 Boss 战。
 *
 * 等级阈值与刷怪数值全部读 Balance，改 Boss 出场节奏这里自动跟着变。
 */
public final class WaveDirector {

    private float acc;
    /** 每只 Boss 是否已登场，长度对齐 Balance.BOSS_LEVELS */
    private final boolean[] bossSpawned = new boolean[Balance.BOSS_LEVELS.length];
    private final Random rng = new Random(0x5EEDL);

    public void update(World w, float dt) {
        float t = w.time();

        // --- Boss 等级表：到等级就上，但若上一只还活着就等它倒下再上 ---
        int level = w.playerLevel();
        for (int i = 0; i < Balance.BOSS_LEVELS.length; i++) {
            if (!bossSpawned[i] && level >= Balance.BOSS_LEVELS[i] && w.bossId() < 0) {
                bossSpawned[i] = true;
                w.spawnBoss(i);
            }
        }

        // --- 场上软上限：开局只有 SOFT_CAP_BASE 只，随时间缓慢放开 ---
        int cap = (int) (Balance.SOFT_CAP_BASE + t * Balance.SOFT_CAP_GROWTH);
        if (cap > Balance.MAX_ENEMIES) {
            cap = Balance.MAX_ENEMIES;
        }
        if (w.enemyCount() >= cap) {
            acc = 0f;
            return;
        }

        // --- 基础刷怪（带 ramp 与封顶）---
        float rate = Balance.SPAWN_BASE_RATE + t * Balance.SPAWN_RAMP;
        if (rate > Balance.SPAWN_RATE_CAP) {
            rate = Balance.SPAWN_RATE_CAP;
        }
        if (w.bossId() >= 0) {
            rate *= Balance.BOSS_SPAWN_SUPPRESS;
        }
        acc += dt * rate;
        while (acc >= 1f) {
            acc -= 1f;
            if (w.enemyCount() >= cap) {
                acc = 0f;
                break;
            }
            w.spawnEnemyVariant(pickVariant(t));
        }
    }

    /** 随时间解锁变体：前 2 分钟只有普通怪，之后逐步混入精英 / 小偷 / 远程 */
    private int pickVariant(float t) {
        if (t < 120f) {
            return World.V_NORMAL;
        }
        double elite = 0.06, thief = 0.08, ranged = 0.07;
        if (t > 600f) {            // 后期变体占比提高，战场更有层次
            elite = 0.10;
            thief = 0.10;
            ranged = 0.10;
        }
        double roll = rng.nextDouble();
        if (roll < elite) {
            return World.V_ELITE;
        }
        if (roll < elite + thief) {
            return World.V_THIEF;
        }
        if (roll < elite + thief + ranged) {
            return World.V_RANGED;
        }
        return World.V_NORMAL;
    }
}
