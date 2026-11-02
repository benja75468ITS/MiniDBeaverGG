package com.its5a.model;

import java.util.List;

/**
 * Representación en memoria de una tabla que comparten los modelos remoto y local.
 *
 * @param columns nombres de columna en el orden de visualización/almacenamiento
 * @param types afinidades SQLite de datos descargados; pueden faltar al leer la base local
 * @param rows valores de la tabla, con una lista por registro
 * @param rowIds identificadores SQLite para actualizar/eliminar; no están presentes en datos remotos
 */
public record TableData(List<String> columns, List<String> types,
                        List<List<Object>> rows, List<Long> rowIds) {
}
