package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Acceso a perfiles: cada registro es una VERSIÓN del perfil (RF-07, nunca se sobrescribe).
 */
public class DPerfil {

    public record Perfil(long id, long proyectoId, int version, String titulo, String objetivoGeneral,
                         String enlace, String estado, String fecha) { }

    public record NuevoPerfil(String titulo, String introduccion, String objetivoGeneral,
                              String objetivosEspecificos, Long archivoId) { }

    /** Última versión del perfil, o null si todavía no hay. */
    public Perfil ultimo(long proyectoId) throws SQLException {
        String sql = """
                SELECT f.id, f.proyecto_id, f.version, f.titulo, f.objetivo_general, a.ruta, f.estado,
                       TO_CHAR(f.created_at, 'DD/MM/YYYY HH24:MI')
                  FROM perfiles f LEFT JOIN archivos a ON a.id = f.archivo_id
                 WHERE f.proyecto_id = ?
                 ORDER BY f.version DESC LIMIT 1
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Perfil(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8));
            }
        }
    }

    /** Todas las versiones: versión, título, estado, fecha, registrado por, enlace. */
    public List<String[]> versiones(long proyectoId) throws SQLException {
        String sql = """
                SELECT f.version::text, f.titulo, f.estado, TO_CHAR(f.created_at, 'DD/MM/YYYY HH24:MI'),
                       u.nombres || ' ' || u.apellidos, COALESCE(a.ruta, '')
                  FROM perfiles f
                  JOIN usuarios u ON u.id = f.registrado_por
                  LEFT JOIN archivos a ON a.id = f.archivo_id
                 WHERE f.proyecto_id = ?
                 ORDER BY f.version DESC
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            return DProyecto.filas(ps, 6);
        }
    }

    /** Crea la versión siguiente en estado borrador. @return número de versión */
    public int crearVersion(long proyectoId, NuevoPerfil p, long usuarioId) throws SQLException {
        String sql = """
                INSERT INTO perfiles (proyecto_id, version, titulo, introduccion, objetivo_general,
                                      objetivos_especificos, archivo_id, estado, registrado_por)
                VALUES (?, (SELECT COALESCE(MAX(version), 0) + 1 FROM perfiles WHERE proyecto_id = ?),
                        ?, ?, ?, ?, ?, 'borrador', ?)
                RETURNING version
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            ps.setLong(2, proyectoId);
            ps.setString(3, p.titulo());
            ps.setString(4, p.introduccion());
            ps.setString(5, p.objetivoGeneral());
            ps.setString(6, p.objetivosEspecificos());
            if (p.archivoId() == null) ps.setNull(7, Types.BIGINT); else ps.setLong(7, p.archivoId());
            ps.setLong(8, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void cambiarEstado(Connection cn, long perfilId, String estado) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("UPDATE perfiles SET estado = ? WHERE id = ?")) {
            ps.setString(1, estado);
            ps.setLong(2, perfilId);
            ps.executeUpdate();
        }
    }
}