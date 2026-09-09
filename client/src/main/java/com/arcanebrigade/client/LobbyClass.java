package com.arcanebrigade.client;

import com.arcanebrigade.core.HeroClass;

/**
 * 大厅/选人相关、但 core 里还没有的职业信息。
 *
 * 召唤师目前只有立绘与名字，战斗逻辑（HeroClass 注册、技能、属性）尚未做，
 * 所以只在客户端用一个独立常量占位，不去动 core：玩家可以看、可以高亮，
 * 但走到光门按 E 会被拦下（见 GameApp.tryDepart）。
 */
public final class LobbyClass {

    /** 召唤师（客户占位职业 id，尚未在 core 注册） */
    public static final int SUMMONER = 4;

    /** 大厅四个站位的顺序：下标与 Renderer.geom 的 station 一一对应 */
    public static final int[] CLASSES = {
            HeroClass.WIZARD,
            HeroClass.WARRIOR,
            HeroClass.ARCHER,
            SUMMONER
    };

    private LobbyClass() {}

    /** 职业中文名（含客户端占位的召唤师） */
    public static String name(int id) {
        return id == SUMMONER ? "召唤师" : HeroClass.name(id);
    }
}
