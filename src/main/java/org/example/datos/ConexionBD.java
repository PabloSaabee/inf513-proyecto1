package org.example.datos;

import org.example.config.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Entrega conexiones JDBC a PostgreSQL.
 *
 * Se abre una conexión nueva en cada operación y se cierra con try-with-resources:
 *     try (Connection cn = ConexionBD.getConexion()) { ... }
 * Así, si el servidor corta una conexión inactiva, el bucle de correo no se cae.
 */
public final class ConexionBD {

    private ConexionBD() { }

    public static Connection getConexion() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", Config.get("db.usuario"));
        p.setProperty("password", Config.get("db.password"));
        p.setProperty("connectTimeout", "10");         // segundos
        p.setProperty("ApplicationName", "SeguimientoTituladosEmail");
        return DriverManager.getConnection(Config.get("db.url"), p);
    }

    /** Prueba rápida: true si se puede conectar. */
    public static boolean probar() {
        try (Connection cn = getConexion()) {
            return cn.isValid(5);
        } catch (SQLException e) {
            System.err.println("[BD] No se pudo conectar: " + e.getMessage());
            org.example.util.Registro.error("ConexionBD · inicio", e);
            return false;
        }
    }
}