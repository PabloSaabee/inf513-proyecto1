package org.example.interprete;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte el asunto del correo en un comando con parámetros.
 *
 *   LISUSU["*"]                         → LISUSU  ["*"]
 *   INSUSU["123","José María","Núñez"]   → INSUSU  ["123","José María","Núñez"]
 *   HELP   /  help  /  HELP["*"]  /  HELP[]
 *
 * Tolera:
 *   - "Re:", "RE:", "Fwd:", "RV:" al inicio (si el usuario responde en el mismo hilo)
 *   - comillas tipográficas “ ” que ponen los celulares
 *   - espacios entre parámetros y minúsculas en el nombre
 *   - comillas dentro de un parámetro escritas como \"
 */
public final class AnalizadorAsunto {

    private static final Pattern PREFIJO_RESPUESTA =
            Pattern.compile("^\\s*((re|rv|fw|fwd|reenviar|respuesta)\\s*:\\s*)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOLO_NOMBRE = Pattern.compile("^([A-Za-z]{3,10})$");
    private static final Pattern FORMATO =
            Pattern.compile("^([A-Za-z]{3,10})\\s*\\[(.*)\\]$", Pattern.DOTALL);
    private static final String PARAM = "\"((?:[^\"\\\\]|\\\\.)*)\"";
    private static final Pattern LISTA =
            Pattern.compile("^\\s*(" + PARAM + "\\s*(,\\s*" + PARAM + "\\s*)*)?$", Pattern.DOTALL);
    private static final Pattern UN_PARAM = Pattern.compile(PARAM);

    private static final String AYUDA_FORMATO = """
            <p style="margin:8px 0 4px">El asunto debe tener la forma:</p>
            <p style="margin:0 0 8px"><code>COMANDO["parámetro1","parámetro2",...]</code></p>
            <p style="margin:0">Ejemplo: <code>LISUSU["*"]</code> · Envíe <code>HELP</code> para ver sus comandos.</p>
            """;

    private AnalizadorAsunto() { }

    public static ComandoParseado analizar(String asunto) {
        if (asunto == null || asunto.isBlank()) {
            throw new ErrorComando("El asunto está vacío.", AYUDA_FORMATO);
        }
        String a = normalizar(asunto);

        Matcher solo = SOLO_NOMBRE.matcher(a);
        if (solo.matches()) {
            // Sin corchetes: solo se permite para HELP
            String nombre = solo.group(1).toUpperCase();
            if (nombre.equals("HELP")) return new ComandoParseado("HELP", List.of());
            throw new ErrorComando("Faltan los corchetes con los parámetros en «" + a + "».", AYUDA_FORMATO);
        }

        Matcher m = FORMATO.matcher(a);
        if (!m.matches()) {
            throw new ErrorComando("El asunto «" + a + "» no tiene el formato de un comando.", AYUDA_FORMATO);
        }
        String nombre = m.group(1).toUpperCase();
        String cuerpo = m.group(2);
        if (!LISTA.matcher(cuerpo).matches()) {
            throw new ErrorComando("Los parámetros de " + nombre
                    + " están mal escritos: cada uno debe ir entre comillas dobles y separados por comas.",
                    AYUDA_FORMATO);
        }

        List<String> parametros = new ArrayList<>();
        Matcher p = UN_PARAM.matcher(cuerpo);
        while (p.find()) {
            parametros.add(p.group(1).replace("\\\"", "\"").replace("\\\\", "\\").trim());
        }
        return new ComandoParseado(nombre, List.copyOf(parametros));
    }

    /** Quita "Re:", unifica comillas y espacios. */
    static String normalizar(String asunto) {
        String a = PREFIJO_RESPUESTA.matcher(asunto).replaceFirst("");
        a = a.replace('“', '"').replace('”', '"').replace('„', '"')
                .replace('«', '"').replace('»', '"')
                .replace(' ', ' ');
        return a.strip();
    }
}