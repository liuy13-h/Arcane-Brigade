package com.arcanebrigade.core;

import java.nio.ByteBuffer;

/**
 * 一帧的输入指令。
 *
 * 这是联机的关键抽象：客户端只上报这个结构，主机只消费这个结构。
 * 现在它来自键盘，D5 联机时它来自网络，World 的代码一行都不用改。
 */
public final class InputCommand {

    public static final int BYTES = 4 + 4 + 4;

    /** 期望移动方向，未归一化时长度 &lt;= 1 */
    public float dx;
    public float dy;
    /** 位标记，预留给主动技能 / 复活令牌 */
    public int buttons;

    public void reset() {
        dx = 0f;
        dy = 0f;
        buttons = 0;
    }

    public void set(float dx, float dy) {
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1e-4f) {
            this.dx = dx / len;
            this.dy = dy / len;
        } else {
            this.dx = 0f;
            this.dy = 0f;
        }
    }

    // ---- 网络序列化（UDP 打包用）----

    public void write(ByteBuffer b) {
        b.putFloat(dx);
        b.putFloat(dy);
        b.putInt(buttons);
    }

    public void read(ByteBuffer b) {
        dx = b.getFloat();
        dy = b.getFloat();
        buttons = b.getInt();
    }

    public InputCommand copy() {
        InputCommand c = new InputCommand();
        c.dx = dx;
        c.dy = dy;
        c.buttons = buttons;
        return c;
    }
}
