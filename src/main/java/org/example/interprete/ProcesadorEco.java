package org.example.interprete;

import org.example.correo.Correo;
import org.example.presentacion.RespuestaHtml;

/**
 * FASE 3 (temporal): responde "recibí tu comando" sin ejecutarlo.
 * Sirve para probar el ciclo completo: leer → registrar → responder → borrar.
 * En la Fase 4 se reemplaza por el Despachador.
 */
public class ProcesadorEco implements ProcesadorComando {

    @Override
    public Respuesta procesar(Correo c) {
        String cuerpo = """
                <p style="margin:0 0 8px">El sistema recibió tu solicitud correctamente.</p>
                <p style="margin:0;color:#5B6573">Todavía no se ejecutan los comandos (Fase 3 de pruebas).</p>
                """;
        String html = RespuestaHtml.exito("Comando recibido", cuerpo, c);
        return new Respuesta(true, null, null, "Eco: comando recibido", html, null);
    }
}