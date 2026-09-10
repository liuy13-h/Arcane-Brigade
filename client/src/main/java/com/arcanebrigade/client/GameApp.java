package com.arcanebrigade.client;

import com.arcanebrigade.core.HeroClass;
import com.arcanebrigade.core.InputCommand;
import com.arcanebrigade.core.Loadout;
import com.arcanebrigade.core.Upgrades;
import com.arcanebrigade.core.World;
import com.arcanebrigade.client.battle.BattleSession;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import java.util.EnumSet;
import java.util.Set;

/**
 * 客户端入口。固定步长模拟 + 插值渲染。
 *
 * 流程：启动先进「标题主菜单」(inTitle=true)，点「开始游戏」进入「准备大厅」
 * (inLobby=true)。大厅是纯客户端轻量状态，不生成 core World：WASD 自由走动、
 * 走上职业祭坛即选中职业、走进出征光门按 E 才 spawnWizard 转入正式战斗。
 * 战斗阶段固定 60Hz 推进 World，渲染帧用 alpha 插值衔接。
 */
public final class GameApp extends Application {

    /** 大厅里化身移动速度(px/s) */
    private static final double LOBBY_SPEED = 300.0;

    private final Set<KeyCode> pressed = EnumSet.noneOf(KeyCode.class);
    private final InputCommand input = new InputCommand();

    /** 战斗生命周期与固定步长只由这个边界对象管理。 */
    private BattleSession battle;
    private Renderer renderer;
    private Stage stage;

    /** 主界面（inTitle）相关：overlay=当前覆盖面板；dragSlider=正在拖动的音量条下标(-1=无)；鼠标位置 */
    private int overlay;
    private int dragSlider = -1;
    private double mouseX = -1000, mouseY = -1000;

    /** 战斗冒烟帧数：-Dab.smoke=180 = 自动选职业打 180 帧后退出（战斗路径回归） */
    private int smokeFrames = -1;
    private int renderedFrames;
    /** 大厅冒烟帧数：-Dab.lobbyFrames=150 = 只渲染大厅 150 帧后退出（大厅回归） */
    private int lobbySmokeFrames = -1;
    private int lobbyRendered;

    /** true=主菜单（标题画面）；false=进入大厅或战斗 */
    private boolean inTitle = true;
    /** true=准备大厅；false=正式战斗 */
    private boolean inLobby = false;
    /** 大厅化身的屏幕坐标（首帧用 geom 出生点赋值） */
    private double lx, ly;
    private boolean lobbyPosInit;
    /** 大厅里已选职业：0=未选，否则是 LobbyClass.CLASSES 之一（含召唤师 4） */
    private int lobbyChoice = 0;
    private double lobbyAnimT;
    /** 右侧细节立绘当前展示的职业：0=不展示。跟随「正站在谁面前」变化 */
    private int cardClass;
    /** 立绘展示进度 0..1：1=完全滑入，0=缩回屏外（离开角色范围时回落） */
    private double cardReveal;
    /** 大厅左上角操作指引是否展开（可点「✕」收起，点「❖ 操作指引 ▸」展开） */
    private boolean lobbyGuide = true;

    /** 鼠标左键是否按住（战斗阶段用于宠物指挥 + 手动开火） */
    private boolean mouseDown;

    @Override
    public void start(Stage stage) {
        String smoke = System.getProperty("ab.smoke");
        if (smoke != null && !smoke.isBlank()) {
            smokeFrames = Integer.parseInt(smoke);
        }
        String lobbySmoke = System.getProperty("ab.lobbyFrames");
        if (lobbySmoke != null && !lobbySmoke.isBlank()) {
            lobbySmokeFrames = Integer.parseInt(lobbySmoke);
        }
        // 冒烟用：ab.lobbySel 强制开局即已随行该勇者(1..4)——覆盖"空神坛+勇者卡"绘制路径；
        // ab.lobbyFace 模拟国王正站在某位勇者面前但尚未招募——覆盖"招募对话条"绘制路径
        String lobbySel = System.getProperty("ab.lobbySel");
        int presetSel = 0;
        if (lobbySel != null && !lobbySel.isBlank()) {
            presetSel = Integer.parseInt(lobbySel);
            lobbyChoice = presetSel;
        }
        String lobbyFace = System.getProperty("ab.lobbyFace");
        int presetNear = 0;
        if (lobbyFace != null && !lobbyFace.isBlank()) {
            presetNear = Integer.parseInt(lobbyFace);
        }
        GameConfig.load();   // 音量 / 显示玩家 ID / 显示模式 / 分辨率 / 帧率（含首次生成玩家 ID）
        Sprites.load();

        battle = new BattleSession(20260907L);

        Canvas canvas = new Canvas(1280, 720);
        Pane root = new Pane(canvas);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        root.setStyle("-fx-background-color: #121019;");

        Scene scene = new Scene(root, 1280, 720);
        scene.setOnKeyPressed(e -> {
            if (inTitle) {
                handleTitleKey(e.getCode());
                return;
            }
            if (inLobby) {
                // 大厅阶段：WASD 靠 pressed 轮询移动；空格/E 先应答面前勇者的招募，其次光门出发
                pressed.add(e.getCode());
                if (e.getCode() == KeyCode.E || e.getCode() == KeyCode.SPACE) {
                    lobbyAction();
                }
                return;
            }
            pressed.add(e.getCode());
            // 战斗阶段：ESC 手动暂停；R 键在胜利/阵亡后重开，在升级面板弹出时重抽
            if (e.getCode() == KeyCode.ESCAPE) {
                battle.toggleManualPause();
                return;
            }
            if (e.getCode() == KeyCode.R) {
                if (battle.world().victory() || battle.world().defeat()) {
                    restart();
                    return;
                }
                battle.rerollUpgradeChoices();
            }
        });
        scene.setOnKeyReleased(e -> pressed.remove(e.getCode()));

        scene.setOnMouseMoved(e -> {
            mouseX = e.getX();
            mouseY = e.getY();
        });
        // 音量滑块：按下即定位、按住拖动连续调节；同时记录左键按下状态供战斗阶段使用
        scene.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                mouseDown = true;
            }
            if (e.getButton() != MouseButton.PRIMARY
                    || !inTitle || overlay != Renderer.OVER_SETTINGS) {
                return;
            }
            int s = settingsSliderAt(e.getX(), e.getY());
            dragSlider = s;
            if (s >= 0) {
                applyVolumeDrag(s, e.getX());
            }
        });
        scene.setOnMouseDragged(e -> {
            if (dragSlider >= 0 && inTitle && overlay == Renderer.OVER_SETTINGS) {
                applyVolumeDrag(dragSlider, e.getX());
            }
        });
        // 音量在拖动中不落盘，松开时写一次，避免拖动过程高频写配置
        scene.setOnMouseReleased(e -> {
            mouseDown = false;
            if (dragSlider >= 0) {
                GameConfig.save();
            }
            dragSlider = -1;
        });
        scene.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (inTitle) {
                handleTitleClick(e.getX(), e.getY());
                return;
            }
            if (inLobby) {
                // 大厅里鼠标只用于指引的收起/展开，角色交互仍走空格/E
                double vw = renderer.getCanvasWidth();
                double vh = renderer.getCanvasHeight();
                Renderer.GuideGeom gg = Renderer.lobbyGuideGeom(vw, vh);
                Renderer.Rect r = lobbyGuide ? gg.hide() : gg.open();
                if (r.hit(e.getX(), e.getY())) {
                    lobbyGuide = !lobbyGuide;
                }
                return;
            }
            handleBattleClick(e.getX(), e.getY());
        });

        this.stage = stage;
        stage.setTitle("Arcane Brigade — 奥术旅团");
        stage.setScene(scene);
        stage.show();
        root.requestFocus();
        applyDisplay();          // 应用本地配置的显示模式 / 窗口分辨率

        renderer = new Renderer(canvas);

        // 大厅冒烟（ab.lobbyFrames）：跳过主界面，直接进入准备大厅渲染
        if (lobbySmokeFrames > 0) {
            enterLobby();
        }

        // 冒烟自检用：把化身放到某位勇者面前，让招募/细节立绘路径真正绘制。
        // 目标 = ab.lobbySel（已随行）或 ab.lobbyFace（站在面前未招募）。
        int smokeStandBy = lobbyChoice != 0 ? lobbyChoice : presetNear;
        if (smokeStandBy != 0) {
            Renderer.LobbyGeom g0 = Renderer.geom(canvas.getWidth(), canvas.getHeight());
            for (int i = 0; i < LobbyClass.CLASSES.length; i++) {
                if (LobbyClass.CLASSES[i] == smokeStandBy) {
                    lx = g0.altarC()[i][0];
                    ly = g0.altarC()[i][1];
                    lobbyPosInit = true;
                    break;
                }
            }
        }

        // 战斗冒烟：跳过大厅，自动选职业直接跑真实模拟+渲染路径做稳定性验证。
        // -Dab.class=N 可指定职业（默认巫师），用来覆盖各职业专属的渲染分支；
        // -Dab.boss=N 直接刷第 N 只 Boss，用来覆盖 Boss 立绘 / 阶段技能渲染路径。
        if (smokeFrames > 0) {
            int pick = HeroClass.WIZARD;
            String cls = System.getProperty("ab.class");
            if (cls != null && !cls.isBlank()) {
                int v = Integer.parseInt(cls);
                if (v > 0 && v < HeroClass.COUNT) {
                    pick = v;
                }
            }
            beginGame(pick);
            String bt = System.getProperty("ab.boss");
            if (bt != null && !bt.isBlank()) {
                int tier = Integer.parseInt(bt);
                if (tier >= 0 && tier < 4) {
                    battle.spawnBossForSmoke(tier);
                }
            }
        }

        // 主界面 BGM：仍在标题（非冒烟直进大厅/战斗）时开始循环播放
        if (inTitle) {
            GameAudio.startMenuBgm();
        }

        final long[] last = { System.nanoTime() };
        final long[] lastDraw = { System.nanoTime() };
        final double[] fps = { 60.0 };

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                double dt = (now - last[0]) / 1e9;
                last[0] = now;
                if (dt > 0.25) {
                    dt = 0.25;
                }
                fps[0] += (1.0 / Math.max(dt, 1e-6) - fps[0]) * 0.08;

                // 帧率上限只限制「画面刷新」：逻辑仍每帧按固定步长推进。
                double period = 1.0 / GameConfig.fpsCap;
                boolean drawNow = (now - lastDraw[0]) / 1e9 >= period - 1e-9;
                if (drawNow) {
                    lastDraw[0] = now;
                }

                // ---- 主菜单阶段（标题画面，纯绘制 + 点击交互） ----
                if (inTitle) {
                    lobbyAnimT += dt;
                    if (drawNow) {
                        renderer.setFps(fps[0]);
                        double vw = canvas.getWidth();
                        double vh = canvas.getHeight();
                        int hover = overlay == Renderer.OVER_NONE
                                ? Renderer.menuHit(mouseX, mouseY, vw, vh) : -1;
                        renderer.drawTitle(lobbyAnimT, hover, overlay,
                                GameConfig.displayMode == GameConfig.MODE_FULLSCREEN);
                    }
                    return;
                }

                // ---- 准备大厅阶段（不推进 core World） ----
                if (inLobby) {
                    lobbyAnimT += dt;
                    double vw = canvas.getWidth();
                    double vh = canvas.getHeight();
                    Renderer.LobbyGeom g = Renderer.geom(vw, vh);
                    stepLobby(dt, g);
                    if (drawNow) {
                        renderer.setFps(fps[0]);
                        renderer.drawLobby(g, lx, ly, lobbyChoice, lobbyAnimT, cardClass, cardReveal,
                                lobbyGuide);
                        if (lobbySmokeFrames > 0 && ++lobbyRendered >= lobbySmokeFrames) {
                            System.out.printf("[lobby] 渲染 %d 帧完成（大厅），退出%n", lobbyRendered);
                            Platform.exit();
                        }
                    }
                    return;
                }

                // ---- 正式战斗阶段（逻辑推进与画面刷新解耦） ----
                renderer.setMouse(mouseX, mouseY);
                World world = battle.world();

                // 胜利：冻结模拟，罩层结算。模拟一旦停了就不再推进，直到按 R 重开
                if (world.victory()) {
                    renderer.setFps(fps[0]);
                    renderer.draw(world, 0f);
                    renderer.drawVictory(world, canvas.getWidth(), canvas.getHeight());
                    if (smokeFrames > 0 && ++renderedFrames >= smokeFrames) {
                        System.out.printf("[smoke] 胜利画面，渲染 %d 帧完成，退出%n", renderedFrames);
                        Platform.exit();
                    }
                    return;
                }

                // 阵亡：同样冻结模拟，弹结算战报，点「继续」或按 R 回大厅。
                // 之前玩家倒下后没有任何终局状态，游戏会一直空转却永远不结束。
                if (world.defeat()) {
                    renderer.setFps(fps[0]);
                    renderer.draw(world, 0f);
                    renderer.drawDefeatOverlay(world, canvas.getWidth(), canvas.getHeight());
                    if (smokeFrames > 0 && ++renderedFrames >= smokeFrames) {
                        System.out.printf("[smoke] 阵亡结算，渲染 %d 帧完成，退出%n", renderedFrames);
                        Platform.exit();
                    }
                    return;
                }

                boolean upgradePaused = battle.hasPendingUpgrade();
                readInput();
                float alpha = battle.advance(dt, input);

                if (!drawNow) {
                    return;
                }
                renderer.setPaused(battle.isManualPaused());
                renderer.setFps(fps[0]);
                renderer.draw(world, alpha);

                // 升级面板始终叠加在画面最上层
                if (upgradePaused) {
                    Loadout lo = battle.playerLoadout();
                    if (lo != null) {
                        Upgrades.Choice[] cs = battle.pendingChoices();
                        renderer.drawUpgradePanel(cs, lo.rerolls,
                                canvas.getWidth(), canvas.getHeight());
                    }
                } else if (battle.isManualPaused()) {
                    renderer.drawPauseOverlay(canvas.getWidth(), canvas.getHeight());
                }

                if (smokeFrames > 0 && ++renderedFrames >= smokeFrames) {
                    System.out.printf("[smoke] 渲染 %d 帧完成，实体 %d，敌人 %d，退出%n",
                            renderedFrames, world.liveCount(), world.enemyCount());
                    Platform.exit();
                }
            }
        }.start();
    }

    /** 从主界面「开始游戏」进入准备大厅。保留冒烟预设的 lobbyChoice 不动。 */
    private void enterLobby() {
        inTitle = false;
        inLobby = true;
        overlay = Renderer.OVER_NONE;
        lobbyPosInit = false;    // 首帧按出生点落位
        cardClass = 0;
        cardReveal = 0;
        GameAudio.stopMenuBgm();        // 离开主界面
        GameAudio.startLobbyBgm();      // 大厅主音乐循环
        pressed.clear();
    }

    /** 主界面键盘：Esc 关闭覆盖面板；无覆盖层时 回车/空格 直接开局；F11 切全屏 */
    private void handleTitleKey(KeyCode code) {
        if (code == KeyCode.ESCAPE) {
            if (overlay != Renderer.OVER_NONE) {
                overlay = Renderer.OVER_NONE;
            }
            return;
        }
        if (code == KeyCode.F11) {
            toggleFullscreen();
            return;
        }
        if (overlay == Renderer.OVER_NONE
                && (code == KeyCode.ENTER || code == KeyCode.SPACE)) {
            enterLobby();
        }
    }

    /**
     * 主界面点击：无覆盖层时命中底部五个菜单按钮；覆盖层内「设置」走专用逻辑，
     * 其余覆盖层只认「返回」钮。
     */
    private void handleTitleClick(double mx, double my) {
        double vw = renderer.getCanvasWidth();
        double vh = renderer.getCanvasHeight();
        if (overlay == Renderer.OVER_NONE) {
            switch (Renderer.menuHit(mx, my, vw, vh)) {
                case 0 -> enterLobby();                     // 开始游戏 → 准备大厅
                case 1 -> overlay = Renderer.OVER_MULTI;    // 多人联机（开发中占位）
                case 2 -> overlay = Renderer.OVER_SETTINGS; // 设置
                case 3 -> overlay = Renderer.OVER_HELP;     // 操作说明
                case 4 -> Platform.exit();                  // 退出游戏
                default -> { /* 空白区不响应 */ }
            }
            return;
        }
        if (overlay == Renderer.OVER_SETTINGS) {
            handleSettingsClick(mx, my);
            return;
        }
        Renderer.OverlayGeom g = Renderer.menuOverlayGeom(vw, vh, overlay);
        if (g.close().hit(mx, my)) {
            overlay = Renderer.OVER_NONE;
        }
    }

    /** 设置面板内的点击：返回 / 玩家ID开关 / 音量 / 三组三选一 */
    private void handleSettingsClick(double mx, double my) {
        double vw = renderer.getCanvasWidth();
        double vh = renderer.getCanvasHeight();
        Renderer.SettingsGeom g = Renderer.settingsGeom(vw, vh);
        if (g.close().hit(mx, my)) {
            overlay = Renderer.OVER_NONE;
            return;
        }
        if (g.showId().hit(mx, my)) {
            GameConfig.showPlayerId = !GameConfig.showPlayerId;
            GameConfig.save();
            return;
        }
        int s = settingsSliderAt(mx, my);
        if (s >= 0) {
            applyVolumeDrag(s, mx);
            return;
        }
        int mi = segmentHit(g.modes(), mx, my);
        if (mi >= 0) {
            if (GameConfig.displayMode != mi) {
                GameConfig.displayMode = mi;
                applyDisplay();
            }
            return;
        }
        int ri = segmentHit(g.resolutions(), mx, my);
        if (ri >= 0 && GameConfig.RESOLUTIONS[ri][0] != GameConfig.winW) {
            GameConfig.winW = GameConfig.RESOLUTIONS[ri][0];
            GameConfig.winH = GameConfig.RESOLUTIONS[ri][1];
            applyDisplay();
            return;
        }
        int fi = segmentHit(g.fps(), mx, my);
        if (fi >= 0 && GameConfig.FPS_CHOICES[fi] != GameConfig.fpsCap) {
            GameConfig.fpsCap = GameConfig.FPS_CHOICES[fi];
            GameConfig.save();
        }
    }

    /** 屏幕坐标落在哪条音量条上；没点上返回 -1 */
    private int settingsSliderAt(double mx, double my) {
        Renderer.SettingsGeom g = Renderer.settingsGeom(
                renderer.getCanvasWidth(), renderer.getCanvasHeight());
        for (int i = 0; i < GameConfig.VOLUME_COUNT; i++) {
            if (g.volumes()[i].hit(mx, my)) {
                return i;
            }
        }
        return -1;
    }

    /** 命中一排三选一里的哪一段 */
    private static int segmentHit(Renderer.Rect[] segs, double mx, double my) {
        for (int i = 0; i < segs.length; i++) {
            if (segs[i].hit(mx, my)) {
                return i;
            }
        }
        return -1;
    }

    /** 把横向位置换算成 0..100 音量并写入配置（点击 / 拖动共用） */
    private void applyVolumeDrag(int idx, double mx) {
        Renderer.SettingsGeom g = Renderer.settingsGeom(
                renderer.getCanvasWidth(), renderer.getCanvasHeight());
        Renderer.Rect r = g.volumes()[idx];
        double frac = Math.max(0, Math.min(1, (mx - r.x()) / r.w()));
        GameConfig.setVolume(idx, (int) Math.round(frac * 100));
        GameAudio.refreshVolume();   // 拖动中实时试听 BGM 音量
    }

    /**
     * 应用显示模式与窗口分辨率（设置面板 / F11 / 启动时共用）。
     * 窗口=带标题栏的指定分辨率；全屏 / 无边框窗口=铺满全屏（JavaFX 的全屏即是
     * 无边框整屏，运行时无法临时去装饰，故两种都走 setFullScreen）。
     */
    private void applyDisplay() {
        if (stage == null) {
            return;
        }
        if (GameConfig.displayMode == GameConfig.MODE_WINDOW) {
            stage.setFullScreen(false);
            stage.setResizable(true);
            stage.setWidth(GameConfig.winW);
            stage.setHeight(GameConfig.winH);
        } else {
            stage.setFullScreen(true);   // MODE_FULLSCREEN 与 MODE_BORDERLESS 同样全屏
        }
        GameConfig.save();
    }

    /** F11：窗口模式与全屏互相切换 */
    private void toggleFullscreen() {
        GameConfig.displayMode = (GameConfig.displayMode == GameConfig.MODE_FULLSCREEN)
                ? GameConfig.MODE_WINDOW : GameConfig.MODE_FULLSCREEN;
        applyDisplay();
    }

    /** 大厅一帧：按 WASD 移动并夹紧在可走范围内，靠近角色即选中（含召唤师可高亮） */
    private void stepLobby(double dt, Renderer.LobbyGeom g) {
        if (!lobbyPosInit) {
            // 国王从王座台阶出发：站在四人一字排开之上、出征光门之下的纵深
            double[] sp = g.kingSpawn();
            lx = sp[0];
            ly = sp[1];
            lobbyPosInit = true;
        }
        double dx = 0, dy = 0;
        if (pressed.contains(KeyCode.A) || pressed.contains(KeyCode.LEFT)) dx -= 1;
        if (pressed.contains(KeyCode.D) || pressed.contains(KeyCode.RIGHT)) dx += 1;
        if (pressed.contains(KeyCode.W) || pressed.contains(KeyCode.UP)) dy -= 1;
        if (pressed.contains(KeyCode.S) || pressed.contains(KeyCode.DOWN)) dy += 1;
        if (dx != 0 || dy != 0) {
            double inv = 1.0 / Math.sqrt(dx * dx + dy * dy);
            lx += dx * inv * LOBBY_SPEED * dt;
            ly += dy * inv * LOBBY_SPEED * dt;
            lx = Math.max(g.minX(), Math.min(g.maxX(), lx));
            ly = Math.max(g.minY(), Math.min(g.maxY(), ly));
        }
        // 圆-圆检测：正站在谁面前（只做判定，不自动选人——选人由空格确认触发）
        int[] cls = LobbyClass.CLASSES;
        double reach = g.altarR() + g.avatarR();
        int near = 0;
        for (int i = 0; i < cls.length; i++) {
            double a = lx - g.altarC()[i][0];
            double b = ly - g.altarC()[i][1];
            if (a * a + b * b <= reach * reach) {
                near = cls[i];
            }
        }
        // 细节立绘：靠近→滑入展示；离开对应区域→滑出缩回；换人→重滑
        boolean want = near != 0;
        if (want && cardClass != near) {
            cardClass = near;
            cardReveal = 0;
        }
        double target = want ? 1 : 0;
        double spd = dt / (want ? 0.30 : 0.22);   // 滑入略缓、缩回略快
        if (cardReveal < target) {
            cardReveal = Math.min(target, cardReveal + spd);
        } else if (cardReveal > target) {
            cardReveal = Math.max(target, cardReveal - spd);
        }
        if (cardReveal <= 0) {
            cardClass = 0;
        }
    }

    /**
     * 大厅主行动键（空格/E）：
     * 站在勇者面前且尚未同行 → 招募/改选，操控对象立即替换为该勇者；
     * 否则尝试从光门出发。用实时坐标重算面前勇者，保证跨帧瞬间按键也灵敏。
     */
    private void lobbyAction() {
        int near = 0;
        if (inLobby) {
            Renderer.LobbyGeom g = Renderer.geom(
                    renderer.getCanvasWidth(), renderer.getCanvasHeight());
            double reach = g.altarR() + g.avatarR();
            for (int i = 0; i < LobbyClass.CLASSES.length; i++) {
                double a = lx - g.altarC()[i][0];
                double b = ly - g.altarC()[i][1];
                if (a * a + b * b <= reach * reach) {
                    near = LobbyClass.CLASSES[i];
                }
            }
        }
        if (near != 0 && near != lobbyChoice) {
            lobbyChoice = near;           // 招募/改选：操控对象随之替换
            return;
        }
        tryDepart();
    }

    /** 站在光门内且已选可出战职业 → 按 E/空格出发。 */
    private void tryDepart() {
        if (!inLobby || lobbyChoice == 0) {
            return;
        }
        Renderer.LobbyGeom g = Renderer.geom(
                renderer.getCanvasWidth(), renderer.getCanvasHeight());
        double reach = g.gR() + g.avatarR();
        double dx = lx - g.gx();
        double dy = ly - g.gy();
        if (dx * dx + dy * dy <= reach * reach) {
            beginGame(lobbyChoice);
        }
    }

    /** 玩家确认职业：生成对应勇者，退出大厅/主菜单，正式开局 */
    private void beginGame(int classKind) {
        if (!inTitle && !inLobby) {
            return;                       // 已在战斗中，忽略重复触发
        }
        battle.start(classKind);
        inTitle = false;
        inLobby = false;
        overlay = Renderer.OVER_NONE;
        GameAudio.stopMenuBgm();  // 出征 / 战斗冒烟都离开主界面
        GameAudio.stopLobbyBgm(); // 战斗中暂时没有 BGM
        pressed.clear();
    }

    /** 胜利/阵亡后重开：换一个种子重建世界，回到准备大厅重新选人 */
    private void restart() {
        battle.reset(System.nanoTime());
        lobbyChoice = 0;
        renderedFrames = 0;
        enterLobby();
    }

    /**
     * 战斗阶段的点击派发：阵亡结算的「继续」按钮 → HUD 开火/暂停按钮 → 升级三选一卡片。
     *
     * 开火与暂停按钮此前只画不响应：这两个坐标只被 drawHud 用来绘制，命中判定从未写过，
     * 点在按钮上毫无反应（暂停只能靠 ESC）。这里补上命中判定，坐标与 Renderer 共用同一组常量。
     *
     * 卡片尺寸和位置必须与 Renderer.drawUpgradePanel 严格一致：
     * 命中区域 3 个等宽矩形，y 范围 vh*0.32 .. vh*0.32 + 220。
     */
    private void handleBattleClick(double mx, double my) {
        World world = battle.world();
        double vw = renderer.getCanvasWidth();
        double vh = renderer.getCanvasHeight();

        // 1) 阵亡结算：右下角「继续」回到准备大厅
        if (world.defeat()) {
            if (hit(Renderer.continueButtonRect(vw, vh), mx, my)) {
                restart();
            }
            return;
        }
        if (world.victory()) {
            return;                      // 胜利画面只认 R 键
        }

        // 2) HUD 按钮：开火模式切换（自动 / 手动）
        if (hit(Renderer.fireButtonRect(vw, vh), mx, my)) {
            world.setAutoFire(!world.isAutoFire());
            return;
        }
        // 3) HUD 按钮：暂停 / 继续
        if (hit(Renderer.pauseButtonRect(vw, vh), mx, my)) {
            battle.toggleManualPause();
            return;
        }

        // 4) 升级三选一
        if (world.wizardCount() == 0) {
            return;
        }
        int wid = world.wizard(0);
        if (world.pendingChoices(wid) == 0) {
            return;
        }
        Upgrades.Choice[] cs = world.peekChoices(wid);
        if (cs == null) {
            return;
        }
        double cardW = Math.min(280, (vw - 80) / 3);
        double gap = 20;
        double total = cs.length * cardW + (cs.length - 1) * gap;
        double x0 = (vw - total) / 2;
        double y0 = vh * 0.32;
        double cardH = 220;
        for (int i = 0; i < cs.length; i++) {
            double bx = x0 + i * (cardW + gap);
            if (mx >= bx && mx <= bx + cardW && my >= y0 && my <= y0 + cardH) {
                battle.chooseUpgrade(i);
                return;
            }
        }
    }

    /** 点 (mx,my) 是否落在 [x, y, w, h] 矩形内 */
    private static boolean hit(double[] r, double mx, double my) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    private void readInput() {
        float dx = 0f;
        float dy = 0f;
        if (pressed.contains(KeyCode.A) || pressed.contains(KeyCode.LEFT)) {
            dx -= 1f;
        }
        if (pressed.contains(KeyCode.D) || pressed.contains(KeyCode.RIGHT)) {
            dx += 1f;
        }
        if (pressed.contains(KeyCode.W) || pressed.contains(KeyCode.UP)) {
            dy -= 1f;
        }
        if (pressed.contains(KeyCode.S) || pressed.contains(KeyCode.DOWN)) {
            dy += 1f;
        }
        input.set(dx, dy);

        // 开火：手动模式下按住鼠标左键，朝鼠标世界坐标开火
        input.buttons = 0;
        if (!battle.world().isAutoFire() && mouseDown) {
            input.buttons |= InputCommand.BUTTON_FIRE;
        }
        // 指挥：按住鼠标左键就是给宠物下令（与开火模式无关，自动开火时也能指挥）
        if (mouseDown) {
            input.buttons |= InputCommand.BUTTON_ORDER;
        }
        double camX = renderer.getCamX();
        double camY = renderer.getCamY();
        double vw = renderer.getCanvasWidth();
        double vh = renderer.getCanvasHeight();
        input.aimX = (float) (camX + (mouseX - vw / 2));
        input.aimY = (float) (camY + (mouseY - vh / 2));
    }
}
