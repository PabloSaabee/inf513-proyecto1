package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tabla notificaciones (CU7-S1). En la versión e-mail, url guarda el comando sugerido
 * para ver el detalle, por ejemplo VERSOL["4"].
 */
public class DNotificacion {

    public record Destinatario(long id, String email) { }

    public long insertar(long usuarioId, String titulo, String mensaje, String comando) throws SQLException {
        String sql = """
                INSERT INTO notificaciones (usuario_id, titulo, mensaje, url, enviada_email)
                VALUES (?, ?, ?, ?, FALSE) RETURNING id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, usuarioId);
            ps.setString(2, recortar(titulo, 150));
            ps.setString(3, recortar(mensaje, 500));
            ps.setString(4, recortar(comando, 255));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void marcarEnviada(long notificacionId) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("UPDATE notificaciones SET enviada_email = TRUE WHERE id = ?")) {
            ps.setLong(1, notificacionId);
            ps.executeUpdate();
        }
    }

    public Destinatario destinatario(long usuarioId) throws SQLException {
        String sql = "SELECT id, email FROM usuarios WHERE id = ? AND estado = 'activo'";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Destinatario(rs.getLong(1), rs.getString(2)) : null;
            }
        }
    }

    /** Usuarios activos que tienen alguno de los roles. */
    public List<Destinatario> conRoles(String... roles) throws SQLException {
        String sql = """
                SELECT DISTINCT u.id, u.email FROM usuarios u
                  JOIN usuario_rol ur ON ur.usuario_id = u.id
                  JOIN roles r ON r.id = ur.rol_id
                 WHERE u.estado = 'activo' AND r.nombre = ANY (?)
                 ORDER BY u.id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setArray(1, cn.createArrayOf("varchar", roles));
            List<Destinatario> lista = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(new Destinatario(rs.getLong(1), rs.getString(2)));
            }
            return lista;
        }
    }

    private static String recortar(String t, int max) {
        return t == null || t.length() <= max ? t : t.substring(0, max);
    }
}