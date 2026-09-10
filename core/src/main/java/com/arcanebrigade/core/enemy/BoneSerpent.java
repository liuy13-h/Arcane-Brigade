package com.arcanebrigade.core.enemy;

import com.arcanebrigade.core.Balance;
import com.arcanebrigade.core.World;

/**
 * 骨蛇（小 Boss）的行为：一条绕玩家走"∞"字的多节飞蛇，类似泰拉瑞亚的飞龙。
 *
 * 为什么单独一个文件：骨蛇是全场唯一"一个怪由多个实体拼成"的敌人，
 * 它的移动不是 EnemyAI 里那种"朝目标走"的通用追击，而是
 * 「头走轨迹 + 身体链式跟随」，两者结构完全不同，硬塞进 chase() 只会互相干扰。
 *
 * 三个要点：
 *   1) 头沿 Gerono 双纽线（cos φ, sin 2φ）绕玩家飞行 —— 这条参数曲线本身就是
 *      一个横躺的"∞"。轨迹以**玩家当前位置**为中心，所以玩家跑到哪，蛇跟到哪。
 *   2) 头始终朝向玩家（美术朝上，故角度 = atan2(dx, -dy)）；身体各节的朝向
 *      由"指向自己前一节"确定，整条蛇自然连成一条曲线。
 *   3) 只有头有速度与转向，身体是刚性跟随：每节钉在前一节后方固定间距处，
 *      不做插值也不做平滑 —— 硬约束解出来的蛇身才会紧贴轨迹、像被拖着飞。
 *
 * 血量不在这个文件里：血池存在头节点上，任意一节挨打由 World.damage() 转发，
 * 所以这里只管"怎么动"和"怎么咬"。
 *
 * 无字段、无对象，纯按 id 操作 World 的 SoA 数组，dt 恒为 Balance.FIXED_STEP。
 */
public final class BoneSerpent {

    private BoneSerpent() {}

    /**
     * 推进整条骨蛇。**只由头节点调用**（EnemyAI 认 serpent[i] == i），
     * 一次调用处理全身：头飞行 → 身体跟随 → 接触伤害。
     */
    public static void update(World w, int head, float dt) {
        // 追击目标含宠物：宠物挡在蛇和玩家之间时，蛇会先啃宠物
        int target = w.nearestPlayerUnit(w.x[head], w.y[head]);
        // 没有目标时以自身为中心画圈，蛇不会突然停摆
        float px = (target >= 0) ? w.x[target] : w.x[head];
        float py = (target >= 0) ? w.y[target] : w.y[head];

        flyHead(w, head, px, py, dt);
        followBody(w, head);
        bite(w, head, target, dt);
    }

    /**
     * 头部：朝"∞"轨迹上的当前点飞。
     *
     * 注意这里是**追**轨迹点而不是直接把它钉在轨迹上：直接赋值的话，玩家一动
     * 整条蛇就跟着刚性平移，看着像贴纸；限速追击则会自然地甩出一段距离，
     * 折返时还会被甩过头再绕回来 —— 这正是飞龙该有的惯性观感。
     */
    private static void flyHead(World w, int head, float px, float py, float dt) {
        float ph = w.time() * EnemyStats.SERPENT_OMEGA;
        // Gerono 双纽线：x 用 cos φ，y 用 sin 2φ，φ 转一圈走一个 8 字
        float gx = px + (float) Math.cos(ph) * EnemyStats.SERPENT_LOOP_R;
        float gy = py + (float) Math.sin(ph * 2f) * EnemyStats.SERPENT_LOOP_RY;

        float dx = gx - w.x[head];
        float dy = gy - w.y[head];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1e-3f) {
            // len/dt 就是"这一帧走完"需要的速度，取它和上限的较小值：
            // 离得远时按最高速追，快到位时自动减速，不会在轨迹点附近来回抖
            float sp = Math.min(len / dt, EnemyStats.SERPENT_SPEED);
            w.vx[head] = dx / len * sp;
            w.vy[head] = dy / len * sp;
        }
        w.x[head] += w.vx[head] * dt;
        w.y[head] += w.vy[head] * dt;
        w.resolveObstacles(head);
        w.clampToWorld(head);
    }

    /**
     * 身体与尾巴：第 n 节钉在第 n-1 节后方固定间距处（从头往尾依次解）。
     *
     * 用的是硬约束而不是弹簧/插值：硬约束每帧都精确满足间距，蛇身看起来是
     * 一条刚性的骨头链；用插值的话高速折返时身体会被拉长成面条。
     *
     * 头的间距（HEAD_GAP）与节间距（SPACING）不同：头美术高 76，脖子接在底端，
     * 得给它的整个头长让位，否则第一节身体会埋在头骨里。
     */
    private static void followBody(World w, int head) {
        int n = w.serpentSegmentCount();
        for (int s = 1; s < n; s++) {
            int cur = w.serpentSegment(s);
            int prev = w.serpentSegment(s - 1);
            if (cur < 0 || prev < 0 || !w.alive[cur] || !w.alive[prev]) {
                continue;
            }
            float gap = (s == 1) ? EnemyStats.SERPENT_HEAD_GAP : EnemyStats.SERPENT_SPACING;
            float ax = w.x[prev] - w.x[cur];
            float ay = w.y[prev] - w.y[cur];
            float al = (float) Math.sqrt(ax * ax + ay * ay);
            if (al < 1e-3f) {
                // 两节完全重合时方向没有定义（实体池复用时可能发生）：
                // 退而用头的速度方向，保证这一帧仍能推出一个合法的位置
                ax = w.vx[head];
                ay = w.vy[head];
                al = (float) Math.sqrt(ax * ax + ay * ay);
                if (al < 1e-3f) {
                    ax = 0f;
                    ay = -1f;
                    al = 1f;
                }
            }
            w.x[cur] = w.x[prev] - ax / al * gap;
            w.y[cur] = w.y[prev] - ay / al * gap;
            w.resolveObstacles(cur);
            w.clampToWorld(cur);
        }
    }

    /**
     * 接触伤害：全身每一节都是伤害区（整条蛇撞上去都疼），但真正防止连击的
     * 是目标的无敌帧 —— 十二节在同一帧里最多命中一次，所以不必只让头结算。
     * 伤害值取的是头的 dmg（各节在生成时已同步成同一个值）。
     */
    private static void bite(World w, int head, int target, float dt) {
        if (target < 0) {
            return;
        }
        int n = w.serpentSegmentCount();
        for (int s = 0; s < n; s++) {
            int seg = w.serpentSegment(s);
            if (seg < 0 || !w.alive[seg]) {
                continue;
            }
            float dx = w.x[target] - w.x[seg];
            float dy = w.y[target] - w.y[seg];
            float rr = w.r[seg] + w.r[target];
            if (dx * dx + dy * dy > rr * rr) {
                continue;
            }
            w.cd[seg] -= dt;
            if (w.cd[seg] > 0f) {
                continue;
            }
            if (w.iframe[target] <= 0f) {
                w.damage(target, w.dmg[head]);
                w.iframe[target] = (w.kind[target] == World.KIND_MINION)
                        ? Balance.MINION_IFRAME : w.heroIframe(target);
            }
            w.cd[seg] = EnemyStats.SERPENT_ATTACK_CD;
        }
    }

    /**
     * 某一节的朝向（弧度），渲染层直接拿去旋转精灵。
     *
     * 头朝玩家；其余各节朝自己的前一节 —— 与 followBody 用的是同一个方向，
     * 所以画出来的骨节朝向和实际骨链完全一致，不会出现"骨头朝左、身体朝右"。
     * 朝向不落库：它下一秒就能从位置重新推出来，存下来反而多一份要同步的状态。
     *
     * 三张美术都是**朝上**画的（头骨在上、脖子在下），所以要把"精灵的朝上"
     * 转到方向 (dx, dy) 上：屏幕坐标 y 向下、旋转顺时针为正，解出来是
     * atan2(dx, -dy)。用 atan2(dy, dx) 的话整条蛇会集体偏 90°。
     */
    public static float segmentAngle(World w, int n) {
        int cur = w.serpentSegment(n);
        if (cur < 0) {
            return 0f;
        }
        float dx, dy;
        if (n == 0) {
            // 头：朝最近的可攻击目标（与 AI 的索敌口径一致）
            int target = w.nearestPlayerUnit(w.x[cur], w.y[cur]);
            if (target >= 0) {
                dx = w.x[target] - w.x[cur];
                dy = w.y[target] - w.y[cur];
            } else {
                // 没有目标时朝自己的飞行方向，总比僵在原地指着一个死方向好
                dx = w.vx[cur];
                dy = w.vy[cur];
            }
        } else {
            int prev = w.serpentSegment(n - 1);
            if (prev < 0) {
                return 0f;
            }
            dx = w.x[prev] - w.x[cur];
            dy = w.y[prev] - w.y[cur];
        }
        if (dx * dx + dy * dy < 1e-6f) {
            return 0f;
        }
        return (float) Math.atan2(dx, -dy);
    }
}
