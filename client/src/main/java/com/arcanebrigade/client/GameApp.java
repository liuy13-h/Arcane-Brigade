package com.arcanebrigade.client;

import com.arcanebrigade.core.Balance;
import com.arcanebrigade.core.HeroClass;
import com.arcanebrigade.core.InputCommand;
import com.arcanebrigade.core.Loadout;
import com.arcanebrigade.core.Upgrades;
import com.arcanebrigade.core.World;
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
 * 流程：启动先进「准备大厅」(inLobby=true)。大厅是纯客户端轻量状态，不生成 core
 * World：WASD 自由走动、走上职业祭坛即选中职业、走进出征光门按 E 才 spawnWizard
 * 转入正式战斗。战斗阶段固定 60Hz 推进 World，渲染帧用 alpha 插值衔接。
 */
public final class GameApp extends Application {

    private static final double STEP = Balance.FIXED_STEP;
    /** 单帧最多补几步逻辑，防止卡顿后出现"死亡螺旋" */
    private static final int MAX_STEPS = 5;
    /** 大厅里化身移动速度(px/s) */
    private static final double LOBBY_SPEED = 300.0;

    private final Set<KeyCode> pressed = EnumSet.noneOf(KeyCode.class);
    private final InputCommand input = new InputCommand();

    private World world;
    private Renderer renderer;

    /** 战斗冒烟帧数：-Dab.smoke=180 = 自动选巫师打 180 帧后退出（战斗路径回归） */
    private int smokeFrames = -1;
    private int renderedFrames;
    /** 大厅冒烟帧数：-Dab.lobbyFrames=150 = 只渲染大厅 150 帧后退出（大厅回归） */
    private int lobbySmokeFrames = -1;
    private int lobbyRendered;

    /** true=准备大厅；false=正式战斗 */
    private boolean inLobby = true;
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
        Sprites.load();

        world = new World(20260907L);

        Canvas canvas = new Canvas(1280, 720);
        Pane root = new Pane(canvas);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        root.setStyle("-fx-background-color: #121019;");

        Scene scene = new Scene(root, 1280, 720);
        scene.setOnKeyPressed(e -> {
            if (inLobby) {
                // 大厅阶段：WASD 靠 pressed 轮询移动；空格/E 先应答面前勇者的招募，其次光门出发
                pressed.add(e.getCode());
                if (e.getCode() == KeyCode.E || e.getCode() == KeyCode.SPACE) {
                    lobbyAction();
                }
                return;
            }
            pressed.add(e.getCode());
            // R 键在升级面板弹出时是重抽
            if (e.getCode() == KeyCode.R && world.wizardCount() > 0
                    && world.pendingChoices(world.wizard(0)) > 0) {
                world.rerollChoices(world.wizard(0));
            }
        });
        scene.setOnKeyReleased(e -> pressed.remove(e.getCode()));

        scene.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || inLobby) {
                return;
            }
            handleChoiceClick(e.getX(), e.getY());
        });

        stage.setTitle("Arcane Brigade — 奥术旅团");
        stage.setScene(scene);
        stage.show();
        root.requestFocus();

        renderer = new Renderer(canvas);

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

        // 战斗冒烟：跳过大厅，自动选巫师直接跑真实模拟+渲染路径做稳定性验证
        if (smokeFrames > 0) {
            beginGame(HeroClass.WIZARD);
        }

        final long[] last = { System.nanoTime() };
        final double[] acc = { 0.0 };
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

                // ---- 准备大厅阶段（不推进 core World） ----
                if (inLobby) {
                    renderer.setFps(fps[0]);
                    lobbyAnimT += dt;
                    double vw = canvas.getWidth();
                    double vh = canvas.getHeight();
                    Renderer.LobbyGeom g = Renderer.geom(vw, vh);
                    stepLobby(dt, g);
                    renderer.drawLobby(g, lx, ly, lobbyChoice, lobbyAnimT, cardClass, cardReveal);
                    if (lobbySmokeFrames > 0 && ++lobbyRendered >= lobbySmokeFrames) {
                        System.out.printf("[lobby] 渲染 %d 帧完成（大厅），退出%n", lobbyRendered);
                        Platform.exit();
                    }
                    return;
                }

                // ---- 正式战斗阶段 ----
                boolean paused = world.wizardCount() > 0
                        && world.pendingChoices(world.wizard(0)) > 0;
                if (!paused) {
                    acc[0] += dt;
                    int steps = 0;
                    while (acc[0] >= STEP && steps < MAX_STEPS) {
                        readInput();
                        world.step((float) STEP, input);
                        acc[0] -= STEP;
                        steps++;
                    }
                    if (steps == MAX_STEPS) {
                        acc[0] = 0.0;
                    }
                } else {
                    acc[0] = 0.0;   // 暂停时不累积
                }

                renderer.setFps(fps[0]);
                renderer.draw(world, (float) (acc[0] / STEP));

                // 升级面板始终叠加在画面最上层
                if (paused) {
                    int wid = world.wizard(0);
                    Loadout lo = world.loadout(wid);
                    if (lo != null) {
                        Upgrades.Choice[] cs = world.peekChoices(wid);
                        renderer.drawUpgradePanel(cs, lo.rerolls,
                                canvas.getWidth(), canvas.getHeight());
                    }
                }

                if (smokeFrames > 0 && ++renderedFrames >= smokeFrames) {
                    System.out.printf("[smoke] 渲染 %d 帧完成，实体 %d，敌人 %d，退出%n",
                            renderedFrames, world.liveCount(), world.enemyCount());
                    Platform.exit();
                }
            }
        }.start();
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
        if (near != 0 && near != lobbyChoice && near != LobbyClass.SUMMONER) {
            lobbyChoice = near;           // 招募/改选：操控对象随之替换
            return;
        }
        tryDepart();
    }

    /** 站在光门内且已选可出战职业 → 按 E/空格出发。召唤师尚未开放，选了也走不了 */
    private void tryDepart() {
        if (!inLobby || lobbyChoice == 0 || lobbyChoice == LobbyClass.SUMMONER) {
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

    /** 玩家确认职业：生成对应巫师，退出大厅，正式开局 */
    private void beginGame(int classKind) {
        if (!inLobby) {
            return;
        }
        world.spawnWizard(0f, 0f, classKind);
        inLobby = false;
    }

    /**
     * 鼠标点击命中三选一卡片。卡片尺寸和位置必须与 Renderer.drawUpgradePanel 严格一致。
     * 命中区域：3 个等宽矩形，y 范围 vh*0.32 .. vh*0.32 + 220。
     */
    private void handleChoiceClick(double mx, double my) {
        if (world.wizardCount() == 0) {
            return;
        }
        int wid = world.wizard(0);
        if (world.pendingChoices(wid) == 0) {
            return;
        }
        double vw = renderer.getCanvasWidth();
        double vh = renderer.getCanvasHeight();
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
                world.applyChoice(wid, i);
                return;
            }
        }
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
    }
}
