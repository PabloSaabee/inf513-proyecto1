package org.example.interprete;

import org.example.correo.Adjunto;

import java.util.List;

/**
 * Resultado de procesar un comando: lo que se responde por correo y lo que se guarda.
 *
 * @param exito        true = "Éxito", false = "Error"
 * @param comando      nombre del comando (LISUSU) o null si no se pudo interpretar
 * @param parametros   parámetros recibidos, como texto (contraseñas como ******), o null
 * @param resumen      una línea para la tabla correos_procesados: "3 usuarios listados"
 * @param html         cuerpo HTML completo que se envía al remitente
 * @param asuntoSeguro asunto sin contraseñas para guardar y mostrar (null = el original)
 * @param adjuntos     archivos adjuntos de la respuesta (por ejemplo el CSV de un reporte)
 */
public record Respuesta(boolean exito, String comando, String parametros, String resumen, String html,
                        String asuntoSeguro, List<Adjunto> adjuntos) {

    /** Respuesta sin adjuntos. */
    public Respuesta(boolean exito, String comando, String parametros, String resumen, String html, String asuntoSeguro) {
        this(exito, comando, parametros, resumen, html, asuntoSeguro, List.of());
    }

    /** Copia de esta respuesta con adjuntos. */
    public Respuesta conAdjuntos(List<Adjunto> archivos) {
        return new Respuesta(exito, comando, parametros, resumen, html, asuntoSeguro, archivos);
    }
}