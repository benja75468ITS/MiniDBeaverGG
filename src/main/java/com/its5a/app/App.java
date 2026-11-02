package com.its5a.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Punto de entrada de la aplicación JavaFX.
 * Carga la pantalla inicial; los controladores gestionan el flujo posterior.
 */
public class App extends Application {
    /** Carga la vista de inicio, aplica los estilos compartidos y muestra la ventana. */
    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(App.class.getResource("/com/its5a/view/login.fxml"));
        Scene scene = new Scene(root, 440, 580);
        scene.getStylesheets().add(App.class.getResource("/com/its5a/view/style.css").toExternalForm());
        stage.setTitle("Mini DBeaver – Inicio de Sesión SSH");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    /** Delega el inicio y el ciclo de vida de la aplicación al entorno JavaFX. */
    public static void main(String[] args) {
        launch(args);
    }
}
