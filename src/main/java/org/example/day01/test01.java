package org.example.day01;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.Optional;

public class test01 extends Application {

    @Override
    public void start(Stage stage) {
        Label label = new Label("Hello, JavaFX!");
        Button button = new Button("点我");
        button.setOnAction(e -> label.setText("你点击了按钮！"));
        VBox root = new VBox(10, label, button);   // 垂直布局，间距 10px
        Scene scene = new Scene(root, 400, 300);   // 创建场景，宽 400 高 300
        stage.setTitle("JavaFX 入门");              // 设置窗口标题
        stage.setScene(scene);                      // 装载场景
        //禁止重置窗口大小
        stage.setResizable(false);
        //窗口关闭时间
        stage.setOnCloseRequest(event -> {
            //消费事件
            event.consume();
            //创建一个确认框
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            //标题
            alert.setTitle("退出程序");
            //头文字
            alert.setHeaderText(null);
            //内容文字
            alert.setContentText("您是否要退出游戏？");
            Optional<ButtonType> result = alert.showAndWait();
            if(result.get() == ButtonType.OK) {
                //退出
                Platform.exit();
            }
        });
        stage.setOnHiding(e -> {
            System.out.println("setOnHiding....");
        });
        stage.setOnHidden(e -> {
            System.out.println("setOnHidden....");
        });
        stage.setOnShowing(event -> {
            //必须在show方法调用之前，绑定
            System.out.println("setOnShowing.....");
        });
        stage.setOnShown(event -> {
            //必须在show方法调用之前，绑定
            System.out.println("setOnShown.....");
        });

        stage.show();                               // 显示窗口
    }
    public static void main(String[] args) {
        launch(args);
    }

}
