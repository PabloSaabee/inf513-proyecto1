package org.example.negocio;

import org.example.datos.DArchivo;
import org.example.datos.DGestion;
import org.example.datos.DGestion.Gestion;
import org.example.datos.DSolicitud;
import org.example.datos.DSolicitud.Requisito;
import org.example.datos.DSolicitud.Solicitud;
import org.example.interprete.Contexto;
import org.example.interprete.ErrorComando;
import org.example.interprete.Respuesta;
import org.example.presentacion.RespuestaHtml;
import org.example.presentacion.TablaHtml;
import org.example.util.Validador;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * CU2 · Gestión de Habilitación Académica de Solicitudes.
 *
 * Estados de la solicitud:
 *   borrador ──ENVSOL──► enviada ──RESSOL──► habilitada
 *       ▲                   │                rechazada
 *       └──── observada ◄───┘  (el estudiante reemplaza lo observado y reenvía)
 *
 * Regla RF-04 / E2: solo se habilita con 100 % de malla, GRL inscrito y todos los
 * requisitos obligatorios válidos.
 */
public class NHabilitacion {

    private static final List<String> ESTADOS = List.of("borrador", "enviada", "observada", "habilitada", "rechazada");

    private final DSolicitud dSolicitud = new DSolicitud();
    private final DGestion dGestion = new DGestion();
    private final DArchivo dArchivo = new DArchivo();
    private final NNotificacion notificar = new NNotificacion();

    // ================================================================== estudiante

    /** LISTRE["*"] · tipos de requisito */
    public Respuesta listarTipos(Contexto ctx) throws SQLException {
        List<String[]> tipos = dSolicitud.tipos();
        String html = TablaHtml.tabla(List.of("Id", "Requisito", "Obligatorio"), tipos)
                + "<p style=\"margin:8px 0 0;font-size:13px\">Para cargar uno: "
                + "<code>INSREQ[\"id\",\"enlace_al_archivo\"]</code> (PDF, JPG o PNG en Drive u OneDrive).</p>";
        return ctx.exito("Tipos de requisito", html, tipos.size() + " tipos");
    }

    /** INSSOL["*"] · crea (o muestra) mi solicitud de la gestión activa */
    public Respuesta crear(Contexto ctx) throws SQLException {
        Gestion g = gestionActiva();
        exigirEstudiante(ctx);
        Solicitud s = dSolicitud.deEstudiante(ctx.usuario().id(), g.id());
        if (s != null) {
            // CU2-E3: ya existe una solicitud de la misma gestión → se reutiliza
            return ctx.exito("Ya tiene una solicitud en la gestión " + g.codigo(),
                    detalle(s) + siguientePaso(s), "Solicitud existente " + s.id());
        }
        long id = dSolicitud.crear(ctx.usuario().id(), g.id());
        Solicitud nueva = dSolicitud.porId(id);
        return ctx.exito("Solicitud creada", detalle(nueva) + siguientePaso(nueva), "Solicitud " + id + " creada");
    }

    /** INSREQ["tipo_id","url"] · carga o reemplaza un requisito */
    public Respuesta cargarRequisito(Contexto ctx) throws SQLException {
        int tipoId = Validador.entero(ctx.p(0), "tipo_id");
        String nombreTipo = dSolicitud.nombreTipo(tipoId);
        if (nombreTipo == null) {
            throw new ErrorComando("No existe el tipo de requisito " + tipoId + ". Envíe LISTRE[\"*\"] para ver los tipos.");
        }
        String url = Validador.url(ctx.p(1), "enlace al archivo");

        Solicitud s = solicitudPropiaOCrear(ctx);
        if (!s.estado().equals("borrador") && !s.estado().equals("observada")) {
            throw new ErrorComando("Su solicitud está " + s.estado() + " y ya no se puede modificar.");
        }
        long archivoId = dArchivo.registrarEnlace(url, nombreTipo, ctx.usuario().id());
        boolean reemplazo = dSolicitud.guardarRequisito(s.id(), tipoId, archivoId);

        Solicitud act = dSolicitud.porId(s.id());
        return ctx.exito(reemplazo ? nombreTipo + " reemplazado" : nombreTipo + " cargado",
                detalle(act) + siguientePaso(act), (reemplazo ? "Reemplazado " : "Cargado ") + nombreTipo);
    }

    /** ENVSOL["*"] · envía mi solicitud a verificación */
    public Respuesta enviar(Contexto ctx) throws SQLException {
        Gestion g = gestionActiva();
        Solicitud s = dSolicitud.deEstudiante(ctx.usuario().id(), g.id());
        if (s == null) throw new ErrorComando("No tiene solicitud en la gestión " + g.codigo() + ". Envíe INSSOL[\"*\"].");
        if (!s.estado().equals("borrador") && !s.estado().equals("observada")) {
            throw new ErrorComando("Su solicitud ya está " + s.estado() + ".");
        }
        List<String> faltan = dSolicitud.obligatoriosFaltantes(s.id());
        if (!faltan.isEmpty()) {
            throw new ErrorComando("Faltan requisitos obligatorios: " + String.join(", ", faltan)
                    + ". Cárguelos con INSREQ (vea los ids con LISTRE[\"*\"]).");
        }
        List<String> observados = dSolicitud.requisitos(s.id()).stream()
                .filter(r -> r.estado().equals("observado")).map(Requisito::tipo).toList();
        if (!observados.isEmpty()) {
            throw new ErrorComando("Reemplace primero los requisitos observados: " + String.join(", ", observados) + ".");
        }
        boolean reenvio = s.estado().equals("observada");
        dSolicitud.enviar(s.id());

        int avisados = notificar.aRoles(
                reenvio ? "Solicitud corregida por verificar" : "Nueva solicitud de habilitación",
                s.estudiante() + " (registro " + s.registro() + ") " + (reenvio ? "corrigió y reenvió" : "envió")
                        + " su solicitud de habilitación de la gestión " + s.gestion() + ".",
                "VERSOL[\"" + s.id() + "\"]", "secretaria", "academico");

        return ctx.exito(reenvio ? "Solicitud reenviada" : "Solicitud enviada",
                detalle(dSolicitud.porId(s.id()))
                        + "<p style=\"margin:12px 0 0\">Se avisó a Secretaría y Coordinación (" + avisados
                        + " persona(s)). Recibirá un correo con el resultado de la verificación.</p>",
                "Solicitud " + s.id() + " enviada");
    }

    /** VERSOL["*" | "id"] */
    public Respuesta ver(Contexto ctx) throws SQLException {
        Solicitud s;
        if (ctx.esTodos(0)) {
            if (!ctx.usuario().tienePermiso("habilitacion.solicitar")) {
                throw new ErrorComando("Indique el id de la solicitud. Use LISSOL[\"enviada\"] para ver la bandeja.");
            }
            s = dSolicitud.ultimaDeEstudiante(ctx.usuario().id());
            if (s == null) throw new ErrorComando("Todavía no tiene una solicitud. Envíe INSSOL[\"*\"] para crearla.");
        } else {
            s = buscar(ctx.p(0));
            boolean verificador = ctx.usuario().tienePermiso("habilitacion.verificar");
            if (!verificador && s.estudianteId() != ctx.usuario().id()) {
                throw new ErrorComando("La solicitud " + s.id() + " no es suya.");
            }
        }
        String extra = s.estudianteId() == ctx.usuario().id() ? siguientePaso(s) : "";
        return ctx.exito("Solicitud de habilitación N° " + s.id(), detalle(s) + extra, "Solicitud " + s.id());
    }

    // ================================================================== verificación

    /** LISSOL["estado" | "*"] · bandeja de verificación */
    public Respuesta listar(Contexto ctx) throws SQLException {
        String estado = null;
        if (!ctx.esTodos(0)) {
            estado = ctx.p(0).toLowerCase();
            if (!ESTADOS.contains(estado)) {
                throw new ErrorComando("Estado «" + ctx.p(0) + "» no válido. Use: " + String.join(", ", ESTADOS) + " o \"*\".");
            }
        }
        List<String[]> filas = dSolicitud.listar(estado);
        String html = TablaHtml.tabla(List.of("Id", "Estudiante", "Registro", "Gestión", "Estado", "Enviada",
                "Válidos", "Malla", "GRL"), filas)
                + TablaHtml.total(filas.size(), "solicitud", "solicitudes")
                + "<p style=\"margin:8px 0 0;font-size:13px\">Para revisar una: <code>VERSOL[\"id\"]</code></p>";
        return ctx.exito(estado == null ? "Solicitudes de habilitación" : "Solicitudes en estado " + estado,
                html, filas.size() + " solicitud(es)");
    }

    /** VALREQ["id_req","valido|observado","observacion"] */
    public Respuesta validarRequisito(Contexto ctx) throws SQLException {
        long reqId = Validador.entero(ctx.p(0), "id_req");
        Object[] req = dSolicitud.requisitoPorId(reqId);
        if (req == null) throw new ErrorComando("No existe el requisito " + reqId + ". Vea los ids con VERSOL[\"id_solicitud\"].");
        Solicitud s = dSolicitud.porId((Long) req[0]);
        exigirEnVerificacion(s);

        String estado = ctx.p(1).toLowerCase().replace("válido", "valido");
        if (!estado.equals("valido") && !estado.equals("observado")) {
            throw new ErrorComando("El estado debe ser valido u observado.");
        }
        String obs = Validador.textoOpcional(ctx.p(2), 255, "observacion");
        if (estado.equals("observado") && obs == null) {
            throw new ErrorComando("Indique la observación para que el estudiante sepa qué corregir.");
        }
        dSolicitud.validarRequisito(reqId, estado, estado.equals("valido") ? null : obs);
        return ctx.exito(req[1] + " marcado como " + estado, detalle(dSolicitud.porId(s.id())),
                "Requisito " + reqId + " " + estado);
    }

    /** REGMAL["id_sol","porcentaje","si|no"] · RF-04 */
    public Respuesta registrarMalla(Contexto ctx) throws SQLException {
        Solicitud s = buscar(ctx.p(0));
        exigirEnVerificacion(s);
        BigDecimal pct;
        try {
            pct = new BigDecimal(ctx.p(1).replace(",", ".").replace("%", "").trim());
        } catch (NumberFormatException e) {
            throw new ErrorComando("El porcentaje de malla debe ser un número entre 0 y 100.");
        }
        if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ErrorComando("El porcentaje de malla debe estar entre 0 y 100.");
        }
        boolean grl = siNo(ctx.p(2), "GRL inscrito");
        dSolicitud.registrarMalla(s.estudianteId(), pct, grl);
        return ctx.exito("Malla y GRL registrados", detalle(dSolicitud.porId(s.id())),
                "Malla " + pct + " % · GRL " + (grl ? "sí" : "no"));
    }

    /** RESSOL["id_sol","habilitada|observada|rechazada","observacion"] */
    public Respuesta resolver(Contexto ctx) throws SQLException {
        Solicitud s = buscar(ctx.p(0));
        exigirEnVerificacion(s);
        String estado = ctx.p(1).toLowerCase();
        String obs = Validador.textoOpcional(ctx.p(2), 1000, "observacion");

        switch (estado) {
            case "habilitada" -> {
                List<String> problemas = new ArrayList<>();
                for (String f : dSolicitud.obligatoriosFaltantes(s.id())) problemas.add("falta " + f);
                for (Requisito r : dSolicitud.requisitos(s.id())) {
                    if (r.obligatorio() && !r.estado().equals("valido")) problemas.add(r.tipo() + " está " + r.estado());
                }
                if (s.malla().compareTo(BigDecimal.valueOf(100)) < 0) {
                    problemas.add("malla aprobada " + s.malla().stripTrailingZeros().toPlainString() + " % (se exige 100 %)");
                }
                if (!s.grl()) problemas.add("GRL no inscrito");
                if (!problemas.isEmpty()) {
                    throw new ErrorComando("No se puede habilitar: " + String.join("; ", problemas)
                            + ". Use VALREQ y REGMAL, u observe la solicitud.");
                }
            }
            case "observada", "rechazada" -> {
                if (obs == null) throw new ErrorComando("Indique el motivo para que el estudiante lo sepa.");
            }
            default -> throw new ErrorComando("El resultado debe ser habilitada, observada o rechazada.");
        }

        dSolicitud.resolver(s.id(), estado, obs, ctx.usuario().id());
        String mensaje = switch (estado) {
            case "habilitada" -> "Su solicitud de habilitación de la gestión " + s.gestion()
                    + " fue HABILITADA. Ya puede registrar su proyecto de grado con INSPRO.";
            case "observada" -> "Su solicitud de habilitación fue OBSERVADA: " + obs
                    + ". Reemplace los requisitos observados con INSREQ y reenvíela con ENVSOL[\"*\"].";
            default -> "Su solicitud de habilitación fue RECHAZADA: " + obs + ".";
        };
        notificar.aUsuario(s.estudianteId(), "Solicitud " + estado, mensaje, "VERSOL[\"*\"]");

        return ctx.exito("Solicitud " + estado, detalle(dSolicitud.porId(s.id()))
                        + "<p style=\"margin:12px 0 0\">Se notificó a " + RespuestaHtml.esc(s.estudiante()) + ".</p>",
                "Solicitud " + s.id() + " " + estado);
    }

    // ================================================================== utilidades

    private Gestion gestionActiva() throws SQLException {
        Gestion g = dGestion.activa();
        if (g == null) throw new ErrorComando("No hay una gestión activa. Comuníquese con Coordinación.");
        return g;
    }

    private void exigirEstudiante(Contexto ctx) throws SQLException {
        if (!dSolicitud.esEstudiante(ctx.usuario().id())) {
            throw new ErrorComando("Su usuario no está registrado como estudiante.");
        }
    }

    /** La solicitud del estudiante en la gestión activa; si no existe, la crea en borrador. */
    private Solicitud solicitudPropiaOCrear(Contexto ctx) throws SQLException {
        Gestion g = gestionActiva();
        exigirEstudiante(ctx);
        Solicitud s = dSolicitud.deEstudiante(ctx.usuario().id(), g.id());
        if (s != null) return s;
        return dSolicitud.porId(dSolicitud.crear(ctx.usuario().id(), g.id()));
    }

    private Solicitud buscar(String idTexto) throws SQLException {
        long id = Validador.entero(idTexto, "id de solicitud");
        Solicitud s = dSolicitud.porId(id);
        if (s == null) throw new ErrorComando("No existe la solicitud " + id + ".");
        return s;
    }

    private static void exigirEnVerificacion(Solicitud s) {
        if (!s.estado().equals("enviada")) {
            throw new ErrorComando("La solicitud " + s.id() + " está " + s.estado()
                    + ": solo se verifican solicitudes en estado enviada.");
        }
    }

    private static boolean siNo(String v, String campo) {
        return switch (v.trim().toLowerCase()) {
            case "si", "sí", "s", "true", "1" -> true;
            case "no", "n", "false", "0" -> false;
            default -> throw new ErrorComando("El campo " + campo + " debe ser si o no.");
        };
    }

    /** Ficha de la solicitud + tabla de requisitos + obligatorios que faltan. */
    private String detalle(Solicitud s) throws SQLException {
        String ficha = TablaHtml.ficha(
                List.of("Solicitud", "Estudiante", "Registro", "Gestión", "Estado", "Enviada",
                        "Malla aprobada", "GRL inscrito", "Verificada por", "Fecha verificación", "Observación"),
                List.of(String.valueOf(s.id()), s.estudiante(), s.registro(), s.gestion(), s.estado(),
                        nvl(s.fechaEnvio()), s.malla().stripTrailingZeros().toPlainString() + " %",
                        s.grl() ? "Sí" : "No", nvl(s.verificadoPor()), nvl(s.fechaVerificacion()),
                        nvl(s.observacion())));

        List<String[]> filas = new ArrayList<>();
        for (Requisito r : dSolicitud.requisitos(s.id())) {
            filas.add(new String[]{String.valueOf(r.id()), RespuestaHtml.esc(r.tipo()),
                    TablaHtml.enlace(r.enlace(), "Abrir"), RespuestaHtml.esc(r.estado()),
                    RespuestaHtml.esc(nvl(r.observacion()))});
        }
        String req = "<p style=\"margin:12px 0 4px;font-weight:bold\">Requisitos presentados</p>"
                + TablaHtml.tablaHtml(List.of("Id", "Requisito", "Archivo", "Estado", "Observación"), filas);

        List<String> faltan = dSolicitud.obligatoriosFaltantes(s.id());
        if (!faltan.isEmpty()) {
            req += "<p style=\"margin:8px 0 0;color:#8A6100;font-size:13px\">Faltan: "
                    + RespuestaHtml.esc(String.join(", ", faltan)) + "</p>";
        }
        return ficha + req;
    }

    /** Qué debe hacer el estudiante ahora (RNF-04: pasos siguientes visibles). */
    private String siguientePaso(Solicitud s) throws SQLException {
        String paso = switch (s.estado()) {
            case "borrador" -> dSolicitud.obligatoriosFaltantes(s.id()).isEmpty()
                    ? "Ya cargó todos los requisitos. Envíe ENVSOL[\"*\"] para mandarla a verificación."
                    : "Cargue los requisitos con INSREQ[\"tipo_id\",\"enlace\"] (vea los tipos con LISTRE[\"*\"]) y luego envíe ENVSOL[\"*\"].";
            case "observada" -> "Reemplace los requisitos observados con INSREQ y reenvíe con ENVSOL[\"*\"].";
            case "enviada" -> "Su solicitud está en verificación. Recibirá un correo con el resultado.";
            case "habilitada" -> "Está habilitado. Siguiente paso: registrar su proyecto de grado (INSPRO).";
            default -> "Su solicitud fue rechazada. Comuníquese con Coordinación.";
        };
        return "<p style=\"margin:12px 0 0;padding:8px 12px;background:#F4F6F9;border-left:4px solid #0B3D6E\">"
                + "<b>Siguiente paso:</b> " + RespuestaHtml.esc(paso) + "</p>";
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}