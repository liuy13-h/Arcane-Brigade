package com.arcanebrigade.client;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.File;

/**
 * 极简音频管理器：维护三组互不干扰的循环音乐槽——
 * 主界面 BGM（audio/bgm_main.mp3）、准备大厅 BGM（audio/bgm_lobby.mp3）、
 * 以及战斗 BGM（audio/bgm_battle.mp3 或 audio/bgm_boss1~4.mp3）。
 *
 * 战斗槽由 {@link #setBattleMusic(int)} 驱动：同一时刻只播一首，Boss 登场就换
 * Boss 曲、Boss 倒下就换回普通战斗曲，可逐帧调用（曲目没变就什么都不做）。
 *
 * 奶蛙（5 关 Boss）是例外：它有专属曲 boss_milky.mp3，走独立的 {@code boss} 槽，
 * 且**优先级高于战斗槽**——出场时战斗槽让位，倒下后自动恢复到让位前那首。
 * 之所以要两套并存而不是统一到 bossTier：奶蛙走的是 milkyId 独立通道，
 * {@code world.bossTier()} 在它出场时返回 -1，硬塞进 0..3 的 Boss 下标会让它永远匹配不到。
 *
 * 音量从 {@link GameConfig} 读取：总音量 × BGM 音量（0..1），所有在播槽位
 * 一起生效。音效 / 语音槽目前还没有对应的播放源，接入时追加同类方法即可。
 * 找不到音频文件或媒体初始化失败时静默降级（不影响游戏运行）。
 */
public final class GameAudio {

    private static final String MENU_FILE = "bgm_main.mp3";
    private static final String LOBBY_FILE = "bgm_lobby.mp3";
    /** 奶蛙专属曲。与下面的 BOSS_FILES（四只大 Boss 登场音乐）是两码事 */
    private static final String BOSS_FILE = "boss_milky.mp3";
    /** 普通战斗曲：没有 Boss 在场时循环 */
    private static final String BATTLE_FILE = "bgm_battle.mp3";
    /** 四只大 Boss 的登场音乐，下标与 {@code bossTier()} 对齐（同 Balance.BOSS_LEVELS 顺序） */
    private static final String[] BOSS_FILES = {
            "bgm_boss1.mp3", "bgm_boss2.mp3", "bgm_boss3.mp3", "bgm_boss4.mp3"
    };
    /**
     * 奶蛙技能二「捧腹大笑」的音效（单次播放，每次释放都从头重播）。
     * 注意：酷狗的 .kgg 是加密格式，JavaFX 无法解码，这里只接受普通 mp3（缺失时尝试 .wav）。
     */
    private static final String LAUGH_FILE = "laugh_milky.mp3";

    /** battleTrack 的哨兵值：战斗槽当前不该放任何曲子（未进战斗 / 已结算） */
    private static final int TRACK_NONE = -99;

    private static MediaPlayer menu;
    private static MediaPlayer lobby;
    /** 5 关 Boss 奶蛙在场时的专属循环音乐（优先级高于战斗槽） */
    private static MediaPlayer boss;
    private static MediaPlayer battle;
    /** 奶蛙「捧腹大笑」音效（单次） */
    private static MediaPlayer laugh;
    /**
     * 音效增益：笑声素材本身比 BGM 轻，这里整体抬一档，避免被背景音乐盖住。
     * 最终音量 = clamp(总音量 × 音效音量 × SFX_GAIN, 0..1)。
     */
    private static final double SFX_GAIN = 1.8;
    /**
     * 战斗槽「应该」播的曲目：TRACK_NONE = 不该播 / -1 = 普通战斗曲 / 0..3 = 对应 Boss 曲。
     * 与实际在播的那首分开记，是为了奶蛙出场时能让位——那时只更新本字段而不起播，
     * 等奶蛙走后按它恢复。
     */
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
     * 奶蛙出场：开始循环播放其专属 BGM，并让战斗槽暂时让位（两首曲子不能叠着放）。
     * 让位前的曲目记在 battleTrack 里，{@link #stopBossBgm()} 会按它恢复。
     */
    public static void startBossBgm() {
        // 战斗曲让位：只停播放器，battleTrack 保留，奶蛙走后据此恢复
        release(battle);
        battle = null;
        release(boss);
        boss = start(BOSS_FILE);
        refreshVolume();
    }

    /** 奶蛙血量归零消失：停止专属 BGM，并把战斗槽恢复到让位前那首 */
    public static void stopBossBgm() {
        release(boss);
        boss = null;
        resumeBattle();
    }

    /**
     * 按「当前是否有 Boss 在场」切换战斗音乐：{@code bossTier < 0} 放普通战斗曲，
     * 0..3 放对应 Boss 的登场音乐。曲目与在播的一致就什么都不做，可每帧调用。
     *
     * bossTier 直接传 {@code world.bossTier()}（Boss 不在场时它返回 -1）。
     * 骨蛇（小 Boss）的 bossTier 是 -2，落进 {@code < 0} 分支 → 普通战斗曲，
     * 与它「不独占舞台」的定位一致。
     *
     * 奶蛙曲在播时只记录、不起播——奶蛙优先级更高，见类注释。
     */
    public static void setBattleMusic(int bossTier) {
        int want = (bossTier >= 0 && bossTier < BOSS_FILES.length) ? bossTier : -1;
        if (boss != null) {
            // 奶蛙专属曲在播：战斗槽让位，只更新「该播哪首」，等 stopBossBgm 恢复
            battleTrack = want;
            return;
        }
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

    /** 奶蛙曲结束后按 battleTrack 恢复战斗槽。TRACK_NONE 表示战斗已结算，不恢复 */
    private static void resumeBattle() {
        if (battleTrack == TRACK_NONE) {
            return;
        }
        release(battle);
        battle = start(battleTrack < 0 ? BATTLE_FILE : BOSS_FILES[battleTrack]);
        refreshVolume();
    }

    /**
     * 奶蛙技能二「捧腹大笑」：每次释放都从头重播该音效（单次，不循环）。
     * 音频缺失时静默跳过，不影响技能与动画。
     */
    public static void playLaugh() {
        if (laugh == null) {
            laugh = makeSfx(LAUGH_FILE);   // 首次释放时加载
        }
        if (laugh == null) {
            return;
        }
        try {
            laugh.stop();
            laugh.seek(Duration.ZERO);
            applyVolume(laugh, sfxVolume());
            laugh.play();
        } catch (Throwable ignored) {
            // 播放器已失效则忽略
        }
    }

    /** 技能二释放完毕：立即停止笑声（播放器保留，下次释放从头重播） */
    public static void stopLaugh() {
        if (laugh == null) {
            return;
        }
        try {
            laugh.stop();
        } catch (Throwable ignored) {
            // 播放器已失效则忽略
        }
    }

    /** 音量配置变化后调用：所有在播的 BGM 统一用 总音量 × BGM 音量 */
    public static void refreshVolume() {
        double v = (GameConfig.volume(GameConfig.VOL_MASTER) / 100.0)
                * (GameConfig.volume(GameConfig.VOL_BGM) / 100.0);
        applyVolume(menu, v);
        applyVolume(lobby, v);
        applyVolume(boss, v);
        applyVolume(battle, v);
        applyVolume(laugh, sfxVolume());
    }

    /** 音效音量 = 总音量 × 音效音量 × 增益（上限 1.0） */
    private static double sfxVolume() {
        double v = (GameConfig.volume(GameConfig.VOL_MASTER) / 100.0)
                * (GameConfig.volume(GameConfig.VOL_SFX) / 100.0)
                * SFX_GAIN;
        return Math.min(1.0, v);
    }

    /** 建一个「单次播放」的音效播放器（不自动 play）。mp3 缺失时退回同名 .wav */
    private static MediaPlayer makeSfx(String fileName) {
        File f = findAsset("audio", fileName);
        if (f == null || !f.isFile()) {
            f = findAsset("audio", fileName.replace(".mp3", ".wav"));
        }
        if (f == null || !f.isFile()) {
            System.err.println("[GameAudio] 缺少音效: audio/" + fileName
                    + "（.kgg 为酷狗加密格式无法解码，请放一个同名 mp3 或 wav）");
            return null;
        }
        try {
            Media m = new Media(f.toURI().toString());
            MediaPlayer p = new MediaPlayer(m);
            p.setCycleCount(1);
            p.setOnError(() ->
                    System.err.println("[GameAudio] 音效播放出错: " + fileName + " " + p.getError()));
            applyVolume(p, sfxVolume());
            return p;
        } catch (Throwable t) {
            System.err.println("[GameAudio] " + fileName + " 初始化失败，忽略: " + t.getMessage());
            return null;
        }
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
