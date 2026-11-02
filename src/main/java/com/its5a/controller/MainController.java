package com.its5a.controller;

import com.its5a.model.LocalDatabase;
import com.its5a.model.RemoteService;
import com.its5a.model.TableData;
import com.its5a.view.RecordDialog;
import com.its5a.view.TableSchemaDialog;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Coordina el explorador: descarga de tablas remotas, CRUD local en SQLite,
 * selección, mensajes de estado y ciclo de vida de la conexión.
 */
public class MainController {
    // Controles FXML para navegar tablas remotas/locales y operar sobre sus filas.
    @FXML private ListView<String> remoteList;
    @FXML private ComboBox<String> localCombo;
    @FXML private TableView<List<Object>> table;
    @FXML private Label status;
    @FXML private Label connectionBadge;
    @FXML private Label remoteCountLabel;
    @FXML private Label tableInfoBadge;
    @FXML private Region statusDot;
    @FXML private Button downloadButton;
    @FXML private Button createTableButton;
    @FXML private Button editStructureButton;
    @FXML private Button deleteTableButton;
    @FXML private Button insertButton;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private Button refreshButton;

    // Servicios y tabla local seleccionada que utilizan las acciones de la vista.
    private RemoteService remote;
    private Stage stage;
    private final LocalDatabase local = new LocalDatabase(Path.of("local.db"));
    private TableData current;
    private String currentName;

    /** Enlaza la vista con los servicios y carga las listas de tablas remotas y locales. */
    public void init(RemoteService remote, Stage stage) {
        this.remote = remote;
        this.stage = stage;
        stage.setOnCloseRequest(e -> remote.close());

        localCombo.setOnAction(e -> {
            String selected = localCombo.getValue();
            setTableActionsEnabled(selected != null);
            if (selected != null) loadLocal(selected);
        });

        connectionBadge.setText("Túnel SSH Activo");
        status.setText("Túnel SSH y conexión a MariaDB (its5a) establecidos.");

        async(remote::listTables, tables -> {
            remoteList.setItems(FXCollections.observableArrayList(tables));
            if (remoteCountLabel != null) {
                remoteCountLabel.setText(String.valueOf(tables.size()));
            }
            status.setText("Túnel SSH activo. " + tables.size() + " tablas encontradas en MariaDB.");
        });

        refreshLocalTables(null);
    }

    /** Crea una tabla vacía en SQLite a partir de la definición capturada en el diálogo. */
    @FXML
    private void onCreateTable() {
        TableSchemaDialog.show().ifPresent(schema -> {
            try {
                local.createTable(schema.name(), schema.columns(), schema.types());
                status.setText("Tabla local '" + schema.name() + "' creada.");
                refreshLocalTables(schema.name());
            } catch (Exception ex) {
                showError(ex);
            }
        });
    }

    /** Permite renombrar la tabla seleccionada o modificar sus columnas locales. */
    @FXML
    private void onEditStructure() {
        if (currentName == null || current == null) {
            status.setText("Selecciona una tabla local para modificar su estructura.");
            return;
        }

        ButtonType renameTable = new ButtonType("Renombrar tabla");
        ButtonType addColumn = new ButtonType("Agregar columna");
        ButtonType renameColumn = new ButtonType("Renombrar columna");
        ButtonType dropColumn = new ButtonType("Eliminar columna");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modificar estructura");
        dialog.setHeaderText("Cambios solo en SQLite local: " + currentName);
        dialog.getDialogPane().getButtonTypes().addAll(
                renameTable, addColumn, renameColumn, dropColumn, ButtonType.CANCEL
        );
        applyDialogTheme(dialog.getDialogPane());

        dialog.showAndWait().ifPresent(action -> {
            if (action == renameTable) renameLocalTable();
            else if (action == addColumn) addLocalColumn();
            else if (action == renameColumn) renameLocalColumn();
            else if (action == dropColumn) dropLocalColumn();
        });
    }

    /** Solicita el nuevo nombre de la tabla y conserva sus filas. */
    private void renameLocalTable() {
        promptText("Renombrar tabla", "Nuevo nombre para " + currentName, currentName).ifPresent(newName -> {
            try {
                local.renameTable(currentName, newName.trim());
                status.setText("Tabla renombrada a '" + newName.trim() + "'.");
                refreshLocalTables(newName.trim());
            } catch (Exception ex) {
                showError(ex);
            }
        });
    }

    /** Captura el nombre/tipo y agrega una columna nullable a la tabla activa. */
    private void addLocalColumn() {
        promptText("Agregar columna", "Nombre de la nueva columna", "").ifPresent(column -> {
            ChoiceDialog<String> typeDialog = new ChoiceDialog<>("TEXT", List.of("TEXT", "INTEGER", "REAL", "BLOB"));
            typeDialog.setTitle("Tipo de columna");
            typeDialog.setHeaderText("Selecciona el tipo de la columna '" + column.trim() + "'.");
            applyDialogTheme(typeDialog.getDialogPane());
            typeDialog.showAndWait().ifPresent(type -> runSchemaChange(
                    () -> local.addColumn(currentName, column.trim(), type),
                    "Columna '" + column.trim() + "' agregada."
            ));
        });
    }

    /** Permite elegir y renombrar una columna de la tabla local activa. */
    private void renameLocalColumn() {
        chooseColumn("Renombrar columna").ifPresent(column ->
                promptText("Renombrar columna", "Nuevo nombre para " + column, column).ifPresent(newName ->
                        runSchemaChange(
                                () -> local.renameColumn(currentName, column, newName.trim()),
                                "Columna renombrada a '" + newName.trim() + "'."
                        )));
    }

    /** Elimina una columna después de advertir que sus valores se perderán. */
    private void dropLocalColumn() {
        chooseColumn("Eliminar columna").ifPresent(column -> {
            Alert confirmation = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "Se eliminará la columna '" + column + "' y todos sus valores de la tabla local '" + currentName + "'.",
                    ButtonType.YES,
                    ButtonType.NO
            );
            confirmation.setTitle("Eliminar columna");
            confirmation.setHeaderText("Esta acción no se puede deshacer.");
            applyAlertTheme(confirmation);
            if (confirmation.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
            runSchemaChange(
                    () -> local.dropColumn(currentName, column),
                    "Columna '" + column + "' eliminada."
            );
        });
    }

    /** Muestra un selector con las columnas actuales de la tabla local. */
    private java.util.Optional<String> chooseColumn(String title) {
        if (current == null || current.columns().isEmpty()) {
            status.setText("La tabla seleccionada no tiene columnas para modificar.");
            return java.util.Optional.empty();
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(current.columns().get(0), current.columns());
        dialog.setTitle(title);
        dialog.setHeaderText("Selecciona una columna de " + currentName + ".");
        applyDialogTheme(dialog.getDialogPane());
        return dialog.showAndWait();
    }

    /** Solicita texto al usuario y descarta entradas vacías o canceladas. */
    private java.util.Optional<String> promptText(String title, String prompt, String initialValue) {
        TextInputDialog dialog = new TextInputDialog(initialValue);
        dialog.setTitle(title);
        dialog.setHeaderText(prompt);
        applyDialogTheme(dialog.getDialogPane());
        return dialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty());
    }

    /** Ejecuta una modificación de esquema y vuelve a cargar la tabla activa. */
    private void runSchemaChange(SqlOperation operation, String successMessage) {
        try {
            operation.run();
            status.setText(successMessage);
            refreshLocalTables(currentName);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Habilita acciones estructurales solo si existe una tabla local seleccionada. */
    private void setTableActionsEnabled(boolean enabled) {
        editStructureButton.setDisable(!enabled);
        deleteTableButton.setDisable(!enabled);
    }

    // ----- Descarga MariaDB → SQLite -----
    /** Descarga la tabla remota seleccionada y reemplaza su copia local en SQLite. */
    @FXML
    private void onDownload() {
        String name = remoteList.getSelectionModel().getSelectedItem();
        if (name == null) {
            status.setText("Por favor selecciona una tabla remota primero.");
            return;
        }
        status.setText("Descargando " + name + " a SQLite local…");
        if (downloadButton != null) downloadButton.setDisable(true);

        async(() -> {
            TableData data = remote.fetchTable(name);
            local.saveTable(name, data);
            return data.rows().size();
        }, n -> {
            if (downloadButton != null) downloadButton.setDisable(false);
            status.setText("Tabla '" + name + "' sincronizada en SQLite local (" + n + " registros).");
            refreshLocalTables(name);
        });
    }

    // ----- CRUD -----
    /** Abre el formulario e inserta los valores enviados en la tabla local seleccionada. */
    @FXML
    private void onInsert() {
        if (current == null) {
            status.setText("Primero selecciona una tabla local.");
            return;
        }
        RecordDialog.show("Insertar registro en " + currentName, current.columns(), null).ifPresent(vals -> {
            try {
                local.insert(currentName, current.columns(), vals);
                loadLocal(currentName);
                status.setText("Registro insertado exitosamente en " + currentName + ".");
            } catch (Exception ex) {
                showError(ex);
            }
        });
    }

    /** Abre el formulario con la fila seleccionada y guarda localmente los cambios. */
    @FXML
    private void onEdit() {
        int i = table.getSelectionModel().getSelectedIndex();
        if (current == null || i < 0) {
            status.setText("Selecciona un registro de la tabla para editar.");
            return;
        }
        List<String> initial = new ArrayList<>();
        for (Object o : current.rows().get(i)) initial.add(o == null ? "" : o.toString());
        long rowId = current.rowIds().get(i);
        RecordDialog.show("Editar registro #" + rowId + " en " + currentName, current.columns(), initial).ifPresent(vals -> {
            try {
                local.update(currentName, rowId, current.columns(), vals);
                loadLocal(currentName);
                status.setText("Registro actualizado exitosamente.");
            } catch (Exception ex) {
                showError(ex);
            }
        });
    }

    /** Elimina la fila local seleccionada después de pedir confirmación. */
    @FXML
    private void onDelete() {
        int i = table.getSelectionModel().getSelectedIndex();
        if (current == null || i < 0) {
            status.setText("Selecciona un registro de la tabla para eliminar.");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "¿Eliminar el registro seleccionado de " + currentName + "?", ButtonType.YES, ButtonType.NO);
        applyAlertTheme(a);
        if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        try {
            local.delete(currentName, current.rowIds().get(i));
            loadLocal(currentName);
            status.setText("Registro eliminado de la base local.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Elimina la tabla SQLite seleccionada tras confirmar; la tabla remota no cambia. */
    @FXML
    private void onDeleteTable() {
        String name = localCombo.getValue();
        if (name == null) {
            status.setText("Selecciona una tabla local para eliminar.");
            return;
        }

        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Se eliminará la tabla '" + name + "' y todos sus datos de local.db. La tabla remota no se modificará.",
                ButtonType.YES,
                ButtonType.NO
        );
        alert.setTitle("Eliminar tabla local");
        alert.setHeaderText("¿Eliminar la tabla seleccionada?");
        applyAlertTheme(alert);
        if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;

        try {
            local.dropTable(name);
            status.setText("Tabla '" + name + "' eliminada de SQLite local.");
            refreshLocalTables(null);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Recarga la tabla local activa o actualiza la lista si no hay una tabla seleccionada. */
    @FXML
    private void onRefresh() {
        if (currentName != null) {
            loadLocal(currentName);
            status.setText("Tabla '" + currentName + "' recargada.");
        } else {
            refreshLocalTables(null);
        }
    }

    /** Muestra el diálogo con información del equipo y las tecnologías. */
    @FXML
    private void onShowTeam() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Equipo y Proyecto Mini DBeaver");
        alert.setHeaderText("Arquitectura MVC • MariaDB SSH ➔ SQLite");
        alert.setContentText(
                "Materia: Programación Avanzada / Bases de Datos\n\n" +
                "Integrantes del Equipo:\n" +
                "• Alberto Benjamin Poot Veloz \n" +
                "• Royfran R Santini Pacheco\n" +
                "• Bryan A Figueroa Quintal\n" +
                "Tecnologías: JavaFX 21, JDBC MariaDB, SQLite3, JSch SSH Tunneling.\n" +
                "Capturas guardadas en la carpeta Screenshots/"
        );
        applyAlertTheme(alert);
        alert.showAndWait();
    }

    /** Cierra la conexión remota y devuelve la aplicación a la pantalla de inicio. */
    @FXML
    private void onLogout() {
        if (remote != null) remote.close();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/its5a/view/login.fxml"));
            Parent root = loader.load();
            Scene scene = new Scene(root, 440, 580);
            scene.getStylesheets().add(getClass().getResource("/com/its5a/view/style.css").toExternalForm());
            stage.setScene(scene);
            stage.setTitle("Mini DBeaver – Inicio de Sesión SSH");
            stage.setResizable(false);
            stage.centerOnScreen();
        } catch (Exception ex) {
            showError(ex);
        }
    }

    // ----- Utilidades -----
    /** Lee las tablas locales y selecciona la solicitada o la primera disponible. */
    private void refreshLocalTables(String select) {
        try {
            localCombo.setOnAction(null);
            List<String> tables = local.listTables();
            localCombo.setItems(FXCollections.observableArrayList(tables));
            localCombo.setOnAction(e -> {
                String selected = localCombo.getValue();
                setTableActionsEnabled(selected != null);
                if (selected != null) loadLocal(selected);
            });

            setTableActionsEnabled(!tables.isEmpty());
            if (select != null) {
                localCombo.setValue(select);
                loadLocal(select);
            } else if (!tables.isEmpty()) {
                localCombo.setValue(tables.get(0));
                loadLocal(tables.get(0));
            } else {
                localCombo.setValue(null);
                current = null;
                currentName = null;
                table.getColumns().clear();
                table.setItems(FXCollections.observableArrayList());
                updateTableInfo(0, 0);
            }
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Lee una tabla local y reconstruye las columnas y filas visibles. */
    private void loadLocal(String name) {
        try {
            current = local.read(name);
            currentName = name;
            table.getColumns().clear();
            for (int i = 0; i < current.columns().size(); i++) {
                final int idx = i;
                TableColumn<List<Object>, String> col = new TableColumn<>(current.columns().get(i));
                col.setCellValueFactory(cd -> {
                    Object v = cd.getValue().get(idx);
                    return new SimpleStringProperty(v == null ? "NULL" : (v instanceof byte[] ? "[BLOB]" : v.toString()));
                });
                table.getColumns().add(col);
            }
            table.setItems(FXCollections.observableArrayList(current.rows()));
            updateTableInfo(current.rows().size(), current.columns().size());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Actualiza el resumen con el nombre de la tabla y sus filas y columnas. */
    private void updateTableInfo(int rows, int cols) {
        if (tableInfoBadge != null) {
            if (currentName == null) {
                tableInfoBadge.setText("Sin tabla activa");
            } else {
                tableInfoBadge.setText(currentName + " • " + rows + " registros • " + cols + " columnas");
            }
        }
    }

    /** Ejecuta una tarea de base de datos en segundo plano y procesa su resultado en JavaFX. */
    private <T> void async(Callable<T> job, Consumer<T> onOk) {
        Task<T> t = new Task<>() {
            @Override
            protected T call() throws Exception {
                return job.call();
            }
        };
        t.setOnSucceeded(e -> onOk.accept(t.getValue()));
        t.setOnFailed(e -> showError(t.getException()));
        Thread th = new Thread(t);
        th.setDaemon(true);
        th.start();
    }

    /** Aplica los estilos de la aplicación a una alerta JavaFX cuando están disponibles. */
    private void applyAlertTheme(Alert alert) {
        applyDialogTheme(alert.getDialogPane());
    }

    private void applyDialogTheme(DialogPane pane) {
        pane.getStylesheets().add(getClass().getResource("/com/its5a/view/style.css").toExternalForm());
    }

    /** Muestra el error de base de datos o aplicación en la barra y en una alerta. */
    private void showError(Throwable ex) {
        status.setText("Error: " + ex.getMessage());
        Alert a = new Alert(Alert.AlertType.ERROR, String.valueOf(ex.getMessage()));
        applyAlertTheme(a);
        a.showAndWait();
    }

    /** Representa una operación SQL que puede fallar y debe notificarse en la interfaz. */
    @FunctionalInterface
    private interface SqlOperation {
        void run() throws Exception;
    }
}
