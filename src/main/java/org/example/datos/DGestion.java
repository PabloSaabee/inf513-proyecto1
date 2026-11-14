package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Acceso a gestiones (periodos académicos: 1-2026, 2-2026...).
 */
public class DGestion {

    public record Gestion(int id, String codigo, String fechaLimitePerfil, int plazoRevisionDias) { }

    /** true si la gestión tiene fecha límite de perfil y ya pasó (CU3-E3). */
    public boolean perfilVencido(int gestionId) throws SQLException {
        String sql = "SELECT fecha_limite_perfil IS NOT NULL AND fecha_limite_perfil < CURRENT_DATE FROM gestiones WHERE id = ?";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, gestionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Fecha límite de perfil como texto dd/mm/aaaa, o null. */
    public String limitePerfil(int gestionId) throws SQLException {
        String sql = "SELECT TO_CHAR(fecha_limite_perfil, 'DD/MM/YYYY') FROM gestiones WHERE id = ?";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, gestionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Gestión por código (2-2026), o null. */
    public Gestion porCodigo(String codigo) throws SQLException {
        String sql = """
                SELECT id, codigo, TO_CHAR(fecha_limite_perfil, 'DD/MM/YYYY'), plazo_revision_dias
                  FROM gestiones WHERE codigo = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Gestion(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)) : null;
            }
        }
    }

    /** La gestión marcada como activa, o null si no hay ninguna. */
    public Gestion activa() throws SQLException {
        String sql = """
                SELECT id, codigo, TO_CHAR(fecha_limite_perfil, 'DD/MM/YYYY'), plazo_revision_dias
                  FROM gestiones WHERE activa ORDER BY fecha_inicio DESC LIMIT 1
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new Gestion(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)) : null;
        }
    }
}