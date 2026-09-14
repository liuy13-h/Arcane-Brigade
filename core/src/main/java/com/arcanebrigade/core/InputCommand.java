package com.arcanebrigade.core;

import java.nio.ByteBuffer;

/**
 * 一帧的输入指令。
 *
 * 一帧的输入指令抽象：上层只上报这个结构，World 只消费它。
 * 当前来源是键盘，未来也可来自其他输入源，World 的写法无需改动。
 */
public final class InputCommand {

    public static final int BYTES = 4 + 4 + 4 + 4 + 4;

    /** 期望移动方向，未归一化时长度 &lt;= 1 */
    public float dx;
    public float dy;
    /** 位标记：bit0 = 手动开火（鼠标按下） */
    public int buttons;
    /** 手动开火时的瞄准世界坐标 */
    public float aimX;
    public float aimY;

    /** 手动开火按钮位 */
    public static final int BUTTON_FIRE = 1;
    /** 指挥按钮位：鼠标点击/按住，给召唤师的宠物下令"朝这里进攻" */
    public static final int BUTTON_ORDER = 2;

    public void reset() {
        dx = 0f;
        dy = 0f;
        buttons = 0;
        aimX = 0f;
        aimY = 0f;
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
        b.putFloat(aimX);
        b.putFloat(aimY);
    }

    public void read(ByteBuffer b) {
        dx = b.getFloat();
        dy = b.getFloat();
        buttons = b.getInt();
        aimX = b.getFloat();
        aimY = b.getFloat();
    }

    public InputCommand copy() {
        InputCommand c = new InputCommand();
        c.dx = dx;
        c.dy = dy;
        c.buttons = buttons;
        c.aimX = aimX;
        c.aimY = aimY;
        return c;
    }
}
