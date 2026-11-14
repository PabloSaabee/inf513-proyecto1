package org.example.negocio;

import org.example.datos.DArchivo;
import org.example.datos.DHistorial;
import org.example.datos.DProyecto;
import org.example.datos.DProyecto.Asignacion;
import org.example.datos.DProyecto.Estudiante;
import org.example.datos.DProyecto.Proyecto;
import org.example.datos.DRevision;
import org.example.datos.DRevision.Revision;
import org.example.datos.DRevision.Ronda;
import org.example.datos.DTribunal;
import org.example.datos.DTribunal.Designacion;
import org.example.datos.Transaccion;
import org.example.interprete.Contexto;
import org.example.interprete.ErrorComando;
import org.example.interprete.Respuesta;
import org.example.presentacion.RespuestaHtml;
import org.example.presentacion.TablaHtml;
import org.example.util.Validador;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * CU5 · Gestión de Revisión, Versionado y Control de Plazos de Tribunales.
 *
 *   ENVREV ─► ronda N (vence en 15 días hábiles) ─► cada tribunal: CONREV ─► OBSREV | VBOREV
 *   Cuando responden los 3:  3 vistos buenos ─► predefensa (CU6)
 *                            alguna observación ─► con_observaciones ─► ENVREV con carta ─► ronda N+1
 *   El tribunal que ya dio visto bueno lo conserva en la ronda siguiente (S1).
 */
public class NRevision {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DRevision dRevision = new DRevision();
    private final DProyecto dProyecto = new DProyecto();
    private final DTribunal dTribunal = new DTribunal();
    private final DHistorial dHistorial = new DHistorial();
    private final DArchivo dArchivo = new DArchivo();
    private final NProyecto nProyecto = new NProyecto();
    private final NNotificacion notificar = new NNotificacion();

    // ================================================================== estudiante

    /** ENVREV["codigo","url_pdf","url_carta" | "-"] */
    public Respuesta enviar(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        long yo = ctx.usuario().id();
        if (!dProyecto.esIntegrante(p.id(), yo)) throw new ErrorComando("Usted no es integrante del proyecto " + p.codigo() + ".");
        if (!p.etapa().equals("revision")) {
            throw new ErrorComando(p.codigo() + " está en la etapa " + p.etapa() + ": el documento se envía en la etapa revision.");
        }
        List<Designacion> tribunales = dTribunal.vigentes(p.id());
        List<Long> aceptados = tribunales.stream().filter(d -> d.estado().equals("aceptada")).map(Designacion::id).toList();
        if (aceptados.size() < 3) {
            throw new ErrorComando("El cuerpo de tribunales no está completo (" + aceptados.size()
                    + " de 3 aceptados). Espere a que Dirección lo conforme.");
        }
        dRevision.marcarVencidas();
        Ronda anterior = dRevision.ultima(p.id());
        if (anterior != null && !anterior.estado().equals("con_observaciones")) {
            throw new ErrorComando("La ronda " + anterior.numero() + " está " + anterior.estado().replace("en_revision", "en revisión")
                    + ": espere a que los tribunales terminen de revisar.");
        }
        String url = Validador.url(ctx.p(1), "url_pdf");
        Long carta = null;
        if (!ctx.esVacio(2)) {
            carta = dArchivo.registrarEnlace(Validador.url(ctx.p(2), "url_carta"), "Carta de correcciones " + p.codigo(), yo);
        } else if (anterior != null) {                                                    // RF-15
            throw new ErrorComando("Desde la segunda ronda debe adjuntar la carta de correcciones (enlace) en el tercer parámetro.");
        }
        long documento = dArchivo.registrarEnlace(url, "Documento " + p.codigo(), yo);
        List<Long> conVistoBueno = anterior == null ? List.of() : dRevision.designacionesConVistoBueno(anterior.id());
        int plazo = dRevision.plazoGestion(p.gestionId());
        int numero = dRevision.crearRonda(p.id(), documento, carta, plazo, aceptados, conVistoBueno,
                "Ronda " + (anterior == null ? 1 : anterior.numero() + 1));
        Ronda r = dRevision.ultima(p.id());
        dHistorial.registrar(p.id(), "revision", "iniciada", "Ronda " + numero + " enviada · vence " + r.vencimiento().format(DMY), yo);

        for (Revision rev : dRevision.revisiones(r.id())) {
            if (rev.vistoBueno()) continue;
            notificar.aUsuario(rev.docenteId(), "Documento para revisar",
                    p.codigo() + " envió la ronda " + numero + " de su documento. Plazo: " + plazo + " días hábiles (vence "
                            + r.vencimiento().format(DMY) + "). Confirme la recepción con CONREV[\"" + p.codigo() + "\",\"-\"].",
                    "LISRON[\"" + p.codigo() + "\"]");
        }
        Long tutor = tutor(p);
        if (tutor != null) notificar.aUsuario(tutor, "Su tutorado envió el documento a revisión",
                p.codigo() + " envió la ronda " + numero + " a los tribunales.", "LISRON[\"" + p.codigo() + "\"]");

        String conservados = conVistoBueno.isEmpty() ? "" : "<p style=\"margin:8px 0 0;font-size:13px\">"
                + conVistoBueno.size() + " tribunal(es) conservan su visto bueno de la ronda anterior.</p>";
        return ctx.exito("Ronda " + numero + " enviada a revisión",
                "<p style=\"margin:0\">Los tribunales tienen <b>" + plazo + " días hábiles</b> (vence el <b>"
                        + r.vencimiento().format(DMY) + "</b>, sin contar fines de semana ni feriados).</p>" + conservados
                        + rondasHtml(p),
                "Ronda " + numero + " de " + p.codigo());
    }

    /** LISRON["codigo"] · rondas y detalle de la última */
    public Respuesta listarRondas(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.proyectoAccesible(ctx, ctx.p(0));
        dRevision.marcarVencidas();
        return ctx.exito("Rondas de revisión de " + p.codigo(), rondasHtml(p), "Rondas de " + p.codigo());
    }

    /** LISVER["codigo"] · historial de versiones (RF-11) */
    public Respuesta listarVersiones(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.proyectoAccesible(ctx, ctx.p(0));
        List<String[]> filas = new ArrayList<>();
        for (String[] v : dRevision.versiones(p.id())) {
            filas.add(new String[]{RespuestaHtml.esc(v[0]), v[1], v[2], RespuestaHtml.esc(v[3]), TablaHtml.enlace(v[4], "Abrir")});
        }
        String html = TablaHtml.tablaHtml(List.of("Documento", "Versión", "Fecha", "Comentario", "Archivo"), filas)
                + "<p style=\"margin:8px 0 0;font-size:13px\">Las versiones anteriores nunca se sobrescriben.</p>";
        return ctx.exito("Versiones de " + p.codigo(), html, filas.size() + " versión(es)");
    }

    // ================================================================== tribunal

    /** CONREV["codigo","url_carta" | "-"] · confirma la recepción */
    public Respuesta confirmar(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        Revision rev = miRevision(ctx, p);
        if (rev.recibido()) throw new ErrorComando("Ya confirmó la recepción de esta ronda.");
        Long carta = ctx.esVacio(1) ? null
                : dArchivo.registrarEnlace(Validador.url(ctx.p(1), "url_carta"), "Carta de recepción " + p.codigo(), ctx.usuario().id());
        dRevision.confirmarRecepcion(rev.id(), carta);
        return ctx.exito("Recepción confirmada",
                "<p style=\"margin:0\">Confirmó la recepción del documento de <b>" + RespuestaHtml.esc(p.codigo())
                        + "</b>. Ahora puede registrar observaciones con <code>OBSREV[\"" + RespuestaHtml.esc(p.codigo())
                        + "\",\"observaciones\",\"url_doc\" ó \"-\"]</code> o dar visto bueno con <code>VBOREV[\""
                        + RespuestaHtml.esc(p.codigo()) + "\"]</code>.</p>" + rondasHtml(p),
                "Recibido " + p.codigo());
    }

    /** OBSREV["codigo","observaciones","url_doc" | "-"] */
    public Respuesta observar(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        Revision rev = revisionLista(ctx, p);
        String obs = ctx.p(1).trim();
        if (obs.length() < 5) throw new ErrorComando("Escriba las observaciones (al menos 5 caracteres).");
        Long doc = ctx.esVacio(2) ? null
                : dArchivo.registrarEnlace(Validador.url(ctx.p(2), "url_doc"), "Revisión " + p.codigo(), ctx.usuario().id());
        Transaccion.ejecutar(cn -> dRevision.observar(cn, rev.id(), obs, doc));
        String cierre = evaluarRonda(p, rev.rondaId(), ctx.usuario().id());
        return ctx.exito("Observaciones registradas",
                "<p style=\"margin:0\">Se registraron sus observaciones a <b>" + RespuestaHtml.esc(p.codigo()) + "</b>.</p>"
                        + cierre + rondasHtml(p),
                "Observa " + p.codigo());
    }

    /** VBOREV["codigo"] */
    public Respuesta vistoBueno(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        Revision rev = revisionLista(ctx, p);
        Transaccion.ejecutar(cn -> dRevision.darVistoBueno(cn, rev.id()));
        String cierre = evaluarRonda(p, rev.rondaId(), ctx.usuario().id());
        return ctx.exito("Visto bueno registrado",
                "<p style=\"margin:0\">Dio su visto bueno a <b>" + RespuestaHtml.esc(p.codigo()) + "</b>.</p>" + cierre + rondasHtml(p),
                "Visto bueno " + p.codigo());
    }

    /**
     * RevisionService.evaluarRonda: si ya respondieron los 3, cierra la ronda.
     * @return texto HTML con lo que pasó (vacío si faltan respuestas)
     */
    private String evaluarRonda(Proyecto p, long rondaId, long usuarioId) throws SQLException {
        List<Revision> revs = dRevision.revisiones(rondaId);
        boolean todos = revs.size() == 3 && revs.stream().allMatch(r -> r.estado().equals("observado") || r.estado().equals("visto_bueno"));
        if (!todos) {
            long faltan = revs.stream().filter(r -> !r.estado().equals("observado") && !r.estado().equals("visto_bueno")).count();
            return "<p style=\"margin:8px 0 0;font-size:13px\">Faltan " + faltan + " tribunal(es) por responder en esta ronda.</p>";
        }
        boolean aprobada = revs.stream().allMatch(Revision::vistoBueno);
        Ronda r = dRevision.ultima(p.id());
        Transaccion.ejecutar(cn -> {
            if (aprobada) {
                dRevision.cerrarRonda(cn, rondaId, "aprobada");
                dProyecto.cambiarEtapa(cn, p.id(), "predefensa");
                dHistorial.registrar(cn, p.id(), "revision", "completada", "Ronda " + r.numero() + ": 3 vistos buenos", usuarioId);
                dHistorial.registrar(cn, p.id(), "predefensa", "iniciada", "Pendiente de programar la predefensa", usuarioId);
            } else {
                dRevision.cerrarRonda(cn, rondaId, "con_observaciones");
                dHistorial.registrar(cn, p.id(), "revision", "observada", "Ronda " + r.numero() + " con observaciones", usuarioId);
            }
        });
        if (aprobada) {
            avisarEstudiantes(p, "¡Tres vistos buenos!", "Los 3 tribunales dieron su visto bueno a " + p.codigo()
                    + ". El proyecto pasó a predefensa: Dirección o Coordinación programará la fecha.");
            notificar.aRoles("Proyecto listo para predefensa", p.codigo() + " obtuvo los 3 vistos buenos. Programe la predefensa con PROEVA.",
                    "VERPRO[\"" + p.codigo() + "\"]", "director", "academico");
            Long tutor = tutor(p);
            if (tutor != null) notificar.aUsuario(tutor, "Su tutorado obtuvo los vistos buenos",
                    p.codigo() + " pasó a predefensa.", "VERPRO[\"" + p.codigo() + "\"]");
            return "<p style=\"margin:8px 0 0;padding:8px 12px;background:#E8F5EC;border-left:4px solid #1E7B3A\">"
                    + "Con esta respuesta el proyecto obtuvo los <b>3 vistos buenos</b> y pasó a <b>predefensa</b>.</p>";
        }
        avisarEstudiantes(p, "Ronda " + r.numero() + " con observaciones",
                "Los tribunales terminaron la ronda " + r.numero() + " de " + p.codigo() + " con observaciones. Revíselas con LISRON y "
                        + "envíe la versión corregida con ENVREV[\"" + p.codigo() + "\",\"url_pdf\",\"url_carta_correcciones\"].");
        return "<p style=\"margin:8px 0 0;padding:8px 12px;background:#FFF6E0;border-left:4px solid #8A6100\">"
                + "Los 3 tribunales respondieron: la ronda " + r.numero() + " cerró <b>con observaciones</b> y se avisó a los estudiantes.</p>";
    }

    // ================================================================== Coordinación: plazos

    /** MODPLA["gestion","dias","fecha_limite_perfil" | "-"] */
    public Respuesta definirPlazos(Contexto ctx) throws SQLException {
        String gestion = ctx.p(0).trim();
        int dias = Validador.entero(ctx.p(1), "dias");
        if (dias < 1 || dias > 60) throw new ErrorComando("El plazo debe estar entre 1 y 60 días hábiles.");
        LocalDate limite = ctx.esVacio(2) ? null : Validador.fecha(ctx.p(2), "fecha_limite_perfil");
        if (dRevision.definirPlazos(gestion, dias, limite) == 0) {
            throw new ErrorComando("No existe la gestión " + gestion + ".");
        }
        String html = TablaHtml.tabla(List.of("Gestión", "Inicio", "Fin", "Límite de perfil", "Plazo de revisión", "Activa"),
                dRevision.gestiones())
                + "<p style=\"margin:8px 0 0;font-size:13px\">El plazo nuevo se aplica a las rondas que se envíen desde ahora; "
                + "para una ronda en curso use AMPPLA.</p>";
        return ctx.exito("Plazos de la gestión " + gestion + " actualizados", html, "Plazos " + gestion);
    }

    /** AMPPLA["codigo","dias","motivo"] · S2 */
    public Respuesta ampliar(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        int dias = Validador.entero(ctx.p(1), "dias");
        if (dias < 1 || dias > 30) throw new ErrorComando("La ampliación debe estar entre 1 y 30 días hábiles.");
        String motivo = Validador.textoOpcional(ctx.p(2), 200, "motivo");
        if (motivo == null) throw new ErrorComando("Indique el motivo de la ampliación.");
        dRevision.marcarVencidas();
        Ronda r = dRevision.ultima(p.id());
        if (r == null || !(r.estado().equals("en_revision") || r.estado().equals("vencida"))) {
            throw new ErrorComando(p.codigo() + " no tiene una ronda de revisión abierta.");
        }
        LocalDate[] nuevo = new LocalDate[1];
        long yo = ctx.usuario().id();
        Transaccion.ejecutar(cn -> {
            nuevo[0] = dRevision.ampliar(cn, r.id(), dias);
            dHistorial.registrar(cn, p.id(), "revision", "iniciada",
                    "Plazo de la ronda " + r.numero() + " ampliado +" + dias + " días hábiles (vence " + nuevo[0].format(DMY) + "): " + motivo, yo);
        });
        String msg = "El plazo de la ronda " + r.numero() + " de " + p.codigo() + " se amplió " + dias
                + " días hábiles: ahora vence el " + nuevo[0].format(DMY) + " (" + motivo + ").";
        for (Revision rev : dRevision.revisiones(r.id())) {
            if (!rev.estado().equals("observado") && !rev.estado().equals("visto_bueno")) {
                notificar.aUsuario(rev.docenteId(), "Plazo de revisión ampliado", msg, "LISRON[\"" + p.codigo() + "\"]");
            }
        }
        avisarEstudiantes(p, "Plazo de revisión ampliado", msg);
        return ctx.exito("Plazo ampliado", "<p style=\"margin:0\">" + RespuestaHtml.esc(msg) + "</p>" + rondasHtml(p),
                "+" + dias + " días " + p.codigo());
    }

    /** LISPLA["*"] · control de plazos */
    public Respuesta controlPlazos(Contexto ctx) throws SQLException {
        dRevision.marcarVencidas();
        List<String[]> filas = dRevision.rondasAbiertas();
        String html = TablaHtml.tabla(List.of("Proyecto", "Ronda", "Enviada", "Vence", "Días hábiles restantes",
                "Respondieron", "Pendientes", "Estado"), filas)
                + TablaHtml.total(filas.size(), "ronda abierta", "rondas abiertas")
                + "<p style=\"margin:8px 0 0;font-size:13px\">Días negativos = vencida. Ampliar: <code>AMPPLA[\"codigo\",\"dias\",\"motivo\"]</code>"
                + " · Recordar: <code>NOTREV[\"codigo\"]</code></p>";
        return ctx.exito("Control de plazos de revisión", html, filas.size() + " ronda(s) abiertas");
    }

    /** NOTREV["codigo"] · recordatorio a tribunales pendientes (S3) */
    public Respuesta recordar(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        dRevision.marcarVencidas();
        Ronda r = dRevision.ultima(p.id());
        if (r == null || !(r.estado().equals("en_revision") || r.estado().equals("vencida"))) {
            throw new ErrorComando(p.codigo() + " no tiene una ronda de revisión abierta.");
        }
        List<String> avisados = new ArrayList<>();
        for (Revision rev : dRevision.revisiones(r.id())) {
            if (rev.estado().equals("observado") || rev.estado().equals("visto_bueno")) continue;
            String plazo = r.diasRestantes() >= 0 ? "vence el " + r.vencimiento().format(DMY) + " (" + r.diasRestantes() + " días hábiles)"
                    : "VENCIÓ el " + r.vencimiento().format(DMY);
            notificar.aUsuario(rev.docenteId(), "Recordatorio de revisión",
                    "Tiene pendiente la revisión de la ronda " + r.numero() + " de " + p.codigo() + "; " + plazo + ". "
                            + (rev.recibido() ? "Registre OBSREV o VBOREV." : "Confirme la recepción con CONREV."),
                    "LISRON[\"" + p.codigo() + "\"]");
            avisados.add(rev.docente());
        }
        if (avisados.isEmpty()) throw new ErrorComando("Todos los tribunales ya respondieron la ronda " + r.numero() + ".");
        return ctx.exito("Recordatorio enviado",
                "<p style=\"margin:0\">Se envió el recordatorio a: " + RespuestaHtml.esc(String.join(", ", avisados)) + ".</p>",
                avisados.size() + " recordatorio(s)");
    }

    /** INSFER["fecha","descripcion"] */
    public Respuesta insertarFeriado(Contexto ctx) throws SQLException {
        LocalDate fecha = Validador.fecha(ctx.p(0), "fecha");
        String desc = ctx.p(1).trim();
        if (desc.length() < 3 || desc.length() > 100) throw new ErrorComando("La descripción debe tener entre 3 y 100 caracteres.");
        if (dRevision.existeFeriado(fecha)) throw new ErrorComando("El " + fecha.format(DMY) + " ya está registrado como feriado.");
        dRevision.insertarFeriado(fecha, desc);
        return ctx.exito("Feriado registrado",
                "<p style=\"margin:0 0 8px\">" + fecha.format(DMY) + " · " + RespuestaHtml.esc(desc)
                        + ". Se tendrá en cuenta en los vencimientos que se calculen desde ahora.</p>"
                        + TablaHtml.tabla(List.of("Fecha", "Día", "Descripción"), dRevision.feriados(fecha.getYear())),
                "Feriado " + fecha);
    }

    /** LISFER["anio" | "*"] */
    public Respuesta listarFeriados(Contexto ctx) throws SQLException {
        Integer anio = null;
        if (!ctx.esTodos(0)) {
            anio = Validador.entero(ctx.p(0), "anio");
            if (anio < 2000 || anio > Year.now().getValue() + 5) throw new ErrorComando("Año fuera de rango.");
        }
        List<String[]> filas = dRevision.feriados(anio);
        return ctx.exito(anio == null ? "Feriados" : "Feriados " + anio,
                TablaHtml.tabla(List.of("Fecha", "Día", "Descripción"), filas) + TablaHtml.total(filas.size(), "feriado", "feriados"),
                filas.size() + " feriado(s)");
    }

    // ================================================================== utilidades

    /** Revisión del remitente en la ronda abierta del proyecto. */
    private Revision miRevision(Contexto ctx, Proyecto p) throws SQLException {
        dRevision.marcarVencidas();
        Ronda r = dRevision.ultima(p.id());
        if (r == null || !(r.estado().equals("en_revision") || r.estado().equals("vencida"))) {
            throw new ErrorComando(p.codigo() + " no tiene una ronda de revisión abierta.");
        }
        return dRevision.revisiones(r.id()).stream().filter(x -> x.docenteId() == ctx.usuario().id()).findFirst()
                .orElseThrow(() -> new ErrorComando("Usted no es tribunal vigente de " + p.codigo() + "."));
    }

    /** Revisión lista para observar o dar visto bueno: recibida y sin responder (E3). */
    private Revision revisionLista(Contexto ctx, Proyecto p) throws SQLException {
        Revision rev = miRevision(ctx, p);
        if (!rev.recibido()) {
            throw new ErrorComando("Primero confirme la recepción con CONREV[\"" + p.codigo() + "\",\"-\"].");
        }
        if (rev.estado().equals("observado") || rev.estado().equals("visto_bueno")) {
            throw new ErrorComando("Ya respondió esta ronda (" + rev.estado().replace('_', ' ') + ").");
        }
        return rev;
    }

    private Long tutor(Proyecto p) throws SQLException {
        Asignacion a = dProyecto.asignacionVigente(p.id());
        return a != null && a.estado().equals("aceptada") ? a.docenteId() : null;
    }

    private void avisarEstudiantes(Proyecto p, String titulo, String mensaje) throws SQLException {
        for (Estudiante e : dProyecto.estudiantes(p.id())) {
            notificar.aUsuario(e.id(), titulo, mensaje, "LISRON[\"" + p.codigo() + "\"]");
        }
    }

    /** Rondas del proyecto + detalle por tribunal de la última. */
    private String rondasHtml(Proyecto p) throws SQLException {
        List<String[]> filas = new ArrayList<>();
        for (String[] r : dRevision.rondas(p.id())) {
            filas.add(new String[]{r[0], TablaHtml.enlace(r[1], "Abrir"), r[2].isEmpty() ? "-" : TablaHtml.enlace(r[2], "Abrir"),
                    r[3], RespuestaHtml.esc(r[4]), r[5], r[6], RespuestaHtml.esc(r[7])});
        }
        if (filas.isEmpty()) {
            return "<p style=\"margin:12px 0 0;color:#5B6573\">Todavía no se envió el documento a revisión.</p>";
        }
        StringBuilder sb = new StringBuilder("<p style=\"margin:12px 0 4px;font-weight:bold\">Rondas de revisión</p>")
                .append(TablaHtml.tablaHtml(List.of("Ronda", "Documento", "Carta", "Enviada", "Plazo", "Vence",
                        "Días hábiles restantes", "Estado"), filas));
        Ronda ultima = dRevision.ultima(p.id());
        List<String[]> det = new ArrayList<>();
        for (String[] d : dRevision.detalleRonda(ultima.id())) {
            det.add(new String[]{d[0], RespuestaHtml.esc(d[1]), d[2], RespuestaHtml.esc(d[3]), RespuestaHtml.esc(d[4]),
                    d[5].isEmpty() ? "-" : TablaHtml.enlace(d[5], "Abrir"), d[6]});
        }
        sb.append("<p style=\"margin:12px 0 4px;font-weight:bold\">Ronda ").append(ultima.numero()).append(" por tribunal</p>")
                .append(TablaHtml.tablaHtml(List.of("Pos.", "Tribunal", "Recepción", "Estado", "Observaciones", "Doc.", "Respondió"), det));
        return sb.toString();
    }
}