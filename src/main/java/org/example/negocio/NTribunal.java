package org.example.negocio;

import org.example.datos.DArchivo;
import org.example.datos.DHistorial;
import org.example.datos.DPerfil;
import org.example.datos.DPerfil.Perfil;
import org.example.datos.DProyecto;
import org.example.datos.DProyecto.Asignacion;
import org.example.datos.DProyecto.Docente;
import org.example.datos.DProyecto.Estudiante;
import org.example.datos.DProyecto.Proyecto;
import org.example.datos.DTribunal;
import org.example.datos.DTribunal.Designacion;
import org.example.datos.DViabilidad;
import org.example.datos.Transaccion;
import org.example.interprete.Contexto;
import org.example.interprete.ErrorComando;
import org.example.interprete.Respuesta;
import org.example.presentacion.RespuestaHtml;
import org.example.presentacion.TablaHtml;
import org.example.util.Validador;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CU4 · Gestión de Evaluación de Viabilidad y Conformar Cuerpo de Tribunales.
 *
 *   viabilidad ──EVAVIA aprobado / aprobado_obs──► tribunales ──DESTRI──► 3 × RESTRI aceptada ──► revision (CU5)
 *              ──EVAVIA observado──► perfil (S1: nueva versión)
 *              ──EVAVIA no_viable──► cancelado (E1)
 */
public class NTribunal {

    private static final List<String> RESULTADOS = List.of("aprobado", "aprobado_obs", "observado", "no_viable");

    private final DProyecto dProyecto = new DProyecto();
    private final DPerfil dPerfil = new DPerfil();
    private final DViabilidad dViabilidad = new DViabilidad();
    private final DTribunal dTribunal = new DTribunal();
    private final DHistorial dHistorial = new DHistorial();
    private final DArchivo dArchivo = new DArchivo();
    private final NProyecto nProyecto = new NProyecto();
    private final NNotificacion notificar = new NNotificacion();

    // ================================================================== viabilidad

    /** LISVIA["*"] · perfiles por evaluar */
    public Respuesta listarViabilidad(Contexto ctx) throws SQLException {
        List<String[]> filas = new ArrayList<>();
        for (String[] f : dViabilidad.pendientes(ctx.usuario().id())) {
            String[] h = new String[f.length];
            for (int i = 0; i < f.length - 1; i++) h[i] = RespuestaHtml.esc(f[i]);
            h[f.length - 1] = f[f.length - 1].isEmpty() ? "-" : TablaHtml.enlace(f[f.length - 1], "Abrir");
            filas.add(h);
        }
        String html = TablaHtml.tablaHtml(List.of("Proyecto", "Título", "Versión", "Área", "De su área", "Estudiantes",
                "Tutor", "Enviado", "Perfil"), filas)
                + TablaHtml.total(filas.size(), "perfil", "perfiles")
                + "<p style=\"margin:8px 0 0;font-size:13px\">Para evaluar: <code>EVAVIA[\"codigo\","
                + "\"aprobado|aprobado_obs|observado|no_viable\",\"observaciones\",\"url_informe\" ó \"-\"]</code></p>";
        return ctx.exito("Perfiles por evaluar", html, filas.size() + " perfil(es) pendientes");
    }

    /** EVAVIA["codigo","resultado","observaciones","url_informe"] */
    public Respuesta evaluarViabilidad(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        if (!p.etapa().equals("viabilidad")) {
            throw new ErrorComando(p.codigo() + " está en la etapa " + p.etapa() + ", no en viabilidad.");
        }
        Perfil perfil = dPerfil.ultimo(p.id());
        if (perfil == null || !(perfil.estado().equals("enviado") || perfil.estado().equals("en_revision"))) {
            throw new ErrorComando("El proyecto " + p.codigo() + " no tiene un perfil enviado pendiente de evaluar.");
        }
        long yo = ctx.usuario().id();
        Long tutor = tutorAceptado(p);
        if (tutor != null && tutor == yo) throw new ErrorComando("No puede evaluar la viabilidad de un proyecto del que es tutor.");

        String resultado = ctx.p(1).trim().toLowerCase().replace(' ', '_').replace("no_viable", "no_viable");
        if (!RESULTADOS.contains(resultado)) {
            throw new ErrorComando("El resultado debe ser: " + String.join(", ", RESULTADOS) + ".");
        }
        String obs = Validador.textoOpcional(ctx.p(2), 2000, "observaciones");
        if (!resultado.equals("aprobado") && obs == null) {
            throw new ErrorComando("Para «" + resultado.replace('_', ' ') + "» escriba las observaciones.");
        }
        Long informe = ctx.esVacio(3) ? null
                : dArchivo.registrarEnlace(Validador.url(ctx.p(3), "url_informe"), "Informe viabilidad " + p.codigo(), yo);

        String etapaNueva = switch (resultado) {
            case "aprobado", "aprobado_obs" -> "tribunales";
            case "observado" -> "perfil";
            default -> "cancelado";
        };
        String comentario = "Perfil v" + perfil.version() + ": " + resultado.replace('_', ' ') + (obs == null ? "" : " · " + obs);
        Transaccion.ejecutar(cn -> {
            dViabilidad.registrar(cn, perfil.id(), yo, resultado, obs, informe);
            dPerfil.cambiarEstado(cn, perfil.id(), resultado);
            dProyecto.cambiarEtapa(cn, p.id(), etapaNueva);
            switch (etapaNueva) {
                case "tribunales" -> {
                    dHistorial.registrar(cn, p.id(), "viabilidad", "completada", comentario, yo);
                    dHistorial.registrar(cn, p.id(), "tribunales", "iniciada", "Pendiente de designar tribunales", yo);
                }
                case "perfil" -> {
                    dHistorial.registrar(cn, p.id(), "viabilidad", "observada", comentario, yo);
                    dHistorial.registrar(cn, p.id(), "perfil", "iniciada", "Corregir el perfil y reenviar", yo);
                }
                default -> dHistorial.registrar(cn, p.id(), "viabilidad", "rechazada", comentario, yo);
            }
        });

        String mensaje = switch (etapaNueva) {
            case "tribunales" -> "El perfil de " + p.codigo() + " fue " + (resultado.equals("aprobado") ? "APROBADO" : "APROBADO CON OBSERVACIONES")
                    + (obs == null ? "" : " (" + obs + ")") + ". Dirección conformará el cuerpo de tribunales.";
            case "perfil" -> "El perfil de " + p.codigo() + " fue OBSERVADO: " + obs
                    + ". Registre una versión corregida con INSPRF y reenvíela con ENVPRF.";
            default -> "El perfil de " + p.codigo() + " fue declarado NO VIABLE: " + obs
                    + ". El proyecto quedó cancelado; puede registrar uno nuevo con INSPRO.";
        };
        avisarEstudiantes(p, "Resultado de viabilidad", mensaje);
        if (tutor != null) notificar.aUsuario(tutor, "Resultado de viabilidad de su tutorado", mensaje, "VERPRO[\"" + p.codigo() + "\"]");
        if (etapaNueva.equals("tribunales")) {
            notificar.aRoles("Proyecto listo para tribunales",
                    "El perfil de " + p.codigo() + " (" + p.area() + ") fue aprobado. Designe 3 tribunales del área.",
                    "LISCAN[\"" + p.codigo() + "\"]", "director");
        }
        return ctx.exito("Viabilidad registrada: " + resultado.replace('_', ' '),
                "<p style=\"margin:0 0 8px\">" + RespuestaHtml.esc(mensaje) + "</p>"
                        + "<p style=\"margin:0;font-size:13px\">Se notificó a los estudiantes y al tutor.</p>",
                p.codigo() + " · " + resultado);
    }

    // ================================================================== tribunales

    /** LISCAN["codigo"] · candidatos del área (sin el tutor) */
    public Respuesta listarCandidatos(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        if (p.areaId() == null) throw new ErrorComando("El proyecto " + p.codigo() + " no tiene área asignada.");
        List<String[]> filas = dTribunal.candidatos(p.id(), p.areaId());
        String html = "<p style=\"margin:0 0 8px\">Docentes con rol tribunal del área <b>" + RespuestaHtml.esc(p.area())
                + "</b>, sin el tutor ni los ya designados (ordenados por menor carga).</p>"
                + TablaHtml.tabla(List.of("CI", "Docente", "Áreas", "Tribunales vigentes"), filas)
                + actualHtml(p)
                + "<p style=\"margin:8px 0 0;font-size:13px\">Para designar: <code>DESTRI[\"" + RespuestaHtml.esc(p.codigo())
                + "\",\"ci1\",\"ci2\",\"ci3\"]</code> (use \"-\" en una posición para dejarla como está).</p>";
        return ctx.exito("Candidatos a tribunal de " + p.codigo(), html, filas.size() + " candidato(s)");
    }

    /** DESTRI["codigo","ci1","ci2","ci3"] · "-" deja la posición como está */
    public Respuesta designar(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        if (!p.etapa().equals("tribunales")) {
            throw new ErrorComando(p.codigo() + " está en la etapa " + p.etapa()
                    + ". Los tribunales se designan en la etapa tribunales (para cambios excepcionales use REETRI).");
        }
        List<Designacion> vigentes = dTribunal.vigentes(p.id());
        Set<Integer> ocupadas = new HashSet<>();
        Set<Long> docentes = new HashSet<>();
        for (Designacion d : vigentes) {
            ocupadas.add(d.posicion());
            docentes.add(d.docenteId());
        }

        List<Object[]> nuevas = new ArrayList<>();                     // {posicion, Docente}
        for (int pos = 1; pos <= 3; pos++) {
            if (ctx.esVacio(pos)) continue;
            if (ocupadas.contains(pos)) {
                throw new ErrorComando("La posición " + pos + " ya tiene tribunal vigente. Use \"-\" para dejarla o REETRI para reemplazarlo.");
            }
            Docente d = tribunalValido(p, ctx.p(pos));
            if (!docentes.add(d.id())) throw new ErrorComando(d.nombre() + " aparece dos veces en el cuerpo de tribunales.");
            nuevas.add(new Object[]{pos, d});
        }
        if (nuevas.isEmpty()) throw new ErrorComando("Indique al menos un docente (CI) para una posición libre.");

        long yo = ctx.usuario().id();
        Transaccion.ejecutar(cn -> {
            for (Object[] n : nuevas) dTribunal.designar(cn, p.id(), ((Docente) n[1]).id(), (Integer) n[0], yo, null);
            dHistorial.registrar(cn, p.id(), "tribunales", "iniciada", "Designados " + nuevas.size() + " tribunal(es)", yo);
        });
        StringBuilder nombres = new StringBuilder();
        for (Object[] n : nuevas) {
            Docente d = (Docente) n[1];
            nombres.append(nombres.isEmpty() ? "" : ", ").append(d.nombre()).append(" (posición ").append(n[0]).append(")");
            notificar.aUsuario(d.id(), "Fue designado tribunal",
                    "Dirección lo designó tribunal (posición " + n[0] + ") del proyecto " + p.codigo() + ": " + p.titulo()
                            + ". Responda con RESTRI[\"" + p.codigo() + "\",\"aceptada\",\"-\"] o \"rechazada\" con el motivo.",
                    "LISTRI[\"" + p.codigo() + "\"]");
        }
        avisarEstudiantes(p, "Tribunales designados", "Dirección designó: " + nombres + ". Falta que acepten.");
        return ctx.exito("Tribunales designados", actualHtml(p)
                        + "<p style=\"margin:8px 0 0;font-size:13px\">Se notificó a cada tribunal para que acepte o rechace.</p>",
                nuevas.size() + " tribunal(es) en " + p.codigo());
    }

    /** REETRI["codigo","posicion","ci_nuevo","motivo"] · reemplazo excepcional (S2) */
    public Respuesta reemplazar(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        if (!p.etapa().equals("tribunales") && !p.etapa().equals("revision")) {
            throw new ErrorComando("Solo se reemplazan tribunales en las etapas tribunales o revision (" + p.codigo()
                    + " está en " + p.etapa() + ").");
        }
        int pos = Validador.entero(ctx.p(1), "posicion");
        if (pos < 1 || pos > 3) throw new ErrorComando("La posición debe ser 1, 2 o 3.");
        String motivo = Validador.textoOpcional(ctx.p(3), 255, "motivo");
        if (motivo == null) throw new ErrorComando("Indique el motivo del cambio excepcional.");

        Designacion actual = dTribunal.vigentes(p.id()).stream().filter(d -> d.posicion() == pos).findFirst()
                .orElseThrow(() -> new ErrorComando("La posición " + pos + " no tiene tribunal vigente: use DESTRI para designarla."));
        Docente nuevo = tribunalValido(p, ctx.p(2));
        if (nuevo.id() == actual.docenteId()) throw new ErrorComando(nuevo.nombre() + " ya ocupa esa posición.");
        if (dTribunal.vigentes(p.id()).stream().anyMatch(d -> d.docenteId() == nuevo.id())) {
            throw new ErrorComando(nuevo.nombre() + " ya es tribunal vigente de " + p.codigo() + ".");
        }

        long yo = ctx.usuario().id();
        Transaccion.ejecutar(cn -> {
            dTribunal.marcar(cn, actual.id(), "reemplazada", motivo);
            dTribunal.designar(cn, p.id(), nuevo.id(), pos, yo, actual.id());
            dHistorial.registrar(cn, p.id(), "tribunales", "observada",
                    "Reemplazo posición " + pos + ": " + actual.docente() + " → " + nuevo.nombre() + " (" + motivo + ")", yo);
        });
        notificar.aUsuario(actual.docenteId(), "Fue reemplazado como tribunal",
                "Dirección lo reemplazó como tribunal (posición " + pos + ") de " + p.codigo() + ": " + motivo + ".", null);
        notificar.aUsuario(nuevo.id(), "Fue designado tribunal",
                "Dirección lo designó tribunal (posición " + pos + ") del proyecto " + p.codigo() + " en reemplazo de "
                        + actual.docente() + ". Responda con RESTRI[\"" + p.codigo() + "\",\"aceptada\",\"-\"].",
                "LISTRI[\"" + p.codigo() + "\"]");
        avisarEstudiantes(p, "Cambio de tribunal", "Posición " + pos + ": " + actual.docente() + " fue reemplazado por "
                + nuevo.nombre() + " (" + motivo + ").");
        return ctx.exito("Tribunal reemplazado", actualHtml(p), "Reemplazo pos. " + pos + " en " + p.codigo());
    }

    /** LISTRI["*" | "codigo"] */
    public Respuesta listarTribunales(Contexto ctx) throws SQLException {
        if (!ctx.esTodos(0)) {
            Proyecto p = nProyecto.proyectoAccesible(ctx, ctx.p(0));
            String html = actualHtml(p)
                    + "<p style=\"margin:12px 0 4px;font-weight:bold\">Historial de designaciones</p>"
                    + TablaHtml.tabla(List.of("Posición", "Docente", "Estado", "Motivo", "Reemplaza a", "Designado", "Respuesta"),
                    dTribunal.historial(p.id()));
            return ctx.exito("Tribunales de " + p.codigo(), html, "Tribunales de " + p.codigo());
        }
        if (!ctx.usuario().tienePermiso("tribunal.designar") && !ctx.usuario().tienePermiso("tribunal.responder")) {
            throw new ErrorComando("Indique el código del proyecto: LISTRI[\"PG-2-2026-001\"].");
        }
        StringBuilder html = new StringBuilder();
        int total = 0;
        if (ctx.usuario().tienePermiso("tribunal.designar")) {
            List<String[]> f = dTribunal.porConformar();
            total += f.size();
            html.append("<p style=\"margin:0 0 4px;font-weight:bold\">Proyectos por conformar (Dirección)</p>")
                    .append(TablaHtml.tabla(List.of("Proyecto", "Título", "Área", "Aceptados", "Pendientes", "Libres"), f))
                    .append("<p style=\"margin:6px 0 12px;font-size:13px\">Candidatos: <code>LISCAN[\"codigo\"]</code></p>");
        }
        if (ctx.usuario().tienePermiso("tribunal.responder")) {
            List<String[]> f = dTribunal.deDocente(ctx.usuario().id());
            total += f.size();
            html.append("<p style=\"margin:0 0 4px;font-weight:bold\">Mis designaciones como tribunal</p>")
                    .append(TablaHtml.tabla(List.of("Proyecto", "Título", "Posición", "Estado", "Etapa", "Designado"), f))
                    .append("<p style=\"margin:6px 0 0;font-size:13px\">Para responder: "
                            + "<code>RESTRI[\"codigo\",\"aceptada|rechazada\",\"motivo\"]</code></p>");
        }
        return ctx.exito("Tribunales", html.toString(), total + " registro(s)");
    }

    /** RESTRI["codigo","aceptada|rechazada","motivo"] */
    public Respuesta responder(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        long yo = ctx.usuario().id();
        Designacion d = dTribunal.pendienteDe(p.id(), yo);
        if (d == null) throw new ErrorComando("No tiene una designación de tribunal pendiente en " + p.codigo() + ".");
        String respuesta = ctx.p(1).trim().toLowerCase();
        String motivo = Validador.textoOpcional(ctx.p(2), 255, "motivo");

        switch (respuesta) {
            case "aceptada", "acepto", "aceptar" -> {
                boolean[] conformado = {false};
                Transaccion.ejecutar(cn -> {
                    dTribunal.marcar(cn, d.id(), "aceptada", motivo);
                    dHistorial.registrar(cn, p.id(), "tribunales", "iniciada",
                            d.docente() + " aceptó (posición " + d.posicion() + ")", yo);
                    if (p.etapa().equals("revision")) {
                        // Reemplazo durante la revisión: el nuevo tribunal entra a la ronda abierta
                        Long ronda = new org.example.datos.DRevision().rondaAbierta(cn, p.id());
                        if (ronda != null) new org.example.datos.DRevision().agregarRevision(cn, ronda, d.id(), false);
                    }
                    if (p.etapa().equals("tribunales") && dTribunal.aceptadas(cn, p.id()) == 3) {   // paso 9
                        dProyecto.cambiarEtapa(cn, p.id(), "revision");
                        dHistorial.registrar(cn, p.id(), "tribunales", "completada", "Cuerpo de tribunales conformado", yo);
                        dHistorial.registrar(cn, p.id(), "revision", "iniciada", "Pendiente de enviar el documento", yo);
                        conformado[0] = true;
                    }
                });
                if (conformado[0]) {
                    avisarEstudiantes(p, "Tribunales conformados",
                            "Los 3 tribunales aceptaron. Envíe su documento a revisión con ENVREV[\"" + p.codigo()
                                    + "\",\"url_pdf\",\"-\"].");
                    notificar.aRoles("Tribunales conformados", p.codigo() + " ya tiene sus 3 tribunales y pasó a revisión.",
                            "LISTRI[\"" + p.codigo() + "\"]", "director", "academico");
                }
                return ctx.exito("Designación aceptada",
                        "<p style=\"margin:0\">Ahora es tribunal (posición " + d.posicion() + ") de <b>"
                                + RespuestaHtml.esc(p.codigo()) + "</b>."
                                + (conformado[0] ? " Con su aceptación el cuerpo de tribunales quedó conformado y el proyecto pasó a revisión." : "")
                                + "</p>" + actualHtml(p),
                        "Acepta tribunal " + p.codigo());
            }
            case "rechazada", "rechazo", "rechazar" -> {
                if (motivo == null) throw new ErrorComando("Indique el motivo del rechazo.");
                Transaccion.ejecutar(cn -> {
                    dTribunal.marcar(cn, d.id(), "rechazada", motivo);
                    dHistorial.registrar(cn, p.id(), "tribunales", "observada",
                            d.docente() + " rechazó la posición " + d.posicion() + ": " + motivo, yo);
                });
                notificar.aRoles("Tribunal rechazó la designación", d.docente() + " rechazó la posición " + d.posicion()
                        + " de " + p.codigo() + " (" + motivo + "). Designe otro con DESTRI.", "LISCAN[\"" + p.codigo() + "\"]", "director");
                return ctx.exito("Designación rechazada",
                        "<p style=\"margin:0\">Se registró su rechazo y se avisó a Dirección para que designe otro tribunal.</p>",
                        "Rechaza tribunal " + p.codigo());
            }
            default -> throw new ErrorComando("La respuesta debe ser aceptada o rechazada.");
        }
    }

    // ================================================================== utilidades

    private Long tutorAceptado(Proyecto p) throws SQLException {
        Asignacion a = dProyecto.asignacionVigente(p.id());
        return a != null && a.estado().equals("aceptada") ? a.docenteId() : null;
    }

    /** RF-09: docente activo, con rol tribunal, del área del proyecto y que no sea el tutor. */
    private Docente tribunalValido(Proyecto p, String ciTexto) throws SQLException {
        String ci = Validador.ci(ciTexto);
        Docente d = dProyecto.docentePorCi(ci);
        if (d == null) throw new ErrorComando("No existe un docente con CI " + ci + ". Vea los candidatos con LISCAN[\"" + p.codigo() + "\"].");
        if (!"activo".equals(d.estado())) throw new ErrorComando(d.nombre() + " no está activo.");
        if (!d.roles().contains("tribunal")) throw new ErrorComando(d.nombre() + " no tiene el rol tribunal.");
        if (p.areaId() != null && !dTribunal.docenteEnArea(d.id(), p.areaId())) {
            throw new ErrorComando(d.nombre() + " no pertenece al área " + p.area() + " del proyecto.");
        }
        Long tutor = tutorAceptado(p);
        if (tutor != null && tutor == d.id()) throw new ErrorComando(d.nombre() + " es el tutor del proyecto: no puede ser tribunal.");
        return d;
    }

    /** Tabla de los 3 tribunales vigentes (la usa también VERPRO). */
    String tribunalesHtml(Proyecto p) throws SQLException {
        return dTribunal.vigentes(p.id()).isEmpty() ? "" : actualHtml(p);
    }

    private String actualHtml(Proyecto p) throws SQLException {
        List<String[]> filas = new ArrayList<>();
        List<Designacion> vigentes = dTribunal.vigentes(p.id());
        for (int pos = 1; pos <= 3; pos++) {
            final int x = pos;
            Designacion d = vigentes.stream().filter(v -> v.posicion() == x).findFirst().orElse(null);
            filas.add(new String[]{String.valueOf(pos), d == null ? "(libre)" : d.docente(), d == null ? "-" : d.estado()});
        }
        return "<p style=\"margin:12px 0 4px;font-weight:bold\">Cuerpo de tribunales actual · etapa "
                + RespuestaHtml.esc(dProyecto.porId(p.id()).etapa()) + "</p>"
                + TablaHtml.tabla(List.of("Posición", "Docente", "Estado"), filas);
    }

    private void avisarEstudiantes(Proyecto p, String titulo, String mensaje) throws SQLException {
        for (Estudiante e : dProyecto.estudiantes(p.id())) {
            notificar.aUsuario(e.id(), titulo, mensaje, "VERPRO[\"" + p.codigo() + "\"]");
        }
    }
}