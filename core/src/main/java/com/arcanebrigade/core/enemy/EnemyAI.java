package com.arcanebrigade.core.enemy;

import com.arcanebrigade.core.Balance;
import com.arcanebrigade.core.Element;
import com.arcanebrigade.core.World;

/**
 * 敌怪 AI 的唯一入口：所有敌人的移动 / 索敌 / 攻击逻辑统一收敛在这里。
 * 新增或修改敌人行为只动这个文件（外加 EnemyStats 调参、World 里登记变体），
 * 不再往 World 里塞散落的行为代码。
 *
 * 唯一的分派例外是骨蛇：它由多个实体拼成一条链，移动是"头走轨迹 + 身体跟随"
 * 而非通用追击，塞进这里只会和 chase() 互相打架，所以整体挪到了 BoneSerpent，
 * 这里只负责把它接进分派表。
 *
 * 状态全部存在 World 的 SoA 数组里，本类无字段、无对象——纯按 id 操作数组，
 * 避免上千实体产生逐对象 GC。dt 恒为 Balance.FIXED_STEP。
 */
public final class EnemyAI {

    private EnemyAI() {}

    /** 敌怪同类分离力的暂存缓冲（单线程游戏循环内复用，避免每帧 new） */
    private static final float[] SEP = new float[2];

    /** 每逻辑帧推进所有敌怪。由 World.step 调用（在 updateStatus 之后）。 */
    public static void update(World w, float dt) {
        for (int i = 0; i < w.highWater(); i++) {
            if (w.kind[i] != World.KIND_ENEMY || !w.alive[i]) {
                continue;
            }
            // 被眩晕：不动也不打，只结算击退位移（看起来才像被炸飞）。
            // stunT 递减与击退衰减已由 World.updateStatus 统一处理，这里不再重复。
            // 跳跳史莱姆若在空中被晕，直接落地，不继续飞行。
            if (w.stunT[i] > 0f) {
                w.x[i] += w.kx[i] * dt;
                w.y[i] += w.ky[i] * dt;
                if (w.variant[i] == EnemyStats.V_SLIME) {
                    w.airT[i] = 0f;
                    w.hopH[i] = 0f;
                }
                continue;
            }

            switch (w.variant[i]) {
                case EnemyStats.V_THIEF  -> updateThief(w, i, dt);
                case EnemyStats.V_RANGED -> updateRanged(w, i, dt);
                case EnemyStats.V_SLIME  -> updateSlime(w, i, dt);
                case EnemyStats.V_SERPENT -> {
                    // 骨蛇是"一个怪、多个实体"：只有头（serpent[i] == i）跑 AI，
                    // 一次调用推完整条链；身体与尾巴的位置由链式跟随定，不单独走逻辑。
                    // 不能用 meta[i] == 0 判断——meta 存的是外观，将来换皮就错了。
                    if (w.serpent[i] == i) {
                        BoneSerpent.update(w, i, dt);
                    }
                }
                default             -> chase(w, i, dt);   // 普通 / 精英 / 分裂 / Boss
            }
        }
    }

    /**
     * 通用追击（普通 / 精英 / 分裂 / Boss）：朝最近的可攻击目标平滑移动，
     * 带冰霜减速、同类分离、障碍/边界约束与接触伤害。
     */
    private static void chase(World w, int i, float dt) {
        // 追击目标含宠物：宠物挡在怪和玩家之间时，怪会先啃宠物——这就是"护主"的实质
        int target = w.nearestPlayerUnit(w.x[i], w.y[i]);
        if (target < 0) {
            return;
        }
        // 冰霜减速
        float slow = (w.elem[i] == Element.FROST)
                ? Math.min(w.elemP[i], Balance.ELEM_FROST_MAX_SLOW) : 0f;
        float moveSpeed = w.speed[i] * (1f - slow);

        float dx = w.x[target] - w.x[i];
        float dy = w.y[target] - w.y[i];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1e-3f) {
            w.vx[i] = dx / len * moveSpeed;
            w.vy[i] = dy / len * moveSpeed;
        }

        // 同类分离力：不做这一步，几百只怪会叠成一个点，手感全毁。
        w.enemySeparation(i, SEP);
        w.x[i] += (w.vx[i] + SEP[0] * moveSpeed * EnemyStats.ENEMY_SEPARATION + w.kx[i]) * dt;
        w.y[i] += (w.vy[i] + SEP[1] * moveSpeed * EnemyStats.ENEMY_SEPARATION + w.ky[i]) * dt;
        w.resolveObstacles(i);   // 障碍碰撞推出（敌人也绕不过去）
        w.clampToWorld(i);       // 敌人同样被棕色城墙挡在内侧，不会被挤飞出去

        // 接触伤害（用 cd 做攻击冷却，配合目标无敌帧）
        float ndx = w.x[target] - w.x[i];
        float ndy = w.y[target] - w.y[i];
        float nlen = (float) Math.sqrt(ndx * ndx + ndy * ndy);
        if (nlen < w.r[i] + w.r[target]) {
            w.cd[i] -= dt;
            if (w.cd[i] <= 0f) {
                if (w.iframe[target] <= 0f) {
                    w.damage(target, w.dmg[i]);
                    // 无敌帧：宠物用固定短帧，玩家用职业基础 + 灵巧被动
                    w.iframe[target] = (w.kind[target] == World.KIND_MINION)
                            ? Balance.MINION_IFRAME : w.heroIframe(target);
                }
                w.cd[i] = EnemyStats.ENEMY_ATTACK_CD;
            }
        }
    }

    /** 小偷：追最近的宝石吸收（不攻击玩家），被击杀时掉落翻倍 */
    private static void updateThief(World w, int id, float dt) {
        int gem = w.nearestPickup(w.x[id], w.y[id]);
        int target = w.nearestWizard(w.x[id], w.y[id]);
        float tx, ty;
        if (gem >= 0) {
            tx = w.x[gem]; ty = w.y[gem];
        } else if (target >= 0) {
            tx = w.x[target]; ty = w.y[target];
        } else {
            return;
        }
        float dx = tx - w.x[id], dy = ty - w.y[id];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1e-3f) {
            w.vx[id] = dx / len * w.speed[id];
            w.vy[id] = dy / len * w.speed[id];
        }
        w.x[id] += w.vx[id] * dt;
        w.y[id] += w.vy[id] * dt;
        w.resolveObstacles(id);
        w.clampToWorld(id);
        if (gem >= 0) {
            float gdx = w.x[gem] - w.x[id], gdy = w.y[gem] - w.y[id];
            if (gdx * gdx + gdy * gdy <= EnemyStats.THIEF_STEAL_RADIUS * EnemyStats.THIEF_STEAL_RADIUS) {
                w.carry[id] += 1f;
                w.despawn(gem);
            }
        }
    }

    /** 远程怪：保持距离并向玩家发射弹幕 */
    private static void updateRanged(World w, int id, float dt) {
        int target = w.nearestWizard(w.x[id], w.y[id]);
        if (target < 0) {
            return;
        }
        float dx = w.x[target] - w.x[id], dy = w.y[target] - w.y[id];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1e-3f) {
            float nx = dx / len, ny = dy / len;
            float move;
            if (len < EnemyStats.RANGED_KEEP_DIST) {
                move = -w.speed[id] * 0.8f;
            } else if (len > EnemyStats.RANGED_RANGE) {
                move = w.speed[id] * 0.6f;
            } else {
                move = 0f;
            }
            w.vx[id] = nx * move;
            w.vy[id] = ny * move;
            w.x[id] += w.vx[id] * dt;
            w.y[id] += w.vy[id] * dt;
            w.resolveObstacles(id);
            w.clampToWorld(id);   // 远程怪同样被边界挡住
            w.cd[id] -= dt;
            if (w.cd[id] <= 0f && len <= EnemyStats.RANGED_RANGE) {
                w.spawnProjectileEnemy(id, w.x[target], w.y[target]);
                w.cd[id] = EnemyStats.RANGED_CD;
            }
        }
    }

    /**
     * 跳跳史莱姆：不做平滑移动，而是「落地蓄力 → 朝玩家抛物线跳跃」的循环。
     * 状态机：
     *   airT <= 0（地面蓄力）：原地不动，cd 倒计时；到点起跳。
     *   airT > 0（腾空）：沿起跳方向水平滑行，hopH 按 sin 抛物线抬升再下落；落地重开蓄力。
     * cd 在这里只当蓄力计时器，接触伤害不依赖 cd（仅靠目标无敌帧防连击）。
     */
    private static void updateSlime(World w, int id, float dt) {
        int target = w.nearestPlayerUnit(w.x[id], w.y[id]);

        if (w.airT[id] > 0f) {
            // ---- 腾空 ----
            w.x[id] += w.vx[id] * dt;
            w.y[id] += w.vy[id] * dt;
            w.resolveObstacles(id);
            w.clampToWorld(id);
            w.airT[id] -= dt;
            if (w.airT[id] <= 0f) {
                // 落地：回到蓄力态，蓄力时长随机，避免全场同步起跳
                w.airT[id] = 0f;
                w.hopH[id] = 0f;
                w.vx[id] = 0f;
                w.vy[id] = 0f;
                w.cd[id] = restTime(w);
            } else {
                // 抛物线：progress 0..1，sin(π·progress) 在起跳/落地为 0、中点为峰值
                float prog = 1f - w.airT[id] / EnemyStats.SLIME_AIR_TIME;
                w.hopH[id] = (float) Math.sin(prog * Math.PI) * EnemyStats.SLIME_HOP_HEIGHT;
            }
            contactDamage(w, id, target);
            return;
        }

        // ---- 地面蓄力 ----
        w.hopH[id] = 0f;
        if (target < 0) {
            return;
        }
        w.cd[id] -= dt;
        if (w.cd[id] <= 0f) {
            jump(w, id, target);
        }
        contactDamage(w, id, target);
    }

    /** 起跳：朝玩家方向（带随机抖动）设定水平速度并进入腾空态 */
    private static void jump(World w, int id, int target) {
        float dx = w.x[target] - w.x[id];
        float dy = w.y[target] - w.y[id];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float nx = 0f, ny = 0f;
        if (len > 1e-3f) {
            nx = dx / len;
            ny = dy / len;
        }
        // 方向加随机抖动，避免所有史莱姆落进同一个点
        float jitter = (w.nextFloat() * 2f - 1f) * EnemyStats.SLIME_JUMP_JITTER;
        float c = (float) Math.cos(jitter), s = (float) Math.sin(jitter);
        w.vx[id] = (nx * c - ny * s) * w.speed[id];
        w.vy[id] = (nx * s + ny * c) * w.speed[id];
        w.airT[id] = EnemyStats.SLIME_AIR_TIME;
        w.hopH[id] = 0f;
    }

    /** 蓄力时长：随机落在 EnemyStats.SLIME_REST_MIN..MAX 之间 */
    private static float restTime(World w) {
        return EnemyStats.SLIME_REST_MIN + w.nextFloat() * (EnemyStats.SLIME_REST_MAX - EnemyStats.SLIME_REST_MIN);
    }

    /** 接触伤害（史莱姆用）：只靠目标无敌帧防连击，不占用 cd */
    private static void contactDamage(World w, int i, int target) {
        if (target < 0) {
            return;
        }
        float ndx = w.x[target] - w.x[i];
        float ndy = w.y[target] - w.y[i];
        float nlen = (float) Math.sqrt(ndx * ndx + ndy * ndy);
        if (nlen < w.r[i] + w.r[target]) {
            if (w.iframe[target] <= 0f) {
                w.damage(target, w.dmg[i]);
                w.iframe[target] = (w.kind[target] == World.KIND_MINION)
                        ? Balance.MINION_IFRAME : w.heroIframe(target);
            }
        }
    }
}
