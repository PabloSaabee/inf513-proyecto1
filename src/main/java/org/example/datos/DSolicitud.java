package org.example.datos;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a solicitudes_habilitacion, requisitos_presentados y tipos_requisito (CU2).
 */
public class DSolicitud {

    public record Solicitud(long id, long estudianteId, String estudiante, String email, String registro,
                            String gestion, int gestionId, String estado, String observacion,
                            String fechaEnvio, String fechaVerificacion, String verificadoPor,
                            BigDecimal malla, boolean grl) { }

    public record Requisito(long id, int tipoId, String tipo, boolean obligatorio, String enlace,
                            String estado, String observacion) { }

    private static final String SELECT_SOLICITUD = """
            SELECT s.id, s.estudiante_id, u.nombres || ' ' || u.apellidos AS estudiante, u.email,
                   e.registro_universitario, g.codigo AS gestion, g.id AS gestion_id, s.estado, s.observacion,
                   TO_CHAR(s.fecha_envio, 'DD/MM/YYYY HH24:MI')        AS fecha_envio,
                   TO_CHAR(s.fecha_verificacion, 'DD/MM/YYYY HH24:MI') AS fecha_verificacion,
                   v.nombres || ' ' || v.apellidos AS verificado_por,
                   e.porcentaje_malla, e.grl_inscrito
              FROM solicitudes_habilitacion s
              JOIN estudiantes e ON e.usuario_id = s.estudiante_id
              JOIN usuarios u    ON u.id = s.estudiante_id
              JOIN gestiones g   ON g.id = s.gestion_id
              LEFT JOIN usuarios v ON v.id = s.verificado_por
            """;

    // ================================================================== consultas

    public Solicitud porId(long id) throws SQLException {
        return una(SELECT_SOLICITUD + " WHERE s.id = ?", id, null);
    }

    public Solicitud deEstudiante(long estudianteId, int gestionId) throws SQLException {
        return una(SELECT_SOLICITUD + " WHERE s.estudiante_id = ? AND s.gestion_id = ?", estudianteId, gestionId);
    }

    /** La más reciente del estudiante (de cualquier gestión), o null. */
    public Solicitud ultimaDeEstudiante(long estudianteId) throws SQLException {
        return una(SELECT_SOLICITUD + " WHERE s.estudiante_id = ? ORDER BY g.fecha_inicio DESC LIMIT 1",
                estudianteId, null);
    }

    /** Bandeja: estado = null para todas. */
    public List<String[]> listar(String estado) throws SQLException {
        String sql = """
                SELECT s.id, u.nombres || ' ' || u.apellidos, e.registro_universitario, g.codigo, s.estado,
                       COALESCE(TO_CHAR(s.fecha_envio, 'DD/MM/YYYY'), '-'),
                       (SELECT COUNT(*) FILTER (WHERE r.estado = 'valido') || '/' || COUNT(*)
                          FROM requisitos_presentados r WHERE r.solicitud_id = s.id),
                       e.porcentaje_malla || ' %',
                       CASE WHEN e.grl_inscrito THEN 'Sí' ELSE 'No' END
                  FROM solicitudes_habilitacion s
                  JOIN estudiantes e ON e.usuario_id = s.estudiante_id
                  JOIN usuarios u    ON u.id = s.estudiante_id
                  JOIN gestiones g   ON g.id = s.gestion_id
                 WHERE (?::text IS NULL OR s.estado = ?::text)
                 ORDER BY s.fecha_envio NULLS LAST, s.id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, estado);
            ps.setString(2, estado);
            return filas(ps, 9);
        }
    }

    public List<Requisito> requisitos(long solicitudId) throws SQLException {
        String sql = """
                SELECT r.id, t.id, t.nombre, t.obligatorio, a.ruta, r.estado, r.observacion
                  FROM requisitos_presentados r
                  JOIN tipos_requisito t ON t.id = r.tipo_requisito_id
                  JOIN archivos a        ON a.id = r.archivo_id
                 WHERE r.solicitud_id = ?
                 ORDER BY t.id
                """;
        List<Requisito> lista = new ArrayList<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, solicitudId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Requisito(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4),
                            rs.getString(5), rs.getString(6), rs.getString(7)));
                }
            }
        }
        return lista;
    }

    /** Nombres de los requisitos obligatorios que la solicitud todavía no cargó. */
    public List<String> obligatoriosFaltantes(long solicitudId) throws SQLException {
        String sql = """
                SELECT t.nombre FROM tipos_requisito t
                 WHERE t.obligatorio
                   AND NOT EXISTS (SELECT 1 FROM requisitos_presentados r
                                    WHERE r.solicitud_id = ? AND r.tipo_requisito_id = t.id)
                 ORDER BY t.id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, solicitudId);
            List<String> faltan = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) faltan.add(rs.getString(1));
            }
            return faltan;
        }
    }

    /** Requisito por id: [solicitud_id, tipo] o null. */
    public Object[] requisitoPorId(long requisitoId) throws SQLException {
        String sql = """
                SELECT r.solicitud_id, t.nombre FROM requisitos_presentados r
                  JOIN tipos_requisito t ON t.id = r.tipo_requisito_id WHERE r.id = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, requisitoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Object[]{rs.getLong(1), rs.getString(2)} : null;
            }
        }
    }

    /** Tipos de requisito: id, nombre, obligatorio. */
    public List<String[]> tipos() throws SQLException {
        String sql = "SELECT id, nombre, CASE WHEN obligatorio THEN 'Sí' ELSE 'No' END FROM tipos_requisito ORDER BY id";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            return filas(ps, 3);
        }
    }

    /** Nombre del tipo de requisito, o null si no existe. */
    public String nombreTipo(int tipoId) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT nombre FROM tipos_requisito WHERE id = ?")) {
            ps.setInt(1, tipoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public boolean esEstudiante(long usuarioId) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM estudiantes WHERE usuario_id = ?")) {
            ps.setLong(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ================================================================== cambios

    public long crear(long estudianteId, int gestionId) throws SQLException {
        String sql = """
                INSERT INTO solicitudes_habilitacion (estudiante_id, gestion_id, estado)
                VALUES (?, ?, 'borrador') RETURNING id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, estudianteId);
            ps.setInt(2, gestionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Carga o reemplaza el requisito de ese tipo (vuelve a 'pendiente').
     * @return true si reemplazó uno existente
     */
    public boolean guardarRequisito(long solicitudId, int tipoId, long archivoId) throws SQLException {
        String actualizar = """
                UPDATE requisitos_presentados SET archivo_id = ?, estado = 'pendiente', observacion = NULL
                 WHERE solicitud_id = ? AND tipo_requisito_id = ?
                """;
        try (Connection cn = ConexionBD.getConexion()) {
            try (PreparedStatement ps = cn.prepareStatement(actualizar)) {
                ps.setLong(1, archivoId);
                ps.setLong(2, solicitudId);
                ps.setInt(3, tipoId);
                if (ps.executeUpdate() > 0) return true;
            }
            try (PreparedStatement ps = cn.prepareStatement("""
                    INSERT INTO requisitos_presentados (solicitud_id, tipo_requisito_id, archivo_id, estado)
                    VALUES (?, ?, ?, 'pendiente')
                    """)) {
                ps.setLong(1, solicitudId);
                ps.setInt(2, tipoId);
                ps.setLong(3, archivoId);
                ps.executeUpdate();
            }
            return false;
        }
    }

    /** El estudiante envía (o reenvía) la solicitud. */
    public void enviar(long solicitudId) throws SQLException {
        ejecutar("""
                UPDATE solicitudes_habilitacion SET estado = 'enviada', fecha_envio = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, solicitudId);
    }

    public void validarRequisito(long requisitoId, String estado, String observacion) throws SQLException {
        String sql = "UPDATE requisitos_presentados SET estado = ?, observacion = ? WHERE id = ?";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, estado);
            texto(ps, 2, observacion);
            ps.setLong(3, requisitoId);
            ps.executeUpdate();
        }
    }

    /** RF-04: porcentaje de malla aprobada y GRL inscrito (se guardan en estudiantes). */
    public void registrarMalla(long estudianteId, BigDecimal porcentaje, boolean grl) throws SQLException {
        String sql = "UPDATE estudiantes SET porcentaje_malla = ?, grl_inscrito = ? WHERE usuario_id = ?";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setBigDecimal(1, porcentaje);
            ps.setBoolean(2, grl);
            ps.setLong(3, estudianteId);
            ps.executeUpdate();
        }
    }

    /** Resultado de la verificación: habilitada, observada o rechazada. */
    public void resolver(long solicitudId, String estado, String observacion, long verificadorId) throws SQLException {
        String sql = """
                UPDATE solicitudes_habilitacion
                   SET estado = ?, observacion = ?, verificado_por = ?, fecha_verificacion = CURRENT_TIMESTAMP
                 WHERE id = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, estado);
            texto(ps, 2, observacion);
            ps.setLong(3, verificadorId);
            ps.setLong(4, solicitudId);
            ps.executeUpdate();
        }
    }

    // ================================================================== utilidades

    private Solicitud una(String sql, long a, Integer b) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, a);
            if (b != null) ps.setInt(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Solicitud(rs.getLong("id"), rs.getLong("estudiante_id"), rs.getString("estudiante"),
                        rs.getString("email"), rs.getString("registro_universitario"), rs.getString("gestion"),
                        rs.getInt("gestion_id"), rs.getString("estado"), rs.getString("observacion"),
                        rs.getString("fecha_envio"), rs.getString("fecha_verificacion"),
                        rs.getString("verificado_por"), rs.getBigDecimal("porcentaje_malla"),
                        rs.getBoolean("grl_inscrito"));
            }
        }
    }

    private static List<String[]> filas(PreparedStatement ps, int columnas) throws SQLException {
        List<String[]> filas = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String[] f = new String[columnas];
                for (int i = 0; i < columnas; i++) f[i] = rs.getString(i + 1);
                filas.add(f);
            }
        }
        return filas;
    }

    private static void ejecutar(String sql, long id) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static void texto(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
    }
}