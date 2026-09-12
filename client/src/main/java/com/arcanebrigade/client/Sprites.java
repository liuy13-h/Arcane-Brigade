package com.arcanebrigade.client;

import com.arcanebrigade.core.Element;
import com.arcanebrigade.core.HeroClass;
import javafx.scene.Group;
import javafx.scene.shape.ArcType;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * 程序化生成的精灵图 + 仓库现成美术的加载与预处理。
 *
 * 所有 Image 在启动时一次性烘焙好，运行时只做 drawImage，绝不临时生成。
 */
public final class Sprites {

    /**
     * 按索引的形象。
     * 0 = 国王（大厅初始操控对象）；1..4 = 巫师 / 战士 / 弓箭手 / 召唤师（HeroClass）。
     * 职业形象统一走「裁透明边 + ×2 最近邻放大」，渲染时 1:1 绘制即是清晰像素风。
     */
    public static Image[] heroes = new Image[5];
    /** 按职业索引的走动动画帧（GIF 解码结果）。null 表示没有动画，退化为静态形象 */
    public static GifDecoder.Animation[] heroWalk = new GifDecoder.Animation[5];
    /** 按 Boss 档位索引的 Boss 形象（BOSS_NAMES 的顺序） */
    public static Image[] bosses = new Image[4];
    /** 召唤师的宠物形象（程序化：没给美术素材，自己画一只秘能仆从） */
    public static Image minion;
    /** 王座大厅背景（启动后的准备大厅整屏底图） */
    public static Image lobbyBg;
    /**
     * 主菜单标题画面（整幅美术，底部内嵌四个菜单按钮：开始游戏 / 设置 / 操作说明 / 退出游戏）。
     * 这是程序启动后第一个画面，按钮命中区坐标见 Renderer 的菜单常量。
     */
    public static Image titleScreen;
    /**
     * 各职业的细节立绘（选人大厅右侧滑出的大图）。
     * 下标 = 职业 id（1..4 = 巫师 / 战士 / 弓箭手 / 召唤师）。
     * 这是美术给的大尺寸全身立绘，与上方 32px 行走小立绘 heroes[] 相互独立。
     */
    public static Image[] heroPortraits = new Image[5];
    public static Image[] enemies = new Image[3];
    /**
     * 小怪的像素 GIF 动画，下标对齐 enemies[]：
     * 0=壁行者 Wall_Creeper、1=海盗诅咒 Pirate's_Curse、2=海盗诅咒·发光 Pirate's_Curse_(glowing)。
     * null 表示该档位没读到素材，渲染时退回 enemies[] 的程序化形象。
     */
    public static GifDecoder.Animation[] enemyAnim = new GifDecoder.Animation[3];
    /**
     * Boss 的像素 GIF 动画，下标对齐 bosses[]：
     * 0=火星飞碟、1=以太双足飞龙 3、2=以太双足飞龙 2、3=暗黑法师。
     * null 时渲染退回 bosses[] 的静态立绘。
     */
    public static GifDecoder.Animation[] bossAnim = new GifDecoder.Animation[4];
    /** 巫师攻击特效：充能爆能法球（Charged_Blaster_Orb.gif） */
    public static GifDecoder.Animation wizardBolt;
    /** 弓箭手攻击特效：飞刀（Throwing_Knife.png）。方向性投射物，预烘焙 32 个朝向 */
    public static Image[] archerBoltRot;
    /** 战士武器：Influx Waver 光刃。原素材是 webp，JavaFX 无 WebP 解码器，已转成 png */
    public static Image warriorWeapon;
    /** 战士武器按朝向预烘焙（挥砍时跟着角色转，避免运行时软件旋转） */
    public static Image[] warriorWeaponRot;
    /** 战士攻击特效：Terragrim 投射刃（Terragrim_(projectile).gif） */
    public static GifDecoder.Animation warriorArc;
    /** 召唤师召唤物：Abigail 仆从（Abigail_(minion).gif） */
    public static GifDecoder.Animation minionAnim;
    /** 按元素索引的弹体颜色，见 Element。运行时只查表，不做任何变换 */
    public static Image[] bolts = new Image[Element.COUNT];
    /** 敌方弹幕（统一的"敌意红"），与玩家元素弹做明显区分 */
    public static Image enemyBolt;
    public static Image gem;

    // ---- 骨蛇（小 Boss，lobby-king 引入）----
    /** 蛇头：38×76 朝上的骨骼立绘。null 时退回 enemies[0] 替代 */
    public static Image serpentHead;
    /** 蛇身：中段骨节，水平朝向 */
    public static Image serpentBody;
    /** 蛇尾：末端骨节 */
    public static Image serpentTail;

    // ---- 5 关 Boss 奶蛙：血条头像 + 四套动作 GIF ----
    /** 奶蛙血条右侧头像（与血条等高显示） */
    public static Image milkyPortrait;
    /** 向左行走 / 向右行走（两张独立动图） */
    public static GifDecoder.Animation milkyWalkLeft;
    public static GifDecoder.Animation milkyWalkRight;
    /** 蓄力踩地（单次）与其镜像版，按玩家在左/右选用 */
    public static GifDecoder.Animation milkyStomp;
    public static GifDecoder.Animation milkyStompMirror;
    /** 捧腹大笑（半血以下的技能二） */
    public static GifDecoder.Animation milkyLaugh;
    /** 被奶蛙击败时，阵亡画面中央展示的图片（透明底像素图） */
    public static Image milkyPressure;

    /** 快照需要节点挂在 Scene 下才可靠，用一个离屏容器兜着 */
    private static final Group OFFSCREEN = new Group();
    private static final Scene OFFSCREEN_SCENE = new Scene(OFFSCREEN, 1, 1);

    /** 小怪形象文件名，下标对齐 enemies[] / enemyAnim[] */
    private static final String[] ENEMY_ART = {
            "Wall_Creeper.gif", "Pirate's_Curse.gif", "Pirate's_Curse_(glowing).gif"
    };
    /** Boss 形象文件名，下标对齐 bosses[] / bossAnim[] */
    private static final String[] BOSS_ART = {
            "Martian_Saucer.gif", "Etherian_Wyvern_3.gif", "Etherian_Wyvern_2.gif", "Dark_Mage.gif"
    };

    private Sprites() {}

    public static void load() {
        // 国王（大厅初始操控对象）：美术 32×32 小立绘，裁透明边再 ×2 最近邻放大。
        // 缺图时退回程序生成的中性旅行者，保证仍能跑。
        heroes[0] = pixelScale(trimOpaque(loadArt("Sprite-00017.png")), 2);
        if (heroes[0] == null) {
            heroes[0] = bake(44, 44, Sprites::paintAdventurer);
        }
        // 职业形象：优先读 resources/sprites 下的真实素材，读不到才回退到程序化绘制。
        // 回退很关键——build.bat 的 javac 兜底路径不会复制 resources，没有兜底就是一片空白。
        loadHero(HeroClass.WIZARD, "wizard", Sprites::paintWizard);
        loadHero(HeroClass.WARRIOR, "warrior", Sprites::paintWarrior);
        loadHero(HeroClass.ARCHER, "archer", Sprites::paintArcher);
        loadHero(HeroClass.SUMMONER, "summoner", Sprites::paintSummoner);
        // Boss：先读旧的 jpg 立绘兜底，再尝试用 image/ 下的像素 GIF 覆盖（按 BOSS_ART 顺序）
        for (int t = 0; t < bosses.length; t++) {
            bosses[t] = loadBoss(t);
            bossAnim[t] = loadArtAnim(BOSS_ART[t]);
            if (bossAnim[t] != null) {
                bosses[t] = bossAnim[t].frames[0];
            }
        }
        minion = bake(28, 28, Sprites::paintMinion);
        minionAnim = loadArtAnim("Abigail_(minion).gif");
        if (minionAnim != null) {
            minion = minionAnim.frames[0];
        }
        // 巫师 / 弓箭手 / 战士的攻击与武器素材
        wizardBolt  = loadArtAnim("Charged_Blaster_Orb.gif");
        warriorArc  = loadArtAnim("Terragrim_(projectile).gif");
        warriorWeapon = loadArt("Influx_Waver_Beam.png");
        if (warriorWeapon != null) {
            warriorWeaponRot = bakeRotations(warriorWeapon, 16);
        }
        archerBoltRot = bakeKnifeRotations(loadArt("Throwing_Knife.png"), 32);

        // 骨蛇（lobby-king 引入）：头/身/尾三张 png 都是朝上的骨骼立绘，
        // 用 knockBackground 扣掉透明外的白底（如果原图有的话）
        serpentHead = loadArt("Bone_Serpent_Head.png");
        serpentBody = loadArt("Bone_Serpent_Body.png");
        serpentTail = loadArt("Bone_Serpent_Tail.png");

        // 大厅背景与标题画面：从仓库根 image/ 读现成美术
        lobbyBg = loadArt("皇宫王座大厅背景.jpg");
        titleScreen = loadArt("title_final_v6_covered_2x.png");
        // 细节立绘（右侧角色卡大图），按下标对齐职业。
        // 美术给的多是带纯色底（黑/白）的整幅图，叠到王座厅上会出现一块黑底/白底，
        // 这里把环绕角色、与图边相连的背景色抠成透明（见 knockoutBackground）。
        heroPortraits[HeroClass.WARRIOR] = knockoutBackground(loadArt("Edit_this_pixel_art_character__2026-09-09T01-59-50.png")); // 战士
        heroPortraits[HeroClass.WIZARD]  = knockoutBackground(loadArt("Edit_this_pixel_art_character__2026-09-09T02-00-45.png")); // 巫师
        heroPortraits[HeroClass.ARCHER]  = knockoutBackground(loadArt("弓箭手角色-尖角额甲版.jpg"));                                // 弓箭手
        heroPortraits[HeroClass.SUMMONER] = knockoutBackground(loadArt("summoner_transparent.png"));                               // 召唤师

        // 小怪：程序化形象先兜底，再尝试用 image/ 下的像素 GIF 覆盖（按 ENEMY_ART 顺序）
        enemies[0] = bake(32, 32, g -> paintSlime(g, Color.rgb(96, 200, 120), Color.rgb(40, 120, 70)));
        enemies[1] = bake(32, 32, g -> paintBat(g, Color.rgb(178, 130, 235), Color.rgb(96, 62, 150)));
        enemies[2] = bake(32, 32, g -> paintBrute(g, Color.rgb(240, 150, 80), Color.rgb(150, 74, 30)));
        for (int e = 0; e < ENEMY_ART.length; e++) {
            enemyAnim[e] = loadArtAnim(ENEMY_ART[e]);
            if (enemyAnim[e] != null) {
                enemies[e] = enemyAnim[e].frames[0];   // 静态回退同步成新素材首帧
            }
        }
        bolts[Element.NONE]   = bake(22, 22, g -> paintBolt(g,
                Color.rgb(255, 250, 225), Color.rgb(255, 215, 120), Color.rgb(230, 180, 90)));
        bolts[Element.FIRE]   = bake(22, 22, g -> paintBolt(g,
                Color.rgb(255, 245, 210), Color.rgb(255, 150, 50), Color.rgb(220, 60, 20)));
        bolts[Element.FROST]  = bake(22, 22, g -> paintBolt(g,
                Color.rgb(240, 252, 255), Color.rgb(130, 205, 255), Color.rgb(40, 110, 220)));
        bolts[Element.SHOCK]  = bake(22, 22, g -> paintBolt(g,
                Color.rgb(255, 255, 215), Color.rgb(195, 235, 120), Color.rgb(125, 90, 240)));
        bolts[Element.ARCANE] = bake(22, 22, g -> paintBolt(g,
                Color.rgb(250, 235, 255), Color.rgb(205, 145, 255), Color.rgb(120, 60, 220)));
        // 敌方弹幕统一用"敌意红"：亮心 + 血红中圈 + 近黑外圈，一眼就能和玩家元素弹区分。
        enemyBolt = bake(22, 22, g -> paintBolt(g,
                Color.rgb(255, 225, 225), Color.rgb(255, 80, 70), Color.rgb(140, 8, 18)));
        gem = bake(14, 14, Sprites::paintGem);

        // 奶蛙素材（resources/sprites/milky/）：原图是白底方图，统一抠背景成透明底
        milkyPortrait = knockoutBackground(loadImage("milky/portrait.jpg"));
        milkyWalkLeft = knockAnim(loadAnim("milky/walk_left.gif"));
        milkyWalkRight = knockAnim(loadAnim("milky/walk_right.gif"));
        milkyStomp = knockAnim(loadAnim("milky/stomp.gif"));
        milkyStompMirror = knockAnim(loadAnim("milky/stomp_mirror.gif"));
        milkyLaugh = knockAnim(loadAnim("milky/laugh.gif"));
        milkyPressure = loadImage("milky/pressure.png");
    }

    /** 把整段 GIF 的每一帧都抠掉背景 */
    private static GifDecoder.Animation knockAnim(GifDecoder.Animation a) {
        if (a == null) {
            return null;
        }
        Image[] fs = new Image[a.frames.length];
        for (int i = 0; i < fs.length; i++) {
            fs[i] = knockoutBackground(a.frames[i]);
        }
        return new GifDecoder.Animation(fs, a.delays);
    }

    /** 解码 resources/sprites 下的 GIF 动画；缺失/解码失败返回 null */
    private static GifDecoder.Animation loadAnim(String name) {
        try (InputStream in = res(name)) {
            return GifDecoder.decode(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** 职业形象 + 走动动画。形象裁边 ×2 放大，任一缺失都用程序化绘制兜底 */
    private static void loadHero(int classKind, String base, Consumer<GraphicsContext> fallback) {
        Image idle = loadImage(base + ".png");
        heroes[classKind] = (idle != null) ? pixelScale(trimOpaque(idle), 2) : bake(44, 44, fallback);
        try (InputStream in = res(base + "_walk.gif")) {
            heroWalk[classKind] = GifDecoder.decode(in);
        } catch (Exception e) {
            heroWalk[classKind] = null;
        }
    }

    /**
     * Boss 形象。原图是上千像素的 jpg，直接按原尺寸加载既占内存又慢，
     * 这里请求 192×192 的缩略图——显示尺寸只有 ~110px，缩放后看不出差别。
     */
    private static Image loadBoss(int tier) {
        String url = Sprites.class.getResource("/sprites/boss_" + tier + ".jpg") != null
                ? Sprites.class.getResource("/sprites/boss_" + tier + ".jpg").toExternalForm()
                : null;
        if (url == null) {
            return null;
        }
        try {
            return new Image(url, 192, 192, true, true);
        } catch (Exception e) {
            return null;
        }
    }

    private static Image loadImage(String name) {
        String url = Sprites.class.getResource("/sprites/" + name) != null
                ? Sprites.class.getResource("/sprites/" + name).toExternalForm()
                : null;
        if (url == null) {
            return null;
        }
        try {
            return new Image(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream res(String name) {
        return Sprites.class.getResourceAsStream("/sprites/" + name);
    }

    /**
     * 裁掉四周全透明的边，只留不透明内容。32×32 精灵内容通常居中、四周留白，
     * 不裁掉的话按"底边贴地"缩放会整张图悬空。返回内容紧贴边框的新图（原图不动）。
     */
    private static Image trimOpaque(Image src) {
        if (src == null) {
            return null;
        }
        PixelReader pr = src.getPixelReader();
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((pr.getArgb(x, y) >>> 24) & 0xFF) != 0) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0 || y1 < 0) {
            return src;                        // 整张全透明：原样返回
        }
        final int fx0 = x0, fy0 = y0, fw = x1 - x0 + 1, fh = y1 - y0 + 1;
        return bake(fw, fh, g -> g.drawImage(src, fx0, fy0, fw, fh, 0, 0, fw, fh));
    }

    /**
     * 最近邻整数倍放大（关闭平滑插值），得到清晰的像素立绘。
     * k 必须是非负整数：k=2 即每个源像素占 2×2 目标像素，边角保持锐利。
     */
    private static Image pixelScale(Image src, int k) {
        if (src == null || k <= 1) {
            return src;
        }
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        return bake(w * k, h * k, g -> {
            g.setImageSmoothing(false);
            g.drawImage(src, 0, 0, w * k, h * k);
        });
    }

    /**
     * 抠掉环绕角色的背景：只把「与图片边缘四连通的近似纯色背景」变透明，
     * 角色主体内部的同色细节（描边、高光、瞳孔等）因不与边相连而完整保留。
     * 黑底、白底、已透明的边都会被清除，四种立绘统一得到干净透明背景。
     */
    private static Image knockoutBackground(Image src) {
        if (src == null) {
            return null;
        }
        PixelReader pr = src.getPixelReader();
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        int[] argb = new int[w * h];
        pr.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        boolean[] key = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            int a = (argb[i] >>> 24) & 0xFF;
            if (a < 40) {
                key[i] = true;                       // 原本透明：放行洪泛
                continue;
            }
            int r = (argb[i] >>> 16) & 0xFF;
            int g = (argb[i] >>> 8) & 0xFF;
            int b = argb[i] & 0xFF;
            key[i] = Math.max(r, Math.max(g, b)) < 48      // 近黑底
                    || Math.min(r, Math.min(g, b)) > 207;   // 近白底
        }
        boolean[] rm = new boolean[w * h];
        int[] stack = new int[w * h];
        int sp = 0;
        // 种子：顶/底/左/右四条边上的背景像素
        for (int x = 0; x < w; x++) {
            if (key[x] && !rm[x]) { rm[x] = true; stack[sp++] = x; }
            int b = (h - 1) * w + x;
            if (key[b] && !rm[b]) { rm[b] = true; stack[sp++] = b; }
        }
        for (int y = 0; y < h; y++) {
            int l = y * w;
            if (key[l] && !rm[l]) { rm[l] = true; stack[sp++] = l; }
            int rr = y * w + (w - 1);
            if (key[rr] && !rm[rr]) { rm[rr] = true; stack[sp++] = rr; }
        }
        while (sp > 0) {
            int idx = stack[--sp];
            if (idx % w > 0) {
                int n = idx - 1;
                if (key[n] && !rm[n]) { rm[n] = true; stack[sp++] = n; }
            }
            if (idx % w < w - 1) {
                int n = idx + 1;
                if (key[n] && !rm[n]) { rm[n] = true; stack[sp++] = n; }
            }
            int u = idx - w;
            if (u >= 0 && key[u] && !rm[u]) { rm[u] = true; stack[sp++] = u; }
            int d = idx + w;
            if (d < w * h && key[d] && !rm[d]) { rm[d] = true; stack[sp++] = d; }
        }
        int removed = 0;
        for (int i = 0; i < w * h; i++) {
            if (rm[i]) {
                argb[i] &= 0x00FFFFFF;               // 清掉 alpha
                removed++;
            }
        }
        if (removed == 0) {
            return src;                              // 本来就没有背景块
        }
        WritableImage out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return out;
    }

    private static Image bake(int w, int h, Consumer<GraphicsContext> painter) {
        Canvas c = new Canvas(w, h);
        OFFSCREEN.getChildren().add(c);
        try {
            painter.accept(c.getGraphicsContext2D());
            SnapshotParameters sp = new SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            return c.snapshot(sp, null);
        } finally {
            OFFSCREEN.getChildren().remove(c);
        }
    }

    /**
     * 从仓库根目录 image/ 载入现成美术。从当前工作目录往上逐级找 image 目录，
     * 兼容 run.bat（项目根）与 IntelliJ（可能是模块目录）两种工作目录。
     * 找不到时打印警告并返回 null，调用方需容忍缺图。
     */
    private static Image loadArt(String fileName) {
        File f = artFile(fileName);
        if (f == null) {
            System.err.println("[Sprites] 缺少美术资源: " + fileName);
            return null;
        }
        return new Image(f.toURI().toString(), false);
    }

    /** 定位 image/ 下的文件。从工作目录往上逐级找 image 目录，找不到或文件不存在返回 null */
    private static File artFile(String fileName) {
        File dir = null;
        for (File d = new File(System.getProperty("user.dir")); d != null; d = d.getParentFile()) {
            File cand = new File(d, "image");
            if (cand.isDirectory()) {
                dir = cand;
                break;
            }
        }
        File f = (dir != null) ? new File(dir, fileName) : new File(fileName);
        return f.isFile() ? f : null;
    }

    /**
     * 从 image/ 载入 GIF 并解码成动画帧。
     * JavaFX 的 Image 读 GIF 只取第一帧且不做动画，所以必须走 GifDecoder。
     * 缺图或解码失败都返回 null，调用方退回静态形象。
     */
    private static GifDecoder.Animation loadArtAnim(String fileName) {
        File f = artFile(fileName);
        if (f == null) {
            System.err.println("[Sprites] 缺少美术资源: " + fileName);
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            GifDecoder.Animation anim = GifDecoder.decode(in);
            if (anim == null || anim.frames.length == 0) {
                System.err.println("[Sprites] GIF 无有效帧: " + f.getAbsolutePath());
                return null;
            }
            return anim;
        } catch (Exception e) {
            System.err.println("[Sprites] GIF 解码失败: " + f.getAbsolutePath() + " -> " + e);
            return null;
        }
    }

    /**
     * 飞刀用的是"刀尖朝上"的竖长图，直接烘焙旋转会让 0 号朝向指向上方。
     * 这里先顺时针预转 90° 让 0 号朝向对齐 +x（向右），再按 angles 个朝向烘焙，
     * 运行期只需按速度方向取下标，不做任何实时旋转。
     */
    private static Image[] bakeKnifeRotations(Image src, int angles) {
        if (src == null) {
            return null;
        }
        double w = src.getWidth();
        double h = src.getHeight();
        double size = Math.ceil(Math.sqrt(w * w + h * h));
        Image pointingRight = bake((int) size, (int) size, g -> {
            g.translate(size / 2.0, size / 2.0);
            g.rotate(90);
            g.drawImage(src, -w / 2.0, -h / 2.0);
        });
        return bakeRotations(pointingRight, angles);
    }

    /**
     * 预烘焙旋转帧。
     * JavaFX 运行时旋转 drawImage 会走软件变换路径，慢一个量级；
     * 方向性投射物（冰锥、飞刀）必须提前把 N 个角度烤成独立 Image。
     */
    public static Image[] bakeRotations(Image src, int angles) {
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        int size = (int) Math.ceil(Math.sqrt(w * w + h * h));
        Image[] out = new Image[angles];
        for (int i = 0; i < angles; i++) {
            double a = i * 2 * Math.PI / angles;
            out[i] = bake(size, size, g -> {
                g.translate(size / 2.0, size / 2.0);
                g.rotate(Math.toDegrees(a));
                g.drawImage(src, -w / 2.0, -h / 2.0);
            });
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 画法
    // ------------------------------------------------------------------

    private static void paintWizard(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下光晕
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(140, 190, 255, 0.45)),
                new Stop(1, Color.rgb(140, 190, 255, 0))));
        g.fillOval(2, 4, 40, 40);

        // 袍子
        g.setFill(Color.rgb(62, 84, 168));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(42, 58, 128));
        g.fillOval(cx - 10, cy + 8, 20, 10);

        // 帽子
        g.setFill(Color.rgb(46, 64, 140));
        g.beginPath();
        g.moveTo(cx, cy - 26);
        g.lineTo(cx + 12, cy - 4);
        g.lineTo(cx - 12, cy - 4);
        g.closePath();
        g.fill();
        g.setFill(Color.rgb(70, 96, 200));
        g.fillRect(cx - 14, cy - 6, 28, 4);

        // 法杖（右侧，顶部发光宝珠）
        g.setStroke(Color.rgb(128, 90, 48));
        g.setLineWidth(3);
        g.strokeLine(cx + 11, cy + 9, cx + 16, cy - 11);
        g.setFill(new RadialGradient(0, 0, cx + 16, cy - 13, 5, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(190, 235, 255)),
                new Stop(1, Color.rgb(120, 90, 230, 0))));
        g.fillOval(cx + 12, cy - 17, 8, 8);

        // 脸与眼睛
        g.setFill(Color.rgb(238, 220, 190));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(30, 32, 48));
        g.fillOval(cx - 3.5, cy + 0.5, 2.4, 2.4);
        g.fillOval(cx + 1.1, cy + 0.5, 2.4, 2.4);
    }

    private static void paintWarrior(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下光晕（暖色）
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 170, 90, 0.45)),
                new Stop(1, Color.rgb(255, 170, 90, 0))));
        g.fillOval(2, 4, 40, 40);

        // 剑（右侧，剑身朝上）
        g.setFill(Color.rgb(214, 224, 238));
        g.fillRect(cx + 9, cy - 13, 3, 15);
        g.setFill(Color.rgb(170, 120, 50));
        g.fillRect(cx + 7, cy + 2, 7, 2);
        g.setFill(Color.rgb(110, 74, 36));
        g.fillRect(cx + 10, cy + 4, 2, 6);

        // 盾（左侧圆形）
        g.setFill(Color.rgb(150, 70, 60));
        g.fillOval(cx - 18, cy - 4, 13, 15);
        g.setStroke(Color.rgb(235, 205, 140));
        g.setLineWidth(1.5);
        g.strokeOval(cx - 18, cy - 4, 13, 15);

        // 身体铠甲（铁灰 + 红带）
        g.setFill(Color.rgb(105, 110, 125));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(78, 82, 96));
        g.fillOval(cx - 10, cy + 8, 20, 10);
        g.setFill(Color.rgb(190, 60, 50));
        g.fillRect(cx - 10, cy - 1, 20, 3);

        // 头盔（带红缨）
        g.setFill(Color.rgb(122, 128, 146));
        g.fillArc(cx - 9, cy - 13, 18, 15, 0, 180, ArcType.ROUND);
        g.setFill(Color.rgb(205, 70, 60));
        g.beginPath();
        g.moveTo(cx, cy - 14);
        g.lineTo(cx - 3, cy - 21);
        g.lineTo(cx + 3, cy - 14);
        g.closePath();
        g.fill();

        // 脸与眼睛
        g.setFill(Color.rgb(238, 220, 190));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(30, 32, 48));
        g.fillOval(cx - 3.5, cy + 0.5, 2.4, 2.4);
        g.fillOval(cx + 1.1, cy + 0.5, 2.4, 2.4);
    }

    private static void paintArcher(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下光晕（绿色）
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(120, 220, 150, 0.45)),
                new Stop(1, Color.rgb(120, 220, 150, 0))));
        g.fillOval(2, 4, 40, 40);

        // 弓（横在身前，弧形弓臂朝左 + 弓弦）
        g.setStroke(Color.rgb(122, 82, 42));
        g.setLineWidth(3);
        g.strokeArc(cx - 17, cy - 7, 24, 20, 90, 180, ArcType.OPEN);
        g.setStroke(Color.rgb(235, 235, 235));
        g.setLineWidth(1);
        g.strokeLine(cx - 17, cy + 3, cx + 7, cy - 3);

        // 身体斗篷（绿色）
        g.setFill(Color.rgb(66, 138, 88));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(46, 104, 66));
        g.fillOval(cx - 10, cy + 8, 20, 10);

        // 兜帽
        g.setFill(Color.rgb(52, 120, 74));
        g.fillArc(cx - 10, cy - 14, 20, 18, 0, 180, ArcType.ROUND);
        g.setFill(Color.rgb(40, 96, 58));
        g.fillRect(cx - 12, cy - 6, 24, 3);

        // 脸与眼睛
        g.setFill(Color.rgb(238, 220, 190));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(30, 32, 48));
        g.fillOval(cx - 3.5, cy + 0.5, 2.4, 2.4);
        g.fillOval(cx + 1.1, cy + 0.5, 2.4, 2.4);
    }

    /** 召唤师兜底形象：紫金法袍 + 悬浮的召唤法阵 */
    private static void paintSummoner(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下召唤法阵（紫色）
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(200, 140, 255, 0.55)),
                new Stop(1, Color.rgb(120, 60, 200, 0))));
        g.fillOval(2, 4, 40, 40);
        g.setStroke(Color.rgb(215, 170, 255, 0.8));
        g.setLineWidth(1.2);
        g.strokeOval(cx - 14, cy + 12, 28, 9);

        // 身体（紫袍 + 金边）
        g.setFill(Color.rgb(112, 74, 168));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(86, 54, 136));
        g.fillOval(cx - 10, cy + 8, 20, 10);
        g.setFill(Color.rgb(240, 210, 120));
        g.fillRect(cx - 10, cy - 1, 20, 3);

        // 兜帽
        g.setFill(Color.rgb(96, 62, 150));
        g.fillArc(cx - 10, cy - 14, 20, 18, 0, 180, ArcType.ROUND);

        // 脸与眼睛（秘能色）
        g.setFill(Color.rgb(238, 220, 190));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(180, 120, 240));
        g.fillOval(cx - 3.5, cy + 0.5, 2.6, 2.6);
        g.fillOval(cx + 0.9, cy + 0.5, 2.6, 2.6);

        // 悬浮的召唤宝珠
        g.setFill(new RadialGradient(0, 0, cx - 14, cy - 12, 6, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(235, 210, 255)),
                new Stop(1, Color.rgb(150, 90, 240, 0))));
        g.fillOval(cx - 19, cy - 17, 10, 10);
    }

    /** 召唤物（宠物）：一只小型秘能仆从。没给美术素材，程序化画一个 */
    private static void paintMinion(GraphicsContext g) {
        double cx = 14, cy = 15;
        // 光晕
        g.setFill(new RadialGradient(0, 0, cx, cy, 13, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(190, 140, 255, 0.5)),
                new Stop(1, Color.rgb(120, 70, 210, 0))));
        g.fillOval(1, 2, 26, 26);

        // 身体（水滴形）
        g.setFill(Color.rgb(138, 92, 208));
        g.fillOval(cx - 8, cy - 7, 16, 17);
        g.setFill(Color.rgb(108, 68, 176));
        g.fillOval(cx - 8, cy + 4, 16, 8);

        // 尖耳（左右各一只）
        g.setFill(Color.rgb(112, 72, 182));
        g.beginPath();
        g.moveTo(cx - 7, cy - 5);
        g.lineTo(cx - 12, cy - 12);
        g.lineTo(cx - 3, cy - 8);
        g.closePath();
        g.fill();
        g.beginPath();
        g.moveTo(cx + 7, cy - 5);
        g.lineTo(cx + 12, cy - 12);
        g.lineTo(cx + 3, cy - 8);
        g.closePath();
        g.fill();

        // 眼睛（发光）
        g.setFill(Color.rgb(245, 235, 255));
        g.fillOval(cx - 4.5, cy - 2, 3.4, 3.4);
        g.fillOval(cx + 1.1, cy - 2, 3.4, 3.4);
        g.setFill(Color.rgb(60, 40, 90));
        g.fillOval(cx - 3.6, cy - 1.4, 1.8, 1.8);
        g.fillOval(cx + 2.0, cy - 1.4, 1.8, 1.8);
    }

    /** 大厅初始操控对象（国王）兜底：中性灰斗篷旅行者 */
    private static void paintAdventurer(GraphicsContext g) {
        double cx = 22, cy = 24;
        // 脚下灰雾
        g.setFill(new RadialGradient(0, 0, cx, cy, 20, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(210, 214, 224, 0.40)),
                new Stop(1, Color.rgb(210, 214, 224, 0))));
        g.fillOval(2, 4, 40, 40);

        // 灰斗篷
        g.setFill(Color.rgb(104, 108, 122));
        g.fillOval(cx - 10, cy - 6, 20, 22);
        g.setFill(Color.rgb(78, 82, 96));
        g.fillOval(cx - 10, cy + 8, 20, 10);

        // 兜帽(比斗篷浅一点,翻起的帽沿)
        g.setFill(Color.rgb(122, 126, 140));
        g.fillArc(cx - 10, cy - 15, 20, 19, 0, 180, ArcType.ROUND);
        g.setFill(Color.rgb(90, 93, 108));
        g.fillRect(cx - 12, cy - 6, 24, 3);

        // 脸与眼睛
        g.setFill(Color.rgb(226, 222, 214));
        g.fillOval(cx - 6, cy - 4, 12, 11);
        g.setFill(Color.rgb(30, 32, 48));
        g.fillOval(cx - 3.5, cy + 0.5, 2.4, 2.4);
        g.fillOval(cx + 1.1, cy + 0.5, 2.4, 2.4);
    }

    private static void paintSlime(GraphicsContext g, Color body, Color dark) {
        g.setFill(dark);
        g.fillOval(4, 8, 24, 22);
        g.setFill(body);
        g.fillOval(4, 6, 24, 20);
        g.setFill(Color.rgb(255, 255, 255, 0.55));
        g.fillOval(9, 9, 7, 5);
        g.setFill(Color.rgb(24, 32, 28));
        g.fillOval(11, 16, 3.4, 3.4);
        g.fillOval(18, 16, 3.4, 3.4);
    }

    private static void paintBat(GraphicsContext g, Color body, Color dark) {
        g.setFill(dark);
        g.beginPath();
        g.moveTo(16, 10);
        g.lineTo(30, 4);
        g.lineTo(28, 18);
        g.closePath();
        g.fill();
        g.beginPath();
        g.moveTo(16, 10);
        g.lineTo(2, 4);
        g.lineTo(4, 18);
        g.closePath();
        g.fill();
        g.setFill(body);
        g.fillOval(10, 8, 12, 16);
        g.setFill(Color.rgb(255, 240, 200));
        g.fillOval(12.5, 13, 2.8, 2.8);
        g.fillOval(16.7, 13, 2.8, 2.8);
    }

    private static void paintBrute(GraphicsContext g, Color body, Color dark) {
        g.setFill(dark);
        g.fillRect(4, 6, 24, 22);
        g.setFill(body);
        g.fillOval(6, 6, 20, 16);
        g.setFill(Color.rgb(255, 220, 190));
        g.fillOval(11, 20, 10, 6);
        g.setFill(Color.rgb(50, 26, 16));
        g.fillRect(9.5, 11, 4, 4);
        g.fillRect(18.5, 11, 4, 4);
    }

    private static void paintBolt(GraphicsContext g, Color core, Color mid, Color outer) {
        g.setFill(new RadialGradient(0, 0, 11, 11, 11, false, CycleMethod.NO_CYCLE,
                new Stop(0, core),
                new Stop(0.35, Color.color(mid.getRed(), mid.getGreen(), mid.getBlue(), 0.95)),
                new Stop(0.7, Color.color(outer.getRed(), outer.getGreen(), outer.getBlue(), 0.55)),
                new Stop(1, Color.color(outer.getRed(), outer.getGreen(), outer.getBlue(), 0))));
        g.fillOval(0, 0, 22, 22);
    }

    private static void paintGem(GraphicsContext g) {
        g.setFill(new RadialGradient(0, 0, 7, 7, 7, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(150, 255, 230, 0.9)),
                new Stop(1, Color.rgb(40, 190, 170, 0))));
        g.fillOval(0, 0, 14, 14);
        g.setFill(Color.rgb(120, 245, 215));
        g.beginPath();
        g.moveTo(7, 1);
        g.lineTo(13, 7);
        g.lineTo(7, 13);
        g.lineTo(1, 7);
        g.closePath();
        g.fill();
    }
}
