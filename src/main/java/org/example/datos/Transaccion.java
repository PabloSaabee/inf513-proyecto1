package org.example.datos;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Ejecuta varias sentencias como una sola: o se guardan todas o ninguna.
 * Uso:  Transaccion.ejecutar(cn -> { dProyecto.cambiarEtapa(cn, ...); dHistorial.registrar(cn, ...); });
 */
public final class Transaccion {

    @FunctionalInterface
    public interface Trabajo {
        void ejecutar(Connection cn) throws SQLException;
    }

    private Transaccion() { }

    public static void ejecutar(Trabajo trabajo) throws SQLException {
        try (Connection cn = ConexionBD.getConexion()) {
            cn.setAutoCommit(false);
            try {
                trabajo.ejecutar(cn);
                cn.commit();
            } catch (SQLException | RuntimeException e) {
                cn.rollback();
                throw e;
            }
        }
    }
}