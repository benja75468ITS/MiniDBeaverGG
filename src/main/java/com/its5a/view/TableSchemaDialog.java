package com.its5a.view;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Diálogo para definir una tabla SQLite local nueva y sus columnas iniciales. */
public final class TableSchemaDialog {
    /** Afinidades SQLite que el formulario permite seleccionar para cada columna. */
    private static final List<String> TYPES = List.of("TEXT", "INTEGER", "REAL", "BLOB");

    /** Clase de utilidades: no requiere instancias. */
    private TableSchemaDialog() { }

    /** Recopila el nombre de la tabla y los nombres/tipos de una o más columnas. */
    public static Optional<NewTableSchema> show() {
        Dialog<NewTableSchema> dialog = new Dialog<>();
        dialog.setTitle("Crear tabla local");
        dialog.setHeaderText("Define una tabla nueva en SQLite. No se creará en MariaDB.");

        TextField tableName = new TextField();
        tableName.setPromptText("Nombre de la tabla");

        VBox columnRows = new VBox(8);
        columnRows.setPadding(new Insets(8, 0, 8, 0));
        List<ColumnInput> columns = new ArrayList<>();
        columns.add(new ColumnInput("id"));

        Button addColumn = new Button("Agregar columna");
        addColumn.setOnAction(event -> {
            columns.add(new ColumnInput("columna" + (columns.size() + 1)));
            renderRows(columnRows, columns);
        });

        renderRows(columnRows, columns);
        ScrollPane columnsScroll = new ScrollPane(columnRows);
        columnsScroll.setFitToWidth(true);
        columnsScroll.setPrefViewportHeight(220);
        columnsScroll.setMinViewportHeight(120);
        columnsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox content = new VBox(10, new Label("Nombre de la tabla"), tableName,
                new Label("Columnas"), addColumn, columnsScroll);
        content.setPadding(new Insets(16));
        VBox.setVgrow(columnsScroll, javafx.scene.layout.Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(500);
        dialog.getDialogPane().setPrefHeight(390);
        dialog.getDialogPane().setMaxHeight(520);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Crear");
        Button cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelButton.setText("Cancelar");

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            return new NewTableSchema(
                    tableName.getText().trim(),
                    columns.stream().map(input -> input.name.getText().trim()).toList(),
                    columns.stream().map(input -> input.type.getValue()).toList()
            );
        });
        return dialog.showAndWait();
    }

    /** Dibuja las filas de edición de columnas y sus controles para quitarlas. */
    private static void renderRows(VBox container, List<ColumnInput> columns) {
        container.getChildren().clear();
        for (int i = 0; i < columns.size(); i++) {
            ColumnInput input = columns.get(i);
            input.name.setPromptText("Nombre de columna");
            input.name.setPrefWidth(200);
            input.type.setItems(javafx.collections.FXCollections.observableArrayList(TYPES));
            input.type.setValue(input.type.getValue() == null ? "TEXT" : input.type.getValue());
            input.type.setPrefWidth(120);
            Button remove = new Button("Quitar");
            remove.setDisable(columns.size() == 1);
            remove.setOnAction(event -> {
                columns.remove(input);
                renderRows(container, columns);
            });
            container.getChildren().add(new HBox(8, input.name, input.type, remove));
        }
    }

    /** Definición inmutable de tabla que el diálogo devuelve al controlador. */
    public record NewTableSchema(String name, List<String> columns, List<String> types) { }

    /** Controles editables asociados con una columna del formulario. */
    private static final class ColumnInput {
        private final TextField name = new TextField();
        private final ComboBox<String> type = new ComboBox<>();

        private ColumnInput(String defaultName) {
            name.setText(defaultName);
        }
    }
}
