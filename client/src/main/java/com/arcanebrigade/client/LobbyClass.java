package com.arcanebrigade.client;

import com.arcanebrigade.core.HeroClass;

/**
 * 大厅/选人相关的职业信息。
 *
 * 召唤师在 main 主线已拥有完整战斗逻辑（HeroClass 注册、技能、属性），
 * 这里仅在客户端用一个独立常量维护其大厅站位信息，让玩家可正常选择并出征。
 */
public final class LobbyClass {

    /** 召唤师（客户端大厅职业 id，对应 core 的 SUMMONER） */
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
