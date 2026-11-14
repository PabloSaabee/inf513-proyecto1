package org.example.negocio;

import org.example.datos.DGestion;
import org.example.datos.DGestion.Gestion;
import org.example.datos.DHistorial;
import org.example.datos.DMonitoreo;
import org.example.datos.DProyecto;
import org.example.datos.DProyecto.Proyecto;
import org.example.interprete.Contexto;
import org.example.interprete.ErrorComando;
import org.example.interprete.Respuesta;
import org.example.presentacion.RespuestaHtml;
import org.example.presentacion.TablaHtml;
import org.example.util.Validador;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CU7 · Gestión de Monitoreo, Trazabilidad, Dashboard y Calendario Académico.
 */
public class NMonitoreo {

    /** Las 9 etapas del proceso (cancelado se muestra aparte). */
    private static final List<String> ETAPAS = List.of("habilitacion", "tutor", "perfil", "viabilidad", "tribunales",
            "revision", "predefensa", "defensa", "concluido");
    private static final List<String> NOMBRES = List.of("Habilitación", "Tutor", "Perfil", "Viabilidad", "Tribunales",
            "Revisión", "Predefensa", "Defensa", "Concluido");
    private static final Map<String, String> COLORES = Map.of("evaluación", "#0B3D6E", "vencimiento", "#C8102E",
            "feriado", "#8A6100", "evento", "#1E7B3A", "límite de perfil", "#6B3FA0");
    private static final Locale ES = Locale.forLanguageTag("es");

    private final DMonitoreo dMonitoreo = new DMonitoreo();
    private final DProyecto dProyecto = new DProyecto();
    private final DHistorial dHistorial = new DHistorial();
    private final DGestion dGestion = new DGestion();
    private final NProyecto nProyecto = new NProyecto();

    // ================================================================== seguimiento

    /** SEGPRO["*" | "codigo"] · CU7.1 */
    public Respuesta seguimiento(Contexto ctx) throws SQLException {
        String codigo;
        if (ctx.esTodos(0)) {
            codigo = dMonitoreo.codigoDeEstudiante(ctx.usuario().id());
            if (codigo == null) {
                if (ctx.usuario().tieneRol("tutor")) return tutorados(ctx);
                throw new ErrorComando("Todavía no tiene un proyecto. Si está habilitado, regístrelo con INSPRO.");
            }
        } else {
            codigo = ctx.p(0);
        }
        Proyecto p = nProyecto.proyectoAccesible(ctx, codigo);                         // E1: 403
        StringBuilder html = new StringBuilder();
        html.append(TablaHtml.ficha(List.of("Proyecto", "Título", "Estudiantes", "Etapa actual"),
                List.of(p.codigo(), nvl(p.titulo()), nvl(p.estudiantes()),
                        ETAPAS.contains(p.etapa()) ? NOMBRES.get(ETAPAS.indexOf(p.etapa())) : "Cancelado")));
        String nota = dMonitoreo.notaFinal(p.id());
        if (nota != null) {
            html.append("<p style=\"margin:8px 0 0;padding:8px 12px;background:#E8F5EC;border-left:4px solid #1E7B3A\">"
                    + "<b>Nota final de la defensa: " + RespuestaHtml.esc(nota) + "</b></p>");
        }
        html.append(etapasHtml(p.etapa()));
        html.append(nProyecto.paso(p));

        List<String[]> obs = dMonitoreo.observacionesPendientes(p.id(), p.etapa());
        if (!obs.isEmpty()) {
            html.append(subtitulo("Observaciones pendientes"))
                    .append(TablaHtml.tabla(List.of("Origen", "De", "Observación", "Fecha"), obs));
        }
        List<String[]> fechas = dMonitoreo.proximasFechas(p.id());
        html.append(subtitulo("Próximas fechas"))
                .append(fechas.isEmpty() ? "<p style=\"margin:0;color:#5B6573\">No hay fechas próximas.</p>"
                        : TablaHtml.tabla(List.of("Fecha", "Qué"), fechas));
        html.append(subtitulo("Línea de tiempo"))
                .append(TablaHtml.tabla(List.of("Fecha", "Etapa", "Estado", "Detalle", "Usuario"), dHistorial.todos(p.id())));
        return ctx.exito("Seguimiento de " + p.codigo(), html.toString(), "Seguimiento " + p.codigo());
    }

    /** Tutor sin proyecto propio: lista de tutorados. */
    private Respuesta tutorados(Contexto ctx) throws SQLException {
        List<String[]> filas = dProyecto.tutoriasDe(ctx.usuario().id()).stream()
                .filter(f -> f[4].equals("aceptada")).map(f -> new String[]{f[0], f[1], f[2], f[5]}).toList();
        String html = TablaHtml.tabla(List.of("Proyecto", "Título", "Estudiantes", "Etapa"), filas)
                + "<p style=\"margin:8px 0 0;font-size:13px\">Para ver el seguimiento de uno: <code>SEGPRO[\"codigo\"]</code></p>";
        return ctx.exito("Mis tutorados", html, filas.size() + " tutorado(s)");
    }

    // ================================================================== dashboard

    /** DASIND["gestion" | "*"] · CU7.2 */
    public Respuesta dashboard(Contexto ctx) throws SQLException {
        Integer gestionId = null;
        String nombre = "todas las gestiones";
        if (!ctx.esTodos(0)) {
            Gestion g = dGestion.porCodigo(ctx.p(0).trim());
            if (g == null) throw new ErrorComando("No existe la gestión " + ctx.p(0) + ".");
            gestionId = g.id();
            nombre = "gestión " + g.codigo();
        }
        Map<String, Integer> ind = dMonitoreo.indicadores(gestionId);
        StringBuilder html = new StringBuilder(tarjetas(ind));

        List<String[]> etapas = dMonitoreo.porEtapa(gestionId);
        int max = etapas.stream().mapToInt(e -> Integer.parseInt(e[1])).max().orElse(0);
        html.append(subtitulo("Proyectos por etapa")).append(barras(etapas, Math.max(max, 1)));

        List<String[]> alertas = dMonitoreo.alertasPlazos(gestionId);
        html.append(subtitulo("Plazos vencidos o por vencer (3 días hábiles o menos)"))
                .append(alertas.isEmpty() ? "<p style=\"margin:0;color:#1E7B3A\">Ninguno. ✔</p>"
                        : TablaHtml.tabla(List.of("Proyecto", "Ronda", "Vence", "Días hábiles"), alertas));
        html.append(subtitulo("Próximas evaluaciones"))
                .append(TablaHtml.tabla(List.of("Fecha y hora", "Proyecto", "Tipo", "Ambiente"), dMonitoreo.proximasEvaluaciones(gestionId)));
        List<String[]> proyectos = dMonitoreo.proyectos(gestionId);
        html.append(subtitulo("Proyectos"))
                .append(TablaHtml.tabla(List.of("Código", "Título", "Etapa", "Estudiantes", "Tutor", "Vence revisión", "Próx. evaluación"),
                        proyectos))
                .append("<p style=\"margin:8px 0 0;font-size:13px\">Calendario: <code>LISCAL[\"*\"]</code> · "
                        + "Detalle: <code>VERPRO[\"codigo\"]</code></p>");
        return ctx.exito("Dashboard · " + nombre, html.toString(), "Dashboard " + nombre);
    }

    // ================================================================== calendario

    /** LISCAL["aaaa-mm" | "*"] · CU7.3 */
    public Respuesta calendario(Contexto ctx) throws SQLException {
        YearMonth ym;
        if (ctx.esTodos(0)) {
            ym = YearMonth.now();
        } else {
            try {
                ym = YearMonth.parse(ctx.p(0).trim());
            } catch (RuntimeException e) {
                throw new ErrorComando("Indique el mes con formato aaaa-mm (por ejemplo 2026-11) o \"*\" para el mes actual.");
            }
        }
        List<String[]> eventos = dMonitoreo.eventosMes(ym.getYear(), ym.getMonthValue());
        String mes = ym.getMonth().getDisplayName(TextStyle.FULL, ES);
        String html = grilla(ym, eventos) + leyenda()
                + subtitulo("Detalle del mes") + detalle(ym, eventos)
                + "<p style=\"margin:8px 0 0;font-size:13px\">"
                + (ctx.usuario().tienePermiso("calendario.gestionar")
                ? "Agregar: <code>INSEVE[\"titulo\",\"descripcion\",\"aaaa-mm-dd hh:mm\",\"-\"]</code> · Eliminar: <code>DELEVE[\"id\"]</code> · " : "")
                + "Otro mes: <code>LISCAL[\"" + ym.plusMonths(1) + "\"]</code></p>";
        return ctx.exito("Calendario académico · " + mes + " " + ym.getYear(), html, eventos.size() + " actividad(es)");
    }

    /** INSEVE["titulo","descripcion","inicio","fin" | "-"] · CU7.4 */
    public Respuesta insertarEvento(Contexto ctx) throws SQLException {
        Gestion g = dGestion.activa();
        if (g == null) throw new ErrorComando("No hay una gestión activa.");
        String titulo = ctx.p(0).trim();
        if (titulo.length() < 3 || titulo.length() > 150) throw new ErrorComando("El título debe tener entre 3 y 150 caracteres.");
        String desc = Validador.textoOpcional(ctx.p(1), 255, "descripcion");
        if (desc != null && desc.toLowerCase().startsWith("evaluacion:")) {                     // E2
            throw new ErrorComando("La descripción no puede empezar con «evaluacion:» (está reservada para las evaluaciones).");
        }
        LocalDateTime inicio = Validador.fechaHora(ctx.p(2), "inicio");
        LocalDateTime fin = ctx.esVacio(3) ? null : Validador.fechaHora(ctx.p(3), "fin");
        if (fin != null && !fin.isAfter(inicio)) throw new ErrorComando("La fecha de fin debe ser posterior al inicio.");
        long id = dMonitoreo.insertarEvento(g.id(), titulo, desc, inicio, fin, ctx.usuario().id());
        YearMonth ym = YearMonth.from(inicio);
        return ctx.exito("Evento registrado (id " + id + ")",
                grilla(ym, dMonitoreo.eventosMes(ym.getYear(), ym.getMonthValue())) + leyenda(),
                "Evento " + id);
    }

    /** DELEVE["id"] · CU7.4 */
    public Respuesta eliminarEvento(Contexto ctx) throws SQLException {
        long id = Validador.entero(ctx.p(0), "id");
        String[] ev = dMonitoreo.evento(id);
        if (ev == null) throw new ErrorComando("No existe el evento " + id + ". Vea los ids con LISCAL.");
        if (ev[1].startsWith("evaluacion:")) {
            throw new ErrorComando("El evento " + id + " corresponde a una evaluación: se cambia con REPEVA (CU6), no se elimina aquí.");
        }
        dMonitoreo.eliminarEvento(id);
        return ctx.exito("Evento eliminado",
                "<p style=\"margin:0\">Se eliminó «" + RespuestaHtml.esc(ev[0]) + "» del " + ev[2] + ".</p>", "Elimina evento " + id);
    }

    // ================================================================== notificaciones

    /** LISNOT["*" | "no_leidas"] · CU7.5 */
    public Respuesta notificaciones(Contexto ctx) throws SQLException {
        boolean soloNuevas = !ctx.esTodos(0);
        if (soloNuevas && !ctx.p(0).trim().equalsIgnoreCase("no_leidas")) {
            throw new ErrorComando("Use LISNOT[\"*\"] (últimas 20) o LISNOT[\"no_leidas\"].");
        }
        long yo = ctx.usuario().id();
        int nuevas = dMonitoreo.noLeidas(yo);
        List<String[]> filas = dMonitoreo.notificaciones(yo, soloNuevas, 20);
        List<Long> ids = new ArrayList<>();
        List<String[]> tabla = new ArrayList<>();
        for (String[] f : filas) {
            ids.add(Long.parseLong(f[0]));
            tabla.add(new String[]{f[1], f[2], f[3], f[4], f[5]});
        }
        dMonitoreo.marcarLeidas(yo, ids);
        String html = "<p style=\"margin:0 0 8px\">Tenía <b>" + nuevas + "</b> notificación(es) sin leer.</p>"
                + TablaHtml.tabla(List.of("Fecha", "Título", "Mensaje", "Ver con", "Estado"), tabla)
                + "<p style=\"margin:8px 0 0;font-size:13px\">Las notificaciones mostradas quedaron marcadas como leídas.</p>";
        return ctx.exito(soloNuevas ? "Notificaciones sin leer" : "Mis notificaciones", html, filas.size() + " notificación(es)");
    }

    // ================================================================== HTML

    private static String etapasHtml(String actual) {
        StringBuilder sb = new StringBuilder(subtitulo("Etapas del proceso"));
        if (actual.equals("cancelado")) {
            return sb.append("<p style=\"margin:0;padding:8px 12px;background:#FDECEE;border-left:4px solid #C8102E\">"
                    + "El proyecto fue <b>cancelado</b> (perfil no viable).</p>").toString();
        }
        int idx = ETAPAS.indexOf(actual);
        sb.append("<table style=\"border-collapse:collapse;width:100%;table-layout:fixed\"><tr>");
        for (int i = 0; i < ETAPAS.size(); i++) {
            boolean hecha = i < idx || actual.equals("concluido");
            boolean esta = i == idx && !actual.equals("concluido");
            String fondo = hecha ? "#1E7B3A" : esta ? "#0B3D6E" : "#E4E8EE";
            String color = hecha || esta ? "#ffffff" : "#5B6573";
            String marca = hecha ? "✔" : esta ? "●" : String.valueOf(i + 1);
            sb.append("<td style=\"background:").append(fondo).append(";color:").append(color)
                    .append(";text-align:center;padding:6px 2px;font-size:11px;border:2px solid #ffffff\">")
                    .append("<div style=\"font-size:14px;font-weight:bold\">").append(marca).append("</div>")
                    .append(NOMBRES.get(i)).append("</td>");
        }
        return sb.append("</tr></table>").toString();
    }

    private static String tarjetas(Map<String, Integer> ind) {
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:separate;border-spacing:6px;width:100%;table-layout:fixed\"><tr>");
        int i = 0;
        for (Map.Entry<String, Integer> e : ind.entrySet()) {
            if (i > 0 && i % 3 == 0) sb.append("</tr><tr>");
            boolean alerta = e.getKey().equals("Rondas vencidas") && e.getValue() > 0;
            sb.append("<td style=\"border:1px solid #D9DEE5;border-radius:6px;padding:10px;background:")
                    .append(alerta ? "#FDECEE" : "#F4F6F9").append("\">")
                    .append("<div style=\"font-size:24px;font-weight:bold;color:").append(alerta ? "#C8102E" : "#0B3D6E").append("\">")
                    .append(e.getValue()).append("</div><div style=\"font-size:12px;color:#5B6573\">")
                    .append(RespuestaHtml.esc(e.getKey())).append("</div></td>");
            i++;
        }
        return sb.append("</tr></table>").toString();
    }

    private static String barras(List<String[]> etapas, int max) {
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:collapse;width:100%\">");
        for (String[] e : etapas) {
            int n = Integer.parseInt(e[1]);
            int ancho = n == 0 ? 0 : Math.max(4, n * 100 / max);
            String color = e[0].equals("concluido") ? "#1E7B3A" : e[0].equals("cancelado") ? "#C8102E" : "#0B3D6E";
            sb.append("<tr><td style=\"padding:3px 8px 3px 0;font-size:13px;width:90px\">").append(e[0]).append("</td>")
                    .append("<td style=\"padding:3px 0\"><div style=\"background:").append(color).append(";height:14px;width:")
                    .append(ancho).append("%;border-radius:3px\"></div></td>")
                    .append("<td style=\"padding:3px 0 3px 8px;font-size:13px;width:30px;text-align:right\">").append(n).append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    /** Grilla mensual lunes a domingo. */
    private static String grilla(YearMonth ym, List<String[]> eventos) {
        Map<Integer, List<String[]>> porDia = new LinkedHashMap<>();
        for (String[] e : eventos) porDia.computeIfAbsent(Integer.parseInt(e[0]), k -> new ArrayList<>()).add(e);
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:collapse;width:100%;table-layout:fixed\"><tr>");
        for (String d : List.of("Lu", "Ma", "Mi", "Ju", "Vi", "Sá", "Do")) {
            sb.append("<th style=\"border:1px solid #D9DEE5;background:#F4F6F9;padding:4px;font-size:12px\">").append(d).append("</th>");
        }
        sb.append("</tr><tr>");
        int desfase = ym.atDay(1).getDayOfWeek().getValue() - 1;
        for (int i = 0; i < desfase; i++) sb.append("<td style=\"border:1px solid #D9DEE5;background:#FAFBFC\"></td>");
        LocalDate hoy = LocalDate.now();
        for (int dia = 1; dia <= ym.lengthOfMonth(); dia++) {
            int col = (desfase + dia - 1) % 7;
            if (col == 0 && dia > 1) sb.append("</tr><tr>");
            boolean esHoy = ym.atDay(dia).equals(hoy);
            boolean feriado = porDia.getOrDefault(dia, List.of()).stream().anyMatch(e -> e[2].equals("feriado"));
            sb.append("<td style=\"border:1px solid #D9DEE5;vertical-align:top;height:58px;padding:3px;font-size:10px;")
                    .append(feriado ? "background:#FFF6E0;" : col >= 5 ? "background:#FAFBFC;" : "")
                    .append(esHoy ? "outline:2px solid #0B3D6E;" : "").append("\">")
                    .append("<div style=\"font-size:12px;font-weight:bold;color:").append(esHoy ? "#0B3D6E" : "#1F2933").append("\">")
                    .append(dia).append("</div>");
            for (String[] e : porDia.getOrDefault(dia, List.of())) {
                String texto = (e[1].isEmpty() ? "" : e[1] + " ") + e[3];
                sb.append("<div style=\"color:").append(COLORES.getOrDefault(e[2], "#1F2933"))
                        .append(";line-height:1.2;margin-top:2px;overflow-wrap:break-word\">• ")
                        .append(RespuestaHtml.esc(texto.length() > 40 ? texto.substring(0, 38) + "…" : texto)).append("</div>");
            }
            sb.append("</td>");
        }
        int resto = (7 - (desfase + ym.lengthOfMonth()) % 7) % 7;
        for (int i = 0; i < resto; i++) sb.append("<td style=\"border:1px solid #D9DEE5;background:#FAFBFC\"></td>");
        return sb.append("</tr></table>").toString();
    }

    private static String leyenda() {
        StringBuilder sb = new StringBuilder("<p style=\"margin:6px 0 0;font-size:12px\">");
        for (Map.Entry<String, String> c : new LinkedHashMap<>(Map.of(
                "evaluación", COLORES.get("evaluación"), "vencimiento", COLORES.get("vencimiento"),
                "feriado", COLORES.get("feriado"), "evento", COLORES.get("evento"),
                "límite de perfil", COLORES.get("límite de perfil"))).entrySet()) {
            sb.append("<span style=\"color:").append(c.getValue()).append(";margin-right:12px\">● ").append(c.getKey()).append("</span>");
        }
        return sb.append("</p>").toString();
    }

    private static String detalle(YearMonth ym, List<String[]> eventos) {
        List<String[]> filas = new ArrayList<>();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM");
        for (String[] e : eventos) {
            LocalDate d = ym.atDay(Integer.parseInt(e[0]));
            filas.add(new String[]{d.format(f) + " " + d.getDayOfWeek().getDisplayName(TextStyle.SHORT, ES),
                    e[1].isEmpty() ? "-" : e[1], e[2], e[3], e[4].isEmpty() ? "-" : e[4]});
        }
        return TablaHtml.tabla(List.of("Fecha", "Hora", "Tipo", "Detalle", "Id evento"), filas);
    }

    private static String subtitulo(String t) {
        return "<p style=\"margin:14px 0 4px;font-weight:bold\">" + RespuestaHtml.esc(t) + "</p>";
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}