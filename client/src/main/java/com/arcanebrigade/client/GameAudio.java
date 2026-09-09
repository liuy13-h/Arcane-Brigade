package com.arcanebrigade.client;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.File;

/**
 * 极简音频管理器：目前维护两个独立的循环音乐槽——
 * 主界面 BGM（audio/bgm_main.mp3）与 准备大厅 BGM（audio/bgm_lobby.mp3）。
 *
 * 音量从 {@link GameConfig} 读取：总音量 × BGM 音量（0..1），所有在播槽位
 * 一起生效。音效 / 语音槽目前还没有对应的播放源，接入时追加同类方法即可。
 * 找不到音频文件或媒体初始化失败时静默降级（不影响游戏运行）。
 */
public final class GameAudio {

    private static final String MENU_FILE = "bgm_main.mp3";
    private static final String LOBBY_FILE = "bgm_lobby.mp3";

    private static MediaPlayer menu;
    private static MediaPlayer lobby;

    private GameAudio() {}

    /** 启动主界面 BGM（若已在播则重来）。失败静默。 */
    public static void startMenuBgm() {
        release(menu);
        menu = start(MENU_FILE);
        refreshVolume();
    }

    /** 启动准备大厅 BGM（若已在播则重来）。失败静默。 */
    public static void startLobbyBgm() {
        release(lobby);
        lobby = start(LOBBY_FILE);
        refreshVolume();
    }

    /** 停止并释放主界面 BGM */
    public static void stopMenuBgm() {
        release(menu);
        menu = null;
    }

    /** 停止并释放准备大厅 BGM */
    public static void stopLobbyBgm() {
        release(lobby);
        lobby = null;
    }

    /** 音量配置变化后调用：所有在播的 BGM 统一用 总音量 × BGM 音量 */
    public static void refreshVolume() {
        double v = (GameConfig.volume(GameConfig.VOL_MASTER) / 100.0)
                * (GameConfig.volume(GameConfig.VOL_BGM) / 100.0);
        applyVolume(menu, v);
        applyVolume(lobby, v);
    }

    /** 建好一个无限循环的 MediaPlayer（不设错误即失败时置空） */
    private static MediaPlayer start(String fileName) {
        File f = findAsset("audio", fileName);
        if (f == null) {
            System.err.println("[GameAudio] 缺少音频: audio/" + fileName);
            return null;
        }
        try {
            Media m = new Media(f.toURI().toString());
            MediaPlayer p = new MediaPlayer(m);
            p.setCycleCount(MediaPlayer.INDEFINITE);   // 无限循环
            p.setOnError(() ->
                    System.err.println("[GameAudio] 播放出错: " + fileName + " " + p.getError()));
            p.play();
            return p;
        } catch (Throwable t) {
            System.err.println("[GameAudio] " + fileName + " 初始化失败（无音频设备等），忽略: "
                    + t.getMessage());
            return null;
        }
    }

    private static void applyVolume(MediaPlayer p, double v) {
        if (p == null) {
            return;
        }
        try {
            p.setVolume(v);
        } catch (Throwable ignored) {
            // 播放器已失效则忽略
        }
    }

    private static void release(MediaPlayer p) {
        if (p == null) {
            return;
        }
        try {
            p.stop();
        } catch (Throwable ignored) {
            // 已经停过的播放器直接丢弃
        }
        p.dispose();
    }

    /** 从当前工作目录逐级向上找「目录名 + 文件名」，兼容 run.bat 与 IntelliJ */
    private static File findAsset(String dirName, String fileName) {
        for (File d = new File(System.getProperty("user.dir")); d != null; d = d.getParentFile()) {
            File cand = new File(d, dirName);
            if (cand.isDirectory()) {
                File f = new File(cand, fileName);
                if (f.isFile()) {
                    return f;
                }
            }
        }
        return new File(dirName + File.separator + fileName);
    }
}
