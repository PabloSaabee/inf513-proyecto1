package org.example.datos;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a documentos, documento_versiones, rondas_revision, revisiones_tribunal y feriados (CU5).
 *
 * Las versiones nunca se sobrescriben (RF-11): cada envío agrega una fila nueva.
 * El vencimiento se calcula en la base con fn_sumar_dias_habiles (sin sábados, domingos ni feriados).
 */
public class DRevision {

    public record Ronda(long id, long proyectoId, int numero, String estado, LocalDate vencimiento,
                        int plazoDias, int diasRestantes) { }

    public record Revision(long id, long rondaId, long designacionId, long docenteId, String docente,
                           int posicion, String estado, boolean vistoBueno, boolean recibido) { }

    /** Días hábiles entre hoy y la fecha (negativo si ya pasó). PlazoService.diasHabilesRestantes. */
    private static final String DIAS_RESTANTES = """
            CASE WHEN r.fecha_vencimiento >= CURRENT_DATE
                 THEN (SELECT COUNT(*) FROM generate_series(CURRENT_DATE + 1, r.fecha_vencimiento, INTERVAL '1 day') d
                        WHERE EXTRACT(ISODOW FROM d) < 6 AND d::date NOT IN (SELECT fecha FROM feriados))
                 ELSE -(SELECT COUNT(*) FROM generate_series(r.fecha_vencimiento + 1, CURRENT_DATE, INTERVAL '1 day') d
                        WHERE EXTRACT(ISODOW FROM d) < 6 AND d::date NOT IN (SELECT fecha FROM feriados))
            END
            """;

    // ================================================================== rondas

    /** Las rondas abiertas cuyo vencimiento ya pasó quedan en estado vencida (CU5-E1). */
    public void marcarVencidas() throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(
                     "UPDATE rondas_revision SET estado = 'vencida' WHERE estado = 'en_revision' AND fecha_vencimiento < CURRENT_DATE")) {
            ps.executeUpdate();
        }
    }

    /** Última ronda del proyecto, o null. */
    public Ronda ultima(long proyectoId) throws SQLException {
        String sql = "SELECT r.id, r.proyecto_id, r.numero, r.estado, r.fecha_vencimiento, r.plazo_dias_habiles, "
                + DIAS_RESTANTES + " FROM rondas_revision r WHERE r.proyecto_id = ? ORDER BY r.numero DESC LIMIT 1";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Ronda(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4),
                        rs.getDate(5).toLocalDate(), rs.getInt(6), rs.getInt(7));
            }
        }
    }

    /** Rondas del proyecto: ronda, documento, carta, enviada, plazo, vence, días restantes, estado. */
    public List<String[]> rondas(long proyectoId) throws SQLException {
        String sql = "SELECT r.numero::text, COALESCE(av.ruta, ''), COALESCE(ac.ruta, ''), "
                + "TO_CHAR(r.fecha_envio, 'DD/MM/YYYY'), r.plazo_dias_habiles || ' días hábiles', "
                + "TO_CHAR(r.fecha_vencimiento, 'DD/MM/YYYY'), "
                + "CASE WHEN r.estado IN ('en_revision','vencida') THEN (" + DIAS_RESTANTES + ")::text ELSE '-' END, "
                + "REPLACE(REPLACE(r.estado, 'en_revision', 'en revisión'), '_', ' ') "
                + """
                  FROM rondas_revision r
                  JOIN documento_versiones v ON v.id = r.version_id
                  JOIN archivos av ON av.id = v.archivo_id
                  LEFT JOIN documento_versiones c ON c.id = r.carta_correcciones_version_id
                  LEFT JOIN archivos ac ON ac.id = c.archivo_id
                 WHERE r.proyecto_id = ?
                 ORDER BY r.numero DESC
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            return DProyecto.filas(ps, 8);
        }
    }

    /** Detalle por tribunal de una ronda: posición, docente, recepción, estado, observaciones, documento, fecha. */
    public List<String[]> detalleRonda(long rondaId) throws SQLException {
        String sql = """
                SELECT dt.posicion::text, COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos,
                       COALESCE(TO_CHAR(rt.fecha_recepcion, 'DD/MM/YYYY'), '-'), REPLACE(rt.estado, '_', ' '),
                       COALESCE(rt.observaciones, '-'), COALESCE(a.ruta, ''),
                       COALESCE(TO_CHAR(rt.fecha_revision, 'DD/MM/YYYY'), '-')
                  FROM revisiones_tribunal rt
                  JOIN designaciones_tribunal dt ON dt.id = rt.designacion_id
                  JOIN usuarios u ON u.id = dt.docente_id
                  LEFT JOIN docentes d ON d.usuario_id = dt.docente_id
                  LEFT JOIN archivos a ON a.id = rt.documento_revision_archivo_id
                 WHERE rt.ronda_id = ? AND dt.estado = 'aceptada'
                 ORDER BY dt.posicion
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, rondaId);
            return DProyecto.filas(ps, 7);
        }
    }

    /** Control de plazos (Coordinación): rondas abiertas de todos los proyectos. */
    public List<String[]> rondasAbiertas() throws SQLException {
        String sql = "SELECT p.codigo, r.numero::text, TO_CHAR(r.fecha_envio, 'DD/MM/YYYY'), "
                + "TO_CHAR(r.fecha_vencimiento, 'DD/MM/YYYY'), (" + DIAS_RESTANTES + ")::text, "
                + """
                       (SELECT COUNT(*) FROM revisiones_tribunal x JOIN designaciones_tribunal dx ON dx.id = x.designacion_id
                         WHERE x.ronda_id = r.id AND dx.estado = 'aceptada' AND x.estado IN ('observado','visto_bueno')) || '/3',
                       COALESCE((SELECT STRING_AGG(u.nombres || ' ' || u.apellidos, ', ' ORDER BY dx.posicion)
                                   FROM revisiones_tribunal x JOIN designaciones_tribunal dx ON dx.id = x.designacion_id
                                   JOIN usuarios u ON u.id = dx.docente_id
                                  WHERE x.ronda_id = r.id AND dx.estado = 'aceptada'
                                    AND x.estado IN ('pendiente_recepcion','recibido')), '-'),
                       REPLACE(REPLACE(r.estado, 'en_revision', 'en revisión'), '_', ' ')
                  FROM rondas_revision r JOIN proyectos p ON p.id = r.proyecto_id
                 WHERE r.estado IN ('en_revision','vencida')
                 ORDER BY r.fecha_vencimiento
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            return DProyecto.filas(ps, 8);
        }
    }

    /** Revisiones de la ronda de tribunales vigentes (aceptados). */
    public List<Revision> revisiones(long rondaId) throws SQLException {
        String sql = """
                SELECT rt.id, rt.ronda_id, rt.designacion_id, dt.docente_id,
                       COALESCE(d.titulo || ' ', '') || u.nombres || ' ' || u.apellidos, dt.posicion,
                       rt.estado, rt.visto_bueno, rt.fecha_recepcion IS NOT NULL
                  FROM revisiones_tribunal rt
                  JOIN designaciones_tribunal dt ON dt.id = rt.designacion_id
                  JOIN usuarios u ON u.id = dt.docente_id
                  LEFT JOIN docentes d ON d.usuario_id = dt.docente_id
                 WHERE rt.ronda_id = ? AND dt.estado = 'aceptada'
                 ORDER BY dt.posicion
                """;
        List<Revision> l = new ArrayList<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, rondaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(new Revision(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                            rs.getInt(6), rs.getString(7), rs.getBoolean(8), rs.getBoolean(9)));
                }
            }
        }
        return l;
    }

    /** Designaciones aceptadas que dieron visto bueno en la ronda (para conservarlo en la siguiente, S1). */
    public List<Long> designacionesConVistoBueno(long rondaId) throws SQLException {
        List<Long> l = new ArrayList<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT designacion_id FROM revisiones_tribunal WHERE ronda_id = ? AND visto_bueno")) {
            ps.setLong(1, rondaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) l.add(rs.getLong(1));
            }
        }
        return l;
    }

    /**
     * Crea la versión del documento y la ronda con sus revisiones, en una transacción.
     * @return número de la ronda creada
     */
    public int crearRonda(long proyectoId, long archivoDocumento, Long archivoCarta, int plazoDias,
                          List<Long> designaciones, List<Long> conVistoBueno, String comentario) throws SQLException {
        int[] numero = new int[1];
        Transaccion.ejecutar(cn -> {
            long version = nuevaVersion(cn, proyectoId, "borrador_proyecto", "Documento del proyecto", archivoDocumento, comentario);
            Long carta = archivoCarta == null ? null
                    : nuevaVersion(cn, proyectoId, "carta_correcciones", "Carta de correcciones", archivoCarta, comentario);
            long rondaId;
            try (PreparedStatement ps = cn.prepareStatement("""
                    INSERT INTO rondas_revision (proyecto_id, numero, version_id, carta_correcciones_version_id,
                                                 plazo_dias_habiles, fecha_vencimiento, estado)
                    VALUES (?, (SELECT COALESCE(MAX(numero), 0) + 1 FROM rondas_revision WHERE proyecto_id = ?),
                            ?, ?, ?, fn_sumar_dias_habiles(CURRENT_DATE, ?), 'en_revision')
                    RETURNING id, numero
                    """)) {
                ps.setLong(1, proyectoId);
                ps.setLong(2, proyectoId);
                ps.setLong(3, version);
                if (carta == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, carta);
                ps.setInt(5, plazoDias);
                ps.setInt(6, plazoDias);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    rondaId = rs.getLong(1);
                    numero[0] = rs.getInt(2);
                }
            }
            for (long designacion : designaciones) {
                boolean vb = conVistoBueno.contains(designacion);
                agregarRevision(cn, rondaId, designacion, vb);
            }
        });
        return numero[0];
    }

    /** Agrega la revisión de un tribunal a la ronda (si ya tenía visto bueno lo conserva, S1). */
    public void agregarRevision(Connection cn, long rondaId, long designacionId, boolean vistoBuenoPrevio) throws SQLException {
        String sql = vistoBuenoPrevio
                ? """
                  INSERT INTO revisiones_tribunal (ronda_id, designacion_id, fecha_recepcion, visto_bueno, fecha_revision,
                                                   observaciones, estado)
                  VALUES (?, ?, CURRENT_TIMESTAMP, TRUE, CURRENT_TIMESTAMP, 'Visto bueno conservado de la ronda anterior', 'visto_bueno')
                  ON CONFLICT (ronda_id, designacion_id) DO NOTHING
                  """
                : """
                  INSERT INTO revisiones_tribunal (ronda_id, designacion_id, visto_bueno, estado)
                  VALUES (?, ?, FALSE, 'pendiente_recepcion')
                  ON CONFLICT (ronda_id, designacion_id) DO NOTHING
                  """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, rondaId);
            ps.setLong(2, designacionId);
            ps.executeUpdate();
        }
    }

    private long nuevaVersion(Connection cn, long proyectoId, String tipo, String titulo, long archivoId,
                              String comentario) throws SQLException {
        long documentoId;
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT id FROM documentos WHERE proyecto_id = ? AND tipo = ? ORDER BY id LIMIT 1")) {
            ps.setLong(1, proyectoId);
            ps.setString(2, tipo);
            try (ResultSet rs = ps.executeQuery()) {
                documentoId = rs.next() ? rs.getLong(1) : -1;
            }
        }
        if (documentoId < 0) {
            try (PreparedStatement ps = cn.prepareStatement(
                    "INSERT INTO documentos (proyecto_id, tipo, titulo) VALUES (?, ?, ?) RETURNING id")) {
                ps.setLong(1, proyectoId);
                ps.setString(2, tipo);
                ps.setString(3, titulo);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    documentoId = rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = cn.prepareStatement("""
                INSERT INTO documento_versiones (documento_id, numero, archivo_id, comentario)
                VALUES (?, (SELECT COALESCE(MAX(numero), 0) + 1 FROM documento_versiones WHERE documento_id = ?), ?, ?)
                RETURNING id
                """)) {
            ps.setLong(1, documentoId);
            ps.setLong(2, documentoId);
            ps.setLong(3, archivoId);
            ps.setString(4, comentario);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Historial de versiones (RF-11): documento, versión, fecha, comentario, enlace. */
    public List<String[]> versiones(long proyectoId) throws SQLException {
        String sql = """
                SELECT CASE d.tipo WHEN 'borrador_proyecto' THEN 'Documento del proyecto'
                                   WHEN 'carta_correcciones' THEN 'Carta de correcciones' ELSE d.titulo END,
                       'v' || v.numero, TO_CHAR(v.created_at, 'DD/MM/YYYY HH24:MI'), COALESCE(v.comentario, '-'), a.ruta
                  FROM documento_versiones v
                  JOIN documentos d ON d.id = v.documento_id
                  JOIN archivos a ON a.id = v.archivo_id
                 WHERE d.proyecto_id = ?
                 ORDER BY v.created_at DESC, d.tipo
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            return DProyecto.filas(ps, 5);
        }
    }

    // ================================================================== revisiones del tribunal

    public void confirmarRecepcion(long revisionId, Long cartaArchivo) throws SQLException {
        String sql = """
                UPDATE revisiones_tribunal SET fecha_recepcion = CURRENT_TIMESTAMP, carta_recepcion_archivo_id = ?,
                       estado = 'recibido'
                 WHERE id = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            if (cartaArchivo == null) ps.setNull(1, Types.BIGINT); else ps.setLong(1, cartaArchivo);
            ps.setLong(2, revisionId);
            ps.executeUpdate();
        }
    }

    public void observar(Connection cn, long revisionId, String observaciones, Long documento) throws SQLException {
        String sql = """
                UPDATE revisiones_tribunal SET observaciones = ?, documento_revision_archivo_id = ?, visto_bueno = FALSE,
                       fecha_revision = CURRENT_TIMESTAMP, estado = 'observado'
                 WHERE id = ?
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, observaciones);
            if (documento == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, documento);
            ps.setLong(3, revisionId);
            ps.executeUpdate();
        }
    }

    public void darVistoBueno(Connection cn, long revisionId) throws SQLException {
        String sql = """
                UPDATE revisiones_tribunal SET visto_bueno = TRUE, fecha_revision = CURRENT_TIMESTAMP, estado = 'visto_bueno'
                 WHERE id = ?
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, revisionId);
            ps.executeUpdate();
        }
    }

    public void cerrarRonda(Connection cn, long rondaId, String estado) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("UPDATE rondas_revision SET estado = ? WHERE id = ?")) {
            ps.setString(1, estado);
            ps.setLong(2, rondaId);
            ps.executeUpdate();
        }
    }

    /** Suma días hábiles al vencimiento (desde hoy si ya venció) y reabre la ronda. @return nuevo vencimiento */
    public LocalDate ampliar(Connection cn, long rondaId, int dias) throws SQLException {
        String sql = """
                UPDATE rondas_revision
                   SET fecha_vencimiento = fn_sumar_dias_habiles(GREATEST(fecha_vencimiento, CURRENT_DATE), ?),
                       plazo_dias_habiles = LEAST(plazo_dias_habiles + ?, 255), estado = 'en_revision'
                 WHERE id = ?
                RETURNING fecha_vencimiento
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, dias);
            ps.setInt(2, dias);
            ps.setLong(3, rondaId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getDate(1).toLocalDate();
            }
        }
    }

    /** Ronda abierta del proyecto (en_revision o vencida), o null. */
    public Long rondaAbierta(Connection cn, long proyectoId) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT id FROM rondas_revision WHERE proyecto_id = ? AND estado IN ('en_revision','vencida')")) {
            ps.setLong(1, proyectoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    // ================================================================== plazos y feriados

    /** MODPLA: plazo por defecto y fecha límite de perfil (null = no cambia). @return filas afectadas */
    public int definirPlazos(String gestion, int dias, LocalDate limitePerfil) throws SQLException {
        String sql = """
                UPDATE gestiones SET plazo_revision_dias = ?, fecha_limite_perfil = COALESCE(?, fecha_limite_perfil)
                 WHERE codigo = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, dias);
            if (limitePerfil == null) ps.setNull(2, Types.DATE); else ps.setDate(2, Date.valueOf(limitePerfil));
            ps.setString(3, gestion);
            return ps.executeUpdate();
        }
    }

    /** Gestiones: código, inicio, fin, límite de perfil, plazo, activa. */
    public List<String[]> gestiones() throws SQLException {
        String sql = """
                SELECT codigo, TO_CHAR(fecha_inicio, 'DD/MM/YYYY'), TO_CHAR(fecha_fin, 'DD/MM/YYYY'),
                       COALESCE(TO_CHAR(fecha_limite_perfil, 'DD/MM/YYYY'), '-'), plazo_revision_dias || ' días hábiles',
                       CASE WHEN activa THEN 'Sí' ELSE 'No' END
                  FROM gestiones ORDER BY fecha_inicio DESC
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            return DProyecto.filas(ps, 6);
        }
    }

    public void insertarFeriado(LocalDate fecha, String descripcion) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("INSERT INTO feriados (fecha, descripcion) VALUES (?, ?)")) {
            ps.setDate(1, Date.valueOf(fecha));
            ps.setString(2, descripcion);
            ps.executeUpdate();
        }
    }

    public boolean existeFeriado(LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM feriados WHERE fecha = ?")) {
            ps.setDate(1, Date.valueOf(fecha));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Feriados del año (null = todos): fecha, día, descripción. */
    public List<String[]> feriados(Integer anio) throws SQLException {
        String sql = """
                SELECT TO_CHAR(fecha, 'DD/MM/YYYY'),
                       (ARRAY['lunes','martes','miércoles','jueves','viernes','sábado','domingo'])[EXTRACT(ISODOW FROM fecha)],
                       descripcion
                  FROM feriados WHERE (?::int IS NULL OR EXTRACT(YEAR FROM fecha) = ?::int) ORDER BY fecha
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            if (anio == null) {
                ps.setNull(1, Types.INTEGER);
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(1, anio);
                ps.setInt(2, anio);
            }
            return DProyecto.filas(ps, 3);
        }
    }

    /** Plazo de revisión por defecto de la gestión del proyecto. */
    public int plazoGestion(int gestionId) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT plazo_revision_dias FROM gestiones WHERE id = ?")) {
            ps.setInt(1, gestionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 15;
            }
        }
    }
}