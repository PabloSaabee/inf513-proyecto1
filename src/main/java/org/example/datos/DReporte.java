package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Consultas de solo lectura para CU8 (ReporteService). Todas filtran por gestión y área (null = todas).
 */
public class DReporte {

    /** Filtro común sobre la tabla proyectos con alias p (4 parámetros). */
    private static final String FILTRO = " (?::int IS NULL OR p.gestion_id = ?::int) AND (?::int IS NULL OR p.area_id = ?::int) ";

    /** REPRES · indicador, valor. */
    public List<String[]> resumen(Integer g, Integer a) throws SQLException {
        String sql = """
                WITH pr AS (SELECT p.* FROM proyectos p WHERE %s),
                     ev AS (SELECT e.* FROM evaluaciones e JOIN pr ON pr.id = e.proyecto_id WHERE e.estado = 'realizada'),
                     ro AS (SELECT r.* FROM rondas_revision r JOIN pr ON pr.id = r.proyecto_id)
                SELECT x.indicador, x.valor FROM (VALUES
                  (1, 'Proyectos registrados', (SELECT COUNT(*) FROM pr)::text),
                  (2, 'Proyectos activos', (SELECT COUNT(*) FROM pr WHERE etapa_actual NOT IN ('concluido','cancelado'))::text),
                  (3, 'Proyectos concluidos (titulados)', (SELECT COUNT(*) FROM pr WHERE etapa_actual = 'concluido')::text),
                  (4, 'Proyectos cancelados (no viables)', (SELECT COUNT(*) FROM pr WHERE etapa_actual = 'cancelado')::text),
                  (5, 'Tasa de titulación', COALESCE(ROUND(100.0 * (SELECT COUNT(*) FROM pr WHERE etapa_actual = 'concluido')
                        / NULLIF((SELECT COUNT(*) FROM pr), 0), 1) || ' %%', '-')),
                  (6, 'Estudiantes en proyectos', (SELECT COUNT(DISTINCT pe.estudiante_id) FROM proyecto_estudiante pe
                        JOIN pr ON pr.id = pe.proyecto_id)::text),
                  (7, 'Solicitudes de habilitación', (SELECT COUNT(*) FROM solicitudes_habilitacion s
                        WHERE (?::int IS NULL OR s.gestion_id = ?::int))::text),
                  (8, 'Solicitudes habilitadas', (SELECT COUNT(*) FROM solicitudes_habilitacion s
                        WHERE s.estado = 'habilitada' AND (?::int IS NULL OR s.gestion_id = ?::int))::text),
                  (9, 'Rondas de revisión', (SELECT COUNT(*) FROM ro)::text),
                  (10, 'Rondas promedio por proyecto revisado', COALESCE((SELECT ROUND(COUNT(*)::numeric / NULLIF(COUNT(DISTINCT proyecto_id), 0), 2)
                        FROM ro)::text, '-')),
                  (11, 'Predefensas realizadas', (SELECT COUNT(*) FROM ev WHERE tipo = 'predefensa')::text),
                  (12, 'Nota promedio de predefensa', COALESCE((SELECT ROUND(AVG(nota_final), 2) FROM ev WHERE tipo = 'predefensa')::text, '-')),
                  (13, 'Defensas realizadas', (SELECT COUNT(*) FROM ev WHERE tipo = 'defensa')::text),
                  (14, 'Nota promedio de defensa final', COALESCE((SELECT ROUND(AVG(nota_final), 2) FROM ev WHERE tipo = 'defensa')::text, '-'))
                ) AS x(orden, indicador, valor) ORDER BY x.orden
                """.formatted(FILTRO);
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            filtro(ps, 1, g, a);
            for (int i = 5; i <= 8; i++) entero(ps, i, g);
            return DProyecto.filas(ps, 2);
        }
    }

    /** REPARE · área + cantidad por cada etapa + total. */
    public List<String[]> porAreaEtapa(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT COALESCE(ar.nombre, 'Sin área'),
                       COUNT(*) FILTER (WHERE p.etapa_actual = 'tutor')::text, COUNT(*) FILTER (WHERE p.etapa_actual = 'perfil')::text,
                       COUNT(*) FILTER (WHERE p.etapa_actual = 'viabilidad')::text, COUNT(*) FILTER (WHERE p.etapa_actual = 'tribunales')::text,
                       COUNT(*) FILTER (WHERE p.etapa_actual = 'revision')::text, COUNT(*) FILTER (WHERE p.etapa_actual = 'predefensa')::text,
                       COUNT(*) FILTER (WHERE p.etapa_actual = 'defensa')::text, COUNT(*) FILTER (WHERE p.etapa_actual = 'concluido')::text,
                       COUNT(*) FILTER (WHERE p.etapa_actual = 'cancelado')::text, COUNT(*)::text
                  FROM proyectos p LEFT JOIN areas_conocimiento ar ON ar.id = p.area_id
                 WHERE %s
                 GROUP BY ar.nombre ORDER BY ar.nombre
                """.formatted(FILTRO);
        return consulta(sql, 11, g, a);
    }

    /** REPARE · listado de proyectos: código, gestión, área, título, etapa, estudiantes, tutor. */
    public List<String[]> proyectos(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT p.codigo, gs.codigo, COALESCE(ar.nombre, '-'), COALESCE(p.titulo, '-'), p.etapa_actual,
                       COALESCE((SELECT STRING_AGG(u.nombres || ' ' || u.apellidos, ', ') FROM proyecto_estudiante pe
                                   JOIN usuarios u ON u.id = pe.estudiante_id WHERE pe.proyecto_id = p.id), '-'),
                       COALESCE((SELECT u.nombres || ' ' || u.apellidos FROM asignaciones_tutor t JOIN usuarios u ON u.id = t.docente_id
                                  WHERE t.proyecto_id = p.id AND t.estado = 'aceptada' LIMIT 1), '-')
                  FROM proyectos p JOIN gestiones gs ON gs.id = p.gestion_id LEFT JOIN areas_conocimiento ar ON ar.id = p.area_id
                 WHERE %s ORDER BY p.codigo
                """.formatted(FILTRO);
        return consulta(sql, 7, g, a);
    }

    /** REPVIA · resultado, cantidad, porcentaje. */
    public List<String[]> viabilidadResumen(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT REPLACE(v.resultado, '_', ' '), COUNT(*)::text,
                       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) || ' %%'
                  FROM evaluaciones_viabilidad v JOIN perfiles f ON f.id = v.perfil_id JOIN proyectos p ON p.id = f.proyecto_id
                 WHERE %s GROUP BY v.resultado ORDER BY COUNT(*) DESC
                """.formatted(FILTRO);
        return consulta(sql, 3, g, a);
    }

    /** REPVIA · proyecto, perfil, resultado, revisante, observaciones, fecha. */
    public List<String[]> viabilidadDetalle(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT p.codigo, 'v' || f.version, REPLACE(v.resultado, '_', ' '), u.nombres || ' ' || u.apellidos,
                       COALESCE(v.observaciones, '-'), TO_CHAR(v.fecha, 'DD/MM/YYYY')
                  FROM evaluaciones_viabilidad v JOIN perfiles f ON f.id = v.perfil_id JOIN proyectos p ON p.id = f.proyecto_id
                  JOIN usuarios u ON u.id = v.revisante_id
                 WHERE %s ORDER BY v.fecha
                """.formatted(FILTRO);
        return consulta(sql, 6, g, a);
    }

    /**
     * REPREV · proyecto, ronda, enviada, vence, cerrada, estado, cumplimiento.
     * Una ronda se cierra cuando responde el último tribunal; cumplió si fue hasta el vencimiento.
     */
    public List<String[]> rondas(Integer g, Integer a) throws SQLException {
        String sql = """
                WITH c AS (SELECT r.*, (SELECT MAX(rt.fecha_revision) FROM revisiones_tribunal rt WHERE rt.ronda_id = r.id) AS cierre
                             FROM rondas_revision r JOIN proyectos p ON p.id = r.proyecto_id WHERE %s)
                SELECT (SELECT codigo FROM proyectos WHERE id = c.proyecto_id), c.numero::text, TO_CHAR(c.fecha_envio, 'DD/MM/YYYY'),
                       TO_CHAR(c.fecha_vencimiento, 'DD/MM/YYYY'),
                       CASE WHEN c.estado IN ('con_observaciones','aprobada') THEN COALESCE(TO_CHAR(c.cierre, 'DD/MM/YYYY'), '-') ELSE '-' END,
                       REPLACE(REPLACE(c.estado, 'en_revision', 'en revisión'), '_', ' '),
                       CASE WHEN c.estado IN ('en_revision','vencida')
                                 THEN CASE WHEN c.fecha_vencimiento < CURRENT_DATE THEN 'Vencida' ELSE 'En curso' END
                            WHEN c.cierre::date <= c.fecha_vencimiento THEN 'A tiempo' ELSE 'Fuera de plazo' END
                  FROM c ORDER BY c.proyecto_id, c.numero
                """.formatted(FILTRO);
        return consulta(sql, 7, g, a);
    }

    /** REPREV · tribunal, revisiones respondidas, a tiempo, fuera de plazo, días promedio de respuesta. */
    public List<String[]> respuestaTribunales(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT u.nombres || ' ' || u.apellidos,
                       COUNT(*) FILTER (WHERE rt.fecha_revision IS NOT NULL)::text,
                       COUNT(*) FILTER (WHERE rt.fecha_revision::date <= r.fecha_vencimiento)::text,
                       COUNT(*) FILTER (WHERE rt.fecha_revision::date > r.fecha_vencimiento
                                           OR (rt.fecha_revision IS NULL AND r.fecha_vencimiento < CURRENT_DATE))::text,
                       COALESCE(ROUND(AVG(EXTRACT(EPOCH FROM (rt.fecha_revision - r.fecha_envio)) / 86400)
                                FILTER (WHERE rt.fecha_revision IS NOT NULL), 1)::text, '-')
                  FROM revisiones_tribunal rt
                  JOIN rondas_revision r ON r.id = rt.ronda_id
                  JOIN designaciones_tribunal dt ON dt.id = rt.designacion_id
                  JOIN usuarios u ON u.id = dt.docente_id
                  JOIN proyectos p ON p.id = r.proyecto_id
                 WHERE %s AND NOT (rt.observaciones IS NOT DISTINCT FROM 'Visto bueno conservado de la ronda anterior')
                 GROUP BY u.id, u.nombres, u.apellidos ORDER BY u.apellidos
                """.formatted(FILTRO);
        return consulta(sql, 5, g, a);
    }

    /** REPDEF · tipo, programadas, realizadas, aprobadas, no aprobadas, nota promedio, nota mínima, nota máxima. */
    public List<String[]> evaluacionesResumen(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT INITCAP(e.tipo),
                       COUNT(*) FILTER (WHERE e.estado IN ('programada','reprogramada'))::text,
                       COUNT(*) FILTER (WHERE e.estado = 'realizada')::text,
                       COUNT(*) FILTER (WHERE e.resultado IN ('aprobado','aprobado_obs'))::text,
                       COUNT(*) FILTER (WHERE e.resultado IN ('repetir','reprobado'))::text,
                       COALESCE(ROUND(AVG(e.nota_final), 2)::text, '-'), COALESCE(MIN(e.nota_final)::text, '-'),
                       COALESCE(MAX(e.nota_final)::text, '-')
                  FROM evaluaciones e JOIN proyectos p ON p.id = e.proyecto_id
                 WHERE %s GROUP BY e.tipo ORDER BY e.tipo DESC
                """.formatted(FILTRO);
        return consulta(sql, 8, g, a);
    }

    /** REPDEF · proyecto, tipo, intento, fecha, ambiente, estado, resultado, nota. */
    public List<String[]> evaluacionesDetalle(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT p.codigo, e.tipo, e.intento::text, TO_CHAR(e.fecha_hora, 'DD/MM/YYYY HH24:MI'), am.nombre, e.estado,
                       COALESCE(REPLACE(e.resultado, '_', ' '), '-'), COALESCE(e.nota_final::text, '-')
                  FROM evaluaciones e JOIN proyectos p ON p.id = e.proyecto_id JOIN ambientes am ON am.id = e.ambiente_id
                 WHERE %s ORDER BY e.fecha_hora
                """.formatted(FILTRO);
        return consulta(sql, 8, g, a);
    }

    /**
     * REPDUR · etapa, proyectos que la completaron, días promedio, mínimo, máximo.
     * Duración = desde que la etapa se inició por primera vez hasta que se completó.
     */
    public List<String[]> duracionEtapas(Integer g, Integer a) throws SQLException {
        String sql = """
                WITH d AS (
                    SELECT h.proyecto_id, h.etapa,
                           EXTRACT(EPOCH FROM (MAX(h.created_at) FILTER (WHERE h.estado = 'completada')
                                             - MIN(h.created_at) FILTER (WHERE h.estado = 'iniciada'))) / 86400 AS dias
                      FROM historial_etapas h JOIN proyectos p ON p.id = h.proyecto_id
                     WHERE %s GROUP BY h.proyecto_id, h.etapa)
                SELECT e.etapa, COUNT(d.dias)::text, COALESCE(ROUND(AVG(d.dias)::numeric, 1)::text, '-'),
                       COALESCE(ROUND(MIN(d.dias)::numeric, 1)::text, '-'), COALESCE(ROUND(MAX(d.dias)::numeric, 1)::text, '-')
                  FROM unnest(ARRAY['tutor','perfil','viabilidad','tribunales','revision','predefensa','defensa'])
                       WITH ORDINALITY AS e(etapa, orden)
                  LEFT JOIN d ON d.etapa = e.etapa AND d.dias IS NOT NULL AND d.dias >= 0
                 GROUP BY e.etapa, e.orden ORDER BY e.orden
                """.formatted(FILTRO);
        return consulta(sql, 5, g, a);
    }

    /** REPDUR · duración total de los proyectos concluidos: proyecto, inicio, conclusión, días. */
    public List<String[]> duracionTotal(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT p.codigo, TO_CHAR(p.created_at, 'DD/MM/YYYY'), TO_CHAR(MAX(h.created_at), 'DD/MM/YYYY'),
                       ROUND(EXTRACT(EPOCH FROM (MAX(h.created_at) - p.created_at)) / 86400)::text
                  FROM proyectos p JOIN historial_etapas h ON h.proyecto_id = p.id AND h.etapa = 'concluido'
                 WHERE %s GROUP BY p.id, p.codigo, p.created_at ORDER BY p.codigo
                """.formatted(FILTRO);
        return consulta(sql, 4, g, a);
    }

    /**
     * REPDOC · docente, tutorías vigentes, tutorías totales, tribunal vigente, tribunal total,
     * viabilidades evaluadas, revisiones respondidas, calificaciones.
     */
    public List<String[]> cargaDocente(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos,
                  (SELECT COUNT(*) FROM asignaciones_tutor t JOIN proyectos p ON p.id = t.proyecto_id
                    WHERE t.docente_id = u.id AND t.estado = 'aceptada' AND p.etapa_actual NOT IN ('concluido','cancelado') AND %1$s)::text,
                  (SELECT COUNT(*) FROM asignaciones_tutor t JOIN proyectos p ON p.id = t.proyecto_id
                    WHERE t.docente_id = u.id AND t.estado = 'aceptada' AND %1$s)::text,
                  (SELECT COUNT(*) FROM designaciones_tribunal dt JOIN proyectos p ON p.id = dt.proyecto_id
                    WHERE dt.docente_id = u.id AND dt.estado = 'aceptada' AND p.etapa_actual NOT IN ('concluido','cancelado') AND %1$s)::text,
                  (SELECT COUNT(*) FROM designaciones_tribunal dt JOIN proyectos p ON p.id = dt.proyecto_id
                    WHERE dt.docente_id = u.id AND dt.estado = 'aceptada' AND %1$s)::text,
                  (SELECT COUNT(*) FROM evaluaciones_viabilidad v JOIN perfiles f ON f.id = v.perfil_id
                     JOIN proyectos p ON p.id = f.proyecto_id WHERE v.revisante_id = u.id AND %1$s)::text,
                  (SELECT COUNT(*) FROM revisiones_tribunal rt JOIN designaciones_tribunal dt ON dt.id = rt.designacion_id
                     JOIN proyectos p ON p.id = dt.proyecto_id WHERE dt.docente_id = u.id AND rt.fecha_revision IS NOT NULL
                       AND rt.observaciones IS DISTINCT FROM 'Visto bueno conservado de la ronda anterior' AND %1$s)::text,
                  (SELECT COUNT(*) FROM calificaciones c JOIN designaciones_tribunal dt ON dt.id = c.designacion_id
                     JOIN proyectos p ON p.id = dt.proyecto_id WHERE dt.docente_id = u.id AND %1$s)::text
                  FROM docentes d JOIN usuarios u ON u.id = d.usuario_id
                 WHERE u.estado = 'activo'
                 ORDER BY u.apellidos, u.nombres
                """.formatted(FILTRO);
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < 7; i++) filtro(ps, 1 + i * 4, g, a);
            return DProyecto.filas(ps, 8);
        }
    }

    /** REPTIT · código, gestión, área, título, estudiantes (registro), tutor, fecha de defensa, nota final. */
    public List<String[]> titulados(Integer g, Integer a) throws SQLException {
        String sql = """
                SELECT p.codigo, gs.codigo, COALESCE(ar.nombre, '-'), COALESCE(p.titulo, '-'),
                       (SELECT STRING_AGG(u.nombres || ' ' || u.apellidos || ' (' || e.registro_universitario || ')', ', ')
                          FROM proyecto_estudiante pe JOIN usuarios u ON u.id = pe.estudiante_id
                          JOIN estudiantes e ON e.usuario_id = u.id WHERE pe.proyecto_id = p.id),
                       COALESCE((SELECT u.nombres || ' ' || u.apellidos FROM asignaciones_tutor t JOIN usuarios u ON u.id = t.docente_id
                                  WHERE t.proyecto_id = p.id AND t.estado = 'aceptada' LIMIT 1), '-'),
                       COALESCE(TO_CHAR(ev.fecha_hora, 'DD/MM/YYYY'), '-'), COALESCE(ev.nota_final::text, '-')
                  FROM proyectos p
                  JOIN gestiones gs ON gs.id = p.gestion_id
                  LEFT JOIN areas_conocimiento ar ON ar.id = p.area_id
                  LEFT JOIN LATERAL (SELECT e.fecha_hora, e.nota_final FROM evaluaciones e WHERE e.proyecto_id = p.id
                                        AND e.tipo = 'defensa' AND e.resultado IN ('aprobado','aprobado_obs')
                                      ORDER BY e.intento DESC LIMIT 1) ev ON TRUE
                 WHERE p.etapa_actual = 'concluido' AND %s
                 ORDER BY ev.fecha_hora DESC NULLS LAST
                """.formatted(FILTRO);
        return consulta(sql, 8, g, a);
    }

    // ================================================================== utilidades

    private static List<String[]> consulta(String sql, int columnas, Integer g, Integer a) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            filtro(ps, 1, g, a);
            return DProyecto.filas(ps, columnas);
        }
    }

    private static void filtro(PreparedStatement ps, int desde, Integer g, Integer a) throws SQLException {
        entero(ps, desde, g);
        entero(ps, desde + 1, g);
        entero(ps, desde + 2, a);
        entero(ps, desde + 3, a);
    }

    private static void entero(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, v);
    }
}