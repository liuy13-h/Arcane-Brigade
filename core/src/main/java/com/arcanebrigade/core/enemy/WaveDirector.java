package com.arcanebrigade.core.enemy;

import com.arcanebrigade.core.World;

import java.util.Random;

/**
 * 刷怪节奏控制器（D4 内容阶段）。
 *
 * 三件事：
 *   1) 基础刷怪：速率随时间爬升，但封顶；场上数量受"软上限"约束，
 *      所以玩家看到的怪明显变少，难度改由敌人血量成长曲线承担。
 *   2) 变体怪：随时间解锁精英 / 小偷 / 远程，按概率混入普通刷怪。
 *   3) Boss：玩家升到 EnemyStats.BOSS_LEVELS 里的等级时登场，一局共 4 只。
 *      Boss 在场时普通刷怪降速，把舞台让给 Boss 战。
 *   4) 小 Boss（骨蛇）：登场等级卡在四只大 Boss 中间（SERPENT_LEVELS），
 *      与大 Boss 共用 bossId 通道因而互斥——两次"考试"之间插一场遭遇战。
 *
 * 等级阈值与刷怪数值全部读 EnemyStats，改 Boss 出场节奏这里自动跟着变。
 */
public final class WaveDirector {

    private float acc;
    /** 每只 Boss 是否已登场，长度对齐 EnemyStats.BOSS_LEVELS */
    private final boolean[] bossSpawned = new boolean[EnemyStats.BOSS_LEVELS.length];
    /** 每条骨蛇（小 Boss）是否已登场，长度对齐 EnemyStats.SERPENT_LEVELS */
    private final boolean[] serpentSpawned = new boolean[EnemyStats.SERPENT_LEVELS.length];
    private final Random rng = new Random(0x5EEDL);
    /** 本局已刷出的普通怪数量（从 1 开始），只给开场保底用（见 pickVariant） */
    private int spawned = 1;
    /** 开场保底是否已用掉，保证只保送一只 */
    private boolean slimeIntroduced;

    public void update(World w, float dt) {
        float t = w.time();

        // --- Boss 等级表：到等级就上，但若上一只还活着就等它倒下再上 ---
        int level = w.playerLevel();
        for (int i = 0; i < EnemyStats.BOSS_LEVELS.length; i++) {
            if (!bossSpawned[i] && level >= EnemyStats.BOSS_LEVELS[i] && w.bossId() < 0) {
                bossSpawned[i] = true;
                w.spawnBoss(i);
            }
        }

        // --- 骨蛇（小 Boss）：夹在四只大 Boss 的等级中间登场 ---
        // 与 Boss 共用 bossId 这一条通道，所以它们天然互斥：同一时刻场上只有一只 Boss 级目标。
        // 正因为互斥，玩家不会遇到"大 Boss 和骨蛇一起来"的混乱场面。
        for (int i = 0; i < EnemyStats.SERPENT_LEVELS.length; i++) {
            if (!serpentSpawned[i] && level >= EnemyStats.SERPENT_LEVELS[i] && w.bossId() < 0) {
                serpentSpawned[i] = true;
                w.spawnBoneSerpent();
            }
        }

        // --- 场上软上限：开局只有 SOFT_CAP_BASE 只，随时间缓慢放开 ---
        int cap = (int) (EnemyStats.SOFT_CAP_BASE + t * EnemyStats.SOFT_CAP_GROWTH);
        if (cap > EnemyStats.MAX_ENEMIES) {
            cap = EnemyStats.MAX_ENEMIES;
        }
        if (w.enemyCount() >= cap) {
            acc = 0f;
            return;
        }

        // --- 基础刷怪（带 ramp 与封顶）---
        float rate = EnemyStats.SPAWN_BASE_RATE + t * EnemyStats.SPAWN_RAMP;
        if (rate > EnemyStats.SPAWN_RATE_CAP) {
            rate = EnemyStats.SPAWN_RATE_CAP;
        }
        if (w.bossId() >= 0) {
            // 小 Boss 只减档、不清场：骨蛇是遭遇战而非 Boss 战，小怪该继续刷
            rate *= w.serpentActive()
                    ? EnemyStats.SERPENT_SPAWN_SUPPRESS : EnemyStats.BOSS_SPAWN_SUPPRESS;
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

    /**
     * 随时间解锁变体：跳跳史莱姆属于基础近战怪，开局就混入；
     * 前 2 分钟其余高级变体（精英 / 小偷 / 远程）不出现，之后逐步混入。
     *
     * 开场保底：rng 用的是固定种子，本局前 59 次掷点全部 >= 0.10，也就是
     * "第 60 只怪之前不可能出现任何变体"。换种子只是换个倒霉位置，固定种子
     * 则意味着每一局都如此——玩家开局一两分钟根本看不到新怪。这里在第
     * SLIME_INTRO_SPAWN 只怪上直接保送一只史莱姆，把新怪尽早摆到玩家面前。
     * 想关掉保底，把 EnemyStats.SLIME_INTRO_SPAWN 设为 0 即可。
     */
    private int pickVariant(float t) {
        if (EnemyStats.SLIME_INTRO_SPAWN > 0 && !slimeIntroduced
                && spawned++ >= EnemyStats.SLIME_INTRO_SPAWN) {
            slimeIntroduced = true;
            return EnemyStats.V_SLIME;
        }
        double slime = EnemyStats.SLIME_MIX;
        double elite = 0, thief = 0, ranged = 0;
        if (t >= 120f) {
            elite = 0.06;
            thief = 0.08;
            ranged = 0.07;
        }
        if (t > 600f) {            // 后期变体占比提高，战场更有层次
            elite = 0.10;
            thief = 0.10;
            ranged = 0.10;
        }
        double roll = rng.nextDouble();
        if (roll < slime) {
            return EnemyStats.V_SLIME;
        }
        if (roll < slime + elite) {
            return EnemyStats.V_ELITE;
        }
        if (roll < slime + elite + thief) {
            return EnemyStats.V_THIEF;
        }
        if (roll < slime + elite + thief + ranged) {
            return EnemyStats.V_RANGED;
        }
        return EnemyStats.V_NORMAL;
    }
}
