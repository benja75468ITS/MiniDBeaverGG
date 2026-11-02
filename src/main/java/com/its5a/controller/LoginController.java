package com.its5a.controller;

import com.its5a.model.RemoteService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Gestiona la validación del inicio de sesión, la conexión SSH y la navegación a la vista principal. */
public class LoginController {
    // Campos y controles del inicio de sesión inyectados desde login.fxml.
    @FXML private TextField hostField;
    @FXML private TextField userField;
    @FXML private PasswordField passField;
    @FXML private Button loginButton;
    @FXML private ProgressIndicator progress;
    @FXML private Label statusLabel;

    /** Inicializa el formulario con el host usado habitualmente; las credenciales se escriben o autocompletan. */
    @FXML
    public void initialize() {
        hostField.setText("fi.jcaguilar.dev");
    }

    /** Carga credenciales opcionales desde ssh.properties, un archivo local ignorado por Git. */
    @FXML
    private void onFillCredentials() {
        Path credentialsPath = Path.of("ssh.properties");
        if (!Files.isRegularFile(credentialsPath)) {
            hostField.setText("fi.jcaguilar.dev");
            statusLabel.setStyle("-fx-text-fill: #9F2F2D;");
            statusLabel.setText("Configura ssh.properties para autocompletar o escribe tus credenciales.");
            return;
        }

        Properties credentials = new Properties();
        try (InputStream input = Files.newInputStream(credentialsPath)) {
            credentials.load(input);
        } catch (IOException ex) {
            statusLabel.setStyle("-fx-text-fill: #9F2F2D;");
            statusLabel.setText("No se pudo leer ssh.properties: " + ex.getMessage());
            return;
        }

        hostField.setText(credentials.getProperty("ssh.host", "fi.jcaguilar.dev").trim());
        userField.setText(credentials.getProperty("ssh.user", "").trim());
        passField.setText(credentials.getProperty("ssh.password", ""));
        statusLabel.setStyle("-fx-text-fill: #346538;");
        statusLabel.setText(userField.getText().isEmpty() || passField.getText().isEmpty()
                ? "Host cargado; completa el usuario y contraseña SSH."
                : "Credenciales locales cargadas.");
    }

    /** Valida el formulario y conecta con SSH/MariaDB en un hilo de trabajo. */
    @FXML
    private void onLogin() {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        String pass = passField.getText();
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            statusLabel.setStyle("-fx-text-fill: #9F2F2D;");
            statusLabel.setText("Completa host, usuario y contraseña.");
            return;
        }
        setBusy(true);
        statusLabel.setStyle("-fx-text-fill: #787774;");
        statusLabel.setText("Abriendo túnel SSH y conectando a MariaDB…");

        Task<RemoteService> task = new Task<>() {
            @Override
            protected RemoteService call() throws Exception {
                RemoteService s = new RemoteService();
                try {
                    s.connect(host, user, pass);
                    return s;
                } catch (Exception e) {
                    s.close();
                    throw e;
                }
            }
        };
        task.setOnSucceeded(e -> openMain(task.getValue()));
        task.setOnFailed(e -> {
            setBusy(false);
            statusLabel.setStyle("-fx-text-fill: #9F2F2D;");
            statusLabel.setText("Error: " + task.getException().getMessage());
        });
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    /** Carga el explorador de bases de datos después de conectar correctamente. */
    private void openMain(RemoteService service) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/its5a/view/main.fxml"));
            Parent root = loader.load();
            MainController controller = loader.getController();
            Stage stage = (Stage) loginButton.getScene().getWindow();
            controller.init(service, stage);

            Scene scene = new Scene(root, 1140, 700);
            scene.getStylesheets().add(getClass().getResource("/com/its5a/view/style.css").toExternalForm());
            stage.setScene(scene);
            stage.setTitle("Mini DBeaver – MariaDB → SQLite");
            stage.setMinWidth(960);
            stage.setMinHeight(600);
            stage.setResizable(true);
            stage.centerOnScreen();
        } catch (Exception ex) {
            service.close();
            setBusy(false);
            statusLabel.setStyle("-fx-text-fill: #f87171;");
            statusLabel.setText("Error al abrir la ventana principal: " + ex.getMessage());
        }
    }

    /** Evita solicitudes duplicadas e indica que la conexión sigue en curso. */
    private void setBusy(boolean busy) {
        loginButton.setDisable(busy);
        progress.setVisible(busy);
    }
}
