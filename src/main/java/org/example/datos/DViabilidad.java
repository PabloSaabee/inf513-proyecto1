package org.example.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Acceso a evaluaciones_viabilidad (CU4 · RF-08).
 */
public class DViabilidad {

    /**
     * Perfiles enviados pendientes de evaluar (proyectos en etapa viabilidad),
     * sin los proyectos donde el revisante es tutor.
     * Columnas: código, título, versión, área, ¿de su área?, estudiantes, tutor, enviado, enlace.
     */
    public List<String[]> pendientes(long revisanteId) throws SQLException {
        String sql = """
                SELECT p.codigo, f.titulo, 'v' || f.version, COALESCE(a.nombre, '-'),
                       CASE WHEN EXISTS (SELECT 1 FROM docente_area da WHERE da.docente_id = ? AND da.area_id = p.area_id)
                            THEN 'Sí' ELSE 'No' END,
                       (SELECT STRING_AGG(u.nombres || ' ' || u.apellidos, ', ') FROM proyecto_estudiante pe
                          JOIN usuarios u ON u.id = pe.estudiante_id WHERE pe.proyecto_id = p.id),
                       COALESCE((SELECT tu.nombres || ' ' || tu.apellidos FROM asignaciones_tutor t
                                   JOIN usuarios tu ON tu.id = t.docente_id
                                  WHERE t.proyecto_id = p.id AND t.estado = 'aceptada' LIMIT 1), '-'),
                       TO_CHAR(f.created_at, 'DD/MM/YYYY'), COALESCE(ar.ruta, '')
                  FROM proyectos p
                  JOIN perfiles f ON f.proyecto_id = p.id
                       AND f.version = (SELECT MAX(version) FROM perfiles WHERE proyecto_id = p.id)
                  LEFT JOIN areas_conocimiento a ON a.id = p.area_id
                  LEFT JOIN archivos ar ON ar.id = f.archivo_id
                 WHERE p.etapa_actual = 'viabilidad' AND f.estado IN ('enviado','en_revision')
                   AND NOT EXISTS (SELECT 1 FROM asignaciones_tutor t WHERE t.proyecto_id = p.id
                                      AND t.docente_id = ? AND t.estado = 'aceptada')
                 ORDER BY f.created_at
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, revisanteId);
            ps.setLong(2, revisanteId);
            return DProyecto.filas(ps, 9);
        }
    }

    public void registrar(Connection cn, long perfilId, long revisanteId, String resultado, String observaciones,
                          Long archivoId) throws SQLException {
        String sql = """
                INSERT INTO evaluaciones_viabilidad (perfil_id, revisante_id, resultado, observaciones, archivo_id)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, perfilId);
            ps.setLong(2, revisanteId);
            ps.setString(3, resultado);
            if (observaciones == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, observaciones);
            if (archivoId == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, archivoId);
            ps.executeUpdate();
        }
    }

    /** Evaluaciones del proyecto: versión, resultado, revisante, observaciones, fecha, informe. */
    public List<String[]> historial(long proyectoId) throws SQLException {
        String sql = """
                SELECT 'v' || f.version, REPLACE(v.resultado, '_', ' '), u.nombres || ' ' || u.apellidos,
                       COALESCE(v.observaciones, '-'), TO_CHAR(v.fecha, 'DD/MM/YYYY'), COALESCE(a.ruta, '')
                  FROM evaluaciones_viabilidad v
                  JOIN perfiles f ON f.id = v.perfil_id
                  JOIN usuarios u ON u.id = v.revisante_id
                  LEFT JOIN archivos a ON a.id = v.archivo_id
                 WHERE f.proyecto_id = ?
                 ORDER BY v.fecha
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, proyectoId);
            return DProyecto.filas(ps, 6);
        }
    }
}