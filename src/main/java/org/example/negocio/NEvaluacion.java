package org.example.negocio;

import org.example.datos.DArchivo;
import org.example.datos.DEvaluacion;
import org.example.datos.DEvaluacion.Calificacion;
import org.example.datos.DEvaluacion.Evaluacion;
import org.example.datos.DHistorial;
import org.example.datos.DProyecto;
import org.example.datos.DProyecto.Asignacion;
import org.example.datos.DProyecto.Estudiante;
import org.example.datos.DProyecto.Proyecto;
import org.example.datos.DTribunal;
import org.example.datos.DTribunal.Designacion;
import org.example.datos.Transaccion;
import org.example.interprete.Contexto;
import org.example.interprete.ErrorComando;
import org.example.interprete.Respuesta;
import org.example.presentacion.RespuestaHtml;
import org.example.presentacion.TablaHtml;
import org.example.util.Validador;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * CU6 · Gestión de Programación y Registro de Evaluaciones (Predefensa y Defensa Final).
 *
 *   predefensa ─PROEVA─► programada ─3 × CALEVA─► ACTEVA ─► nota ≥ 51: defensa  · < 51: repetir (nuevo intento)
 *   defensa    ─PROEVA─► programada ─3 × CALEVA─► ACTEVA ─► nota ≥ 51: concluido · < 51: reprobado (nuevo intento)
 */
public class NEvaluacion {

    private static final BigDecimal APROBACION = BigDecimal.valueOf(51);
    private static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final DEvaluacion dEvaluacion = new DEvaluacion();
    private final DProyecto dProyecto = new DProyecto();
    private final DTribunal dTribunal = new DTribunal();
    private final DHistorial dHistorial = new DHistorial();
    private final DArchivo dArchivo = new DArchivo();
    private final NProyecto nProyecto = new NProyecto();
    private final NNotificacion notificar = new NNotificacion();

    // ================================================================== ambientes

    /** INSAMB["nombre","ubicacion"] */
    public Respuesta insertarAmbiente(Contexto ctx) throws SQLException {
        String nombre = ctx.p(0).trim();
        if (nombre.length() < 2 || nombre.length() > 80) throw new ErrorComando("El nombre debe tener entre 2 y 80 caracteres.");
        if (dEvaluacion.existeAmbiente(nombre)) throw new ErrorComando("Ya existe el ambiente «" + nombre + "».");
        String ubicacion = Validador.textoOpcional(ctx.p(1), 150, "ubicacion");
        int id = dEvaluacion.insertarAmbiente(nombre, ubicacion);
        return ctx.exito("Ambiente registrado con id " + id, ambientesHtml(), "Ambiente " + id);
    }

    /** LISAMB["*"] */
    public Respuesta listarAmbientes(Contexto ctx) throws SQLException {
        return ctx.exito("Ambientes", ambientesHtml(), "Ambientes");
    }

    // ================================================================== programación

    /** PROEVA["codigo","predefensa|defensa","fecha","hora","ambiente_id"] */
    public Respuesta programar(Contexto ctx) throws SQLException {
        Proyecto p = nProyecto.buscar(ctx.p(0));
        String tipo = ctx.p(1).trim().toLowerCase();
        if (!tipo.equals("predefensa") && !tipo.equals("defensa")) throw new ErrorComando("El tipo debe ser predefensa o defensa.");
        if (!p.etapa().equals(tipo)) {
            throw new ErrorComando(p.codigo() + " está en la etapa " + p.etapa() + ": no se puede programar la " + tipo + ".");
        }
        Evaluacion pendiente = dEvaluacion.pendiente(p.id(), tipo);
        if (pendiente != null) {
            throw new ErrorComando("Ya hay una " + tipo + " programada (id " + pendiente.id() + ", " + pendiente.fechaHora().format(FH)
                    + "). Para cambiarla use REPEVA[\"" + pendiente.id() + "\",...].");
        }
        if (dTribunal.vigentes(p.id()).stream().filter(d -> d.estado().equals("aceptada")).count() < 3) {
            throw new ErrorComando(p.codigo() + " no tiene los 3 tribunales aceptados.");
        }
        LocalDateTime fh = fechaHora(ctx.p(2), ctx.p(3));
        int ambiente = ambienteValido(ctx.p(4));
        exigirSinChoque(p, fh, ambiente, 0);

        int intento = dEvaluacion.siguienteIntento(p.id(), tipo);
        String nombreAmbiente = dEvaluacion.nombreAmbiente(ambiente);
        long yo = ctx.usuario().id();
        long[] id = new long[1];
        String titulo = capital(tipo) + " " + p.codigo() + (intento > 1 ? " (intento " + intento + ")" : "");
        Transaccion.ejecutar(cn -> {
            id[0] = dEvaluacion.programar(cn, p.id(), p.gestionId(), tipo, intento, fh, ambiente, yo, titulo);
            dHistorial.registrar(cn, p.id(), tipo, "iniciada",
                    capital(tipo) + (intento > 1 ? " intento " + intento : "") + " programada: " + fh.format(FH) + " · " + nombreAmbiente, yo);
        });
        String msg = "La " + tipo + " de " + p.codigo() + (intento > 1 ? " (intento " + intento + ")" : "")
                + " se programó para el " + fh.format(FH) + " en " + nombreAmbiente + ".";
        notificarParticipantes(p, capital(tipo) + " programada", msg, id[0], true);
        return ctx.exito(capital(tipo) + " programada (id " + id[0] + ")", fichaHtml(dEvaluacion.porId(id[0]), true)
                        + "<p style=\"margin:8px 0 0;font-size:13px\">Se registró en el calendario y se notificó a estudiantes, "
                        + "tribunales y tutor.</p>",
                capital(tipo) + " " + p.codigo() + " " + fh.format(FH));
    }

    /** REPEVA["id_eval","fecha","hora","ambiente_id","motivo"] · S1 */
    public Respuesta reprogramar(Contexto ctx) throws SQLException {
        Evaluacion e = buscar(ctx.p(0));
        if (!e.pendiente()) throw new ErrorComando("La evaluación " + e.id() + " está " + e.estado() + ": no se puede reprogramar.");
        String motivo = Validador.textoOpcional(ctx.p(4), 200, "motivo");
        if (motivo == null) throw new ErrorComando("Indique el motivo de la reprogramación.");
        LocalDateTime fh = fechaHora(ctx.p(1), ctx.p(2));
        int ambiente = ambienteValido(ctx.p(3));
        Proyecto p = dProyecto.porId(e.proyectoId());
        exigirSinChoque(p, fh, ambiente, e.id());

        String nombreAmbiente = dEvaluacion.nombreAmbiente(ambiente);
        long yo = ctx.usuario().id();
        Transaccion.ejecutar(cn -> {
            dEvaluacion.reprogramar(cn, e.id(), fh, ambiente, motivo);
            dHistorial.registrar(cn, p.id(), e.tipo(), "iniciada", capital(e.tipo()) + " reprogramada: " + e.fechaHora().format(FH)
                    + " → " + fh.format(FH) + " · " + nombreAmbiente + " (" + motivo + ")", yo);
        });
        String msg = "La " + e.tipo() + " de " + p.codigo() + " cambió: ahora es el " + fh.format(FH) + " en " + nombreAmbiente
                + " (antes " + e.fechaHora().format(FH) + "). Motivo: " + motivo + ".";
        notificarParticipantes(p, capital(e.tipo()) + " reprogramada", msg, e.id(), true);
        return ctx.exito(capital(e.tipo()) + " reprogramada", fichaHtml(dEvaluacion.porId(e.id()), true),
                "Reprograma " + e.id());
    }

    /** LISEVA["programada|realizada|suspendida" | "*"] */
    public Respuesta listar(Contexto ctx) throws SQLException {
        String estado = null;
        if (!ctx.esTodos(0)) {
            estado = ctx.p(0).trim().toLowerCase();
            if (!List.of("programada", "realizada", "suspendida", "reprogramada").contains(estado)) {
                throw new ErrorComando("Estado no válido. Use programada, realizada, suspendida o \"*\".");
            }
        }
        Long docente = ctx.usuario().tienePermiso("evaluacion.programar") ? null : ctx.usuario().id();
        List<String[]> filas = dEvaluacion.listar(estado, docente);
        String html = TablaHtml.tabla(List.of("Id", "Proyecto", "Tipo", "Intento", "Fecha y hora", "Ambiente", "Estado",
                "Notas", "Resultado", "Nota final"), filas)
                + TablaHtml.total(filas.size(), "evaluación", "evaluaciones")
                + "<p style=\"margin:8px 0 0;font-size:13px\">Detalle: <code>VEREVA[\"id\"]</code></p>";
        return ctx.exito(docente == null ? "Evaluaciones" : "Mis evaluaciones como tribunal", html, filas.size() + " evaluación(es)");
    }

    /** VEREVA["id_eval"] */
    public Respuesta ver(Contexto ctx) throws SQLException {
        Evaluacion e = buscar(ctx.p(0));
        Proyecto p = dProyecto.porId(e.proyectoId());
        var u = ctx.usuario();
        boolean general = u.tienePermiso("evaluacion.programar");
        boolean tribunal = dTribunal.vigentes(p.id()).stream().anyMatch(d -> d.docenteId() == u.id());
        boolean estudiante = dProyecto.esIntegrante(p.id(), u.id());
        if (!general && !tribunal && !estudiante && !dProyecto.esDocenteDelProyecto(p.id(), u.id())) {
            throw new ErrorComando("No tiene relación con la evaluación " + e.id() + ".");
        }
        boolean verNotas = general || tribunal || e.estado().equals("realizada");
        return ctx.exito(capital(e.tipo()) + " de " + p.codigo(), fichaHtml(e, verNotas), "Evaluación " + e.id());
    }

    // ================================================================== calificación y acta

    /** CALEVA["id_eval","nota","observacion"] */
    public Respuesta calificar(Contexto ctx) throws SQLException {
        Evaluacion e = buscar(ctx.p(0));
        if (!e.pendiente()) throw new ErrorComando("La evaluación " + e.id() + " ya está " + e.estado() + ": no admite notas.");
        Designacion d = dTribunal.vigentes(e.proyectoId()).stream()
                .filter(x -> x.docenteId() == ctx.usuario().id() && x.estado().equals("aceptada")).findFirst()
                .orElseThrow(() -> new ErrorComando("Usted no es tribunal de " + e.codigo() + "."));
        if (LocalDate.now().isBefore(e.fechaHora().toLocalDate())) {
            throw new ErrorComando("La " + e.tipo() + " es el " + e.fechaHora().format(FH) + ": califique desde ese día.");
        }
        BigDecimal nota;
        try {
            nota = new BigDecimal(ctx.p(1).trim().replace(",", ".")).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new ErrorComando("La nota debe ser un número de 0 a 100.");
        }
        if (nota.compareTo(BigDecimal.ZERO) < 0 || nota.compareTo(BigDecimal.valueOf(100)) > 0) {      // E2
            throw new ErrorComando("La nota debe estar entre 0 y 100.");
        }
        String obs = Validador.textoOpcional(ctx.p(2), 255, "observacion");
        boolean corrige = dEvaluacion.calificaciones(e.id()).stream().anyMatch(c -> c.designacionId() == d.id() && c.nota() != null);
        dEvaluacion.calificar(e.id(), d.id(), nota, obs);

        long registradas = dEvaluacion.calificaciones(e.id()).stream().filter(c -> c.nota() != null).count();
        if (registradas == 3 && !corrige) {
            notificarRoles("Notas completas", "Los 3 tribunales calificaron la " + e.tipo() + " de " + e.codigo()
                    + ". Registre el acta con ACTEVA[\"" + e.id() + "\",\"url_acta\",\"-\"].", "VEREVA[\"" + e.id() + "\"]");
        }
        return ctx.exito(corrige ? "Nota corregida" : "Nota registrada",
                "<p style=\"margin:0\">Registró <b>" + nota.stripTrailingZeros().toPlainString() + "</b> en la " + e.tipo() + " de <b>"
                        + RespuestaHtml.esc(e.codigo()) + "</b> (" + registradas + " de 3 notas). Puede corregirla hasta que se registre el acta.</p>"
                        + fichaHtml(dEvaluacion.porId(e.id()), true),
                "Nota " + nota.stripTrailingZeros().toPlainString() + " · eval " + e.id());
    }

    /** ACTEVA["id_eval","url_acta" | "-","observaciones" | "-"] */
    public Respuesta registrarActa(Contexto ctx) throws SQLException {
        Evaluacion e = buscar(ctx.p(0));
        if (!e.pendiente()) throw new ErrorComando("La evaluación " + e.id() + " ya está " + e.estado() + ".");
        List<Calificacion> notas = dEvaluacion.calificaciones(e.id());
        List<String> faltan = notas.stream().filter(c -> c.nota() == null).map(Calificacion::docente).toList();
        if (notas.size() < 3 || !faltan.isEmpty()) {
            throw new ErrorComando("Faltan notas de: " + String.join(", ", faltan) + ". Espere a que califiquen con CALEVA.");
        }
        BigDecimal promedio = notas.stream().map(Calificacion::nota).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        String obs = Validador.textoOpcional(ctx.p(2), 2000, "observaciones");
        Long acta = ctx.esVacio(1) ? null
                : dArchivo.registrarEnlace(Validador.url(ctx.p(1), "url_acta"), "Acta " + e.tipo() + " " + e.codigo(), ctx.usuario().id());
        boolean aprobo = promedio.compareTo(APROBACION) >= 0;
        String resultado = aprobo ? (obs == null ? "aprobado" : "aprobado_obs") : (e.tipo().equals("predefensa") ? "repetir" : "reprobado");

        Proyecto p = dProyecto.porId(e.proyectoId());
        long yo = ctx.usuario().id();
        String nota = promedio.stripTrailingZeros().toPlainString();
        Transaccion.ejecutar(cn -> {
            dEvaluacion.registrarActa(cn, e.id(), resultado, promedio, obs, acta);
            if (e.tipo().equals("predefensa") && aprobo) {
                dProyecto.cambiarEtapa(cn, p.id(), "defensa");
                dHistorial.registrar(cn, p.id(), "predefensa", "completada", "Predefensa aprobada con " + nota, yo);
                dHistorial.registrar(cn, p.id(), "defensa", "iniciada", "Pendiente de programar la defensa final", yo);
            } else if (e.tipo().equals("defensa") && aprobo) {
                dProyecto.cambiarEtapa(cn, p.id(), "concluido");
                dHistorial.registrar(cn, p.id(), "defensa", "completada", "Defensa final aprobada con " + nota, yo);
                dHistorial.registrar(cn, p.id(), "concluido", "completada", "Proyecto concluido: titulado", yo);
            } else {
                dHistorial.registrar(cn, p.id(), e.tipo(), "observada",
                        capital(e.tipo()) + " " + resultado + " con " + nota + ": se programará un nuevo intento", yo);
            }
        });

        String msg = switch (resultado) {
            case "aprobado", "aprobado_obs" -> e.tipo().equals("predefensa")
                    ? "¡Aprobó la predefensa de " + p.codigo() + " con " + nota + "! Siguiente paso: la defensa final."
                    : "¡Felicidades! Aprobó la DEFENSA FINAL de " + p.codigo() + " con " + nota + ". El proyecto quedó CONCLUIDO.";
            case "repetir" -> "La predefensa de " + p.codigo() + " obtuvo " + nota + " (mínimo 51): debe repetirla. "
                    + "Dirección o Coordinación programará un nuevo intento.";
            default -> "La defensa final de " + p.codigo() + " obtuvo " + nota + " (mínimo 51). Se programará un nuevo intento.";
        };
        notificarParticipantes(p, "Resultado de la " + e.tipo(), msg + (obs == null ? "" : " Observaciones: " + obs), e.id(), false);
        String color = aprobo ? "#E8F5EC;border-left:4px solid #1E7B3A" : "#FFF6E0;border-left:4px solid #8A6100";
        return ctx.exito("Acta de " + e.tipo() + " registrada",
                "<p style=\"margin:0 0 8px;padding:8px 12px;background:" + color + "\"><b>Promedio: " + nota + "</b> · "
                        + RespuestaHtml.esc(resultado.replace('_', ' ')) + ". " + RespuestaHtml.esc(msg) + "</p>"
                        + fichaHtml(dEvaluacion.porId(e.id()), true),
                "Acta " + e.id() + " · " + nota + " · " + resultado);
    }

    // ================================================================== utilidades

    private Evaluacion buscar(String idTexto) throws SQLException {
        long id = Validador.entero(idTexto, "id_eval");
        Evaluacion e = dEvaluacion.porId(id);
        if (e == null) throw new ErrorComando("No existe la evaluación " + id + ". Vea las evaluaciones con LISEVA[\"*\"].");
        return e;
    }

    private LocalDateTime fechaHora(String fecha, String hora) throws SQLException {
        LocalDate f = Validador.fecha(fecha, "fecha");
        LocalTime h = Validador.hora(hora, "hora");
        if (h.isBefore(LocalTime.of(7, 0)) || h.isAfter(LocalTime.of(21, 0))) {
            throw new ErrorComando("La hora debe estar entre 07:00 y 21:00.");
        }
        LocalDateTime fh = LocalDateTime.of(f, h);
        if (fh.isBefore(LocalDateTime.now())) throw new ErrorComando("La fecha y hora " + fh.format(FH) + " ya pasó.");
        if (f.getDayOfWeek() == DayOfWeek.SUNDAY) throw new ErrorComando("No se programan evaluaciones en domingo.");
        if (dEvaluacion.esFeriado(f)) throw new ErrorComando("El " + f.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " es feriado.");
        return fh;
    }

    private int ambienteValido(String texto) throws SQLException {
        int id = Validador.entero(texto, "ambiente_id");
        if (dEvaluacion.nombreAmbiente(id) == null) throw new ErrorComando("No existe el ambiente " + id + ". Vea los ambientes con LISAMB[\"*\"].");
        return id;
    }

    /** E1: no se programa si el ambiente o algún tribunal tiene otra evaluación a menos de 2 horas. */
    private void exigirSinChoque(Proyecto p, LocalDateTime fh, int ambiente, long excluir) throws SQLException {
        String choque = dEvaluacion.conflicto(p.id(), fh, ambiente, excluir);
        if (choque != null) {
            throw new ErrorComando("Choque de horario (±2 horas) con " + choque + ". Elija otra hora o ambiente.");
        }
    }

    /** EvaluacionService.notificarParticipantes: estudiantes, tribunales y tutor. */
    private void notificarParticipantes(Proyecto p, String titulo, String mensaje, long evaluacionId, boolean pedirCalificar)
            throws SQLException {
        String ver = "VEREVA[\"" + evaluacionId + "\"]";
        for (Estudiante e : dProyecto.estudiantes(p.id())) notificar.aUsuario(e.id(), titulo, mensaje, ver);
        for (Designacion d : dTribunal.vigentes(p.id())) {
            if (!d.estado().equals("aceptada")) continue;
            notificar.aUsuario(d.docenteId(), titulo, mensaje + (pedirCalificar
                    ? " Después de la evaluación registre su nota con CALEVA[\"" + evaluacionId + "\",\"nota\",\"observacion\"]." : ""), ver);
        }
        Asignacion t = dProyecto.asignacionVigente(p.id());
        if (t != null && t.estado().equals("aceptada") && t.docenteId() != null) notificar.aUsuario(t.docenteId(), titulo, mensaje, ver);
        if (!pedirCalificar) notificarRoles(titulo, mensaje, ver);
    }

    private void notificarRoles(String titulo, String mensaje, String ver) {
        notificar.aRoles(titulo, mensaje, ver, "director", "academico");
    }

    private String fichaHtml(Evaluacion e, boolean verNotas) throws SQLException {
        String html = TablaHtml.ficha(
                List.of("Id", "Proyecto", "Tipo", "Intento", "Fecha y hora", "Ambiente", "Estado", "Resultado", "Nota final",
                        "Observaciones", "Acta"),
                List.of(String.valueOf(e.id()), e.codigo(), e.tipo(), String.valueOf(e.intento()), e.fechaHora().format(FH),
                        e.ambiente(), e.estado(), e.resultado() == null ? "-" : e.resultado().replace('_', ' '),
                        e.notaFinal() == null ? "-" : e.notaFinal().stripTrailingZeros().toPlainString(),
                        e.observaciones() == null ? "-" : e.observaciones(), e.acta() == null ? "-" : e.acta()));
        List<String[]> filas = new ArrayList<>();
        for (Calificacion c : dEvaluacion.calificaciones(e.id())) {
            String nota = c.nota() == null ? "pendiente" : verNotas ? c.nota().stripTrailingZeros().toPlainString() : "registrada";
            filas.add(new String[]{String.valueOf(c.posicion()), c.docente(), nota,
                    verNotas && c.observacion() != null ? c.observacion() : "-"});
        }
        return html + "<p style=\"margin:12px 0 4px;font-weight:bold\">Calificaciones (mínimo de aprobación: 51)</p>"
                + TablaHtml.tabla(List.of("Pos.", "Tribunal", "Nota", "Observación"), filas);
    }

    private String ambientesHtml() throws SQLException {
        return TablaHtml.tabla(List.of("Id", "Ambiente", "Ubicación", "Evaluaciones pendientes"), dEvaluacion.ambientes());
    }

    private static String capital(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}