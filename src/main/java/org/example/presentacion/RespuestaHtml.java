package org.example.presentacion;

import org.example.correo.Correo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Plantilla HTML de todas las respuestas del sistema.
 * Estilos en línea (style="...") porque Gmail ignora las hojas de estilo.
 * Colores de la paleta del documento (Tabla 65).
 */
public final class RespuestaHtml {

    private static final String PRIMARIO = "#0B3D6E";
    private static final String EXITO = "#1E7B3A";
    private static final String ERROR = "#C8102E";
    private static final String BORDE = "#D9DEE5";
    private static final String FONDO = "#F4F6F9";
    private static final String TEXTO = "#1F2933";
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private RespuestaHtml() { }

    /** Respuesta verde. 'contenido' ya es HTML (texto, tablas...). */
    public static String exito(String titulo, String contenido, Correo c) {
        return armar(EXITO, "&#10004; " + esc(titulo), contenido, c);
    }

    /** Respuesta roja con el motivo; 'ayuda' (HTML, opcional) muestra la sintaxis correcta. */
    public static String error(String motivo, String ayuda, Correo c) {
        String contenido = "<p style=\"margin:0 0 8px\">" + esc(motivo) + "</p>"
                + (ayuda == null || ayuda.isBlank() ? "" : ayuda);
        return armar(ERROR, "&#10008; Error", contenido, c);
    }

    /**
     * Aviso que el sistema envía por iniciativa propia (notificación), sin un comando recibido.
     * 'comandoSugerido' (opcional) indica qué enviar para ver el detalle.
     */
    public static String aviso(String titulo, String mensaje, String comandoSugerido) {
        String contenido = "<p style=\"margin:0 0 8px\">" + esc(mensaje) + "</p>"
                + (comandoSugerido == null ? "" :
                "<p style=\"margin:0;font-size:13px\">Para ver el detalle envíe: <code>" + esc(comandoSugerido) + "</code></p>");
        return """
                <!DOCTYPE html>
                <html lang="es"><head><meta charset="UTF-8"></head>
                <body style="margin:0;padding:16px;background:%s;font-family:Arial,Helvetica,sans-serif;color:%s">
                <div style="max-width:720px;margin:0 auto">
                  <div style="background:%s;color:#ffffff;padding:12px 16px;border-radius:6px 6px 0 0;font-weight:bold">
                    Sistema de Seguimiento a Titulados · Notificación
                  </div>
                  <div style="background:#ffffff;border:1px solid %s;border-top:0;padding:16px;border-radius:0 0 6px 6px">
                    <p style="color:%s;font-size:18px;font-weight:bold;margin:0 0 12px">&#128276; %s</p>
                    %s
                    <hr style="border:0;border-top:1px solid %s;margin:16px 0 8px">
                    <p style="font-size:12px;color:#5B6573;margin:0">Enviado: %s</p>
                  </div>
                  <p style="font-size:11px;color:#8A94A3;text-align:center;margin:8px 0 0">
                    Carrera de Ingeniería en Redes y Telecomunicaciones · FICCT – UAGRM · Grupo 03 SA
                  </p>
                </div>
                </body></html>
                """.formatted(FONDO, TEXTO, PRIMARIO, BORDE, PRIMARIO, esc(titulo), contenido, BORDE,
                LocalDateTime.now().format(FECHA));
    }

    private static String armar(String color, String titulo, String contenido, Correo c) {
        return """
                <!DOCTYPE html>
                <html lang="es"><head><meta charset="UTF-8"></head>
                <body style="margin:0;padding:16px;background:%s;font-family:Arial,Helvetica,sans-serif;color:%s">
                <div style="max-width:720px;margin:0 auto">
                  <div style="background:%s;color:#ffffff;padding:12px 16px;border-radius:6px 6px 0 0;font-weight:bold">
                    Sistema de Seguimiento a Titulados
                  </div>
                  <div style="background:#ffffff;border:1px solid %s;border-top:0;padding:16px;border-radius:0 0 6px 6px">
                    <p style="color:%s;font-size:18px;font-weight:bold;margin:0 0 12px">%s</p>
                    %s
                    <hr style="border:0;border-top:1px solid %s;margin:16px 0 8px">
                    <p style="font-size:12px;color:#5B6573;margin:0">
                      Comando recibido: <code>%s</code><br>
                      Remitente: %s<br>
                      Procesado: %s
                    </p>
                  </div>
                  <p style="font-size:11px;color:#8A94A3;text-align:center;margin:8px 0 0">
                    Carrera de Ingeniería en Redes y Telecomunicaciones · FICCT – UAGRM · Grupo 03 SA
                  </p>
                </div>
                </body></html>
                """.formatted(FONDO, TEXTO, PRIMARIO, BORDE, color, titulo, contenido, BORDE,
                esc(c.asunto()), esc(c.remitente()), LocalDateTime.now().format(FECHA));
    }

    /** Escapa texto para meterlo en HTML sin romperlo (y sin permitir inyectar etiquetas). */
    public static String esc(String texto) {
        if (texto == null) return "";
        StringBuilder sb = new StringBuilder(texto.length());
        for (char ch : texto.toCharArray()) {
            switch (ch) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}