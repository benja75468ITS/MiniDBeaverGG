package com.its5a.view;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Construye el formulario JavaFX para insertar una fila o editar una fila local. */
public final class RecordDialog {
    /** Clase de utilidades: no requiere instancias. */
    private RecordDialog() { }

    /**
     * Muestra un campo por cada columna de la tabla.
     *
     * @param title título del diálogo que describe la operación y la tabla
     * @param columns nombres de columna usados como etiquetas
     * @param initial valores de la fila actual, o {@code null} al insertar
     * @return valores capturados al guardar o resultado vacío al cancelar
     */
    public static Optional<List<String>> show(String title, List<String> columns, List<String> initial) {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText("Completa o modifica los valores de cada columna:");

        try {
            dialog.getDialogPane().getStylesheets().add(RecordDialog.class.getResource("/com/its5a/view/style.css").toExternalForm());
        } catch (Exception ignored) {}

        // El formulario queda desplazable para admitir tablas con muchas columnas.
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.setStyle("-fx-background-color: #FFFFFF;");

        // Conserva el orden de columnas para construir la fila que recibe el controlador.
        List<TextField> fields = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Label lbl = new Label(columns.get(i));
            lbl.setStyle("-fx-text-fill: #5A5A58; -fx-font-weight: 600; -fx-font-size: 12px;");
            grid.add(lbl, 0, i);

            TextField tf = new TextField(initial == null ? "" : initial.get(i));
            tf.setPromptText("Valor para " + columns.get(i));
            tf.setPrefWidth(260);
            grid.add(tf, 1, i);
            fields.add(tf);
        }

        ScrollPane sp = new ScrollPane(grid);
        sp.setStyle("-fx-background-color: transparent; -fx-background: #FFFFFF;");
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(Math.min(420, columns.size() * 44 + 40));

        dialog.getDialogPane().setContent(sp);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okBtn != null) {
            okBtn.setText("Guardar");
            okBtn.getStyleClass().add("btn-primary");
        }
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) {
            cancelBtn.setText("Cancelar");
            cancelBtn.getStyleClass().add("btn-secondary");
        }

        dialog.setResultConverter(bt -> bt == ButtonType.OK
                ? fields.stream().map(TextField::getText).toList() : null);
        return dialog.showAndWait();
    }
}
