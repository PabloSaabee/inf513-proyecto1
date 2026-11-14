package org.example.negocio;

import org.example.datos.DArchivo;
import org.example.datos.DGestion;
import org.example.datos.DGestion.Gestion;
import org.example.datos.DHistorial;
import org.example.datos.DPerfil;
import org.example.datos.DPerfil.NuevoPerfil;
import org.example.datos.DPerfil.Perfil;
import org.example.datos.DProyecto;
import org.example.datos.DProyecto.Asignacion;
import org.example.datos.DProyecto.Docente;
import org.example.datos.DProyecto.Estudiante;
import org.example.datos.DProyecto.Proyecto;
import org.example.datos.Transaccion;
import org.example.interprete.Contexto;
import org.example.interprete.ErrorComando;
import org.example.interprete.Respuesta;
import org.example.presentacion.RespuestaHtml;
import org.example.presentacion.TablaHtml;
import org.example.util.Validador;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * CU3 · Gestión de Asignación de Tutor y Registro de Perfil de Proyecto.
 *
 * Etapas del proyecto que maneja este caso de uso:
 *   tutor ──(tutor acepta)──► perfil ──(ENVPRF)──► viabilidad (CU4)
 *
 * Asignación de tutor:
 *   SOLTUT[ci]        → pendiente_respuesta ──RESTUT──► aceptada / rechazada
 *   SOLTUT[DIRECCION] → solicitada ──DESTUT──► pendiente_respuesta ──RESTUT──► ...
 */
public class NProyecto {

    static final List<String> ETAPAS = List.of("habilitacion", "tutor", "perfil", "viabilidad", "tribunales",
            "revision", "predefensa", "defensa", "concluido", "cancelado");

    private final DProyecto dProyecto = new DProyecto();
    private final DPerfil dPerfil = new DPerfil();
    private final DGestion dGestion = new DGestion();
    private final DHistorial dHistorial = new DHistorial();
    private final DArchivo dArchivo = new DArchivo();
    private final NNotificacion notificar = new NNotificacion();

    // ================================================================== proyecto

    /** INSPRO["area_id","titulo","registro_companero" | "-"] */
    public Respuesta registrar(Contexto ctx) throws SQLException {
        Gestion g = dGestion.activa();
        if (g == null) throw new ErrorComando("No hay una gestión activa.");
        long yo = ctx.usuario().id();

        Long miSolicitud = dProyecto.solicitudHabilitada(yo, g.id());
        if (miSolicitud == null) {
            throw new ErrorComando("Debe tener su solicitud de habilitación de la gestión " + g.codigo()
                    + " en estado habilitada (CU2). Consulte con VERSOL[\"*\"].");
        }
        Proyecto actual = dProyecto.vigenteDeEstudiante(yo);
        if (actual != null) throw new ErrorComando("Ya está registrado en el proyecto " + actual.codigo() + ".");

        int areaId = Validador.entero(ctx.p(0), "area_id");
        String area = dProyecto.nombreArea(areaId);
        if (area == null) throw new ErrorComando("No existe el área " + areaId + ". Vea las áreas con LISDOC[\"*\"].");
        String titulo = ctx.p(1).trim();
        if (titulo.length() < 5 || titulo.length() > 255) {
            throw new ErrorComando("El título debe tener entre 5 y 255 caracteres.");
        }

        Estudiante companero = null;
        Long solicitudCompanero = null;
        if (!ctx.esVacio(2)) {                                                    // CU3-E1
            companero = dProyecto.estudiantePorRegistro(Validador.registro(ctx.p(2)));
            if (companero == null) throw new ErrorComando("No existe un estudiante activo con registro " + ctx.p(2) + ".");
            if (companero.id() == yo) throw new ErrorComando("El compañero no puede ser usted mismo.");
            solicitudCompanero = dProyecto.solicitudHabilitada(companero.id(), g.id());
            if (solicitudCompanero == null) {
                throw new ErrorComando(companero.nombre() + " no está habilitado en la gestión " + g.codigo() + ".");
            }
            Proyecto delCompanero = dProyecto.vigenteDeEstudiante(companero.id());
            if (delCompanero != null) {
                throw new ErrorComando(companero.nombre() + " ya está en el proyecto " + delCompanero.codigo() + ".");
            }
        }

        Proyecto p = dProyecto.crear(g.id(), g.codigo(), areaId, titulo, yo, miSolicitud,
                companero == null ? null : companero.id(), solicitudCompanero, dHistorial);

        if (companero != null) {
            notificar.aUsuario(companero.id(), "Fue agregado a un proyecto de grado",
                    ctx.usuario().nombreCompleto() + " lo registró como integrante del proyecto " + p.codigo()
                            + ": " + titulo + ".", "VERPRO[\"" + p.codigo() + "\"]");
        }
        return ctx.exito("Proyecto " + p.codigo() + " registrado", fichaProyecto(p) + paso(p),
                "Proyecto " + p.codigo() + " registrado");
    }

    /** LISPRO["*" | "etapa"] */
    public Respuesta listar(Contexto ctx) throws SQLException {
        String etapa = null;
        if (!ctx.esTodos(0)) {
            etapa = ctx.p(0).toLowerCase();
            if (!ETAPAS.contains(etapa)) {
                throw new ErrorComando("Etapa «" + ctx.p(0) + "» no válida. Use: " + String.join(", ", ETAPAS) + " o \"*\".");
            }
        }
        List<String[]> filas = dProyecto.listar(etapa);
        String html = TablaHtml.tabla(List.of("Código", "Gestión", "Área", "Título", "Etapa", "Estudiantes",
                "Tutor", "Vence revisión"), filas)
                + TablaHtml.total(filas.size(), "proyecto", "proyectos");
        return ctx.exito(etapa == null ? "Proyectos de grado" : "Proyectos en etapa " + etapa, html,
                filas.size() + " proyecto(s)");
    }

    /** VERPRO["codigo"] */
    public Respuesta ver(Contexto ctx) throws SQLException {
        Proyecto p = proyectoAccesible(ctx, ctx.p(0));
        List<String[]> tutores = dProyecto.historialTutores(p.id());
        String html = fichaProyecto(p)
                + "<p style=\"margin:12px 0 4px;font-weight:bold\">Tutor</p>"
                + TablaHtml.tabla(List.of("Docente", "Origen", "Estado", "Motivo", "Fecha"), tutores)
                + viabilidadHtml(p)
                + new NTribunal().tribunalesHtml(p)
                + "<p style=\"margin:12px 0 4px;font-weight:bold\">Últimos movimientos</p>"
                + TablaHtml.tabla(List.of("Fecha", "Etapa", "Estado", "Comentario", "Usuario"),
                dHistorial.ultimos(p.id(), 6));
        if (dProyecto.esIntegrante(p.id(), ctx.usuario().id())) html += paso(p);
        return ctx.exito("Proyecto " + p.codigo(), html, "Detalle de " + p.codigo());
    }

    // ================================================================== tutor

    /** LISDOC["area_id" | "*"] · docentes que pueden ser tutores */
    public Respuesta listarDocentes(Contexto ctx) throws SQLException {
        Integer areaId = null;
        String titulo = "Docentes tutores";
        if (!ctx.esTodos(0)) {
            areaId = Validador.entero(ctx.p(0), "area_id");
            String area = dProyecto.nombreArea(areaId);
            if (area == null) throw new ErrorComando("No existe el área " + areaId + ".");
            titulo = "Docentes tutores del área " + area;
        }
        List<String[]> filas = dProyecto.docentes(areaId);
        String html = TablaHtml.tabla(List.of("CI", "Docente", "Áreas", "Roles", "Tutorías vigentes"), filas)
                + TablaHtml.total(filas.size(), "docente", "docentes");
        return ctx.exito(titulo, html, filas.size() + " docente(s)");
    }

    /** SOLTUT["codigo","ci_docente" | "DIRECCION"] */
    public Respuesta solicitarTutor(Contexto ctx) throws SQLException {
        Proyecto p = proyectoPropio(ctx, ctx.p(0));
        exigirEtapa(p, "tutor");
        Asignacion vigente = dProyecto.asignacionVigente(p.id());
        if (vigente != null) throw new ErrorComando("El proyecto ya tiene una asignación de tutor "
                + estadoTexto(vigente.estado()) + (vigente.docente() == null ? "" : " (" + vigente.docente() + ")") + ".");

        if (ctx.p(1).trim().equalsIgnoreCase("DIRECCION") || ctx.p(1).trim().equalsIgnoreCase("DIRECCIÓN")) {
            dProyecto.crearAsignacion(p.id(), null, "designado_direccion", "solicitada", null);
            int avisados = notificar.aRoles("Solicitud de designación de tutor",
                    "El proyecto " + p.codigo() + " (" + p.area() + ") pide que Dirección le designe un tutor.",
                    "LISTUT[\"*\"]", "director");
            return ctx.exito("Solicitud enviada a Dirección",
                    "<p style=\"margin:0\">Se pidió a Dirección (" + avisados + " persona(s)) que designe un tutor "
                            + "para " + RespuestaHtml.esc(p.codigo()) + ". Recibirá un correo cuando lo haga.</p>",
                    "Pide tutor a Dirección");
        }

        Docente d = tutorValido(ctx.p(1));
        dProyecto.crearAsignacion(p.id(), d.id(), "propuesto_estudiante", "pendiente_respuesta", null);
        notificar.aUsuario(d.id(), "Le propusieron como tutor",
                p.estudiantes() + " le propone como tutor del proyecto " + p.codigo() + ": " + p.titulo()
                        + ". Responda con RESTUT[\"" + p.codigo() + "\",\"aceptada\",\"-\"] o \"rechazada\" con el motivo.",
                "VERPRO[\"" + p.codigo() + "\"]");
        return ctx.exito("Tutor propuesto",
                "<p style=\"margin:0\">Se envió la propuesta a <b>" + RespuestaHtml.esc(d.nombre())
                        + "</b>. Recibirá un correo cuando acepte o rechace.</p>",
                "Propone a " + d.ci());
    }

    /** DESTUT["codigo","ci_docente"] · Dirección designa */
    public Respuesta designarTutor(Contexto ctx) throws SQLException {
        Proyecto p = buscar(ctx.p(0));
        exigirEtapa(p, "tutor");
        Docente d = tutorValido(ctx.p(1));
        Asignacion vigente = dProyecto.asignacionVigente(p.id());

        if (vigente != null && vigente.estado().equals("aceptada")) {
            throw new ErrorComando(p.codigo() + " ya tiene tutor aceptado: " + vigente.docente() + ".");
        }
        if (vigente != null && vigente.estado().equals("solicitada")) {
            dProyecto.designarEnSolicitud(vigente.id(), d.id(), ctx.usuario().id());
        } else {
            if (vigente != null) {                                    // pendiente_respuesta: se reemplaza
                dProyecto.marcarAsignacion(vigente.id(), "reemplazada", "Dirección designó a otro tutor");
            }
            dProyecto.crearAsignacion(p.id(), d.id(), "designado_direccion", "pendiente_respuesta", ctx.usuario().id());
        }
        notificar.aUsuario(d.id(), "Fue designado como tutor",
                "Dirección lo designó tutor del proyecto " + p.codigo() + ": " + p.titulo()
                        + ". Responda con RESTUT[\"" + p.codigo() + "\",\"aceptada\",\"-\"] o \"rechazada\" con el motivo.",
                "VERPRO[\"" + p.codigo() + "\"]");
        avisarEstudiantes(p, "Dirección designó tutor",
                "Dirección designó a " + d.nombre() + " como tutor de su proyecto. Falta que acepte.");
        return ctx.exito("Tutor designado",
                "<p style=\"margin:0\">" + RespuestaHtml.esc(d.nombre()) + " fue designado tutor de "
                        + RespuestaHtml.esc(p.codigo()) + ". Se le notificó para que acepte o rechace.</p>",
                "Designa a " + d.ci() + " en " + p.codigo());
    }

    /** LISTUT["*"] · mis tutorías (tutor) y pendientes de designar (director) */
    public Respuesta listarTutorias(Contexto ctx) throws SQLException {
        StringBuilder html = new StringBuilder();
        int total = 0;
        if (ctx.usuario().tienePermiso("tutor.designar")) {
            List<String[]> pendientes = dProyecto.tutoriasPendientes();
            total += pendientes.size();
            html.append("<p style=\"margin:0 0 4px;font-weight:bold\">Pendientes (para Dirección)</p>")
                    .append(TablaHtml.tabla(List.of("Proyecto", "Título", "Área", "Estudiantes", "Docente", "Estado", "Fecha"),
                            pendientes))
                    .append("<p style=\"margin:6px 0 12px;font-size:13px\">Para designar: "
                            + "<code>DESTUT[\"codigo\",\"ci_docente\"]</code> · docentes del área: <code>LISDOC[\"area_id\"]</code></p>");
        }
        if (ctx.usuario().tienePermiso("tutor.responder")) {
            List<String[]> mias = dProyecto.tutoriasDe(ctx.usuario().id());
            total += mias.size();
            html.append("<p style=\"margin:0 0 4px;font-weight:bold\">Mis tutorías</p>")
                    .append(TablaHtml.tabla(List.of("Proyecto", "Título", "Estudiantes", "Origen", "Estado", "Etapa", "Fecha"),
                            mias))
                    .append("<p style=\"margin:6px 0 0;font-size:13px\">Para responder: "
                            + "<code>RESTUT[\"codigo\",\"aceptada|rechazada\",\"motivo\"]</code></p>");
        }
        return ctx.exito("Tutorías", html.toString(), total + " registro(s)");
    }

    /** RESTUT["codigo","aceptada|rechazada","motivo"] */
    public Respuesta responderTutoria(Contexto ctx) throws SQLException {
        Proyecto p = buscar(ctx.p(0));
        Asignacion a = dProyecto.asignacionVigente(p.id());
        if (a == null || a.docenteId() == null || a.docenteId() != ctx.usuario().id()
                || !a.estado().equals("pendiente_respuesta")) {
            throw new ErrorComando("No tiene una tutoría pendiente de respuesta en " + p.codigo() + ".");
        }
        String respuesta = ctx.p(1).trim().toLowerCase();
        String motivo = Validador.textoOpcional(ctx.p(2), 255, "motivo");
        long yo = ctx.usuario().id();

        switch (respuesta) {
            case "aceptada", "acepto", "aceptar" -> {
                Transaccion.ejecutar(cn -> {
                    dProyecto.marcarAsignacion(cn, a.id(), "aceptada", motivo);
                    dProyecto.cambiarEtapa(cn, p.id(), "perfil");
                    dHistorial.registrar(cn, p.id(), "tutor", "completada", "Tutor " + a.docente() + " aceptó", yo);
                    dHistorial.registrar(cn, p.id(), "perfil", "iniciada", "Registro del perfil", yo);
                });
                String limite = dGestion.limitePerfil(p.gestionId());
                avisarEstudiantes(p, "Su tutor aceptó",
                        a.docente() + " aceptó ser tutor de su proyecto. Ya puede registrar el perfil con INSPRF"
                                + (limite == null ? "" : " (fecha límite: " + limite + ")") + ".");
                return ctx.exito("Tutoría aceptada",
                        "<p style=\"margin:0\">Ahora es tutor de <b>" + RespuestaHtml.esc(p.codigo()) + "</b>: "
                                + RespuestaHtml.esc(p.titulo()) + ". Se avisó a los estudiantes.</p>",
                        "Acepta " + p.codigo());
            }
            case "rechazada", "rechazo", "rechazar" -> {
                if (motivo == null) throw new ErrorComando("Indique el motivo del rechazo.");
                Transaccion.ejecutar(cn -> {
                    dProyecto.marcarAsignacion(cn, a.id(), "rechazada", motivo);
                    dHistorial.registrar(cn, p.id(), "tutor", "observada", a.docente() + " rechazó: " + motivo, yo);
                });
                avisarEstudiantes(p, "Su propuesta de tutor fue rechazada",
                        a.docente() + " no aceptó la tutoría (" + motivo + "). Proponga otro tutor con SOLTUT o pida "
                                + "a Dirección con SOLTUT[\"" + p.codigo() + "\",\"DIRECCION\"].");
                if (a.origen().equals("designado_direccion")) {
                    notificar.aRoles("Tutor designado rechazó", a.docente() + " rechazó la tutoría de " + p.codigo()
                            + " (" + motivo + "). Designe otro con DESTUT.", "LISTUT[\"*\"]", "director");
                }
                return ctx.exito("Tutoría rechazada",
                        "<p style=\"margin:0\">Se registró su rechazo y se avisó a los estudiantes.</p>",
                        "Rechaza " + p.codigo());
            }
            default -> throw new ErrorComando("La respuesta debe ser aceptada o rechazada.");
        }
    }

    // ================================================================== perfil

    /** INSPRF["codigo","titulo","url_pdf","objetivo_general"(,"objetivos_especificos","introduccion")] */
    public Respuesta registrarPerfil(Contexto ctx) throws SQLException {
        Proyecto p = proyectoPropio(ctx, ctx.p(0));
        exigirEtapa(p, "perfil");
        if (dGestion.perfilVencido(p.gestionId())) {                                       // CU3-E3
            throw new ErrorComando("Venció la fecha límite de perfiles (" + dGestion.limitePerfil(p.gestionId())
                    + "): el perfil ya no se puede editar. Comuníquese con Coordinación.");
        }
        String titulo = ctx.p(1).trim();
        if (titulo.length() < 5 || titulo.length() > 255) throw new ErrorComando("El título debe tener entre 5 y 255 caracteres.");
        String url = Validador.url(ctx.p(2), "url_pdf");
        String objetivo = ctx.p(3).trim();
        if (objetivo.length() < 10) throw new ErrorComando("Escriba el objetivo general (al menos 10 caracteres).");
        String especificos = ctx.comando().parametros().size() > 4 && !ctx.esVacio(4) ? ctx.p(4).trim() : "Ver documento del perfil";
        String introduccion = ctx.comando().parametros().size() > 5 && !ctx.esVacio(5) ? ctx.p(5).trim() : "Ver documento del perfil";

        long archivo = dArchivo.registrarEnlace(url, "Perfil " + p.codigo(), ctx.usuario().id());
        int version = dPerfil.crearVersion(p.id(), new NuevoPerfil(titulo, introduccion, objetivo, especificos, archivo),
                ctx.usuario().id());
        dProyecto.cambiarTitulo(p.id(), titulo);

        return ctx.exito("Perfil versión " + version + " registrado",
                versionesHtml(p) + bloquePaso("Puede seguir corrigiéndolo (cada INSPRF crea una versión nueva). "
                        + "Cuando esté listo, envíelo a evaluación con ENVPRF[\"" + p.codigo() + "\"]."),
                "Perfil v" + version + " de " + p.codigo());
    }

    /** LISPRF["codigo"] · historial de versiones del perfil */
    public Respuesta listarPerfiles(Contexto ctx) throws SQLException {
        Proyecto p = proyectoAccesible(ctx, ctx.p(0));
        return ctx.exito("Versiones del perfil de " + p.codigo(), versionesHtml(p), "Versiones de " + p.codigo());
    }

    /** ENVPRF["codigo"] · envía el perfil a evaluación de viabilidad */
    public Respuesta enviarPerfil(Contexto ctx) throws SQLException {
        Proyecto p = proyectoPropio(ctx, ctx.p(0));
        exigirEtapa(p, "perfil");
        Perfil perfil = dPerfil.ultimo(p.id());
        if (perfil == null) throw new ErrorComando("Todavía no registró el perfil. Use INSPRF.");
        if (!perfil.estado().equals("borrador")) {
            throw new ErrorComando("La última versión del perfil (v" + perfil.version() + ") está " + perfil.estado()
                    + ". Registre una versión nueva con INSPRF.");
        }
        long yo = ctx.usuario().id();
        Transaccion.ejecutar(cn -> {
            dPerfil.cambiarEstado(cn, perfil.id(), "enviado");
            dProyecto.cambiarEtapa(cn, p.id(), "viabilidad");
            dHistorial.registrar(cn, p.id(), "perfil", "completada", "Perfil v" + perfil.version() + " enviado a viabilidad", yo);
            dHistorial.registrar(cn, p.id(), "viabilidad", "iniciada", "En evaluación de viabilidad", yo);
        });
        int avisados = notificar.aRoles("Perfil por evaluar",
                "El perfil v" + perfil.version() + " del proyecto " + p.codigo() + " (" + p.area() + ") está "
                        + "pendiente de evaluación de viabilidad: " + perfil.titulo() + ".",
                "LISVIA[\"*\"]", "revisante");
        Asignacion tutor = dProyecto.asignacionVigente(p.id());
        if (tutor != null && tutor.docenteId() != null) {
            notificar.aUsuario(tutor.docenteId(), "Su tutorado envió el perfil",
                    "El proyecto " + p.codigo() + " envió el perfil v" + perfil.version() + " a viabilidad.",
                    "LISPRF[\"" + p.codigo() + "\"]");
        }
        return ctx.exito("Perfil enviado a viabilidad",
                "<p style=\"margin:0\">El perfil v" + perfil.version() + " de <b>" + RespuestaHtml.esc(p.codigo())
                        + "</b> pasó a evaluación de viabilidad. Se avisó a " + avisados + " revisante(s) y a su tutor. "
                        + "Recibirá un correo con el resultado.</p>",
                "Perfil v" + perfil.version() + " enviado");
    }

    // ================================================================== utilidades

    Proyecto buscar(String codigo) throws SQLException {
        Proyecto p = dProyecto.porCodigo(codigo.trim());
        if (p == null) throw new ErrorComando("No existe el proyecto " + codigo + ".");
        return p;
    }

    /** El proyecto debe ser del remitente (estudiante integrante). */
    private Proyecto proyectoPropio(Contexto ctx, String codigo) throws SQLException {
        Proyecto p = buscar(codigo);
        if (!dProyecto.esIntegrante(p.id(), ctx.usuario().id())) {
            throw new ErrorComando("Usted no es integrante del proyecto " + p.codigo() + ".");
        }
        return p;
    }

    /**
     * Puede verlo: integrantes, su tutor o tribunal, y los roles con vista general
     * (dashboard, Dirección, revisantes). Si no, CU7-E1.
     */
    Proyecto proyectoAccesible(Contexto ctx, String codigo) throws SQLException {
        Proyecto p = buscar(codigo);
        var u = ctx.usuario();
        boolean general = u.tienePermiso("dashboard.ver") || u.tienePermiso("tutor.designar")
                || u.tienePermiso("tribunal.designar") || u.tienePermiso("viabilidad.evaluar")
                || u.tienePermiso("revision.plazos");
        if (general || dProyecto.esIntegrante(p.id(), u.id()) || dProyecto.esDocenteDelProyecto(p.id(), u.id())) {
            return p;
        }
        throw new ErrorComando("No tiene relación con el proyecto " + p.codigo() + ".");
    }

    private static void exigirEtapa(Proyecto p, String etapa) {
        if (!p.etapa().equals(etapa)) {
            throw new ErrorComando("El proyecto " + p.codigo() + " está en la etapa " + p.etapa()
                    + "; esta acción corresponde a la etapa " + etapa + ".");
        }
    }

    private Docente tutorValido(String ciTexto) throws SQLException {
        String ci = Validador.ci(ciTexto);
        Docente d = dProyecto.docentePorCi(ci);
        if (d == null) throw new ErrorComando("No existe un docente con CI " + ci + ". Vea los tutores con LISDOC[\"*\"].");
        if (!"activo".equals(d.estado())) throw new ErrorComando(d.nombre() + " no está activo.");
        if (!d.roles().contains("tutor")) throw new ErrorComando(d.nombre() + " no tiene el rol tutor.");
        return d;
    }

    private void avisarEstudiantes(Proyecto p, String titulo, String mensaje) throws SQLException {
        for (Estudiante e : dProyecto.estudiantes(p.id())) {
            notificar.aUsuario(e.id(), titulo, mensaje, "VERPRO[\"" + p.codigo() + "\"]");
        }
    }

    private String fichaProyecto(Proyecto p) throws SQLException {
        Asignacion t = dProyecto.asignacionVigente(p.id());
        String tutor = t == null ? "Sin asignar"
                : (t.docente() == null ? "(a designar por Dirección)" : t.docente()) + " · " + estadoTexto(t.estado());
        Perfil perfil = dPerfil.ultimo(p.id());
        return TablaHtml.ficha(
                List.of("Código", "Título", "Área", "Gestión", "Etapa actual", "Estudiantes", "Tutor", "Perfil"),
                List.of(p.codigo(), nvl(p.titulo()), nvl(p.area()), p.gestion(), p.etapa(), nvl(p.estudiantes()), tutor,
                        perfil == null ? "Sin registrar" : "v" + perfil.version() + " · " + perfil.estado()));
    }

    private String versionesHtml(Proyecto p) throws SQLException {
        List<String[]> filas = new ArrayList<>();
        for (String[] v : dPerfil.versiones(p.id())) {
            filas.add(new String[]{RespuestaHtml.esc(v[0]), RespuestaHtml.esc(v[1]), RespuestaHtml.esc(v[2]),
                    RespuestaHtml.esc(v[3]), RespuestaHtml.esc(v[4]), v[5].isEmpty() ? "-" : TablaHtml.enlace(v[5], "Abrir")});
        }
        return TablaHtml.tablaHtml(List.of("Versión", "Título", "Estado", "Fecha", "Registrado por", "Documento"), filas);
    }

    /** Siguiente paso para los estudiantes según la etapa (RNF-04). */
    String paso(Proyecto p) throws SQLException {
        Asignacion t = dProyecto.asignacionVigente(p.id());
        String texto = switch (p.etapa()) {
            case "tutor" -> t == null
                    ? "Proponga un tutor con SOLTUT[\"" + p.codigo() + "\",\"ci_docente\"] (vea los tutores del área con "
                    + "LISDOC[\"" + p.areaId() + "\"]) o pida a Dirección con SOLTUT[\"" + p.codigo() + "\",\"DIRECCION\"]."
                    : "Espere la respuesta del tutor (estado: " + estadoTexto(t.estado()) + ").";
            case "perfil" -> "Registre el perfil con INSPRF y envíelo con ENVPRF[\"" + p.codigo() + "\"].";
            case "viabilidad" -> "Su perfil está en evaluación de viabilidad. Recibirá un correo con el resultado.";
            case "tribunales" -> "Dirección está conformando el cuerpo de tribunales. Vea el avance con LISTRI[\"" + p.codigo() + "\"].";
            case "revision" -> "Envíe su documento a revisión de tribunales con ENVREV[\"" + p.codigo() + "\",\"url_pdf\",\"-\"].";
            case "predefensa" -> "Obtuvo los 3 vistos buenos. Dirección o Coordinación programará la predefensa (vea la fecha con VEREVA cuando le llegue el aviso).";
            case "defensa" -> "Aprobó la predefensa. Dirección o Coordinación programará la defensa final.";
            case "concluido" -> "¡Proyecto concluido! Aprobó la defensa final.";
            case "cancelado" -> "El proyecto fue cancelado. Puede registrar uno nuevo con INSPRO.";
            default -> "Etapa actual: " + p.etapa() + ".";
        };
        return bloquePaso(texto);
    }

    private static String bloquePaso(String texto) {
        return "<p style=\"margin:12px 0 0;padding:8px 12px;background:#F4F6F9;border-left:4px solid #0B3D6E\">"
                + "<b>Siguiente paso:</b> " + RespuestaHtml.esc(texto) + "</p>";
    }

    private String viabilidadHtml(Proyecto p) throws SQLException {
        List<String[]> filas = new ArrayList<>();
        for (String[] v : new org.example.datos.DViabilidad().historial(p.id())) {
            filas.add(new String[]{RespuestaHtml.esc(v[0]), RespuestaHtml.esc(v[1]), RespuestaHtml.esc(v[2]),
                    RespuestaHtml.esc(v[3]), RespuestaHtml.esc(v[4]), v[5].isEmpty() ? "-" : TablaHtml.enlace(v[5], "Abrir")});
        }
        if (filas.isEmpty()) return "";
        return "<p style=\"margin:12px 0 4px;font-weight:bold\">Evaluación de viabilidad</p>"
                + TablaHtml.tablaHtml(List.of("Perfil", "Resultado", "Revisante", "Observaciones", "Fecha", "Informe"), filas);
    }

    private static String estadoTexto(String estado) {
        return estado.equals("pendiente_respuesta") ? "pendiente de respuesta" : estado.replace('_', ' ');
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}