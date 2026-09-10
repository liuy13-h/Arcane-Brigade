package com.arcanebrigade.client;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.File;

/**
 * 极简音频管理器：维护三个互不干扰的循环音乐槽——主界面 BGM、准备大厅 BGM、
 * 以及战斗 BGM。战斗槽由 {@link #setBattleMusic(int)} 驱动：同一时刻只播一首，
 * Boss 登场就换 Boss 曲，Boss 倒下就换回普通战斗曲。
 *
 * 音量从 {@link GameConfig} 读取：总音量 × BGM 音量（0..1），所有在播槽位
 * 一起生效。音效 / 语音槽目前还没有对应的播放源，接入时追加同类方法即可。
 * 找不到音频文件或媒体初始化失败时静默降级（不影响游戏运行）。
 */
public final class GameAudio {

    private static final String MENU_FILE = "bgm_main.mp3";
    private static final String LOBBY_FILE = "bgm_lobby.mp3";
    private static final String BATTLE_FILE = "bgm_battle.mp3";
    /** 四只 Boss 的登场音乐，下标与 {@code bossTier()} 对齐（同 Balance.BOSS_LEVELS 顺序） */
    private static final String[] BOSS_FILES = {
            "bgm_boss1.mp3", "bgm_boss2.mp3", "bgm_boss3.mp3", "bgm_boss4.mp3"
    };

    /** battleTrack 的哨兵值：战斗槽当前没有在播 */
    private static final int TRACK_NONE = -99;

    private static MediaPlayer menu;
    private static MediaPlayer lobby;
    private static MediaPlayer battle;
    /** 战斗槽在播的曲目：TRACK_NONE 未播 / -1 普通战斗 / 0..3 对应 Boss */
    private static int battleTrack = TRACK_NONE;

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

    /**
     * 按「当前是否有 Boss 在场」切换战斗音乐：{@code bossTier < 0} 放普通战斗曲，
     * 0..3 放对应 Boss 的登场音乐。曲目与在播的一致就什么都不做，可每帧调用。
     *
     * bossTier 直接传 {@code world.bossTier()}（Boss 不在场时它返回 -1）。
     */
    public static void setBattleMusic(int bossTier) {
        int want = (bossTier >= 0 && bossTier < BOSS_FILES.length) ? bossTier : -1;
        if (want == battleTrack) {
            return;          // 已在放这首。音源缺失时也靠它避免每帧重试刷屏
        }
        release(battle);
        battle = start(want < 0 ? BATTLE_FILE : BOSS_FILES[want]);
        battleTrack = want;
        refreshVolume();
    }

    /** 停止并释放战斗 BGM（离开战斗 / 结算时调用） */
    public static void stopBattleBgm() {
        release(battle);
        battle = null;
        battleTrack = TRACK_NONE;
    }

    /** 音量配置变化后调用：所有在播的 BGM 统一用 总音量 × BGM 音量 */
    public static void refreshVolume() {
        double v = (GameConfig.volume(GameConfig.VOL_MASTER) / 100.0)
                * (GameConfig.volume(GameConfig.VOL_BGM) / 100.0);
        applyVolume(menu, v);
        applyVolume(lobby, v);
        applyVolume(battle, v);
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
