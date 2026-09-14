package com.arcanebrigade.core;

/**
 * 元素与元素反应。
 *
 * 设计要点：反应不是"加数字"，而是玩法突变——所以反应表是独立的一张查找表，
 * 新增元素或新反应只改这里，不动 World 的逻辑。
 *
 * 五种元素（对应设计文档"元素注入"一节）：
 *   火焰 / 冰霜 / 雷电 / 秘法 / 剧毒
 * 秘法不附着状态、不参与反应，只在命中时提供 +20% 伤害（见 SpellDef 使用处）。
 * 剧毒是 DoT 元素，唯一来源是"毒雾轨迹"被动——它存在的意义就是引爆反应。
 */
public final class Element {

    private Element() {}

    public static final int NONE   = 0;
    public static final int FIRE   = 1;
    public static final int FROST  = 2;
    public static final int SHOCK  = 3;
    public static final int ARCANE = 4;
    public static final int POISON = 5;

    public static final int COUNT = 6;

    // ---- 反应 ----
    public static final int R_NONE         = 0;
    /** 燃烧 + 冰霜：范围伤害 + 致盲 */
    public static final int R_STEAM        = 1;
    /** 冰冻 + 雷电：破冰，额外伤害并眩晕 */
    public static final int R_SUPERCONDUCT = 2;
    /** 燃烧 + 雷电：爆炸击退 */
    public static final int R_OVERLOAD     = 3;
    /** 中毒 + 燃烧：立即结算剩余全部毒伤 */
    public static final int R_DETONATE     = 4;

    private static final int[][] TABLE = new int[COUNT][COUNT];

    static {
        put(FIRE, FROST, R_STEAM);
        put(FROST, SHOCK, R_SUPERCONDUCT);
        put(FIRE, SHOCK, R_OVERLOAD);
        put(POISON, FIRE, R_DETONATE);
    }

    private static void put(int a, int b, int reaction) {
        TABLE[a][b] = reaction;
        TABLE[b][a] = reaction;
    }

    /** 查询 a、b 两种元素相遇产生的反应，无反应返回 R_NONE */
    public static int reaction(int a, int b) {
        if (a <= NONE || b <= NONE || a >= COUNT || b >= COUNT) {
            return R_NONE;
        }
        return TABLE[a][b];
    }

    public static String name(int e) {
        return switch (e) {
            case FIRE -> "火焰";
            case FROST -> "冰霜";
            case SHOCK -> "雷电";
            case ARCANE -> "秘法";
            case POISON -> "剧毒";
            default -> "无";
        };
    }

    public static String reactionName(int r) {
        return switch (r) {
            case R_STEAM -> "蒸汽爆发";
            case R_SUPERCONDUCT -> "超导";
            case R_OVERLOAD -> "过载";
            case R_DETONATE -> "引爆";
            default -> "无";
        };
    }
}
