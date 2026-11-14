package org.example.interprete;

import java.util.List;
import java.util.Set;

/**
 * Resultado de analizar el asunto: LISUSU["*"] → nombre "LISUSU", parámetros ["*"].
 */
public record ComandoParseado(String nombre, List<String> parametros) {

    public String parametro(int i) {
        return parametros.get(i);
    }

    /** Parámetros como texto para guardar en correos_procesados: ["*","tutor"]. */
    public String parametrosTexto() {
        return parametrosTexto(Set.of());
    }

    /** Igual, pero las posiciones indicadas se muestran como ****** (contraseñas). */
    public String parametrosTexto(Set<Integer> ocultos) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parametros.size(); i++) {
            if (i > 0) sb.append(',');
            String valor = ocultos.contains(i) ? "******" : parametros.get(i).replace("\"", "\\\"");
            sb.append('"').append(valor).append('"');
        }
        return sb.append(']').toString();
    }

    /** El asunto reconstruido sin contraseñas: INSUSU["123",...,"******","secretaria"]. */
    public String asuntoSeguro(Set<Integer> ocultos) {
        return nombre + parametrosTexto(ocultos);
    }
}