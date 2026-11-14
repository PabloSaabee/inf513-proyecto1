package org.example.datos;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a evaluaciones, calificaciones, ambientes y al calendario de evaluaciones (CU6).
 * Una evaluación "pendiente" está programada o reprogramada.
 */
public class DEvaluacion {

    public record Evaluacion(long id, long proyectoId, String codigo, String tipo, int intento, LocalDateTime fechaHora,
                             int ambienteId, String ambiente, String estado, String resultado, BigDecimal notaFinal,
                             String observaciones, String acta, int gestionId) {
        public boolean pendiente() {
            return estado.equals("programada") || estado.equals("reprogramada");
        }
    }

    public record Calificacion(long designacionId, int posicion, long docenteId, String docente, BigDecimal nota,
                               String observacion) { }

    private static final String SELECT = """
            SELECT e.id, e.proyecto_id, p.codigo, e.tipo, e.intento, e.fecha_hora, e.ambiente_id, a.nombre,
                   e.estado, e.resultado, e.nota_final, e.observaciones, ar.ruta, p.gestion_id
              FROM evaluaciones e
              JOIN proyectos p ON p.id = e.proyecto_id
              JOIN ambientes a ON a.id = e.ambiente_id
              LEFT JOIN archivos ar ON ar.id = e.acta_archivo_id
            """;

    // ================================================================== consultas

    public Evaluacion porId(long id) throws SQLException {
        List<Evaluacion> l = lista(SELECT + " WHERE e.id = ?", id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Evaluación pendiente del proyecto de ese tipo, o null. */
    public Evaluacion pendiente(long proyectoId, String tipo) throws SQLException {
        String sql = SELECT + " WHERE e.proyecto_id = ? AND e.tipo = '" + (tipo.equals("defensa") ? "defensa" : "predefensa")
                + "' AND e.estado IN ('programada','reprogramada') ORDER BY e.intento DESC LIMIT 1";
        List<Evaluacion> l = lista(sql, proyectoId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Evaluaciones del proyecto (todas). */
    public List<Evaluacion> deProyecto(long proyectoId) throws SQLException {
        return lista(SELECT + " WHERE e.proyecto_id = ? ORDER BY e.fecha_hora", proyectoId);
    }

    /**
     * Listado: estado = programada (incluye reprogramada), realizada, suspendida o null (todas).
     * docenteId != null: solo donde ese docente es tribunal aceptado.
     * Columnas: id, proyecto, tipo, intento, fecha y hora, ambiente, estado, notas, resultado, nota final.
     */
    public List<String[]> listar(String estado, Long docenteId) throws SQLException {
        String sql = """
                SELECT e.id::text, p.codigo, e.tipo, e.intento::text, TO_CHAR(e.fecha_hora, 'DD/MM/YYYY HH24:MI'), a.nombre,
                       e.estado, (SELECT COUNT(*) FROM calificaciones c WHERE c.evaluacion_id = e.id) || '/3',
                       COALESCE(REPLACE(e.resultado, '_', ' '), '-'), COALESCE(e.nota_final::text, '-')
                  FROM evaluaciones e
                  JOIN proyectos p ON p.id = e.proyecto_id
                  JOIN ambientes a ON a.id = e.ambiente_id
                 WHERE (?::text IS NULL OR e.estado = ?::text OR (?::text = 'programada' AND e.estado = 'reprogramada'))
                   AND (?::bigint IS NULL OR EXISTS (SELECT 1 FROM designaciones_tribunal dt WHERE dt.proyecto_id = e.proyecto_id
                                                       AND dt.docente_id = ?::bigint AND dt.estado = 'aceptada'))
                 ORDER BY e.fecha_hora DESC
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, estado);
            ps.setString(2, estado);
            ps.setString(3, estado);
            if (docenteId == null) {
                ps.setNull(4, Types.BIGINT);
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(4, docenteId);
                ps.setLong(5, docenteId);
            }
            return DProyecto.filas(ps, 10);
        }
    }

    /**
     * EvaluacionService.conflicto: otra evaluación pendiente a menos de 2 horas
     * en el mismo ambiente o con alguno de los mismos tribunales.
     * @return descripción del choque, o null si no hay
     */
    public String conflicto(long proyectoId, LocalDateTime fechaHora, int ambienteId, long excluirEvaluacion) throws SQLException {
        String sql = """
                SELECT p.codigo || ' (' || e.tipo || ', ' || TO_CHAR(e.fecha_hora, 'DD/MM HH24:MI') || ', ' || a.nombre || ') · ' ||
                       CASE WHEN e.ambiente_id = ? THEN 'mismo ambiente' ELSE 'comparten tribunal' END
                  FROM evaluaciones e
                  JOIN proyectos p ON p.id = e.proyecto_id
                  JOIN ambientes a ON a.id = e.ambiente_id
                 WHERE e.estado IN ('programada','reprogramada') AND e.id <> ?
                   AND ABS(EXTRACT(EPOCH FROM (e.fecha_hora - ?::timestamp))) < 7200
                   AND (e.ambiente_id = ?
                        OR EXISTS (SELECT 1 FROM designaciones_tribunal d1
                                     JOIN designaciones_tribunal d2 ON d2.docente_id = d1.docente_id
                                    WHERE d1.proyecto_id = e.proyecto_id AND d1.estado = 'aceptada'
                                      AND d2.proyecto_id = ? AND d2.estado = 'aceptada'))
                 LIMIT 1
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, ambienteId);
            ps.setLong(2, excluirEvaluacion);
            ps.setTimestamp(3, Timestamp.valueOf(fechaHora));
            ps.setInt(4, ambienteId);
            ps.setLong(5, proyectoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public List<Calificacion> calificaciones(long evaluacionId) throws SQLException {
        String sql = """
                SELECT dt.id, dt.posicion, dt.docente_id, COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos,
                       c.nota, c.observacion
                  FROM designaciones_tribunal dt
                  JOIN evaluaciones e ON e.proyecto_id = dt.proyecto_id
                  JOIN usuarios u ON u.id = dt.docente_id
                  LEFT JOIN docentes d ON d.usuario_id = dt.docente_id
                  LEFT JOIN calificaciones c ON c.evaluacion_id = e.id AND c.designacion_id = dt.id
                 WHERE e.id = ? AND dt.estado = 'aceptada'
                 ORDER BY dt.posicion
                """;
        List<Calificacion> l = new ArrayList<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, evaluacionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(new Calificacion(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4),
                            rs.getBigDecimal(5), rs.getString(6)));
                }
            }
        }
        return l;
    }

    public int siguienteIntento(long proyectoId, String tipo) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT COALESCE(MAX(intento), 0) + 1 FROM evaluaciones WHERE proyecto_id = ? AND tipo = ?")) {
            ps.setLong(1, proyectoId);
            ps.setString(2, tipo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ================================================================== cambios

    /** Registra la evaluación y su evento en el calendario (EvaluacionService.registrarEnCalendario). */
    public long programar(Connection cn, long proyectoId, int gestionId, String tipo, int intento, LocalDateTime fechaHora,
                          int ambienteId, long usuarioId, String tituloEvento) throws SQLException {
        long id;
        try (PreparedStatement ps = cn.prepareStatement("""
                INSERT INTO evaluaciones (proyecto_id, tipo, intento, fecha_hora, ambiente_id, estado, programado_por)
                VALUES (?, ?, ?, ?, ?, 'programada', ?) RETURNING id
                """)) {
            ps.setLong(1, proyectoId);
            ps.setString(2, tipo);
            ps.setInt(3, intento);
            ps.setTimestamp(4, Timestamp.valueOf(fechaHora));
            ps.setInt(5, ambienteId);
            ps.setLong(6, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = cn.prepareStatement("""
                INSERT INTO eventos_calendario (gestion_id, titulo, descripcion, inicio, fin, creado_por)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setInt(1, gestionId);
            ps.setString(2, tituloEvento);
            ps.setString(3, "evaluacion:" + id);                // marca reservada (CU7-E2)
            ps.setTimestamp(4, Timestamp.valueOf(fechaHora));
            ps.setTimestamp(5, Timestamp.valueOf(fechaHora.plusHours(2)));
            ps.setLong(6, usuarioId);
            ps.executeUpdate();
        }
        return id;
    }

    public void reprogramar(Connection cn, long evaluacionId, LocalDateTime fechaHora, int ambienteId, String motivo)
            throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("""
                UPDATE evaluaciones SET fecha_hora = ?, ambiente_id = ?, estado = 'reprogramada',
                       observaciones = TRIM(COALESCE(observaciones || E'\\n', '') || 'Reprogramada: ' || ?)
                 WHERE id = ?
                """)) {
            ps.setTimestamp(1, Timestamp.valueOf(fechaHora));
            ps.setInt(2, ambienteId);
            ps.setString(3, motivo);
            ps.setLong(4, evaluacionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = cn.prepareStatement(
                "UPDATE eventos_calendario SET inicio = ?, fin = ? WHERE descripcion = ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(fechaHora));
            ps.setTimestamp(2, Timestamp.valueOf(fechaHora.plusHours(2)));
            ps.setString(3, "evaluacion:" + evaluacionId);
            ps.executeUpdate();
        }
    }

    /** Registra o corrige la nota del tribunal (antes del acta). */
    public void calificar(long evaluacionId, long designacionId, BigDecimal nota, String observacion) throws SQLException {
        String sql = """
                INSERT INTO calificaciones (evaluacion_id, designacion_id, nota, observacion) VALUES (?, ?, ?, ?)
                ON CONFLICT (evaluacion_id, designacion_id) DO UPDATE SET nota = EXCLUDED.nota, observacion = EXCLUDED.observacion
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, evaluacionId);
            ps.setLong(2, designacionId);
            ps.setBigDecimal(3, nota);
            if (observacion == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, observacion);
            ps.executeUpdate();
        }
    }

    public void registrarActa(Connection cn, long evaluacionId, String resultado, BigDecimal notaFinal, String observaciones,
                              Long actaArchivo) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("""
                UPDATE evaluaciones SET estado = 'realizada', resultado = ?, nota_final = ?,
                       observaciones = COALESCE(?, observaciones), acta_archivo_id = ?
                 WHERE id = ?
                """)) {
            ps.setString(1, resultado);
            ps.setBigDecimal(2, notaFinal);
            if (observaciones == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, observaciones);
            if (actaArchivo == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, actaArchivo);
            ps.setLong(5, evaluacionId);
            ps.executeUpdate();
        }
    }

    // ================================================================== ambientes

    public int insertarAmbiente(String nombre, String ubicacion) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("INSERT INTO ambientes (nombre, ubicacion) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, nombre);
            if (ubicacion == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, ubicacion);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public boolean existeAmbiente(String nombre) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM ambientes WHERE LOWER(nombre) = LOWER(?)")) {
            ps.setString(1, nombre);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Nombre del ambiente, o null si no existe. */
    public String nombreAmbiente(int id) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT nombre FROM ambientes WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Ambientes: id, nombre, ubicación, evaluaciones pendientes. */
    public List<String[]> ambientes() throws SQLException {
        String sql = """
                SELECT a.id::text, a.nombre, COALESCE(a.ubicacion, '-'),
                       (SELECT COUNT(*) FROM evaluaciones e WHERE e.ambiente_id = a.id
                           AND e.estado IN ('programada','reprogramada'))::text
                  FROM ambientes a ORDER BY a.id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            return DProyecto.filas(ps, 4);
        }
    }

    public boolean esFeriado(java.time.LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM feriados WHERE fecha = ?")) {
            ps.setDate(1, java.sql.Date.valueOf(fecha));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ================================================================== utilidades

    private static List<Evaluacion> lista(String sql, long valor) throws SQLException {
        List<Evaluacion> l = new ArrayList<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(new Evaluacion(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                            rs.getTimestamp(6).toLocalDateTime(), rs.getInt(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getBigDecimal(11), rs.getString(12), rs.getString(13), rs.getInt(14)));
                }
            }
        }
        return l;
    }
}