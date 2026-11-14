package org.example.negocio;

import org.example.correo.Adjunto;
import org.example.datos.DBitacora;
import org.example.datos.DGestion;
import org.example.datos.DGestion.Gestion;
import org.example.datos.DProyecto;
import org.example.datos.DReporte;
import org.example.interprete.Contexto;
import org.example.interprete.ErrorComando;
import org.example.interprete.Respuesta;
import org.example.presentacion.Csv;
import org.example.presentacion.RespuestaHtml;
import org.example.presentacion.TablaHtml;
import org.example.util.Validador;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * CU8 · Reportes y Estadísticas.
 * Todos reciben ["gestion" | "*","area_id" | "*"], responden en HTML y adjuntan el CSV (S1).
 * Consultas de solo lectura; cada exportación queda en la bitácora.
 */
public class NReporte {

    /** Una tabla del reporte (va al HTML y al CSV). */
    private record Tabla(String titulo, List<String> cabecera, List<String[]> filas) { }

    /** Filtro ya validado. */
    private record Filtro(Integer gestionId, Integer areaId, String texto, String sufijo) { }

    private final DReporte dReporte = new DReporte();
    private final DGestion dGestion = new DGestion();
    private final DProyecto dProyecto = new DProyecto();
    private final DBitacora dBitacora = new DBitacora();

    /** REPRES · resumen general */
    public Respuesta resumen(Contexto ctx) throws SQLException {
        Filtro f = filtro(ctx);
        return responder(ctx, f, "resumen", "Resumen general",
                List.of(new Tabla("Indicadores", List.of("Indicador", "Valor"), dReporte.resumen(f.gestionId(), f.areaId()))));
    }

    /** REPARE · proyectos por área y etapa */
    public Respuesta porArea(Contexto ctx) throws SQLException {
        Filtro f = filtro(ctx);
        return responder(ctx, f, "proyectos_area", "Proyectos por área y etapa", List.of(
                new Tabla("Cantidad por área y etapa", List.of("Área", "Tutor", "Perfil", "Viabilidad", "Tribunales", "Revisión",
                        "Predefensa", "Defensa", "Concluido", "Cancelado", "Total"), dReporte.porAreaEtapa(f.gestionId(), f.areaId())),
                new Tabla("Proyectos", List.of("Código", "Gestión", "Área", "Título", "Etapa", "Estudiantes", "Tutor"),
                        dReporte.proyectos(f.gestionId(), f.areaId()))));
    }

    /** REPVIA · resultados de viabilidad */
    public Respuesta viabilidad(Contexto ctx) throws SQLException {
        Filtro f = filtro(ctx);
        return responder(ctx, f, "viabilidad", "Resultados de viabilidad", List.of(
                new Tabla("Por resultado", List.of("Resultado", "Cantidad", "Porcentaje"), dReporte.viabilidadResumen(f.gestionId(), f.areaId())),
                new Tabla("Evaluaciones", List.of("Proyecto", "Perfil", "Resultado", "Revisante", "Observaciones", "Fecha"),
                        dReporte.viabilidadDetalle(f.gestionId(), f.areaId()))));
    }

    /** REPREV · revisiones y cumplimiento de plazos */
    public Respuesta revisiones(Contexto ctx) throws SQLException {
        Filtro f = filtro(ctx);
        List<String[]> rondas = dReporte.rondas(f.gestionId(), f.areaId());
        long aTiempo = rondas.stream().filter(r -> r[6].equals("A tiempo")).count();
        long fuera = rondas.stream().filter(r -> r[6].equals("Fuera de plazo") || r[6].equals("Vencida")).count();
        long enCurso = rondas.stream().filter(r -> r[6].equals("En curso")).count();
        long cerradas = aTiempo + rondas.stream().filter(r -> r[6].equals("Fuera de plazo")).count();
        List<String[]> resumen = List.of(
                new String[]{"Rondas", String.valueOf(rondas.size())},
                new String[]{"Cerradas a tiempo", String.valueOf(aTiempo)},
                new String[]{"Fuera de plazo o vencidas", String.valueOf(fuera)},
                new String[]{"En curso", String.valueOf(enCurso)},
                new String[]{"Cumplimiento de plazos", cerradas == 0 ? "-" : Math.round(100.0 * aTiempo / cerradas) + " %"});
        return responder(ctx, f, "revisiones_plazos", "Revisiones y plazos", List.of(
                new Tabla("Resumen", List.of("Indicador", "Valor"), resumen),
                new Tabla("Rondas", List.of("Proyecto", "Ronda", "Enviada", "Vence", "Cerrada", "Estado", "Cumplimiento"), rondas),
                new Tabla("Respuesta de tribunales", List.of("Tribunal", "Respondidas", "A tiempo", "Fuera de plazo o sin responder", "Días promedio"),
                        dReporte.respuestaTribunales(f.gestionId(), f.areaId()))));
    }

    /** REPDEF · predefensas y defensas */
    public Respuesta evaluaciones(Contexto ctx) throws SQLException {
        Filtro f = filtro(ctx);
        return responder(ctx, f, "predefensas_defensas", "Predefensas y defensas", List.of(
                new Tabla("Resumen por tipo", List.of("Tipo", "Programadas", "Realizadas", "Aprobadas", "No aprobadas",
                        "Nota promedio", "Mínima", "Máxima"), dReporte.evaluacionesResumen(f.gestionId(), f.areaId())),
                new Tabla("Evaluaciones", List.of("Proyecto", "Tipo", "Intento", "Fecha y hora", "Ambiente", "Estado", "Resultado", "Nota"),
                        dReporte.evaluacionesDetalle(f.gestionId(), f.areaId()))));
    }

    /** REPDUR · duración promedio por etapa */
    public Respuesta duracion(Contexto ctx) throws SQLException {
        Filtro f = filtro(ctx);
        return responder(ctx, f, "duracion_etapas", "Duración por etapa (en días)", List.of(
                new Tabla("Duración por etapa", List.of("Etapa", "Proyectos", "Promedio", "Mínimo", "Máximo"),
                        dReporte.duracionEtapas(f.gestionId(), f.areaId())),
                new Tabla("Duración total de los proyectos concluidos", List.of("Proyecto", "Inicio", "Conclusión", "Días"),
                        dReporte.duracionTotal(f.gestionId(), f.areaId()))));
    }

    /** REPDOC · carga docente */
    public Respuesta cargaDocente(Contexto ctx) throws SQLException {
        Filtro f = filtro(ctx);
        return responder(ctx, f, "carga_docente", "Carga docente", List.of(
                new Tabla("Carga por docente", List.of("Docente", "Tutorías vigentes", "Tutorías total", "Tribunal vigente",
                        "Tribunal total", "Viabilidades", "Revisiones", "Calificaciones"), dReporte.cargaDocente(f.gestionId(), f.areaId()))));
    }

    /** REPTIT · titulados */
    public Respuesta titulados(Contexto ctx) throws SQLException {
        Filtro f = filtro(ctx);
        return responder(ctx, f, "titulados", "Listado de titulados", List.of(
                new Tabla("Titulados", List.of("Proyecto", "Gestión", "Área", "Título", "Estudiantes (registro)", "Tutor",
                        "Defensa", "Nota final"), dReporte.titulados(f.gestionId(), f.areaId()))));
    }

    // ================================================================== utilidades

    /** CU8.1: gestión ("2-2026" o "*") y área (id o "*"). */
    private Filtro filtro(Contexto ctx) throws SQLException {
        Integer gestionId = null, areaId = null;
        List<String> partes = new ArrayList<>();
        String sufijo = "";
        if (!ctx.esTodos(0)) {
            Gestion g = dGestion.porCodigo(ctx.p(0).trim());
            if (g == null) throw new ErrorComando("No existe la gestión " + ctx.p(0) + ". Use por ejemplo \"2-2026\" o \"*\".");
            gestionId = g.id();
            partes.add("gestión " + g.codigo());
            sufijo += "_" + g.codigo();
        }
        if (!ctx.esTodos(1)) {
            areaId = Validador.entero(ctx.p(1), "area_id");
            String area = dProyecto.nombreArea(areaId);
            if (area == null) throw new ErrorComando("No existe el área " + areaId + ". Use \"*\" para todas.");
            partes.add("área " + area);
            sufijo += "_area" + areaId;
        }
        String texto = partes.isEmpty() ? "Todas las gestiones y áreas" : String.join(" · ", partes);
        return new Filtro(gestionId, areaId, texto, sufijo);
    }

    /** Arma el HTML con todas las tablas y adjunta el CSV (CU8.3 y CU8.4). */
    private Respuesta responder(Contexto ctx, Filtro f, String archivo, String titulo, List<Tabla> tablas) {
        StringBuilder html = new StringBuilder("<p style=\"margin:0 0 4px;color:#5B6573\">Filtro: <b>")
                .append(RespuestaHtml.esc(f.texto())).append("</b> · Generado el ")
                .append(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("</p>");
        Csv csv = new Csv();
        csv.tabla(titulo + " · " + f.texto(), List.of(), List.of());
        int filas = 0;
        for (Tabla t : tablas) {
            html.append("<p style=\"margin:14px 0 4px;font-weight:bold\">").append(RespuestaHtml.esc(t.titulo())).append("</p>")
                    .append(TablaHtml.tabla(t.cabecera(), t.filas()));
            csv.tabla(t.titulo(), t.cabecera(), t.filas());
            filas += t.filas().size();
        }
        String nombre = "reporte_" + archivo + f.sufijo() + "_" + LocalDate.now() + ".csv";
        html.append("<p style=\"margin:12px 0 0;font-size:13px\">Se adjunta <b>").append(RespuestaHtml.esc(nombre))
                .append("</b> (separador «;», se abre con Excel). Para PDF: abra este correo y use Imprimir → Guardar como PDF.</p>");
        dBitacora.registrar(ctx.usuario().id(), "EXPORTAR", "reporte", null, nombre);         // CU8 paso 6
        return ctx.exito(titulo, html.toString(), titulo + " · " + filas + " fila(s)")
                .conAdjuntos(List.of(new Adjunto(nombre, "text/csv; charset=UTF-8", csv.bytes())));
    }
}