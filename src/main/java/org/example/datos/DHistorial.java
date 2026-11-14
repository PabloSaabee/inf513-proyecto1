package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tabla historial_etapas: la línea de tiempo de cada proyecto (TrazabilidadService del documento).
 */
public class DHistorial {

    /** estado: iniciada, completada, observada o rechazada. */
    public void registrar(Connection cn, long proyectoId, String etapa, String estado, String comentario,
                          long usuarioId) throws SQLException {
        String sql = """
                INSERT INTO historial_etapas (proyecto_id, etapa, estado, comentario, usuario_id)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            ps.setString(2, etapa);
            ps.setString(3, estado);
            ps.setString(4, comentario == null || comentario.length() <= 255 ? comentario : comentario.substring(0, 255));
            ps.setLong(5, usuarioId);
            ps.executeUpdate();
        }
    }

    public void registrar(long proyectoId, String etapa, String estado, String comentario, long usuarioId)
            throws SQLException {
        try (Connection cn = ConexionBD.getConexion()) {
            registrar(cn, proyectoId, etapa, estado, comentario, usuarioId);
        }
    }

    /** Línea de tiempo completa en orden cronológico: fecha, etapa, estado, comentario, usuario. */
    public List<String[]> todos(long proyectoId) throws SQLException {
        List<String[]> l = ultimos(proyectoId, 1000);
        java.util.Collections.reverse(l);
        return l;
    }

    /** Últimos n movimientos: fecha, etapa, estado, comentario, usuario. */
    public List<String[]> ultimos(long proyectoId, int n) throws SQLException {
        String sql = """
                SELECT TO_CHAR(h.created_at, 'DD/MM/YYYY HH24:MI'), h.etapa, h.estado,
                       COALESCE(h.comentario, '-'), u.nombres || ' ' || u.apellidos
                  FROM historial_etapas h JOIN usuarios u ON u.id = h.usuario_id
                 WHERE h.proyecto_id = ?
                 ORDER BY h.created_at DESC, h.id DESC
                 LIMIT ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            ps.setInt(2, n);
            List<String[]> filas = new ArrayList<>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
                }
            }
            return filas;
        }
    }
}