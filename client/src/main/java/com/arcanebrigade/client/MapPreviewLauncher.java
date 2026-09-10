package com.arcanebrigade.client;

import javafx.application.Application;

/** MapPreview 的独立启动器，理由同 GameLauncher（绕开 JavaFX 模块检查） */
public final class MapPreviewLauncher {

    private MapPreviewLauncher() {}

    public static void main(String[] args) {
        Application.launch(MapPreview.class, args);
    }
}
