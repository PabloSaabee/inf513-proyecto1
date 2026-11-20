package org.example.datos;

import org.example.negocio.UsuarioSesion;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Acceso a usuarios, usuario_rol, estudiantes, docentes, docente_area y la matriz rol_permiso.
 * (Solo SQL: las reglas van en la capa de negocio.)
 */
public class DUsuario {

    // ================================================================== consultas

    /** Busca por email (sin importar mayúsculas) con sus roles y permisos. null si no existe. */
    public UsuarioSesion buscarSesionPorEmail(String email) throws SQLException {
        String sql = """
                SELECT u.id, u.ci, u.nombres, u.apellidos, u.email, u.estado,
                       ARRAY(SELECT r.nombre FROM usuario_rol ur JOIN roles r ON r.id = ur.rol_id
                              WHERE ur.usuario_id = u.id ORDER BY r.id)                     AS roles,
                       ARRAY(SELECT DISTINCT p.codigo FROM usuario_rol ur
                               JOIN rol_permiso rp ON rp.rol_id = ur.rol_id
                               JOIN permisos p     ON p.id = rp.permiso_id
                              WHERE ur.usuario_id = u.id)                                   AS permisos
                  FROM usuarios u
                 WHERE LOWER(u.email) = LOWER(?)
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new UsuarioSesion(
                        rs.getLong("id"), rs.getString("ci"), rs.getString("nombres"),
                        rs.getString("apellidos"), rs.getString("email"), rs.getString("estado"),
                        new LinkedHashSet<>(aLista(rs.getArray("roles"))),
                        new LinkedHashSet<>(aLista(rs.getArray("permisos"))));
            }
        }
    }

    /** Lista usuarios; rol = null para todos. Columnas: ci, nombre, email, telefono, roles, estado. */
    public List<String[]> listar(String rol) throws SQLException {
        String sql = """
                SELECT u.ci, u.nombres || ' ' || u.apellidos AS nombre, u.email,
                       COALESCE(u.telefono, '') AS telefono,
                       COALESCE(STRING_AGG(r.nombre, ', ' ORDER BY r.id), '') AS roles,
                       u.estado
                  FROM usuarios u
                  LEFT JOIN usuario_rol ur ON ur.usuario_id = u.id
                  LEFT JOIN roles r        ON r.id = ur.rol_id
                 WHERE (?::text IS NULL OR EXISTS (
                         SELECT 1 FROM usuario_rol x JOIN roles y ON y.id = x.rol_id
                          WHERE x.usuario_id = u.id AND y.nombre = ?::text))
                 GROUP BY u.id
                 ORDER BY u.apellidos, u.nombres
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, rol);
            ps.setString(2, rol);
            List<String[]> filas = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(new String[]{rs.getString("ci"), rs.getString("nombre"), rs.getString("email"),
                            rs.getString("telefono"), rs.getString("roles"), rs.getString("estado")});
                }
            }
            return filas;
        }
    }

    public UsuarioDetalle detallePorCi(String ci) throws SQLException {
        return detalle("u.ci = ?", ci);
    }

    public UsuarioDetalle detallePorId(long id) throws SQLException {
        return detalle("u.id = ?", id);
    }

    private UsuarioDetalle detalle(String condicion, Object valor) throws SQLException {
        String sql = """
                SELECT u.id, u.ci, u.nombres, u.apellidos, u.email, u.telefono, u.foto_ruta, u.estado,
                       u.intentos_fallidos,
                       TO_CHAR(u.ultimo_acceso, 'DD/MM/YYYY HH24:MI') AS ultimo_acceso,
                       TO_CHAR(u.created_at, 'DD/MM/YYYY HH24:MI')    AS creado,
                       ARRAY(SELECT r.nombre FROM usuario_rol ur JOIN roles r ON r.id = ur.rol_id
                              WHERE ur.usuario_id = u.id ORDER BY r.id) AS roles,
                       e.registro_universitario, c.nombre AS carrera,
                       e.porcentaje_malla::text AS malla,
                       CASE WHEN e.usuario_id IS NULL THEN NULL WHEN e.grl_inscrito THEN 'Sí' ELSE 'No' END AS grl,
                       d.usuario_id IS NOT NULL AS es_docente, d.titulo,
                       (SELECT STRING_AGG(a.id || '. ' || a.nombre, ', ' ORDER BY a.id)
                          FROM docente_area da JOIN areas_conocimiento a ON a.id = da.area_id
                         WHERE da.docente_id = u.id) AS areas
                  FROM usuarios u
                  LEFT JOIN estudiantes e ON e.usuario_id = u.id
                  LEFT JOIN carreras c    ON c.id = e.carrera_id
                  LEFT JOIN docentes d    ON d.usuario_id = u.id
                 WHERE\s""" + condicion;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setObject(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new UsuarioDetalle(rs.getLong("id"), rs.getString("ci"), rs.getString("nombres"),
                        rs.getString("apellidos"), rs.getString("email"), rs.getString("telefono"),
                        rs.getString("foto_ruta"), rs.getString("estado"), rs.getInt("intentos_fallidos"),
                        rs.getString("ultimo_acceso"), rs.getString("creado"), aLista(rs.getArray("roles")),
                        rs.getString("registro_universitario"), rs.getString("carrera"), rs.getString("malla"),
                        rs.getString("grl"), rs.getBoolean("es_docente"), rs.getString("titulo"),
                        rs.getString("areas"));
            }
        }
    }

    /** Nombres de todos los roles (para validar filtros). */
    public List<String> nombresRoles() throws SQLException {
        List<String> roles = new ArrayList<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT nombre FROM roles ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) roles.add(rs.getString(1));
        }
        return roles;
    }

    public boolean existeCi(String ci) throws SQLException {
        return existe("SELECT 1 FROM usuarios WHERE ci = ?", ci);
    }

    /** ¿Otro usuario (distinto de excluirId) ya usa este email? excluirId = 0 para no excluir. */
    /**
     * Emails de todos los usuarios registrados (en minúsculas), sin importar su estado.
     * El monitor lo carga una vez por ciclo para descartar, sin responder, los correos
     * de remitentes desconocidos (protección contra inundación de correos).
     */
    public Set<String> emailsRegistrados() throws SQLException {
        Set<String> emails = new java.util.HashSet<>();
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT LOWER(email) FROM usuarios");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) emails.add(rs.getString(1));
        }
        return emails;
    }

    public boolean emailEnUso(String email, long excluirId) throws SQLException {
        return existe("SELECT 1 FROM usuarios WHERE LOWER(email) = LOWER(?) AND id <> " + excluirId, email);
    }

    public boolean existeRegistro(String registro) throws SQLException {
        return existe("SELECT 1 FROM estudiantes WHERE registro_universitario = ?", registro);
    }

    /** De una lista de ids de área, devuelve los que NO existen. */
    public List<Integer> areasInexistentes(List<Integer> ids) throws SQLException {
        List<Integer> faltan = new ArrayList<>(ids);
        String sql = "SELECT id FROM areas_conocimiento WHERE id = ANY (?)";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setArray(1, cn.createArrayOf("int4", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) faltan.remove(Integer.valueOf(rs.getInt(1)));
            }
        }
        return faltan;
    }

    /** Lista "id. nombre" de las áreas (para mensajes de ayuda). */
    public String areasDisponibles() throws SQLException {
        String sql = "SELECT STRING_AGG(id || '. ' || nombre, ', ' ORDER BY id) FROM areas_conocimiento";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    /** Cantidad de administradores activos (para no dejar el sistema sin ninguno). */
    public int administradoresActivos() throws SQLException {
        String sql = """
                SELECT COUNT(DISTINCT u.id) FROM usuarios u
                  JOIN usuario_rol ur ON ur.usuario_id = u.id
                  JOIN roles r ON r.id = ur.rol_id
                 WHERE r.nombre = 'administrador' AND u.estado = 'activo'
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public String hashContrasena(long usuarioId) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement("SELECT password FROM usuarios WHERE id = ?")) {
            ps.setLong(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ================================================================== registro (transacción)

    /** Datos comunes de un usuario nuevo. telefono puede ser null. */
    public record NuevoUsuario(String ci, String nombres, String apellidos, String email,
                               String telefono, String hashPassword) { }

    /** INSUSU: usuario + un rol administrativo. Devuelve el id. */
    public long insertarConRol(NuevoUsuario u, String rol) throws SQLException {
        return enTransaccion(cn -> {
            long id = insertarUsuario(cn, u);
            asignarRol(cn, id, rol);
            return id;
        });
    }

    /** INSEST: usuario + estudiantes + rol estudiante. */
    public long insertarEstudiante(NuevoUsuario u, String registro) throws SQLException {
        return enTransaccion(cn -> {
            long id = insertarUsuario(cn, u);
            String sql = """
                    INSERT INTO estudiantes (usuario_id, registro_universitario, carrera_id, porcentaje_malla, grl_inscrito)
                    VALUES (?, ?, (SELECT MIN(id) FROM carreras), 0, FALSE)
                    """;
            try (PreparedStatement ps = cn.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setString(2, registro);
                ps.executeUpdate();
            }
            asignarRol(cn, id, "estudiante");
            return id;
        });
    }

    /** INSDOC: usuario + docentes + docente_area + rol tutor. */
    public long insertarDocente(NuevoUsuario u, String titulo, List<Integer> areas) throws SQLException {
        return enTransaccion(cn -> {
            long id = insertarUsuario(cn, u);
            try (PreparedStatement ps = cn.prepareStatement(
                    "INSERT INTO docentes (usuario_id, titulo) VALUES (?, ?)")) {
                ps.setLong(1, id);
                ps.setString(2, titulo);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = cn.prepareStatement(
                    "INSERT INTO docente_area (docente_id, area_id) VALUES (?, ?)")) {
                for (int area : areas) {
                    ps.setLong(1, id);
                    ps.setInt(2, area);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            asignarRol(cn, id, "tutor");
            return id;
        });
    }

    private long insertarUsuario(Connection cn, NuevoUsuario u) throws SQLException {
        String sql = """
                INSERT INTO usuarios (ci, nombres, apellidos, email, telefono, password, estado,
                                      intentos_fallidos, password_expira_en)
                VALUES (?, ?, ?, ?, ?, ?, 'activo', 0, CURRENT_DATE + 180)
                RETURNING id
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, u.ci());
            ps.setString(2, u.nombres());
            ps.setString(3, u.apellidos());
            ps.setString(4, u.email());
            setTextoNulo(ps, 5, u.telefono());
            ps.setString(6, u.hashPassword());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void asignarRol(Connection cn, long usuarioId, String rol) throws SQLException {
        String sql = """
                INSERT INTO usuario_rol (usuario_id, rol_id)
                SELECT ?, id FROM roles WHERE nombre = ?
                ON CONFLICT DO NOTHING
                """;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, usuarioId);
            ps.setString(2, rol);
            ps.executeUpdate();
        }
    }

    // ================================================================== modificaciones

    public void modificar(long id, String nombres, String apellidos, String email, String telefono) throws SQLException {
        String sql = """
                UPDATE usuarios SET nombres = ?, apellidos = ?, email = ?, telefono = ?,
                                    updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, nombres);
            ps.setString(2, apellidos);
            ps.setString(3, email);
            setTextoNulo(ps, 4, telefono);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /** activo / inactivo / bloqueado. Al activar se reinician los intentos fallidos. */
    public void cambiarEstado(long id, String estado) throws SQLException {
        String sql = """
                UPDATE usuarios SET estado = ?,
                       intentos_fallidos = CASE WHEN ? = 'activo' THEN 0 ELSE intentos_fallidos END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, estado);
            ps.setString(2, estado);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** @return true si se agregó (false si ya lo tenía). */
    public boolean agregarRol(long id, String rol) throws SQLException {
        try (Connection cn = ConexionBD.getConexion()) {
            String sql = """
                    INSERT INTO usuario_rol (usuario_id, rol_id)
                    SELECT ?, id FROM roles WHERE nombre = ?
                    ON CONFLICT DO NOTHING
                    """;
            try (PreparedStatement ps = cn.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setString(2, rol);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /** @return true si se quitó (false si no lo tenía). */
    public boolean quitarRol(long id, String rol) throws SQLException {
        String sql = "DELETE FROM usuario_rol WHERE usuario_id = ? AND rol_id = (SELECT id FROM roles WHERE nombre = ?)";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, rol);
            return ps.executeUpdate() > 0;
        }
    }

    public void cambiarFoto(long id, String url) throws SQLException {
        actualizarCampo("UPDATE usuarios SET foto_ruta = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", url, id);
    }

    /** Guarda la nueva contraseña y renueva su vencimiento (180 días). */
    public void cambiarContrasena(long id, String hash) throws SQLException {
        actualizarCampo("""
                UPDATE usuarios SET password = ?, password_expira_en = CURRENT_DATE + 180,
                                    updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, hash, id);
    }

    // ================================================================== utilidades

    @FunctionalInterface
    private interface Trabajo<T> {
        T ejecutar(Connection cn) throws SQLException;
    }

    /** Ejecuta varias sentencias como una sola: o se guardan todas o ninguna. */
    private static <T> T enTransaccion(Trabajo<T> trabajo) throws SQLException {
        try (Connection cn = ConexionBD.getConexion()) {
            cn.setAutoCommit(false);
            try {
                T resultado = trabajo.ejecutar(cn);
                cn.commit();
                return resultado;
            } catch (SQLException | RuntimeException e) {
                cn.rollback();
                throw e;
            }
        }
    }

    private static void actualizarCampo(String sql, String valor, long id) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, valor);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static boolean existe(String sql, String valor) throws SQLException {
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void setTextoNulo(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
    }

    private static List<String> aLista(Array arreglo) throws SQLException {
        if (arreglo == null) return List.of();
        return Arrays.stream((Object[]) arreglo.getArray()).map(String::valueOf).toList();
    }
}