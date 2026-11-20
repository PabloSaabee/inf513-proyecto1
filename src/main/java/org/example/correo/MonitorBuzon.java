package org.example.correo;

import org.example.config.Config;
import org.example.datos.DCorreoProcesado;
import org.example.datos.DUsuario;
import org.example.interprete.ProcesadorComando;
import org.example.interprete.Respuesta;
import org.example.util.Registro;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bucle principal: revisa el buzón cada N segundos y responde cada correo nuevo.
 *
 * Ciclo:
 *   1. UIDL: lista los correos del buzón (rápido, sin descargarlos).
 *   2. Compara con correos_procesados: los ya registrados solo se borran.
 *   3. Por cada correo nuevo:
 *        TOP (cabeceras) → registrar 'pendiente' → procesar → responder → guardar resultado → DELE
 *   4. QUIT: el servidor recién borra los marcados con DELE.
 */
public class MonitorBuzon {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ProcesadorComando procesador;
    private final DCorreoProcesado dCorreo = new DCorreoProcesado();
    private final DUsuario dUsuario = new DUsuario();
    private final int intervaloSegundos;
    private final boolean borrarProcesados;
    /** true: los correos de remitentes no registrados se ignoran sin responder (protección contra inundación). */
    private final boolean ignorarNoRegistrados;
    private volatile boolean activo = true;
    /** Correos repetidos (mismo Message-ID que uno ya procesado) vistos en esta ejecución. */
    private final Set<String> uidsRepetidos = new HashSet<>();

    public MonitorBuzon(ProcesadorComando procesador) {
        this.procesador = procesador;
        this.intervaloSegundos = Config.getInt("mail.intervalo.segundos");
        this.borrarProcesados = Config.getBoolean("mail.borrar.procesados", true);
        this.ignorarNoRegistrados = Config.getBoolean("mail.ignorar.no.registrados", true);
    }

    /** Bloquea el hilo actual hasta que se llame a detener() (o Ctrl+C). */
    public void iniciar() {
        log("Monitor iniciado · revisa " + Config.get("mail.direccion") + " cada " + intervaloSegundos + " s"
                + (borrarProcesados ? " · borra los procesados" : " · NO borra los procesados")
                + (ignorarNoRegistrados ? " · ignora remitentes no registrados" : " · responde a remitentes no registrados"));
        while (activo) {
            try {
                ejecutarCiclo();
            } catch (IOException e) {
                log("Error de correo: " + e.getMessage() + " (se reintenta en el próximo ciclo)");
                Registro.error("MonitorBuzon · conexión POP3", e);
            } catch (SQLException e) {
                log("Error de base de datos: " + e.getMessage() + " (se reintenta en el próximo ciclo)");
                Registro.error("MonitorBuzon · base de datos", e);
            } catch (RuntimeException e) {
                log("Error inesperado: " + e);
                Registro.error("MonitorBuzon · ciclo", e);
            }
            dormir();
        }
        log("Monitor detenido");
    }

    public void detener() {
        activo = false;
    }

    // ------------------------------------------------------------------ ciclo

    void ejecutarCiclo() throws IOException, SQLException {
        try (ClientePOP3 pop = ClientePOP3.conectar()) {
            Map<Integer, String> uids = pop.uidl();
            if (uids.isEmpty()) return;

            Set<String> registrados = dCorreo.uidsRegistrados(uids.values());
            List<Integer> nuevos = new ArrayList<>();
            for (Map.Entry<Integer, String> e : uids.entrySet()) {
                if (registrados.contains(e.getValue()) || uidsRepetidos.contains(e.getValue())) {
                    if (borrarProcesados) pop.eliminar(e.getKey());       // quedó de un ciclo anterior
                } else {
                    nuevos.add(e.getKey());
                }
            }
            if (nuevos.isEmpty()) return;

            log(nuevos.size() + " correo(s) nuevo(s)");
            // Remitentes registrados: una sola consulta por ciclo
            Set<String> emailsRegistrados = ignorarNoRegistrados ? dUsuario.emailsRegistrados() : Set.of();
            int ignorados = 0;
            for (int numero : nuevos) {
                Correo c = ParserCorreo.analizar(numero, uids.get(numero), pop.cabeceras(numero));
                if (ignorarNoRegistrados && !esRemitenteRegistrado(c, emailsRegistrados)) {
                    // Protección contra inundación: no se responde ni se guarda en la base
                    ignorarNoRegistrado(c);
                    ignorados++;
                    if (borrarProcesados) pop.eliminar(numero);
                    continue;
                }
                if (procesarCorreo(c) && borrarProcesados) {
                    pop.eliminar(numero);
                }
            }
            if (ignorados > 0) {
                log("  " + ignorados + " correo(s) de remitentes no registrados: ignorados sin responder");
            }
        }   // QUIT: aplica los DELE
    }

    private static boolean esRemitenteRegistrado(Correo c, Set<String> emailsRegistrados) {
        return c.remitente() != null && emailsRegistrados.contains(c.remitente().trim().toLowerCase());
    }

    /**
     * Correo de un remitente que no está en la tabla usuarios: no se responde (así un ataque
     * de muchos correos no genera respuestas), no se guarda en correos_procesados y solo
     * queda una línea IGNORADO en access.log. Su UID se recuerda para no volver a anotarlo
     * si el buzón no se vacía.
     */
    private void ignorarNoRegistrado(Correo c) {
        uidsRepetidos.add(c.uid());
        Registro.acceso(c.remitente(), c.asunto(), "IGNORADO", "Remitente no registrado: no se responde");
    }

    /** @return true si el correo quedó registrado (y por lo tanto se puede borrar del buzón). */
    private boolean procesarCorreo(Correo c) throws SQLException {
        Long id = dCorreo.registrarPendiente(c);
        if (id == null) {
            // Mismo Message-ID ya procesado (por ejemplo, el correo llegó dos veces)
            uidsRepetidos.add(c.uid());
            log("  ← " + c.remitente() + " · ya procesado antes: se omite");
            Registro.acceso(c.remitente(), null, "IGNORADO", "Correo repetido (Message-ID ya procesado)");
            return true;
        }

        if (c.automatico()) {
            dCorreo.finalizar(id, null, null, false, "Ignorado: rebote o respuesta automática", false);
            log("  ← " + c.remitente() + " · " + c.asunto());
            log("    rebote o respuesta automática: se ignora sin responder");
            Registro.acceso(c.remitente(), null, "IGNORADO", "Rebote o respuesta automática");
            return true;
        }

        Respuesta r;
        try {
            r = procesador.procesar(c);
        } catch (RuntimeException e) {
            // Un error en un comando nunca debe detener el monitor
            dCorreo.finalizar(id, null, null, false, "Error interno: " + e.getMessage(), false);
            log("  ← " + c.remitente() + " · ERROR interno al procesar: " + e);
            Registro.acceso(c.remitente(), null, "ERROR", "Error interno al procesar");
            Registro.error("MonitorBuzon · procesar correo de " + c.remitente(), e);
            return true;
        }

        boolean enviada = false;
        String detalle = r.resumen();
        try {
            String asuntoRespuesta = r.asuntoSeguro() != null ? r.asuntoSeguro() : c.asunto();
            ClienteSMTP.enviar(c.remitente(), "Re: " + asuntoRespuesta, r.html(), c.messageId(), r.adjuntos());
            enviada = true;
        } catch (IOException e) {
            detalle = r.resumen() + " · No se pudo enviar la respuesta: " + e.getMessage();
            Registro.error("ClienteSMTP · respuesta a " + c.remitente(), e);
        }
        dCorreo.finalizar(id, r.comando(), r.parametros(), r.exito(), detalle, enviada, r.asuntoSeguro());
        Registro.acceso(c.remitente(), r.asuntoSeguro() != null ? r.asuntoSeguro() : c.asunto(),
                r.exito() ? "ÉXITO" : "ERROR", detalle + (enviada ? "" : " · RESPUESTA NO ENVIADA"));
        // El asunto se muestra sin contraseñas
        log("  ← " + c.remitente() + " · " + (r.asuntoSeguro() != null ? r.asuntoSeguro() : c.asunto()));
        log("    → " + (r.exito() ? "ÉXITO" : "ERROR") + " · " + r.resumen()
                + (enviada ? " · respuesta enviada" : " · RESPUESTA NO ENVIADA"));
        return true;
    }

    // ------------------------------------------------------------------ utilidades

    private void dormir() {
        try {
            Thread.sleep(intervaloSegundos * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            activo = false;
        }
    }

    private static void log(String texto) {
        System.out.println("[" + LocalTime.now().format(HORA) + "] " + texto);
    }
}