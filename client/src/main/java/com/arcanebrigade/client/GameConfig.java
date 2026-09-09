package com.arcanebrigade.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * 全局配置（主界面「设置」面板的数据源 + 本地持久化）。
 *
 * 目前代码库没有音频引擎/文件，所以四个音量只是**框架值**：设置面板负责调，
 * 存进配置文件；将来音频接入后直接读这里的值即可。所有字段静态可变，
 * Renderer 每帧直接读它画 UI，GameApp 改它后调 {@link #save()} 落盘。
 *
 * 配置文件位置：%USERPROFILE%\.arcanebrigade\settings.properties
 */
public final class GameConfig {

    private GameConfig() {}

    /** 音量下标：0=总音量，1=BGM，2=音效，3=语音 */
    public static final int VOL_MASTER = 0;
    public static final int VOL_BGM = 1;
    public static final int VOL_SFX = 2;
    public static final int VOL_VOICE = 3;
    public static final int VOLUME_COUNT = 4;

    /** 显示模式：0=窗口，1=全屏，2=无边框窗口 */
    public static final int MODE_WINDOW = 0;
    public static final int MODE_FULLSCREEN = 1;
    public static final int MODE_BORDERLESS = 2;

    /** 帧率上限档位 */
    public static final int[] FPS_CHOICES = { 30, 60, 120 };

    /** 窗口模式下可选的分辨率 */
    public static final int[][] RESOLUTIONS = {
            { 1280, 720 }, { 1600, 900 }, { 1920, 1080 } };

    private static final SecureRandom RND = new SecureRandom();

    /** 四个音量 0..100（默认：总 80 / BGM 70 / 音效 90 / 语音 90） */
    private static final int[] VOL = { 80, 70, 90, 90 };

    /** 是否在战斗画面角落显示玩家 ID */
    public static boolean showPlayerId = true;
    /** 显示模式：MODE_* 之一 */
    public static int displayMode = MODE_WINDOW;
    /** 窗口模式下的宽高（全屏 / 无边框时会忽略） */
    public static int winW = 1280;
    public static int winH = 720;
    /** 帧率上限：FPS_CHOICES 之一 */
    public static int fpsCap = 60;
    /** 本机玩家 ID（首次运行生成并持久化） */
    public static String playerId;

    /** 当前播放的声音总开关（框架占位，音频引擎接入后读取它） */
    public static boolean audioEnabled() {
        return VOL[VOL_MASTER] > 0;
    }

    public static int volume(int idx) {
        return VOL[idx];
    }

    /** 设置音量并夹到 0..100（调用方负责 save） */
    public static void setVolume(int idx, int v) {
        if (idx < 0 || idx >= VOLUME_COUNT) {
            return;
        }
        VOL[idx] = Math.max(0, Math.min(100, v));
    }

    /** 读取本地配置。无配置文件时按默认值并落盘一次（保证玩家 ID 持久）。 */
    public static void load() {
        Properties p = new Properties();
        File f = file();
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("[GameConfig] 读取配置失败，使用默认值: " + e.getMessage());
            }
        }
        VOL[VOL_MASTER] = clampInt(p.getProperty("volume.master"), VOL[VOL_MASTER]);
        VOL[VOL_BGM]    = clampInt(p.getProperty("volume.bgm"), VOL[VOL_BGM]);
        VOL[VOL_SFX]    = clampInt(p.getProperty("volume.sfx"), VOL[VOL_SFX]);
        VOL[VOL_VOICE]  = clampInt(p.getProperty("volume.voice"), VOL[VOL_VOICE]);
        showPlayerId = p.getProperty("showPlayerId", "true").equalsIgnoreCase("true");
        displayMode = clampInt(p.getProperty("displayMode"), displayMode);
        winW = clampInt(p.getProperty("winW"), winW);
        winH = clampInt(p.getProperty("winH"), winH);
        fpsCap = clampInt(p.getProperty("fpsCap"), fpsCap);
        if (fpsCap != 30 && fpsCap != 60 && fpsCap != 120) {
            fpsCap = 60;
        }
        playerId = p.getProperty("playerId");
        if (playerId == null || playerId.isBlank()) {
            playerId = generatePlayerId();
        }
        save();
    }

    /** 把当前配置写回本地文件 */
    public static void save() {
        Properties p = new Properties();
        p.setProperty("volume.master", String.valueOf(VOL[VOL_MASTER]));
        p.setProperty("volume.bgm", String.valueOf(VOL[VOL_BGM]));
        p.setProperty("volume.sfx", String.valueOf(VOL[VOL_SFX]));
        p.setProperty("volume.voice", String.valueOf(VOL[VOL_VOICE]));
        p.setProperty("showPlayerId", String.valueOf(showPlayerId));
        p.setProperty("displayMode", String.valueOf(displayMode));
        p.setProperty("winW", String.valueOf(winW));
        p.setProperty("winH", String.valueOf(winH));
        p.setProperty("fpsCap", String.valueOf(fpsCap));
        if (playerId != null) {
            p.setProperty("playerId", playerId);
        }
        File f = file();
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            System.err.println("[GameConfig] 无法创建配置目录: " + dir);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "Arcane Brigade settings");
        } catch (IOException e) {
            System.err.println("[GameConfig] 写入配置失败: " + e.getMessage());
        }
    }

    private static File file() {
        File dir = new File(System.getProperty("user.home"), ".arcanebrigade");
        return new File(dir, "settings.properties");
    }

    private static int clampInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String generatePlayerId() {
        char[] hex = "0123456789ABCDEF".toCharArray();
        StringBuilder sb = new StringBuilder("AB-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(hex[RND.nextInt(16)]);
        }
        return sb.toString();
    }
}
