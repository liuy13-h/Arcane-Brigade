package com.arcanebrigade.client;

import com.arcanebrigade.core.Balance;
import com.arcanebrigade.core.ArenaMap;
import com.arcanebrigade.core.HeroClass;
import com.arcanebrigade.core.InputCommand;
import com.arcanebrigade.core.World;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 地图预览工具（开发用，不参与游戏）。
 *
 * 离屏渲染四个阶段的地图各出一张 PNG，方便不进游戏就能核对模板风格是否到位。
 * 用法：java com.arcanebrigade.client.MapPreviewLauncher [输出目录]
 */
public final class MapPreview extends Application {

    private static final int W = 1280;
    private static final int H = 720;
    private static String outDir = ".";
    private static ArenaMap previewMap = ArenaMap.DESERT_RUINS;

    @Override
    public void start(Stage stage) {
        java.util.List<String> raw = getParameters().getRaw();
        if (!raw.isEmpty() && !raw.get(0).isBlank()) {
            outDir = raw.get(0);
        }
        String requestedMap = System.getProperty("ab.previewMap");
        if (requestedMap != null && !requestedMap.isBlank()) {
            previewMap = ArenaMap.valueOf(requestedMap.trim().toUpperCase());
        }
        Sprites.load();
        try {
            for (int s = 0; s < Balance.STAGE_DURATIONS.length; s++) {
                render(s, new File(outDir, "map-stage" + s + ".png"));
            }
            System.out.println("[preview] 完成，输出到 " + new File(outDir).getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
        Platform.exit();
    }

    private static void render(int targetStage, File out) throws Exception {
        World w = new World(20260909L + targetStage, previewMap);
        int wid = w.spawnWizard(0f, 0f, HeroClass.WIZARD);
        w.maxHp[wid] = 1_000_000f;
        w.hp[wid] = w.maxHp[wid];

        // 关卡改为作者固定摆放后，预览必须停在出生点：这样才能核对中央掩体和
        // 机关的位置，不再沿用旧版“围绕玩家随机散布障碍”的偏移逻辑。

        InputCommand in = new InputCommand();
        in.set(0f, 0f);

        // 推进到目标阶段（障碍物在阶段切换时围绕玩家生成）。
        // 至少走一帧：第 0 阶段的障碍也是靠首帧生成的
        int guard = 0;
        do {
            w.step(Balance.FIXED_STEP, in);
        } while (w.stage() < targetStage && guard++ < 40_000);

        Canvas canvas = new Canvas(W, H);
        Renderer r = new Renderer(canvas);
        r.draw(w, 0f);
        WritableImage img = canvas.snapshot(null, null);

        BufferedImage bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        int[] buf = new int[W * H];
        img.getPixelReader().getPixels(0, 0, W, H, PixelFormat.getIntArgbInstance(), buf, 0, W);
        bi.setRGB(0, 0, W, H, buf, 0, W);
        ImageIO.write(bi, "png", out);
        System.out.printf("[preview] %s stage %d -> %s（障碍 %d）%n",
                previewMap.displayName(), targetStage, out.getName(), w.obstacleCount());
    }
}
