package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a proyectos, proyecto_estudiante y asignaciones_tutor (CU3).
 */
public class DProyecto {

    public record Proyecto(long id, String codigo, String titulo, Integer areaId, String area,
                           int gestionId, String gestion, String etapa, String estudiantes) { }

    public record Asignacion(long id, long proyectoId, Long docenteId, String docente, String docenteEmail,
                             String origen, String estado, String motivo, String fechaSolicitud,
                             String fechaRespuesta) { }

    public record Estudiante(long id, String nombre, String email, String registro) { }

    public record Docente(long id, String ci, String nombre, String email, String estado, List<String> roles) { }

    private static final String SELECT_PROYECTO = """
            SELECT p.id, p.codigo, p.titulo, p.area_id, a.nombre AS area, p.gestion_id, g.codigo AS gestion,
                   p.etapa_actual,
                   (SELECT STRING_AGG(u.nombres || ' ' || u.apellidos, ', ' ORDER BY pe.es_responsable DESC, u.id)
                      FROM proyecto_estudiante pe JOIN usuarios u ON u.id = pe.estudiante_id
                     WHERE pe.proyecto_id = p.id) AS estudiantes
              FROM proyectos p
              JOIN gestiones g ON g.id = p.gestion_id
              LEFT JOIN areas_conocimiento a ON a.id = p.area_id
            """;

    // ================================================================== proyectos

    public Proyecto porCodigo(String codigo) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(SELECT_PROYECTO + " WHERE UPPER(p.codigo) = UPPER(?)")) {
            ps.setString(1, codigo);
            return proyecto(ps);
        }
    }

    public Proyecto porId(long id) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(SELECT_PROYECTO + " WHERE p.id = ?")) {
            ps.setLong(1, id);
            return proyecto(ps);
        }
    }

    /** Proyecto vigente (no cancelado) del estudiante, o null. */
    public Proyecto vigenteDeEstudiante(long estudianteId) throws SQLException {
        String sql = SELECT_PROYECTO + """
                 WHERE p.etapa_actual <> 'cancelado'
                   AND EXISTS (SELECT 1 FROM proyecto_estudiante pe WHERE pe.proyecto_id = p.id AND pe.estudiante_id = ?)
                 ORDER BY p.created_at DESC LIMIT 1
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, estudianteId);
            return proyecto(ps);
        }
    }

    /** Listado desde la vista v_dashboard_proyectos; etapa = null para todas. */
    public List<String[]> listar(String etapa) throws SQLException {
        String sql = """
                SELECT codigo, gestion, COALESCE(area, '-'), COALESCE(titulo, '-'), etapa_actual,
                       COALESCE(estudiantes, '-'), COALESCE(tutor, '-'),
                       COALESCE(TO_CHAR(proximo_vencimiento, 'DD/MM/YYYY'), '-')
                  FROM v_dashboard_proyectos
                 WHERE (?::text IS NULL OR etapa_actual = ?::text)
                 ORDER BY codigo
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, etapa);
            ps.setString(2, etapa);
            return filas(ps, 8);
        }
    }

    public List<Estudiante> estudiantes(long proyectoId) throws SQLException {
        String sql = """
                SELECT u.id, u.nombres || ' ' || u.apellidos, u.email, e.registro_universitario
                  FROM proyecto_estudiante pe
                  JOIN usuarios u ON u.id = pe.estudiante_id
                  JOIN estudiantes e ON e.usuario_id = u.id
                 WHERE pe.proyecto_id = ?
                 ORDER BY pe.es_responsable DESC, u.id
                """;
        List<Estudiante> lista = new ArrayList<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(new Estudiante(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        }
        return lista;
    }

    public boolean esIntegrante(long proyectoId, long usuarioId) throws SQLException {
        return existe("SELECT 1 FROM proyecto_estudiante WHERE proyecto_id = ? AND estudiante_id = ?", proyectoId, usuarioId);
    }

    /** ¿El docente es o fue tutor (aceptado o pendiente) o tribunal vigente del proyecto? */
    public boolean esDocenteDelProyecto(long proyectoId, long docenteId) throws SQLException {
        return existe("""
                SELECT 1 FROM asignaciones_tutor WHERE proyecto_id = ? AND docente_id = ?
                   AND estado IN ('pendiente_respuesta','aceptada')
                UNION ALL
                SELECT 1 FROM designaciones_tribunal WHERE proyecto_id = ? AND docente_id = ?
                   AND estado IN ('pendiente','aceptada')
                """, proyectoId, docenteId, proyectoId, docenteId);
    }

    /** Estudiante por registro universitario: [usuario_id, nombre] o null. */
    public Estudiante estudiantePorRegistro(String registro) throws SQLException {
        String sql = """
                SELECT u.id, u.nombres || ' ' || u.apellidos, u.email, e.registro_universitario
                  FROM estudiantes e JOIN usuarios u ON u.id = e.usuario_id
                 WHERE e.registro_universitario = ? AND u.estado = 'activo'
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, registro);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Estudiante(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)) : null;
            }
        }
    }

    /** Id de la solicitud habilitada del estudiante en la gestión, o null (HabilitacionService.solicitudHabilitada). */
    public Long solicitudHabilitada(long estudianteId, int gestionId) throws SQLException {
        String sql = """
                SELECT id FROM solicitudes_habilitacion
                 WHERE estudiante_id = ? AND gestion_id = ? AND estado = 'habilitada'
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, estudianteId);
            ps.setInt(2, gestionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public String nombreArea(int areaId) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT nombre FROM areas_conocimiento WHERE id = ?")) {
            ps.setInt(1, areaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Registra el proyecto con sus integrantes en una transacción.
     * El código se genera como PG-{gestión}-{NNN}: PG-2-2026-001 (ProyectoService.generarCodigo).
     */
    public Proyecto crear(int gestionId, String codigoGestion, int areaId, String titulo, long responsableId,
                          long solicitudResponsable, Long companeroId, Long solicitudCompanero,
                          DHistorial historial) throws SQLException {
        try (Connection cn = ConexionBD.getConexion()) {
            cn.setAutoCommit(false);
            try {
                int numero;
                try (PreparedStatement ps = cn.prepareStatement("""
                        SELECT COALESCE(MAX(CAST(SUBSTRING(codigo FROM '(\\d+)$') AS INTEGER)), 0) + 1
                          FROM proyectos WHERE gestion_id = ?
                        """)) {
                    ps.setInt(1, gestionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        numero = rs.getInt(1);
                    }
                }
                String codigo = "PG-" + codigoGestion + "-" + String.format("%03d", numero);

                long id;
                try (PreparedStatement ps = cn.prepareStatement("""
                        INSERT INTO proyectos (codigo, gestion_id, area_id, titulo, etapa_actual)
                        VALUES (?, ?, ?, ?, 'tutor') RETURNING id
                        """)) {
                    ps.setString(1, codigo);
                    ps.setInt(2, gestionId);
                    ps.setInt(3, areaId);
                    ps.setString(4, titulo);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = cn.prepareStatement("""
                        INSERT INTO proyecto_estudiante (proyecto_id, estudiante_id, solicitud_id, es_responsable)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    ps.setLong(1, id);
                    ps.setLong(2, responsableId);
                    ps.setLong(3, solicitudResponsable);
                    ps.setBoolean(4, true);
                    ps.executeUpdate();
                    if (companeroId != null) {
                        ps.setLong(2, companeroId);
                        ps.setLong(3, solicitudCompanero);
                        ps.setBoolean(4, false);
                        ps.executeUpdate();
                    }
                }
                historial.registrar(cn, id, "habilitacion", "completada", "Proyecto " + codigo + " registrado", responsableId);
                historial.registrar(cn, id, "tutor", "iniciada", "Pendiente de asignar tutor", responsableId);
                cn.commit();
                return porId(id);
            } catch (SQLException | RuntimeException e) {
                cn.rollback();
                throw e;
            }
        }
    }

    public void cambiarEtapa(Connection cn, long proyectoId, String etapa) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(
                "UPDATE proyectos SET etapa_actual = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            ps.setString(1, etapa);
            ps.setLong(2, proyectoId);
            ps.executeUpdate();
        }
    }

    public void cambiarTitulo(long proyectoId, String titulo) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(
                     "UPDATE proyectos SET titulo = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            ps.setString(1, titulo);
            ps.setLong(2, proyectoId);
            ps.executeUpdate();
        }
    }

    // ================================================================== docentes y tutores

    /** Docente activo por CI con sus roles, o null. */
    public Docente docentePorCi(String ci) throws SQLException {
        String sql = """
                SELECT u.id, u.ci, COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos, u.email, u.estado,
                       ARRAY(SELECT r.nombre FROM usuario_rol ur JOIN roles r ON r.id = ur.rol_id
                              WHERE ur.usuario_id = u.id ORDER BY r.id)
                  FROM docentes d JOIN usuarios u ON u.id = d.usuario_id
                 WHERE u.ci = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, ci);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                List<String> roles = new ArrayList<>();
                for (Object o : (Object[]) rs.getArray(6).getArray()) roles.add(String.valueOf(o));
                return new Docente(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), roles);
            }
        }
    }

    /** Docentes con rol tutor de un área (null = todas): CI, nombre, áreas, roles, tutorías vigentes. */
    public List<String[]> docentes(Integer areaId) throws SQLException {
        String sql = """
                SELECT u.ci, COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos,
                       (SELECT STRING_AGG(a.nombre, ', ' ORDER BY a.id) FROM docente_area da
                          JOIN areas_conocimiento a ON a.id = da.area_id WHERE da.docente_id = u.id),
                       (SELECT STRING_AGG(r.nombre, ', ' ORDER BY r.id) FROM usuario_rol ur
                          JOIN roles r ON r.id = ur.rol_id WHERE ur.usuario_id = u.id),
                       (SELECT COUNT(*) FROM asignaciones_tutor t JOIN proyectos p ON p.id = t.proyecto_id
                         WHERE t.docente_id = u.id AND t.estado = 'aceptada'
                           AND p.etapa_actual NOT IN ('concluido','cancelado'))::text
                  FROM docentes d JOIN usuarios u ON u.id = d.usuario_id
                 WHERE u.estado = 'activo'
                   AND EXISTS (SELECT 1 FROM usuario_rol ur JOIN roles r ON r.id = ur.rol_id
                                WHERE ur.usuario_id = u.id AND r.nombre = 'tutor')
                   AND (?::int IS NULL OR EXISTS (SELECT 1 FROM docente_area da
                                                   WHERE da.docente_id = u.id AND da.area_id = ?::int))
                 ORDER BY u.apellidos, u.nombres
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            if (areaId == null) {
                ps.setNull(1, Types.INTEGER);
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(1, areaId);
                ps.setInt(2, areaId);
            }
            return filas(ps, 5);
        }
    }

    private static final String SELECT_ASIGNACION = """
            SELECT t.id, t.proyecto_id, t.docente_id,
                   COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos AS docente, u.email,
                   t.origen, t.estado, t.motivo_respuesta,
                   TO_CHAR(t.fecha_solicitud, 'DD/MM/YYYY HH24:MI'), TO_CHAR(t.fecha_respuesta, 'DD/MM/YYYY HH24:MI')
              FROM asignaciones_tutor t
              LEFT JOIN usuarios u ON u.id = t.docente_id
              LEFT JOIN docentes d ON d.usuario_id = t.docente_id
            """;

    /** Asignación vigente (solicitada, pendiente o aceptada) del proyecto, o null. */
    public Asignacion asignacionVigente(long proyectoId) throws SQLException {
        String sql = SELECT_ASIGNACION + """
                 WHERE t.proyecto_id = ? AND t.estado IN ('solicitada','pendiente_respuesta','aceptada')
                 ORDER BY t.fecha_solicitud DESC LIMIT 1
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? asignacion(rs) : null;
            }
        }
    }

    /** Historial de asignaciones del proyecto: docente, origen, estado, motivo, fecha. */
    public List<String[]> historialTutores(long proyectoId) throws SQLException {
        String sql = """
                SELECT COALESCE(COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos, '(a designar por Dirección)'),
                       CASE t.origen WHEN 'propuesto_estudiante' THEN 'Propuesto por estudiante' ELSE 'Designado por Dirección' END, t.estado, COALESCE(t.motivo_respuesta, '-'), TO_CHAR(t.fecha_solicitud, 'DD/MM/YYYY')
                  FROM asignaciones_tutor t
                  LEFT JOIN usuarios u ON u.id = t.docente_id
                  LEFT JOIN docentes d ON d.usuario_id = t.docente_id
                 WHERE t.proyecto_id = ?
                 ORDER BY t.fecha_solicitud
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            return filas(ps, 5);
        }
    }

    /** Tutorías de un docente (todas las asignaciones donde aparece). */
    public List<String[]> tutoriasDe(long docenteId) throws SQLException {
        String sql = """
                SELECT p.codigo, COALESCE(p.titulo, '-'),
                       (SELECT STRING_AGG(u.nombres || ' ' || u.apellidos, ', ') FROM proyecto_estudiante pe
                          JOIN usuarios u ON u.id = pe.estudiante_id WHERE pe.proyecto_id = p.id),
                       CASE t.origen WHEN 'propuesto_estudiante' THEN 'Propuesto por estudiante' ELSE 'Designado por Dirección' END, t.estado, p.etapa_actual, TO_CHAR(t.fecha_solicitud, 'DD/MM/YYYY')
                  FROM asignaciones_tutor t JOIN proyectos p ON p.id = t.proyecto_id
                 WHERE t.docente_id = ?
                 ORDER BY (t.estado = 'pendiente_respuesta') DESC, t.fecha_solicitud DESC
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, docenteId);
            return filas(ps, 7);
        }
    }

    /** Para Dirección: solicitudes de designación y propuestas sin respuesta. */
    public List<String[]> tutoriasPendientes() throws SQLException {
        String sql = """
                SELECT p.codigo, COALESCE(p.titulo, '-'), COALESCE(a.nombre, '-'),
                       (SELECT STRING_AGG(u.nombres || ' ' || u.apellidos, ', ') FROM proyecto_estudiante pe
                          JOIN usuarios u ON u.id = pe.estudiante_id WHERE pe.proyecto_id = p.id),
                       COALESCE(du.nombres || ' ' || du.apellidos, '(a designar)'),
                       t.estado, TO_CHAR(t.fecha_solicitud, 'DD/MM/YYYY')
                  FROM asignaciones_tutor t
                  JOIN proyectos p ON p.id = t.proyecto_id
                  LEFT JOIN areas_conocimiento a ON a.id = p.area_id
                  LEFT JOIN usuarios du ON du.id = t.docente_id
                 WHERE t.estado IN ('solicitada','pendiente_respuesta')
                 ORDER BY (t.estado = 'solicitada') DESC, t.fecha_solicitud
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            return filas(ps, 7);
        }
    }

    /** Nueva asignación. docenteId null = el estudiante pide que Dirección designe. */
    public long crearAsignacion(long proyectoId, Long docenteId, String origen, String estado, Long designadoPor)
            throws SQLException {
        String sql = """
                INSERT INTO asignaciones_tutor (proyecto_id, docente_id, origen, estado, designado_por)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            if (docenteId == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, docenteId);
            ps.setString(3, origen);
            ps.setString(4, estado);
            if (designadoPor == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, designadoPor);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Dirección completa una solicitud de designación: pone el docente y queda pendiente de respuesta. */
    public void designarEnSolicitud(long asignacionId, long docenteId, long directorId) throws SQLException {
        String sql = """
                UPDATE asignaciones_tutor SET docente_id = ?, designado_por = ?, estado = 'pendiente_respuesta'
                 WHERE id = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, docenteId);
            ps.setLong(2, directorId);
            ps.setLong(3, asignacionId);
            ps.executeUpdate();
        }
    }

    public void marcarAsignacion(long asignacionId, String estado, String motivo) throws SQLException {
        try (Connection cn = ConexionBD.getConexion()) {
            marcarAsignacion(cn, asignacionId, estado, motivo);
        }
    }

    public void marcarAsignacion(Connection cn, long asignacionId, String estado, String motivo) throws SQLException {
        String sql = """
                UPDATE asignaciones_tutor SET estado = ?, motivo_respuesta = ?, fecha_respuesta = CURRENT_TIMESTAMP
                 WHERE id = ?
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, estado);
            if (motivo == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, motivo);
            ps.setLong(3, asignacionId);
            ps.executeUpdate();
        }
    }

    // ================================================================== utilidades

    private Proyecto proyecto(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            int area = rs.getInt("area_id");
            return new Proyecto(rs.getLong("id"), rs.getString("codigo"), rs.getString("titulo"),
                    rs.wasNull() ? null : area, rs.getString("area"), rs.getInt("gestion_id"),
                    rs.getString("gestion"), rs.getString("etapa_actual"), rs.getString("estudiantes"));
        }
    }

    private static Asignacion asignacion(ResultSet rs) throws SQLException {
        long docente = rs.getLong(3);
        Long docenteId = rs.wasNull() ? null : docente;
        return new Asignacion(rs.getLong(1), rs.getLong(2), docenteId, rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10));
    }

    static List<String[]> filas(PreparedStatement ps, int columnas) throws SQLException {
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

    private static boolean existe(String sql, long... valores) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < valores.length; i++) ps.setLong(i + 1, valores[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}