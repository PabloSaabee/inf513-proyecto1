package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a designaciones_tribunal (CU4 · RF-09, RF-10).
 * Vigente = pendiente o aceptada. El trigger trg_max_tres_tribunales impide más de 3 vigentes.
 */
public class DTribunal {

    public record Designacion(long id, long proyectoId, long docenteId, String docente, String email,
                              int posicion, String estado, String motivo) { }

    private static final String SELECT = """
            SELECT dt.id, dt.proyecto_id, dt.docente_id,
                   COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos, u.email,
                   dt.posicion, dt.estado, dt.motivo
              FROM designaciones_tribunal dt
              JOIN usuarios u ON u.id = dt.docente_id
              LEFT JOIN docentes d ON d.usuario_id = dt.docente_id
            """;

    /** Designaciones vigentes del proyecto, ordenadas por posición. */
    public List<Designacion> vigentes(long proyectoId) throws SQLException {
        return lista(SELECT + " WHERE dt.proyecto_id = ? AND dt.estado IN ('pendiente','aceptada') ORDER BY dt.posicion",
                proyectoId);
    }

    /** Designación pendiente del docente en el proyecto, o null. */
    public Designacion pendienteDe(long proyectoId, long docenteId) throws SQLException {
        List<Designacion> l = lista(SELECT + " WHERE dt.proyecto_id = ? AND dt.docente_id = ? AND dt.estado = 'pendiente'",
                proyectoId, docenteId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Todas las designaciones del proyecto (historial): posición, docente, estado, motivo, reemplaza a, fechas. */
    public List<String[]> historial(long proyectoId) throws SQLException {
        String sql = """
                SELECT dt.posicion::text, COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos,
                       dt.estado, COALESCE(dt.motivo, '-'),
                       COALESCE((SELECT ru.nombres || ' ' || ru.apellidos FROM designaciones_tribunal r
                                   JOIN usuarios ru ON ru.id = r.docente_id WHERE r.id = dt.reemplaza_a_id), '-'),
                       TO_CHAR(dt.fecha_designacion, 'DD/MM/YYYY'), COALESCE(TO_CHAR(dt.fecha_respuesta, 'DD/MM/YYYY'), '-')
                  FROM designaciones_tribunal dt
                  JOIN usuarios u ON u.id = dt.docente_id
                  LEFT JOIN docentes d ON d.usuario_id = dt.docente_id
                 WHERE dt.proyecto_id = ?
                 ORDER BY dt.posicion, dt.fecha_designacion
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            return DProyecto.filas(ps, 7);
        }
    }

    /** Mis designaciones como tribunal: código, título, posición, estado, etapa, fecha. */
    public List<String[]> deDocente(long docenteId) throws SQLException {
        String sql = """
                SELECT p.codigo, COALESCE(p.titulo, '-'), dt.posicion::text, dt.estado, p.etapa_actual,
                       TO_CHAR(dt.fecha_designacion, 'DD/MM/YYYY')
                  FROM designaciones_tribunal dt JOIN proyectos p ON p.id = dt.proyecto_id
                 WHERE dt.docente_id = ? AND dt.estado IN ('pendiente','aceptada')
                 ORDER BY (dt.estado = 'pendiente') DESC, dt.fecha_designacion DESC
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, docenteId);
            return DProyecto.filas(ps, 6);
        }
    }

    /** Para Dirección: proyectos en etapa tribunales con el avance de su cuerpo de tribunales. */
    public List<String[]> porConformar() throws SQLException {
        String sql = """
                SELECT p.codigo, COALESCE(p.titulo, '-'), COALESCE(a.nombre, '-'),
                       (SELECT COUNT(*) FROM designaciones_tribunal dt WHERE dt.proyecto_id = p.id AND dt.estado = 'aceptada')::text,
                       (SELECT COUNT(*) FROM designaciones_tribunal dt WHERE dt.proyecto_id = p.id AND dt.estado = 'pendiente')::text,
                       (3 - (SELECT COUNT(*) FROM designaciones_tribunal dt WHERE dt.proyecto_id = p.id
                                AND dt.estado IN ('pendiente','aceptada')))::text
                  FROM proyectos p LEFT JOIN areas_conocimiento a ON a.id = p.area_id
                 WHERE p.etapa_actual = 'tribunales'
                 ORDER BY p.codigo
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            return DProyecto.filas(ps, 6);
        }
    }

    /**
     * TribunalService.candidatos(area): docentes activos con rol tribunal del área,
     * sin el tutor ni los que ya son tribunal vigente del proyecto.
     * Columnas: CI, docente, áreas, designaciones vigentes (carga).
     */
    public List<String[]> candidatos(long proyectoId, int areaId) throws SQLException {
        String sql = """
                SELECT u.ci, COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos,
                       (SELECT STRING_AGG(a.nombre, ', ' ORDER BY a.id) FROM docente_area da
                          JOIN areas_conocimiento a ON a.id = da.area_id WHERE da.docente_id = u.id),
                       (SELECT COUNT(*) FROM designaciones_tribunal x JOIN proyectos px ON px.id = x.proyecto_id
                         WHERE x.docente_id = u.id AND x.estado IN ('pendiente','aceptada')
                           AND px.etapa_actual NOT IN ('concluido','cancelado'))::text
                  FROM docentes d JOIN usuarios u ON u.id = d.usuario_id
                 WHERE u.estado = 'activo'
                   AND EXISTS (SELECT 1 FROM usuario_rol ur JOIN roles r ON r.id = ur.rol_id
                                WHERE ur.usuario_id = u.id AND r.nombre = 'tribunal')
                   AND EXISTS (SELECT 1 FROM docente_area da WHERE da.docente_id = u.id AND da.area_id = ?)
                   AND NOT EXISTS (SELECT 1 FROM asignaciones_tutor t WHERE t.proyecto_id = ? AND t.docente_id = u.id
                                      AND t.estado = 'aceptada')
                   AND NOT EXISTS (SELECT 1 FROM designaciones_tribunal x WHERE x.proyecto_id = ? AND x.docente_id = u.id
                                      AND x.estado IN ('pendiente','aceptada'))
                 ORDER BY 4, u.apellidos
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, areaId);
            ps.setLong(2, proyectoId);
            ps.setLong(3, proyectoId);
            return DProyecto.filas(ps, 4);
        }
    }

    public boolean docenteEnArea(long docenteId, int areaId) throws SQLException {
        String sql = "SELECT 1 FROM docente_area WHERE docente_id = ? AND area_id = ?";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, docenteId);
            ps.setInt(2, areaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long designar(Connection cn, long proyectoId, long docenteId, int posicion, long directorId,
                         Long reemplazaA) throws SQLException {
        String sql = """
                INSERT INTO designaciones_tribunal (proyecto_id, docente_id, posicion, estado, reemplaza_a_id, autorizado_por)
                VALUES (?, ?, ?, 'pendiente', ?, ?) RETURNING id
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            ps.setLong(2, docenteId);
            ps.setInt(3, posicion);
            if (reemplazaA == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, reemplazaA);
            ps.setLong(5, directorId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void marcar(Connection cn, long designacionId, String estado, String motivo) throws SQLException {
        String sql = "UPDATE designaciones_tribunal SET estado = ?, motivo = ?, fecha_respuesta = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, estado);
            if (motivo == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, motivo);
            ps.setLong(3, designacionId);
            ps.executeUpdate();
        }
    }

    public int aceptadas(Connection cn, long proyectoId) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT COUNT(*) FROM designaciones_tribunal WHERE proyecto_id = ? AND estado = 'aceptada'")) {
            ps.setLong(1, proyectoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<Designacion> lista(String sql, long... valores) throws SQLException {
        List<Designacion> l = new ArrayList<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < valores.length; i++) ps.setLong(i + 1, valores[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(new Designacion(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                            rs.getInt(6), rs.getString(7), rs.getString(8)));
                }
            }
        }
        return l;
    }
}