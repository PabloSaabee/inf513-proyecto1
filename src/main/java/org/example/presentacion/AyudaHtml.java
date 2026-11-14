package org.example.presentacion;

import org.example.interprete.DefComando;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contenido de la respuesta a HELP: los comandos del usuario agrupados por caso de uso.
 */
public final class AyudaHtml {

    private static final Map<String, String> NOMBRES_CU = Map.of(
            "General", "General",
            "CU1", "CU1 · Gestión de Usuarios",
            "CU2", "CU2 · Habilitación Académica",
            "CU3", "CU3 · Tutor y Perfil de Proyecto",
            "CU4", "CU4 · Viabilidad y Tribunales",
            "CU5", "CU5 · Revisión, Versionado y Plazos",
            "CU6", "CU6 · Predefensa y Defensa",
            "CU7", "CU7 · Monitoreo, Dashboard y Calendario",
            "CU8", "CU8 · Reportes y Estadísticas");

    private AyudaHtml() { }

    public static String contenido(String nombreUsuario, List<String> roles, List<DefComando> comandos) {
        Map<String, List<DefComando>> porCu = new LinkedHashMap<>();
        for (DefComando d : comandos) {
            porCu.computeIfAbsent(d.cu(), k -> new ArrayList<>()).add(d);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<p style=\"margin:0 0 8px\">Hola <b>").append(RespuestaHtml.esc(nombreUsuario))
                .append("</b> (").append(RespuestaHtml.esc(String.join(", ", roles))).append("). ")
                .append("Estos son los ").append(comandos.size()).append(" comandos que puede usar:</p>");

        for (Map.Entry<String, List<DefComando>> e : porCu.entrySet()) {
            sb.append("<p style=\"margin:14px 0 4px;font-weight:bold;color:#0B3D6E\">")
                    .append(RespuestaHtml.esc(NOMBRES_CU.getOrDefault(e.getKey(), e.getKey()))).append("</p>");
            sb.append(tabla(e.getValue()));
        }

        sb.append("""
                <p style="margin:16px 0 4px;font-weight:bold">Reglas del asunto</p>
                <ul style="margin:0;padding-left:20px;font-size:13px">
                  <li>Formato: <code>COMANDO["param1","param2"]</code>, cada parámetro entre comillas dobles.</li>
                  <li><code>"*"</code> significa todos o sin filtro; <code>"-"</code> deja vacío un parámetro opcional.</li>
                  <li>Fechas <code>2026-11-20</code>, horas <code>10:00</code>; archivos como enlace (Drive u OneDrive).</li>
                  <li>El sistema lo reconoce por el correo desde el que envía.</li>
                </ul>
                """);
        return sb.toString();
    }

    /** Dos columnas: comando (sintaxis + ejemplo, con corte de línea) y descripción. */
    private static String tabla(List<DefComando> comandos) {
        String th = "border:1px solid #D9DEE5;padding:6px 8px;background:#F4F6F9;text-align:left;font-size:13px";
        String td = "border:1px solid #D9DEE5;padding:6px 8px;font-size:13px;vertical-align:top";
        String code = "font-family:Consolas,Menlo,monospace;font-size:12px;word-break:break-all";
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:collapse;width:100%;table-layout:fixed\">")
                .append("<tr><th style=\"").append(th).append(";width:65%\">Comando</th>")
                .append("<th style=\"").append(th).append("\">Descripción</th></tr>");
        for (DefComando d : comandos) {
            sb.append("<tr><td style=\"").append(td).append("\">")
                    .append("<div style=\"").append(code).append(";font-weight:bold\">").append(RespuestaHtml.esc(d.sintaxis())).append("</div>")
                    .append("<div style=\"").append(code).append(";color:#5B6573;margin-top:4px\">Ej.: ")
                    .append(RespuestaHtml.esc(d.ejemplo())).append("</div></td>")
                    .append("<td style=\"").append(td).append("\">").append(RespuestaHtml.esc(d.descripcion())).append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }
}