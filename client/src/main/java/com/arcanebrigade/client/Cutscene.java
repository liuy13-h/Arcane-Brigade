package com.arcanebrigade.client;

import javafx.scene.image.Image;

/**
 * 剧情 CG 的完整脚本（数据与演出分离）。
 *
 * 一条剧情 = 一串 Step，每步有类型：
 *   K_LINE    galgame 对话（战场背景 + 底部对话框；空格推进 / 打字机）
 *   K_NARRATE 黑屏旁白（电影式字幕；空格推进 / 打字机）
 *   K_RECALL  闪回碎片（黑屏快闪一张图，自动）
 *   K_FLASH   闪白（自动，一瞬）
 *   K_PARTY   勇者小队集结（黑屏四人阵，自动）
 *   K_BLACK   黑场停留（自动）
 *   K_HALL    转场到王宫大殿（镜头拉远 + 标题字，自动）
 *   K_TITLE   决战标题卡（深渊裂隙铺底 + 大字主标题/副标题，自动）
 *
 * 演出层只认 kind 与时间轴，所有「怎么画」都在 Renderer.drawCutscene。
 * 后续新增剧情（王宫前置剧情 / 国王决战台词）只需在这里追加新的工厂方法。
 *
 * 播放规则（用户要求「锁操作、全程自动 + 空格可推进」）：
 *   · 文字步按固定速度逐字打出（打字机），打完停留 HOLD 秒自动翻页；
 *   · 按空格：打字未完成 → 立即补全当前句；已打完 → 立即翻下一句；
 *   · 闪回 / 闪白 / 转场等演出步不接受推进，按节拍自动走。
 */
public final class Cutscene {

    // ---- 步骤类型 ----
    public static final int K_LINE = 0;
    public static final int K_NARRATE = 1;
    public static final int K_RECALL = 2;
    public static final int K_FLASH = 3;
    public static final int K_PARTY = 4;
    public static final int K_BLACK = 5;
    public static final int K_HALL = 6;
    public static final int K_TITLE = 7;

    /** 一步剧情。文字步用 speaker/text，演出步用 image/dur */
    public static final class Step {
        public final int kind;
        public final String speaker;
        public final String text;
        public final Image image;
        public final float dur;

        Step(int kind, String speaker, String text, Image image, float dur) {
            this.kind = kind;
            this.speaker = speaker;
            this.text = text;
            this.image = image;
            this.dur = dur;
        }
    }

    /** 打字机速度：每秒显示的字符数 */
    private static final float CPS = 26f;
    /** 文字步打完后额外停留的秒数（自动翻页；空格可随时提前） */
    private static final float HOLD = 2.2f;

    private final Step[] steps;
    /** 当前正在播第几步；>= steps.length 表示全片结束 */
    private int index;
    /** 当前步已播放秒数（驱动打字机与自动翻页） */
    private float t;
    /** 全片已播放秒数（总时长，调试与"待续"计时用） */
    private float total;

    private Cutscene(Step[] steps) {
        this.steps = steps;
    }

    // ---- 脚本建造小工具 ----
    private static Step line(String who, String text) {
        return new Step(K_LINE, who, text, null, 0f);
    }

    private static Step narrate(String text) {
        return new Step(K_NARRATE, "", text, null, 0f);
    }

    private static Step recall(Image img, float dur) {
        return new Step(K_RECALL, null, null, img, dur);
    }

    private static Step flash() {
        return new Step(K_FLASH, null, null, null, 0.42f);
    }

    private static Step party() {
        return new Step(K_PARTY, null, null, null, 2.6f);
    }

    private static Step black(float dur) {
        return new Step(K_BLACK, null, null, null, dur);
    }

    private static Step hall(float dur) {
        return new Step(K_HALL, null, null, null, dur);
    }

    /** 标题卡：speaker 存主标题、text 存副标题，自动播放固定时长 */
    private static Step title(String main, String sub) {
        return new Step(K_TITLE, main, sub, null, 3.0f);
    }

    /**
     * 击败奶娃后的完整剧情链：奶娃遗言（CG1）→ 勇者顿悟反转（CG2）→ 转场王宫大殿。
     *
     * CG1 台词与 CG2 旁白均为用户指定的原文，不可改动。
     * 用户明确要求：不再做"化作微光消散"的收尾，最后一句后直接闪白进入顿悟。
     */
    public static Cutscene postMilky() {
        return new Cutscene(new Step[] {
                // ---- CG1：奶娃倒下的诉说（galgame 对话框，空格可推进） ----
                line("奶娃", "战斗…… 终于停下了。"),
                line("奶娃", "我只是，从根源之中诞生的存在。我没有想要毁灭一切，只是本能地活着。"),
                line("奶娃", "我只是无数魔物中的一个中继。诞生我的那个源头，在我成型之后，就把我遗弃在了这片地底。"),
                line("奶娃", "很奇怪…… 你们身上，带着那股源头的气息。"),
                line("奶娃", "那是一切魔物诞生的根源。我一直都在追寻这股气息，可为什么…… 这股源头的味道，会留在你们身上？"),
                // 画面快速闪白：顿悟的界点
                flash(),

                // ---- CG2：勇者顿悟反转（黑屏 + 闪回 + 旁白） ----
                black(0.5f),
                narrate("原来如此……一切都解释得通了。"),
                // 快速闪回：国王的虚伪面容 → 地底魔物幼体（催逼深入洞穴的暗影）→ 暗中孕育的魔物
                recall(Sprites.heroes[0], 1.0f),
                recall(Sprites.milkyPortrait, 1.0f),
                recall(Sprites.bosses[2], 1.0f),
                narrate("整片大陆的魔物灾祸、诞生于地底的魔物幼体、被抛弃的魔物子嗣……根本不是天灾。"),
                narrate("奶娃身上的血脉气息、勇者身上沾染的王室气息完全同源。"),
                narrate("一直躲在王座之上，伪装成仁慈明君、命令勇者斩尽杀绝魔物的国王——才是所有魔物的源头，才是抛弃奶娃、制造一切灾难的真正幕后黑手！"),
                // 全员瞬间握紧武器：沉默的集结
                party(),
                narrate("不好！王宫有危险！整个王国、所有真相，全部被他欺骗了！必须立刻赶回王宫！"),

                // ---- 转场：拉远 → 王宫大殿 ----
                hall(2.0f),
        });
    }

    /**
     * 国王决裂对白（一阶段击破后）：短暂安静 → 国王跪地低笑 → 勇者质问 →「一切才刚刚开始」。
     *
     * 台词与舞台指示为用户原文，不可改动；舞台指示并入说话人名牌展示。
     * 客户端在播完本段后调用 World.beginKingPhase2 完成转场
     * （王座重生新国王 + 背景换为王宫二阶段 + 角色损失 20 点移速）。
     */
    public static Cutscene postKing1() {
        return new Cutscene(new Step[] {
                // 短暂安静：画面暗下，只剩大殿回声（BGM 已由客户端停掉）
                black(1.4f),
                line("国王（跪地，低声，像在笑）", "……你们以为，这样就结束了？"),
                line("勇者A（皱眉，武器未收）", "你还有什么遗言？"),
                line("国王（缓缓抬头，嘴角带血，笑意诡异）", "遗言？不。"),
                line("国王（声音逐渐变得平稳，甚至恢复了几分王座上的从容）", "一切才刚刚开始"),
                // 闪白：王座之上，二阶段重生（beginKingPhase2 在闪白之后触发）
                flash(),
                black(0.35f),
        });
    }

    /**
     * 「王座本体」过渡剧情（二阶段击破后）：国王滑落王座说出真相 → 深渊裂隙标题卡。
     *
     * 台词与舞台指示为用户原文，不可改动；舞台指示并入说话人名牌展示。
     * 客户端在播完本段后调用 World.beginKingPhase3 完成转场
     * （王座觉醒：12000 生命 + 80% 减伤，王宫本身开始参与攻击）。
     */
    public static Cutscene postKing2() {
        return new Cutscene(new Step[] {
                // 短暂安静：魔光黯淡，只剩国王从王座上滑落后的喘息（BGM 已由客户端停掉）
                black(1.2f),
                line("国王（声音沙哑，断断续续）", "……你们，真的以为……打败我了？"),
                line("勇者B（皱眉）", "什么意思？"),
                line("国王（抬起仅剩的一只手，指向王座）", "我从一开始……就不是坐在王座上的人。"),
                line("国王（身体逐渐碎裂）", "我是王座本身。是王国历代君王……堆叠出来的东西。"),
                line("勇者C（瞳孔收缩）", "历代……君王？"),
                line("国王（笑声越来越轻）", "每一代国王，都往王座里注入野心、恐惧、对魔物的贪婪。一代，又一代。最后……王座醒了"),
                line("勇者A（低声）", "所以，你根本不是某个人。"),
                line("国王（最后一句，几乎像叹息）", "我是王国自己……长出来的怪物。"),
                // 暗紫魔光剧烈闪烁（客户端按 storyMode 5 渲染为紫色），王座觉醒
                flash(),
                // 标题卡：最终决战 · 第三阶段
                title("最终决战 · 第三阶段", "目标：国王（王座本体）"),
                // 阶段特性（用户原文）
                narrate("阶段特性：王宫本身参与攻击，场景中会持续出现王座脉络攻击。"),
                narrate("国王不再固定于王座，会随王宫结构移动。"),
                narrate("第二阶段机制部分保留，并叠加王座本体的新技能。"),
                black(0.5f),
        });
    }

    /** 推进一步。dt 为真实帧间隔（CG 用真实时间，不受暂停/固定步长影响） */
    public void update(float dt) {
        if (done()) {
            return;
        }
        t += dt;
        total += dt;
        Step s = steps[index];
        float need = isTextStep(s) ? s.text.length() / CPS + HOLD : s.dur;
        if (t >= need) {
            index++;
            t = 0f;
        }
    }

    /**
     * 玩家按空格：文字的"下一句"通道。
     * 打字中 → 立即补全当前句；整句已显示 → 立刻翻页。演出步无响应。
     */
    public void pressAdvance() {
        if (done()) {
            return;
        }
        Step s = steps[index];
        if (!isTextStep(s)) {
            return;
        }
        float typeNeed = s.text.length() / CPS;
        if (t < typeNeed) {
            t = typeNeed;      // 补全当前句（保持"打完"状态，等到再次空格或 HOLD）
        } else {
            index++;
            t = 0f;
        }
    }

    private static boolean isTextStep(Step s) {
        return s.kind == K_LINE || s.kind == K_NARRATE;
    }

    /** 全片是否播完（K_HALL 转场结束即 true） */
    public boolean done() {
        return index >= steps.length;
    }

    /** 当前步骤；全片结束后返回 null */
    public Step current() {
        return done() ? null : steps[index];
    }

    /** 当前步已播放秒数（闪回/转场进度用） */
    public float stepT() {
        return t;
    }

    /** 全片已播放秒数 */
    public float totalTime() {
        return total;
    }

    /** 当前句的说话人（非对话步或全片结束后为空串） */
    public String speaker() {
        if (done()) {
            return "";
        }
        Step s = steps[index];
        return s.speaker != null ? s.speaker : "";
    }

    /** 当前句的完整文本（非文字步或全片结束后为空串） */
    public String text() {
        if (done()) {
            return "";
        }
        Step s = steps[index];
        return s.text != null ? s.text : "";
    }

    /** 当前应显示的字符数：打字机进度（整句打完后等于文本长度） */
    public int revealed() {
        if (done()) {
            return 0;
        }
        Step s = steps[index];
        if (!isTextStep(s)) {
            return 0;
        }
        return Math.min(s.text.length(), (int) (t * CPS));
    }

    /** 当前句是否已打完（渲染层据此切换"继续"标记） */
    public boolean lineFinished() {
        if (done()) {
            return true;
        }
        Step s = steps[index];
        return !isTextStep(s) || revealed() >= s.text.length();
    }
}
