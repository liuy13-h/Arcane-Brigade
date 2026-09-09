package com.arcanebrigade.client;

import javafx.application.Application;

/**
 * 独立启动器。
 *
 * 存在的理由：JavaFX 会检查主类是否继承了 Application，如果从 classpath
 * 启动 JavaFX 没有被当作命名模块加载，会直接报
 * "JavaFX runtime components are missing"。把 main 挪出 Application 子类即可绕过。
 * 这样无论用 IntelliJ 直接跑、mvn 跑、还是 jpackage 打包，路径都一致。
 */
public final class GameLauncher {

    private GameLauncher() {}

    public static void main(String[] args) {
        Application.launch(GameApp.class, args);
    }
}