package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consultas de CU7: dashboard (DashboardService), seguimiento (SeguimientoService),
 * calendario (eventos_calendario) y notificaciones.
 */
public class DMonitoreo {

    private static final String DIAS_RESTANTES = """
            CASE WHEN r.fecha_vencimiento >= CURRENT_DATE
                 THEN (SELECT COUNT(*) FROM generate_series(CURRENT_DATE + 1, r.fecha_vencimiento, INTERVAL '1 day') d
                        WHERE EXTRACT(ISODOW FROM d) < 6 AND d::date NOT IN (SELECT fecha FROM feriados))
                 ELSE -(SELECT COUNT(*) FROM generate_series(r.fecha_vencimiento + 1, CURRENT_DATE, INTERVAL '1 day') d
                        WHERE EXTRACT(ISODOW FROM d) < 6 AND d::date NOT IN (SELECT fecha FROM feriados))
            END
            """;

    // ================================================================== dashboard

    /** Indicadores de la gestión (null = todas). */
    public Map<String, Integer> indicadores(Integer gestionId) throws SQLException {
        String sql = """
                SELECT
                  (SELECT COUNT(*) FROM proyectos p WHERE (?::int IS NULL OR p.gestion_id = ?::int)),
                  (SELECT COUNT(*) FROM proyectos p WHERE (?::int IS NULL OR p.gestion_id = ?::int)
                      AND p.etapa_actual NOT IN ('concluido','cancelado')),
                  (SELECT COUNT(*) FROM proyectos p WHERE (?::int IS NULL OR p.gestion_id = ?::int) AND p.etapa_actual = 'concluido'),
                  (SELECT COUNT(*) FROM proyectos p WHERE (?::int IS NULL OR p.gestion_id = ?::int) AND p.etapa_actual = 'cancelado'),
                  (SELECT COUNT(*) FROM solicitudes_habilitacion s WHERE (?::int IS NULL OR s.gestion_id = ?::int) AND s.estado = 'enviada'),
                  (SELECT COUNT(*) FROM asignaciones_tutor t JOIN proyectos p ON p.id = t.proyecto_id
                    WHERE (?::int IS NULL OR p.gestion_id = ?::int) AND t.estado IN ('solicitada','pendiente_respuesta')),
                  (SELECT COUNT(*) FROM rondas_revision r JOIN proyectos p ON p.id = r.proyecto_id
                    WHERE (?::int IS NULL OR p.gestion_id = ?::int) AND r.estado IN ('en_revision','vencida')),
                  (SELECT COUNT(*) FROM rondas_revision r JOIN proyectos p ON p.id = r.proyecto_id
                    WHERE (?::int IS NULL OR p.gestion_id = ?::int) AND r.estado IN ('en_revision','vencida')
                      AND r.fecha_vencimiento < CURRENT_DATE),
                  (SELECT COUNT(*) FROM evaluaciones e JOIN proyectos p ON p.id = e.proyecto_id
                    WHERE (?::int IS NULL OR p.gestion_id = ?::int) AND e.estado IN ('programada','reprogramada')
                      AND e.fecha_hora >= CURRENT_TIMESTAMP)
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 1; i <= 18; i++) {
                if (gestionId == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, gestionId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Integer> m = new LinkedHashMap<>();
                String[] nombres = {"Proyectos", "Activos", "Concluidos (titulados)", "Cancelados", "Solicitudes por verificar",
                        "Tutorías pendientes", "Rondas en revisión", "Rondas vencidas", "Evaluaciones próximas"};
                for (int i = 0; i < nombres.length; i++) m.put(nombres[i], rs.getInt(i + 1));
                return m;
            }
        }
    }

    /** Proyectos por etapa (en orden del proceso): etapa, cantidad. */
    public List<String[]> porEtapa(Integer gestionId) throws SQLException {
        String sql = """
                SELECT e.etapa, COUNT(p.id)::text
                  FROM unnest(ARRAY['habilitacion','tutor','perfil','viabilidad','tribunales','revision',
                                    'predefensa','defensa','concluido','cancelado']) WITH ORDINALITY AS e(etapa, orden)
                  LEFT JOIN proyectos p ON p.etapa_actual = e.etapa AND (?::int IS NULL OR p.gestion_id = ?::int)
                 GROUP BY e.etapa, e.orden ORDER BY e.orden
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            gestion(ps, gestionId, 2);
            return DProyecto.filas(ps, 2);
        }
    }

    /** Pendientes: rondas vencidas o que vencen en 3 días hábiles o menos. */
    public List<String[]> alertasPlazos(Integer gestionId) throws SQLException {
        String sql = "SELECT p.codigo, r.numero::text, TO_CHAR(r.fecha_vencimiento, 'DD/MM/YYYY'), (" + DIAS_RESTANTES + ")::text "
                + """
                  FROM rondas_revision r JOIN proyectos p ON p.id = r.proyecto_id
                 WHERE r.estado IN ('en_revision','vencida') AND (?::int IS NULL OR p.gestion_id = ?::int)
                """
                + " AND (" + DIAS_RESTANTES + ") <= 3 ORDER BY r.fecha_vencimiento";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            gestion(ps, gestionId, 2);
            return DProyecto.filas(ps, 4);
        }
    }

    /** Evaluaciones programadas desde hoy: fecha y hora, proyecto, tipo, ambiente. */
    public List<String[]> proximasEvaluaciones(Integer gestionId) throws SQLException {
        String sql = """
                SELECT TO_CHAR(e.fecha_hora, 'DD/MM/YYYY HH24:MI'), p.codigo, e.tipo, a.nombre
                  FROM evaluaciones e JOIN proyectos p ON p.id = e.proyecto_id JOIN ambientes a ON a.id = e.ambiente_id
                 WHERE e.estado IN ('programada','reprogramada') AND e.fecha_hora >= CURRENT_DATE
                   AND (?::int IS NULL OR p.gestion_id = ?::int)
                 ORDER BY e.fecha_hora LIMIT 10
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            gestion(ps, gestionId, 2);
            return DProyecto.filas(ps, 4);
        }
    }

    /** Proyectos (vista v_dashboard_proyectos): código, título, etapa, estudiantes, tutor, vence revisión, próxima evaluación. */
    public List<String[]> proyectos(Integer gestionId) throws SQLException {
        String sql = """
                SELECT v.codigo, COALESCE(v.titulo, '-'), v.etapa_actual, COALESCE(v.estudiantes, '-'), COALESCE(v.tutor, '-'),
                       COALESCE(TO_CHAR(v.proximo_vencimiento, 'DD/MM/YYYY'), '-'),
                       COALESCE(TO_CHAR(v.proxima_evaluacion, 'DD/MM/YYYY HH24:MI'), '-')
                  FROM v_dashboard_proyectos v JOIN proyectos p ON p.id = v.id
                 WHERE (?::int IS NULL OR p.gestion_id = ?::int)
                 ORDER BY v.codigo
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            gestion(ps, gestionId, 2);
            return DProyecto.filas(ps, 7);
        }
    }

    // ================================================================== calendario

    /**
     * Todo lo que ocurre en el mes: día (1-31), hora, tipo, detalle, id del evento (o vacío).
     * Tipos: evento, evaluación, vencimiento, feriado, límite de perfil.
     */
    public List<String[]> eventosMes(int anio, int mes) throws SQLException {
        String sql = """
                WITH rango AS (SELECT make_date(?, ?, 1) AS ini, (make_date(?, ?, 1) + INTERVAL '1 month')::date AS fin)
                SELECT EXTRACT(DAY FROM x.dia)::text, x.hora, x.tipo, x.detalle, x.id FROM (
                    SELECT ev.inicio::date AS dia, TO_CHAR(ev.inicio, 'HH24:MI') AS hora,
                           CASE WHEN ev.descripcion LIKE 'evaluacion:%' THEN 'evaluación' ELSE 'evento' END AS tipo,
                           ev.titulo || CASE WHEN ev.descripcion IS NULL OR ev.descripcion LIKE 'evaluacion:%' THEN ''
                                             ELSE ' · ' || ev.descripcion END AS detalle,
                           ev.id::text AS id, ev.inicio AS orden
                      FROM eventos_calendario ev, rango WHERE ev.inicio >= rango.ini AND ev.inicio < rango.fin
                    UNION ALL
                    SELECT r.fecha_vencimiento, '', 'vencimiento',
                           'Vence ronda ' || r.numero || ' de ' || p.codigo, '', r.fecha_vencimiento + TIME '23:59'
                      FROM rondas_revision r JOIN proyectos p ON p.id = r.proyecto_id, rango
                     WHERE r.estado IN ('en_revision','vencida') AND r.fecha_vencimiento >= rango.ini AND r.fecha_vencimiento < rango.fin
                    UNION ALL
                    SELECT f.fecha, '', 'feriado', f.descripcion, '', f.fecha::timestamp
                      FROM feriados f, rango WHERE f.fecha >= rango.ini AND f.fecha < rango.fin
                    UNION ALL
                    SELECT g.fecha_limite_perfil, '', 'límite de perfil', 'Fecha límite de perfiles · gestión ' || g.codigo, '',
                           g.fecha_limite_perfil + TIME '23:58'
                      FROM gestiones g, rango WHERE g.fecha_limite_perfil >= rango.ini AND g.fecha_limite_perfil < rango.fin
                ) x ORDER BY x.orden
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, anio);
            ps.setInt(2, mes);
            ps.setInt(3, anio);
            ps.setInt(4, mes);
            return DProyecto.filas(ps, 5);
        }
    }

    public long insertarEvento(int gestionId, String titulo, String descripcion, LocalDateTime inicio, LocalDateTime fin,
                               long usuarioId) throws SQLException {
        String sql = """
                INSERT INTO eventos_calendario (gestion_id, titulo, descripcion, inicio, fin, creado_por)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, gestionId);
            ps.setString(2, titulo);
            if (descripcion == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, descripcion);
            ps.setTimestamp(4, Timestamp.valueOf(inicio));
            if (fin == null) ps.setNull(5, Types.TIMESTAMP); else ps.setTimestamp(5, Timestamp.valueOf(fin));
            ps.setLong(6, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Evento: [titulo, descripcion, inicio dd/mm/aaaa hh:mm] o null. */
    public String[] evento(long id) throws SQLException {
        String sql = "SELECT titulo, COALESCE(descripcion, ''), TO_CHAR(inicio, 'DD/MM/YYYY HH24:MI') FROM eventos_calendario WHERE id = ?";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3)} : null;
            }
        }
    }

    public void eliminarEvento(long id) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("DELETE FROM eventos_calendario WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    // ================================================================== notificaciones

    /** Notificaciones del usuario: id, fecha, título, mensaje, comando, leída. */
    public List<String[]> notificaciones(long usuarioId, boolean soloNoLeidas, int limite) throws SQLException {
        String sql = """
                SELECT n.id::text, TO_CHAR(n.created_at, 'DD/MM/YYYY HH24:MI'), n.titulo, n.mensaje, COALESCE(n.url, '-'),
                       CASE WHEN n.leida_en IS NULL THEN 'Nueva' ELSE 'Leída' END
                  FROM notificaciones n
                 WHERE n.usuario_id = ? AND (NOT ? OR n.leida_en IS NULL)
                 ORDER BY n.created_at DESC, n.id DESC LIMIT ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, usuarioId);
            ps.setBoolean(2, soloNoLeidas);
            ps.setInt(3, limite);
            return DProyecto.filas(ps, 6);
        }
    }

    public int noLeidas(long usuarioId) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT COUNT(*) FROM notificaciones WHERE usuario_id = ? AND leida_en IS NULL")) {
            ps.setLong(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void marcarLeidas(long usuarioId, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) return;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(
                     "UPDATE notificaciones SET leida_en = CURRENT_TIMESTAMP WHERE usuario_id = ? AND id = ANY (?) AND leida_en IS NULL")) {
            ps.setLong(1, usuarioId);
            ps.setArray(2, cn.createArrayOf("bigint", ids.toArray()));
            ps.executeUpdate();
        }
    }

    // ================================================================== seguimiento

    /** Observaciones pendientes del proyecto: origen, quién, observación, fecha. */
    public List<String[]> observacionesPendientes(long proyectoId, String etapa) throws SQLException {
        String sql = """
                SELECT 'Ronda ' || r.numero || ' de revisión', u.nombres || ' ' || u.apellidos, rt.observaciones,
                       TO_CHAR(rt.fecha_revision, 'DD/MM/YYYY')
                  FROM rondas_revision r
                  JOIN revisiones_tribunal rt ON rt.ronda_id = r.id AND rt.estado = 'observado'
                  JOIN designaciones_tribunal dt ON dt.id = rt.designacion_id
                  JOIN usuarios u ON u.id = dt.docente_id
                 WHERE r.proyecto_id = ? AND r.estado = 'con_observaciones' AND ? = 'revision'
                   AND r.numero = (SELECT MAX(numero) FROM rondas_revision WHERE proyecto_id = ?)
                UNION ALL
                SELECT 'Viabilidad del perfil v' || f.version, u.nombres || ' ' || u.apellidos, v.observaciones,
                       TO_CHAR(v.fecha, 'DD/MM/YYYY')
                  FROM evaluaciones_viabilidad v JOIN perfiles f ON f.id = v.perfil_id JOIN usuarios u ON u.id = v.revisante_id
                 WHERE f.proyecto_id = ? AND v.resultado = 'observado' AND ? = 'perfil'
                   AND f.version = (SELECT MAX(version) FROM perfiles WHERE proyecto_id = ? AND estado = 'observado')
                UNION ALL
                SELECT INITCAP(e.tipo) || ' intento ' || e.intento, 'Tribunal', e.observaciones, TO_CHAR(e.fecha_hora, 'DD/MM/YYYY')
                  FROM evaluaciones e
                 WHERE e.proyecto_id = ? AND e.resultado IN ('repetir','reprobado') AND e.tipo = ?
                   AND e.observaciones IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM evaluaciones x WHERE x.proyecto_id = e.proyecto_id AND x.tipo = e.tipo AND x.intento > e.intento)
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            ps.setString(2, etapa);
            ps.setLong(3, proyectoId);
            ps.setLong(4, proyectoId);
            ps.setString(5, etapa);
            ps.setLong(6, proyectoId);
            ps.setLong(7, proyectoId);
            ps.setString(8, etapa);
            return DProyecto.filas(ps, 4);
        }
    }

    /** Próximas fechas del proyecto: fecha, qué. */
    public List<String[]> proximasFechas(long proyectoId) throws SQLException {
        String sql = """
                SELECT f, que FROM (
                    SELECT TO_CHAR(r.fecha_vencimiento, 'DD/MM/YYYY') AS f, 'Vence la ronda ' || r.numero || ' de revisión' AS que,
                           r.fecha_vencimiento::timestamp AS orden
                      FROM rondas_revision r WHERE r.proyecto_id = ? AND r.estado IN ('en_revision','vencida')
                    UNION ALL
                    SELECT TO_CHAR(e.fecha_hora, 'DD/MM/YYYY HH24:MI'), INITCAP(e.tipo) || ' en ' || a.nombre, e.fecha_hora
                      FROM evaluaciones e JOIN ambientes a ON a.id = e.ambiente_id
                     WHERE e.proyecto_id = ? AND e.estado IN ('programada','reprogramada')
                    UNION ALL
                    SELECT TO_CHAR(g.fecha_limite_perfil, 'DD/MM/YYYY'), 'Fecha límite para registrar el perfil', g.fecha_limite_perfil
                      FROM proyectos p JOIN gestiones g ON g.id = p.gestion_id
                     WHERE p.id = ? AND p.etapa_actual IN ('tutor','perfil') AND g.fecha_limite_perfil >= CURRENT_DATE
                ) x ORDER BY orden
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            ps.setLong(2, proyectoId);
            ps.setLong(3, proyectoId);
            return DProyecto.filas(ps, 2);
        }
    }

    /** Nota final de la defensa aprobada, o null. */
    public String notaFinal(long proyectoId) throws SQLException {
        String sql = """
                SELECT nota_final::text FROM evaluaciones
                 WHERE proyecto_id = ? AND tipo = 'defensa' AND resultado IN ('aprobado','aprobado_obs')
                 ORDER BY intento DESC LIMIT 1
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Último proyecto del estudiante (vigente primero, luego concluido o cancelado), o null. */
    public String codigoDeEstudiante(long estudianteId) throws SQLException {
        String sql = """
                SELECT p.codigo FROM proyectos p JOIN proyecto_estudiante pe ON pe.proyecto_id = p.id
                 WHERE pe.estudiante_id = ?
                 ORDER BY (p.etapa_actual = 'cancelado'), p.created_at DESC LIMIT 1
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, estudianteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void gestion(PreparedStatement ps, Integer gestionId, int veces) throws SQLException {
        for (int i = 1; i <= veces; i++) {
            if (gestionId == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, gestionId);
        }
    }
}