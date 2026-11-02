package com.its5a.model;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Abre el túnel SSH y se conecta a la MariaDB remota a través de él. */
public class RemoteService {
    // Conexiones SSH y JDBC que se conservan hasta cerrar sesión o la ventana.
    private Session session;
    private Connection conn;

    /** Lee la configuración privada, abre el túnel SSH y conecta con MariaDB mediante JDBC. */
    public void connect(String host, String sshUser, String sshPass) throws Exception {
        Properties cfg = new Properties();
        InputStream config = getClass().getResourceAsStream("/db.properties");
        if (config == null) {
            throw new IOException("No se encontró db.properties. Cópialo desde db.properties.example y configura MariaDB.");
        }
        try (InputStream in = config) {
            cfg.load(in);
        }
        session = new JSch().getSession(sshUser, host, 22);
        session.setPassword(sshPass);
        session.setConfig("StrictHostKeyChecking", "no"); // uso académico
        session.connect(10_000);
        int localPort = session.setPortForwardingL(0, "127.0.0.1", Integer.parseInt(cfg.getProperty("db.remotePort")));

        String url = "jdbc:mariadb://127.0.0.1:" + localPort + "/" + cfg.getProperty("db.name");
        conn = DriverManager.getConnection(url, cfg.getProperty("db.user"), cfg.getProperty("db.password"));
    }

    /** Devuelve los nombres de las tablas del esquema MariaDB configurado. */
    public List<String> listTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW TABLES")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        return tables;
    }

    /** Lee una tabla remota y convierte sus columnas y valores a tipos compatibles con SQLite. */
    public TableData fetchTable(String name) throws SQLException {
        if (!listTables().contains(name)) throw new SQLException("La tabla no existe: " + name);
        List<String> cols = new ArrayList<>(), types = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM `" + name.replace("`", "``") + "`")) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            for (int i = 1; i <= n; i++) {
                cols.add(md.getColumnLabel(i));
                types.add(affinity(md.getColumnType(i)));
            }
            while (rs.next()) {
                List<Object> row = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) row.add(normalize(rs.getObject(i)));
                rows.add(row);
            }
        }
        return new TableData(cols, types, rows, null);
    }

    /** Traduce los tipos numéricos/binarios de JDBC a afinidades de almacenamiento SQLite. */
    private static String affinity(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.BIT, Types.BOOLEAN -> "INTEGER";
            case Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> "REAL";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BLOB";
            default -> "TEXT";
        };
    }

    /** Convierte los valores JDBC a tipos aceptados por el controlador SQLite. */
    private static Object normalize(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b ? 1L : 0L;
        if (o instanceof Byte || o instanceof Short || o instanceof Integer || o instanceof Long)
            return ((Number) o).longValue();
        if (o instanceof Float || o instanceof Double || o instanceof BigDecimal)
            return ((Number) o).doubleValue();
        if (o instanceof byte[]) return o;
        return o.toString();
    }

    /** Cierra la conexión JDBC y desconecta el túnel SSH si se llegaron a abrir. */
    public void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) { }
        if (session != null) session.disconnect();
    }
}
