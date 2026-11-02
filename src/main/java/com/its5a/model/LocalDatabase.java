package com.its5a.model;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Acceso a la base SQLite local: guardado de tablas descargadas y CRUD. */
public class LocalDatabase {
    // URL JDBC para acceder al archivo SQLite local.
    private final String url;

    /** Crea un acceso a la base de datos para la ruta local recibida. */
    public LocalDatabase(Path file) {
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
    }

    /** Abre una conexión SQLite temporal; el método que la llama debe cerrarla. */
    private Connection open() throws SQLException {
        return DriverManager.getConnection(url);
    }

    /** Escapa un identificador SQL para usar nombres de tabla o columna de forma segura. */
    private static String q(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /** Convierte los campos vacíos del formulario en valores SQL NULL. */
    private static Object blankToNull(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static void validateIdentifier(String identifier, String description) throws SQLException {
        if (identifier == null || identifier.isBlank()) {
            throw new SQLException("El nombre de " + description + " no puede estar vacío.");
        }
    }

    private static void validateColumns(List<String> columns, List<String> types) throws SQLException {
        if (columns == null || types == null || columns.isEmpty() || columns.size() != types.size()) {
            throw new SQLException("La tabla debe tener al menos una columna con un tipo válido.");
        }
        Set<String> normalizedNames = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            validateIdentifier(column, "columna");
            if (!normalizedNames.add(column.toLowerCase(Locale.ROOT))) {
                throw new SQLException("Hay nombres de columna duplicados: " + column);
            }
            validateType(types.get(i));
        }
    }

    private static String validateType(String type) throws SQLException {
        if (type == null) throw new SQLException("Selecciona un tipo de dato válido.");
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!List.of("INTEGER", "REAL", "TEXT", "BLOB").contains(normalized)) {
            throw new SQLException("Tipo de dato no permitido: " + type);
        }
        return normalized;
    }

    /** Reemplaza o crea la tabla local; la definición y los registros se confirman juntos. */
    public void saveTable(String name, TableData data) throws SQLException {
        validateIdentifier(name, "tabla");
        validateColumns(data.columns(), data.types());
        for (List<Object> row : data.rows()) {
            if (row.size() != data.columns().size()) {
                throw new SQLException("Una fila descargada no coincide con la cantidad de columnas.");
            }
        }
        List<String> defs = new ArrayList<>();
        for (int i = 0; i < data.columns().size(); i++)
            defs.add(q(data.columns().get(i)) + " " + validateType(data.types().get(i)));
        String cols = String.join(",", data.columns().stream().map(LocalDatabase::q).toList());
        String marks = String.join(",", data.columns().stream().map(c -> "?").toList());

        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS " + q(name));
                    st.execute("CREATE TABLE " + q(name) + " (" + String.join(",", defs) + ")");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + q(name) + " (" + cols + ") VALUES (" + marks + ")")) {
                    for (List<Object> row : data.rows()) {
                        for (int i = 0; i < row.size(); i++) ps.setObject(i + 1, row.get(i));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException ex) {
                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    ex.addSuppressed(rollbackError);
                }
                throw ex;
            }
        }
    }

    /** Crea una tabla local vacía con las columnas y afinidades indicadas. */
    public void createTable(String name, List<String> columns, List<String> types) throws SQLException {
        validateIdentifier(name, "tabla");
        validateColumns(columns, types);
        List<String> definitions = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            definitions.add(q(columns.get(i)) + " " + validateType(types.get(i)));
        }
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + q(name) + " (" + String.join(",", definitions) + ")");
        }
    }

    /** Lista las tablas de usuario y excluye las tablas internas de SQLite. */
    public List<String> listTables() throws SQLException {
        List<String> t = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) t.add(rs.getString(1));
        }
        return t;
    }

    /** Elimina una tabla SQLite local junto con todas sus filas. */
    public void dropTable(String table) throws SQLException {
        validateIdentifier(table, "tabla");
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE " + q(table));
        }
    }

    /** Cambia el nombre de una tabla local sin modificar sus registros. */
    public void renameTable(String table, String newName) throws SQLException {
        validateIdentifier(table, "tabla");
        validateIdentifier(newName, "tabla");
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + q(table) + " RENAME TO " + q(newName));
        }
    }

    /** Agrega una columna nullable a una tabla local existente. */
    public void addColumn(String table, String column, String type) throws SQLException {
        validateIdentifier(table, "tabla");
        validateIdentifier(column, "columna");
        String safeType = validateType(type);
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + q(table) + " ADD COLUMN " + q(column) + " " + safeType);
        }
    }

    /** Renombra una columna, conservando sus valores. */
    public void renameColumn(String table, String column, String newName) throws SQLException {
        validateIdentifier(table, "tabla");
        validateIdentifier(column, "columna");
        validateIdentifier(newName, "columna");
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + q(table) + " RENAME COLUMN " + q(column) + " TO " + q(newName));
        }
    }

    /** Elimina una columna y sus valores; SQLite requiere conservar al menos una columna. */
    public void dropColumn(String table, String column) throws SQLException {
        validateIdentifier(table, "tabla");
        validateIdentifier(column, "columna");
        TableData data = read(table);
        if (data.columns().size() <= 1) {
            throw new SQLException("No se puede eliminar la última columna de una tabla.");
        }
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + q(table) + " DROP COLUMN " + q(column));
        }
    }

    // ---- READ ----
    /** Lee las filas y conserva su rowid para las operaciones posteriores de edición/eliminación. */
    public TableData read(String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT rowid AS _rid_, * FROM " + q(table))) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            for (int i = 2; i <= n; i++) cols.add(md.getColumnName(i));
            while (rs.next()) {
                ids.add(rs.getLong(1));
                List<Object> row = new ArrayList<>();
                for (int i = 2; i <= n; i++) row.add(rs.getObject(i));
                rows.add(row);
            }
        }
        return new TableData(cols, null, rows, ids);
    }

    // ---- CREATE ----
    /** Inserta una fila capturada en el formulario en la tabla local indicada. */
    public void insert(String table, List<String> cols, List<String> values) throws SQLException {
        String names = String.join(",", cols.stream().map(LocalDatabase::q).toList());
        String marks = String.join(",", cols.stream().map(c -> "?").toList());
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO " + q(table) + " (" + names + ") VALUES (" + marks + ")")) {
            for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, blankToNull(values.get(i)));
            ps.executeUpdate();
        }
    }

    // ---- UPDATE ----
    /** Actualiza las columnas de la fila identificada por su rowid de SQLite. */
    public void update(String table, long rowId, List<String> cols, List<String> values) throws SQLException {
        String set = String.join(",", cols.stream().map(c -> q(c) + "=?").toList());
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("UPDATE " + q(table) + " SET " + set + " WHERE rowid=?")) {
            for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, blankToNull(values.get(i)));
            ps.setLong(values.size() + 1, rowId);
            ps.executeUpdate();
        }
    }

    // ---- DELETE ----
    /** Elimina la fila identificada por su rowid de SQLite. */
    public void delete(String table, long rowId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + q(table) + " WHERE rowid=?")) {
            ps.setLong(1, rowId);
            ps.executeUpdate();
        }
    }
}
