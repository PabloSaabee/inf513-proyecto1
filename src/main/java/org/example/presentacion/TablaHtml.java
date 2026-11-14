package org.example.presentacion;

import java.util.List;

/**
 * Convierte filas en una tabla HTML con estilos en línea (compatibles con Gmail).
 */
public final class TablaHtml {

    private static final String TH = "border:1px solid #D9DEE5;padding:6px 8px;background:#F4F6F9;"
            + "text-align:left;font-size:13px;white-space:nowrap";
    private static final String TD = "border:1px solid #D9DEE5;padding:6px 8px;font-size:13px;vertical-align:top;"
            + "overflow-wrap:break-word";

    private TablaHtml() { }

    public static String tabla(List<String> cabecera, List<String[]> filas) {
        if (filas.isEmpty()) {
            return "<p style=\"margin:0;color:#5B6573\">No se encontraron registros.</p>";
        }
        StringBuilder sb = new StringBuilder(
                "<table style=\"border-collapse:collapse;width:100%;margin:4px 0\"><tr>");
        for (String c : cabecera) {
            sb.append("<th style=\"").append(TH).append("\">").append(RespuestaHtml.esc(c)).append("</th>");
        }
        sb.append("</tr>");
        for (String[] fila : filas) {
            sb.append("<tr>");
            for (String celda : fila) {
                sb.append("<td style=\"").append(TD).append("\">").append(RespuestaHtml.esc(celda)).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    /** Enlace corto para meter en una celda con tablaHtml(). */
    public static String enlace(String url, String texto) {
        return "<a href=\"" + RespuestaHtml.esc(url) + "\" style=\"color:#0B3D6E\">" + RespuestaHtml.esc(texto) + "</a>";
    }

    /** Igual que tabla(), pero las celdas ya vienen en HTML (escaparlas con RespuestaHtml.esc). */
    public static String tablaHtml(List<String> cabecera, List<String[]> filasHtml) {
        if (filasHtml.isEmpty()) {
            return "<p style=\"margin:0;color:#5B6573\">No se encontraron registros.</p>";
        }
        StringBuilder sb = new StringBuilder(
                "<table style=\"border-collapse:collapse;width:100%;margin:4px 0\"><tr>");
        for (String c : cabecera) {
            sb.append("<th style=\"").append(TH).append("\">").append(RespuestaHtml.esc(c)).append("</th>");
        }
        sb.append("</tr>");
        for (String[] fila : filasHtml) {
            sb.append("<tr>");
            for (String celda : fila) sb.append("<td style=\"").append(TD).append("\">").append(celda).append("</td>");
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    /** Tabla de dos columnas "Campo | Valor" para ver el detalle de un registro. */
    public static String ficha(List<String> campos, List<String> valores) {
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:collapse;margin:4px 0\">");
        for (int i = 0; i < campos.size(); i++) {
            sb.append("<tr><th style=\"").append(TH).append("\">").append(RespuestaHtml.esc(campos.get(i)))
                    .append("</th><td style=\"").append(TD).append("\">").append(RespuestaHtml.esc(valores.get(i)))
                    .append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    /** Igual que ficha(), pero los valores ya vienen en HTML (escaparlos con RespuestaHtml.esc). */
    public static String fichaHtml(List<String> campos, List<String> valoresHtml) {
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:collapse;margin:4px 0\">");
        for (int i = 0; i < campos.size(); i++) {
            sb.append("<tr><th style=\"").append(TH).append("\">").append(RespuestaHtml.esc(campos.get(i)))
                    .append("</th><td style=\"").append(TD).append("\">").append(valoresHtml.get(i))
                    .append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    /**
     * Miniatura de una foto (120 px) con el enlace "Ver foto" debajo.
     * Si el cliente de correo no carga la imagen (link que no es directo, imágenes bloqueadas), queda el enlace.
     */
    public static String foto(String url) {
        String u = RespuestaHtml.esc(url);
        return "<a href=\"" + u + "\"><img src=\"" + u + "\" alt=\"Foto\" width=\"120\" "
                + "style=\"display:block;width:120px;max-width:120px;height:auto;margin:0 0 4px;"
                + "border:1px solid #D9DEE5;border-radius:6px\"></a>"
                + "<a href=\"" + u + "\" style=\"color:#0B3D6E;font-size:12px\">Ver foto</a>";
    }

    /** Fila de cantidad: "12 registros". */
    public static String total(int n, String singular, String plural) {
        return "<p style=\"margin:8px 0 0;font-size:13px;color:#5B6573\">Total: " + n + " "
                + (n == 1 ? singular : plural) + "</p>";
    }
}