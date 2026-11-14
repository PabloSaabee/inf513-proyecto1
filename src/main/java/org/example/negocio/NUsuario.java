package org.example.negocio;

import org.example.datos.DUsuario;
import org.example.datos.DUsuario.NuevoUsuario;
import org.example.datos.UsuarioDetalle;
import org.example.interprete.Contexto;
import org.example.interprete.ErrorComando;
import org.example.interprete.Respuesta;
import org.example.presentacion.RespuestaHtml;
import org.example.presentacion.TablaHtml;
import org.example.util.Contrasenas;
import org.example.util.Validador;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CU1 · Gestión de Usuarios.
 *
 * Roles:
 *   - administrativos (INSUSU): administrador, director, academico, secretaria
 *   - de docente (INSDOC + ASIROL): tutor, revisante, tribunal → el usuario debe ser docente
 *   - estudiante: solo con INSEST (necesita registro universitario)
 */
public class NUsuario {

    private static final List<String> ROLES_ADMINISTRATIVOS = List.of("administrador", "director", "academico", "secretaria");
    private static final List<String> ROLES_DOCENTE = List.of("tutor", "revisante", "tribunal");

    private final DUsuario dUsuario = new DUsuario();

    // ================================================================== consultas

    /** LISUSU["*" | "rol"] */
    public Respuesta listar(Contexto ctx) throws SQLException {
        String rol = null;
        if (!ctx.esTodos(0)) {
            rol = rolValido(ctx.p(0));
        }
        List<String[]> filas = dUsuario.listar(rol);
        String titulo = rol == null ? "Listando los usuarios" : "Listando los usuarios con rol " + rol;
        String html = TablaHtml.tabla(List.of("CI", "Nombre", "Email", "Teléfono", "Roles", "Estado"), filas)
                + TablaHtml.total(filas.size(), "usuario", "usuarios");
        return ctx.exito(titulo, html, filas.size() + " usuario(s) listados");
    }

    /** VERUSU["ci"] */
    public Respuesta ver(Contexto ctx) throws SQLException {
        UsuarioDetalle u = buscar(ctx.p(0));
        return ctx.exito("Usuario " + u.nombreCompleto(), ficha(u, true), "Detalle de " + u.ci());
    }

    /** VERPER["*"] · Mi perfil */
    public Respuesta verPerfil(Contexto ctx) throws SQLException {
        UsuarioDetalle u = dUsuario.detallePorId(ctx.usuario().id());
        String html = ficha(u, false)
                + "<p style=\"margin:12px 0 4px;font-weight:bold\">Mis permisos</p>"
                + "<p style=\"margin:0;font-size:13px\">"
                + RespuestaHtml.esc(String.join(", ", ctx.usuario().permisos().stream().sorted().toList()))
                + "</p>";
        return ctx.exito("Mi perfil", html, "Perfil de " + u.email());
    }

    // ================================================================== registro

    /** INSUSU["ci","nombres","apellidos","email","telefono","password","rol"] */
    public Respuesta insertar(Contexto ctx) throws SQLException {
        NuevoUsuario nuevo = datosNuevos(ctx);
        String rol = rolValido(ctx.p(6));
        if (!ROLES_ADMINISTRATIVOS.contains(rol)) {
            throw new ErrorComando("Con INSUSU solo se registran: " + String.join(", ", ROLES_ADMINISTRATIVOS)
                    + ". Para estudiantes use INSEST y para docentes INSDOC.");
        }
        long id = dUsuario.insertarConRol(nuevo, rol);
        return registrado(ctx, id, "Usuario registrado con rol " + rol);
    }

    /** INSEST["ci","nombres","apellidos","email","telefono","password","registro"] */
    public Respuesta insertarEstudiante(Contexto ctx) throws SQLException {
        NuevoUsuario nuevo = datosNuevos(ctx);
        String registro = Validador.registro(ctx.p(6));
        if (dUsuario.existeRegistro(registro)) {
            throw new ErrorComando("El registro universitario " + registro + " ya pertenece a otro estudiante.");
        }
        long id = dUsuario.insertarEstudiante(nuevo, registro);
        return registrado(ctx, id, "Estudiante registrado");
    }

    /** INSDOC["ci","nombres","apellidos","email","telefono","password","titulo","areas"] */
    public Respuesta insertarDocente(Contexto ctx) throws SQLException {
        NuevoUsuario nuevo = datosNuevos(ctx);
        String titulo = Validador.textoOpcional(ctx.p(6), 80, "titulo");
        List<Integer> areas = areas(ctx.p(7));
        long id = dUsuario.insertarDocente(nuevo, titulo, areas);
        return registrado(ctx, id, "Docente registrado con rol tutor");
    }

    /** Valida los 6 primeros parámetros comunes y genera el hash de la contraseña. */
    private NuevoUsuario datosNuevos(Contexto ctx) throws SQLException {
        String ci = Validador.ci(ctx.p(0));
        String nombres = Validador.nombre(ctx.p(1), "nombres");
        String apellidos = Validador.nombre(ctx.p(2), "apellidos");
        String email = Validador.email(ctx.p(3));
        String telefono = Validador.telefono(ctx.p(4));
        String clave = Validador.contrasena(ctx.p(5));

        if (dUsuario.existeCi(ci)) throw new ErrorComando("Ya existe un usuario con CI " + ci + ".");
        if (dUsuario.emailEnUso(email, 0)) throw new ErrorComando("El email " + email + " ya está registrado.");
        return new NuevoUsuario(ci, nombres, apellidos, email, telefono, Contrasenas.hash(clave));
    }

    private Respuesta registrado(Contexto ctx, long id, String titulo) throws SQLException {
        UsuarioDetalle u = dUsuario.detallePorId(id);
        String html = ficha(u, false)
                + "<p style=\"margin:12px 0 0;font-size:13px\">Desde ahora puede enviar comandos desde "
                + "<b>" + RespuestaHtml.esc(u.email()) + "</b>. Envíe <code>HELP</code> para ver los suyos.</p>";
        return ctx.exito(titulo, html, "Registrado " + u.ci() + " · " + u.email());
    }

    // ================================================================== modificaciones

    /** MODUSU["ci","nombres","apellidos","email","telefono"] */
    public Respuesta modificar(Contexto ctx) throws SQLException {
        UsuarioDetalle u = buscar(ctx.p(0));
        String nombres = Validador.nombre(ctx.p(1), "nombres");
        String apellidos = Validador.nombre(ctx.p(2), "apellidos");
        String email = Validador.email(ctx.p(3));
        String telefono = Validador.telefono(ctx.p(4));
        if (dUsuario.emailEnUso(email, u.id())) {
            throw new ErrorComando("El email " + email + " ya lo usa otro usuario.");
        }
        dUsuario.modificar(u.id(), nombres, apellidos, email, telefono);
        String aviso = u.email().equalsIgnoreCase(email) ? "" :
                "<p style=\"margin:12px 0 0;font-size:13px\">El email cambió: desde ahora el usuario debe enviar "
                        + "sus comandos desde <b>" + RespuestaHtml.esc(email) + "</b>.</p>";
        return ctx.exito("Usuario modificado", ficha(dUsuario.detallePorId(u.id()), false) + aviso,
                "Modificado " + u.ci());
    }

    /** DELUSU["ci"] · desactiva (no borra: conserva su historial) */
    public Respuesta desactivar(Contexto ctx) throws SQLException {
        UsuarioDetalle u = buscar(ctx.p(0));
        if (u.id() == ctx.usuario().id()) throw new ErrorComando("No puede desactivar su propia cuenta.");
        if ("inactivo".equals(u.estado())) throw new ErrorComando("El usuario " + u.ci() + " ya está inactivo.");
        exigirOtroAdministrador(u, "desactivar");
        dUsuario.cambiarEstado(u.id(), "inactivo");
        return ctx.exito("Usuario desactivado",
                "<p style=\"margin:0\">" + RespuestaHtml.esc(u.nombreCompleto()) + " (CI " + RespuestaHtml.esc(u.ci())
                        + ") ya no puede usar el sistema. Su historial se conserva. "
                        + "Para reactivarlo envíe <code>ACTUSU[\"" + RespuestaHtml.esc(u.ci()) + "\"]</code>.</p>",
                "Desactivado " + u.ci());
    }

    /** ACTUSU["ci"] · reactiva una cuenta inactiva o bloqueada (CU1-E1) */
    public Respuesta activar(Contexto ctx) throws SQLException {
        UsuarioDetalle u = buscar(ctx.p(0));
        if ("activo".equals(u.estado())) throw new ErrorComando("El usuario " + u.ci() + " ya está activo.");
        dUsuario.cambiarEstado(u.id(), "activo");
        return ctx.exito("Usuario reactivado",
                "<p style=\"margin:0\">" + RespuestaHtml.esc(u.nombreCompleto()) + " (CI " + RespuestaHtml.esc(u.ci())
                        + ") estaba " + u.estado() + " y ya puede usar el sistema.</p>",
                "Reactivado " + u.ci());
    }

    /** ASIROL["ci","rol"] */
    public Respuesta asignarRol(Contexto ctx) throws SQLException {
        UsuarioDetalle u = buscar(ctx.p(0));
        String rol = rolValido(ctx.p(1));
        if (rol.equals("estudiante")) {
            throw new ErrorComando("El rol estudiante no se asigna con ASIROL: registre al estudiante con INSEST.");
        }
        if (ROLES_DOCENTE.contains(rol) && !u.esDocente()) {
            throw new ErrorComando("El rol " + rol + " es solo para docentes y " + u.nombreCompleto()
                    + " no está registrado como docente (use INSDOC).");
        }
        if (!dUsuario.agregarRol(u.id(), rol)) {
            throw new ErrorComando(u.nombreCompleto() + " ya tiene el rol " + rol + ".");
        }
        return rolesActualizados(ctx, u.id(), "Rol " + rol + " asignado", "Asignado " + rol + " a " + u.ci());
    }

    /** QUIROL["ci","rol"] */
    public Respuesta quitarRol(Contexto ctx) throws SQLException {
        UsuarioDetalle u = buscar(ctx.p(0));
        String rol = rolValido(ctx.p(1));
        if (!u.roles().contains(rol)) throw new ErrorComando(u.nombreCompleto() + " no tiene el rol " + rol + ".");
        if (rol.equals("administrador")) {
            if (u.id() == ctx.usuario().id()) throw new ErrorComando("No puede quitarse a sí mismo el rol administrador.");
            exigirOtroAdministrador(u, "quitarle el rol administrador a");
        }
        if (u.roles().size() == 1) throw new ErrorComando("Es el único rol de " + u.nombreCompleto()
                + ": un usuario debe tener al menos un rol. Si ya no debe usar el sistema, use DELUSU.");
        dUsuario.quitarRol(u.id(), rol);
        return rolesActualizados(ctx, u.id(), "Rol " + rol + " quitado", "Quitado " + rol + " a " + u.ci());
    }

    /** MODFOT["ci","url_foto"] */
    public Respuesta cambiarFoto(Contexto ctx) throws SQLException {
        UsuarioDetalle u = buscar(ctx.p(0));
        String url = Validador.url(ctx.p(1), "url_foto");
        dUsuario.cambiarFoto(u.id(), url);
        return ctx.exito("Foto registrada",
                "<p style=\"margin:0 0 8px\">Foto de " + RespuestaHtml.esc(u.nombreCompleto()) + ":</p>"
                        + TablaHtml.foto(url),
                "Foto de " + u.ci());
    }

    /** MODPAS["actual","nueva"] · cambia la contraseña propia */
    public Respuesta cambiarContrasena(Contexto ctx) throws SQLException {
        long id = ctx.usuario().id();
        if (!Contrasenas.coincide(ctx.p(0), dUsuario.hashContrasena(id))) {
            throw new ErrorComando("La contraseña actual no es correcta.");
        }
        String nueva = Validador.contrasena(ctx.p(1));
        if (nueva.equals(ctx.p(0))) throw new ErrorComando("La contraseña nueva debe ser distinta de la actual.");
        dUsuario.cambiarContrasena(id, Contrasenas.hash(nueva));
        return ctx.exito("Contraseña actualizada",
                "<p style=\"margin:0\">Su contraseña se cambió correctamente y vence en 180 días. "
                        + "Por seguridad, borre de su bandeja de enviados el correo con este comando.</p>",
                "Contraseña cambiada");
    }

    // ================================================================== utilidades

    private UsuarioDetalle buscar(String ciTexto) throws SQLException {
        String ci = Validador.ci(ciTexto);
        UsuarioDetalle u = dUsuario.detallePorCi(ci);
        if (u == null) throw new ErrorComando("No existe un usuario con CI " + ci + ". Use LISUSU[\"*\"] para ver los CI.");
        return u;
    }

    private String rolValido(String texto) throws SQLException {
        String rol = texto.trim().toLowerCase().replace("académico", "academico");
        List<String> roles = dUsuario.nombresRoles();
        if (!roles.contains(rol)) {
            throw new ErrorComando("El rol «" + texto + "» no existe. Roles válidos: " + String.join(", ", roles) + ".");
        }
        return rol;
    }

    /** "1,3" → [1, 3], verificando que existan. */
    private List<Integer> areas(String texto) throws SQLException {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String parte : texto.split(",")) {
            if (!parte.isBlank()) ids.add(Validador.entero(parte, "areas"));
        }
        if (ids.isEmpty()) {
            throw new ErrorComando("Indique al menos un área. Áreas: " + dUsuario.areasDisponibles() + ".");
        }
        List<Integer> faltan = dUsuario.areasInexistentes(new ArrayList<>(ids));
        if (!faltan.isEmpty()) {
            throw new ErrorComando("No existen las áreas " + faltan + ". Áreas: " + dUsuario.areasDisponibles() + ".");
        }
        return new ArrayList<>(ids);
    }

    /** Evita dejar el sistema sin ningún administrador activo. */
    private void exigirOtroAdministrador(UsuarioDetalle u, String accion) throws SQLException {
        if (u.roles().contains("administrador") && "activo".equals(u.estado())
                && dUsuario.administradoresActivos() <= 1) {
            throw new ErrorComando("No se puede " + accion + " " + u.nombreCompleto()
                    + ": es el único administrador activo del sistema.");
        }
    }

    private Respuesta rolesActualizados(Contexto ctx, long id, String titulo, String resumen) throws SQLException {
        UsuarioDetalle u = dUsuario.detallePorId(id);
        return ctx.exito(titulo, "<p style=\"margin:0\">" + RespuestaHtml.esc(u.nombreCompleto())
                + " ahora tiene los roles: <b>" + RespuestaHtml.esc(String.join(", ", u.roles())) + "</b>.</p>", resumen);
    }

    /** Ficha del usuario; 'completa' agrega datos administrativos (intentos, accesos). */
    private static String ficha(UsuarioDetalle u, boolean completa) {
        List<String> c = new ArrayList<>(List.of("CI", "Nombre", "Email", "Teléfono", "Estado", "Roles"));
        List<String> v = new ArrayList<>(List.of(u.ci(), u.nombreCompleto(), u.email(), nvl(u.telefono()),
                u.estado(), String.join(", ", u.roles())));
        if (u.esEstudiante()) {
            c.addAll(List.of("Registro universitario", "Carrera", "Malla aprobada", "GRL inscrito"));
            v.addAll(List.of(u.registroUniversitario(), nvl(u.carrera()), u.malla() + " %", u.grl()));
        }
        if (u.esDocente()) {
            c.addAll(List.of("Título", "Áreas de conocimiento"));
            v.addAll(List.of(nvl(u.titulo()), nvl(u.areas())));
        }
        if (completa) {
            c.addAll(List.of("Intentos fallidos", "Último acceso", "Registrado"));
            v.addAll(List.of(String.valueOf(u.intentosFallidos()), nvl(u.ultimoAcceso()), nvl(u.registradoEn())));
        }
        // Todos los valores se escapan; la foto va como miniatura con su enlace
        List<String> html = new ArrayList<>(v.stream().map(RespuestaHtml::esc).toList());
        c.add("Foto");
        html.add(u.foto() == null || u.foto().isBlank() ? "Sin foto" : TablaHtml.foto(u.foto()));
        return TablaHtml.fichaHtml(c, html);
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}