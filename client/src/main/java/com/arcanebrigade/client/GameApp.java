package com.arcanebrigade.client;

import com.arcanebrigade.core.Balance;
import com.arcanebrigade.core.HeroClass;
import com.arcanebrigade.core.InputCommand;
import com.arcanebrigade.core.Loadout;
import com.arcanebrigade.core.Spells;
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
 * 模拟固定 60Hz 是关键：D5 做联机预测与回滚时，逻辑帧必须可重放，
 * 渲染则能跑多快跑多快，两者用 alpha 插值衔接。
 *
 * D3 起增加了"升级三选一"：pendingChoices>0 时暂停推进，等待玩家点击。
 */
public final class GameApp extends Application {

    private static final double STEP = Balance.FIXED_STEP;
    /** 单帧最多补几步逻辑，防止卡顿后出现"死亡螺旋" */
    private static final int MAX_STEPS = 5;

    private final Set<KeyCode> pressed = EnumSet.noneOf(KeyCode.class);
    private final InputCommand input = new InputCommand();

    private World world;
    private Renderer renderer;

    /** 冒烟模式帧数：-Dab.smoke=180 表示渲染 180 帧后自动退出，用于自动化验证渲染路径不抛异常 */
    private int smokeFrames = -1;
    private int renderedFrames;

    /** 选职业状态：进入游戏先选职业，选完才生成法师并开始模拟 */
    private boolean selecting = true;
    private int chosenClass = HeroClass.WIZARD;
    private double mouseX, mouseY;
    /** 鼠标左键是否按下（手动开火模式用） */
    private boolean mouseDown;

    @Override
    public void start(Stage stage) {
        String smoke = System.getProperty("ab.smoke");
        if (smoke != null && !smoke.isBlank()) {
            smokeFrames = Integer.parseInt(smoke);
        }
        Sprites.load();

        world = new World(20260907L);
        // 先不生成法师：进入游戏先弹选职业界面，玩家点选后才正式开局

        Canvas canvas = new Canvas(1280, 720);
        Pane root = new Pane(canvas);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        root.setStyle("-fx-background-color: #121019;");

        Scene scene = new Scene(root, 1280, 720);
        scene.setOnKeyPressed(e -> {
            if (selecting) {
                // 选职业阶段：数字键 1/2/3 快速选
                if (e.getCode() == KeyCode.DIGIT1 || e.getCode() == KeyCode.NUMPAD1) {
                    beginGame(HeroClass.WIZARD);
                } else if (e.getCode() == KeyCode.DIGIT2 || e.getCode() == KeyCode.NUMPAD2) {
                    beginGame(HeroClass.WARRIOR);
                } else if (e.getCode() == KeyCode.DIGIT3 || e.getCode() == KeyCode.NUMPAD3) {
                    beginGame(HeroClass.ARCHER);
                }
                return;
            }
            pressed.add(e.getCode());
            // R 键：胜利画面下重开一局；升级面板弹出时则是重抽
            if (e.getCode() == KeyCode.R) {
                if (world.victory()) {
                    restart();
                } else if (world.wizardCount() > 0
                        && world.pendingChoices(world.wizard(0)) > 0) {
                    world.rerollChoices(world.wizard(0));
                }
            }
        });
        scene.setOnKeyReleased(e -> pressed.remove(e.getCode()));

        scene.setOnMouseMoved(e -> {
            mouseX = e.getX();
            mouseY = e.getY();
        });

        scene.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (selecting) {
                return;
            }
            mouseDown = true;
            // 点开火切换按钮：切换自动/手动开火
            double[] fb = Renderer.fireButtonRect(canvas.getWidth(), canvas.getHeight());
            if (e.getX() >= fb[0] && e.getX() <= fb[0] + fb[2]
                    && e.getY() >= fb[1] && e.getY() <= fb[1] + fb[3]) {
                world.setAutoFire(!world.isAutoFire());
            }
        });

        scene.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                mouseDown = false;
            }
        });

        scene.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (selecting) {
                double[][] r = classCardRects(canvas.getWidth(), canvas.getHeight());
                for (int i = 0; i < 3; i++) {
                    if (e.getX() >= r[i][0] && e.getX() <= r[i][0] + r[i][2]
                            && e.getY() >= r[i][1] && e.getY() <= r[i][1] + r[i][3]) {
                        beginGame(cardClass(i));
                        return;
                    }
                }
                return;
            }
            handleChoiceClick(e.getX(), e.getY());
        });

        stage.setTitle("Arcane Brigade — 奥术旅团");
        stage.setScene(scene);
        stage.show();
        root.requestFocus();

        renderer = new Renderer(canvas);

        // 冒烟模式：自动选巫师，直接跑真实模拟+渲染路径做稳定性验证
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

                renderer.setMouse(mouseX, mouseY);

                if (selecting) {
                    renderer.setFps(fps[0]);
                    double vw = canvas.getWidth();
                    double vh = canvas.getHeight();
                    renderer.drawClassSelect(vw, vh, classCardRects(vw, vh), mouseX, mouseY);
                    return;
                }

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

    /** 选职业卡片序号 → 职业常量 */
    private static int cardClass(int i) {
        return switch (i) {
            case 1 -> HeroClass.WARRIOR;
            case 2 -> HeroClass.ARCHER;
            default -> HeroClass.WIZARD;
        };
    }

    /** 玩家确认职业：生成对应法师，退出选职业状态，正式开局 */
    private void beginGame(int classKind) {
        if (!selecting) {
            return;
        }
        world.spawnWizard(0f, 0f, classKind);
        chosenClass = classKind;
        selecting = false;
    }

    /** 胜利后重开：换一个种子重建世界，回到选职业界面 */
    private void restart() {
        world = new World(System.nanoTime());
        selecting = true;
        pressed.clear();
        renderedFrames = 0;
    }

    /** 三张职业卡片的矩形 [x, y, w, h]，命中判定与 Renderer 必须一致 */
    private static double[][] classCardRects(double vw, double vh) {
        double cardW = Math.min(300, (vw - 80) / 3);
        double cardH = 360;
        double gap = 24;
        double total = 3 * cardW + 2 * gap;
        double x0 = (vw - total) / 2;
        double y0 = vh * 0.28;
        double[][] r = new double[3][4];
        for (int i = 0; i < 3; i++) {
            r[i][0] = x0 + i * (cardW + gap);
            r[i][1] = y0;
            r[i][2] = cardW;
            r[i][3] = cardH;
        }
        return r;
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
        if (!world.isAutoFire() && mouseDown) {
            input.buttons |= InputCommand.BUTTON_FIRE;
        }
        double camX = renderer.getCamX();
        double camY = renderer.getCamY();
        double vw = renderer.getCanvasWidth();
        double vh = renderer.getCanvasHeight();
        input.aimX = (float) (camX + (mouseX - vw / 2));
        input.aimY = (float) (camY + (mouseY - vh / 2));
    }
}
