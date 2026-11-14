package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Pista de auditoría (tabla bitacora).
 * En la versión e-mail: ip = "email" y user_agent = asunto del correo.
 */
public class DBitacora {

    public void registrar(Long usuarioId, String accion, String tabla, Long registroId, String asunto) {
        String sql = """
                INSERT INTO bitacora (usuario_id, accion, tabla, registro_id, ip, user_agent)
                VALUES (?, ?, ?, ?, 'email', ?)
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            if (usuarioId == null) ps.setNull(1, Types.BIGINT); else ps.setLong(1, usuarioId);
            ps.setString(2, recortar(accion, 40));
            ps.setString(3, tabla);
            if (registroId == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, registroId);
            ps.setString(5, recortar(asunto, 255));
            ps.executeUpdate();
        } catch (SQLException e) {
            // La bitácora nunca debe impedir que el comando responda
            System.err.println("[Bitácora] No se pudo registrar: " + e.getMessage());
        }
    }

    private static String recortar(String t, int max) {
        return t == null || t.length() <= max ? t : t.substring(0, max);
    }
}