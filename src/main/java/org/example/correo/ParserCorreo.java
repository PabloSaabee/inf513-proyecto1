package org.example.correo;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte las cabeceras crudas de un correo (lo que devuelve POP3) en un {@link Correo}.
 *
 * Resuelve:
 *  - cabeceras partidas en varias líneas (Subject largo de Gmail)
 *  - asuntos codificados =?UTF-8?B?...?= y =?UTF-8?Q?...?=
 *  - From con nombre: "Pablo Arandia <pablosabea@gmail.com>" → pablosabea@gmail.com
 *  - correos sin Message-ID (se genera uno estable)
 */
public final class ParserCorreo {

    private static final Pattern PALABRA_CODIFICADA =
            Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");
    private static final Pattern ESPACIO_ENTRE_PALABRAS =
            Pattern.compile("(\\?=)\\s+(=\\?)");
    private static final Pattern EMAIL_ENTRE_ANGULOS = Pattern.compile("<\\s*([^<>\\s]+@[^<>\\s]+)\\s*>");
    private static final Pattern EMAIL_SUELTO = Pattern.compile("([A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+)");

    private ParserCorreo() { }

    /** Para pruebas sin UIDL. */
    public static Correo analizar(int numero, String crudo) {
        return analizar(numero, "", crudo);
    }

    public static Correo analizar(int numero, String uid, String crudo) {
        Map<String, String> cab = cabeceras(crudo);

        String from = decodificar(cab.getOrDefault("from", ""));
        String remitente = extraerEmail(from);
        String nombre = extraerNombre(from);
        String asunto = decodificar(cab.getOrDefault("subject", "")).trim();
        String fecha = cab.getOrDefault("date", "").trim();

        String messageId = cab.getOrDefault("message-id", "").trim();
        if (messageId.isEmpty()) {
            // Sin Message-ID: se arma uno estable para no procesarlo dos veces
            messageId = "<sin-id-" + Integer.toHexString((from + fecha + asunto).hashCode()) + "@local>";
        }
        return new Correo(numero, uid, messageId, remitente, nombre, asunto, fecha,
                esAutomatico(cab, remitente));
    }

    /**
     * Rebotes (MAILER-DAEMON) y respuestas automáticas ("fuera de oficina").
     * Responderles podría crear un bucle infinito de correos.
     */
    static boolean esAutomatico(Map<String, String> cab, String remitente) {
        if (remitente.isEmpty()) return true;
        String usuario = remitente.substring(0, Math.max(remitente.indexOf('@'), 0)).toLowerCase();
        if (usuario.equals("mailer-daemon") || usuario.equals("postmaster")
                || usuario.startsWith("noreply") || usuario.startsWith("no-reply")) return true;
        String auto = cab.getOrDefault("auto-submitted", "no").trim().toLowerCase();
        if (!auto.startsWith("no")) return true;
        String precedencia = cab.getOrDefault("precedence", "").trim().toLowerCase();
        return precedencia.equals("bulk") || precedencia.equals("junk") || precedencia.equals("list");
    }

    // ------------------------------------------------------------------ cabeceras

    /** Separa las cabeceras (hasta la primera línea vacía) y une las líneas de continuación. */
    static Map<String, String> cabeceras(String crudo) {
        Map<String, String> mapa = new HashMap<>();
        String nombre = null;
        StringBuilder valor = new StringBuilder();

        for (String linea : crudo.split("\r?\n", -1)) {
            if (linea.isEmpty()) break;                              // fin de cabeceras
            if ((linea.charAt(0) == ' ' || linea.charAt(0) == '\t') && nombre != null) {
                valor.append(' ').append(linea.strip());             // línea de continuación
                continue;
            }
            guardar(mapa, nombre, valor);
            int dosPuntos = linea.indexOf(':');
            if (dosPuntos <= 0) { nombre = null; continue; }
            nombre = linea.substring(0, dosPuntos).trim().toLowerCase();
            valor.setLength(0);
            valor.append(linea.substring(dosPuntos + 1).strip());
        }
        guardar(mapa, nombre, valor);
        return mapa;
    }

    private static void guardar(Map<String, String> mapa, String nombre, StringBuilder valor) {
        if (nombre != null) mapa.putIfAbsent(nombre, valor.toString());   // se queda con la primera
    }


    /** Decodifica =?charset?B|Q?...?= y repara texto UTF-8 que llegó sin codificar. */
    static String decodificar(String valor) {
        if (valor == null || valor.isEmpty()) return "";
        // el espacio entre dos palabras codificadas seguidas no forma parte del texto
        String v = ESPACIO_ENTRE_PALABRAS.matcher(valor).replaceAll("$1$2");

        Matcher m = PALABRA_CODIFICADA.matcher(v);
        StringBuilder sb = new StringBuilder();
        int ultimo = 0;
        while (m.find()) {
            sb.append(repararUtf8(v.substring(ultimo, m.start())));
            sb.append(decodificarPalabra(m.group(1), m.group(2), m.group(3)));
            ultimo = m.end();
        }
        sb.append(repararUtf8(v.substring(ultimo)));
        return sb.toString();
    }

    private static String decodificarPalabra(String charset, String tipo, String texto) {
        Charset cs;
        try {
            cs = Charset.forName(charset.split("\\*")[0]);           // ignora "utf-8*es"
        } catch (RuntimeException e) {
            cs = StandardCharsets.UTF_8;
        }
        try {
            byte[] bytes = tipo.equalsIgnoreCase("B")
                    ? Base64.getMimeDecoder().decode(texto)
                    : decodificarQ(texto);
            return new String(bytes, cs);
        } catch (RuntimeException e) {
            return texto;                                            // si viene mal, se deja como está
        }
    }

    /** Codificación Q: "_" es espacio y "=XX" es un byte en hexadecimal. */
    private static byte[] decodificarQ(String texto) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '_') {
                out.write(' ');
            } else if (c == '=' && i + 2 < texto.length()) {
                out.write(Integer.parseInt(texto.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    /**
     * POP3 se lee como ISO-8859-1 (byte a byte). Si el texto en realidad era UTF-8
     * sin codificar (algunos clientes lo envían así), se vuelve a interpretar.
     */
    private static String repararUtf8(String texto) {
        if (texto.chars().allMatch(c -> c < 128)) return texto;
        byte[] bytes = texto.getBytes(StandardCharsets.ISO_8859_1);
        var decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return texto;                                            // no era UTF-8: queda en Latin-1
        }
    }

    // ------------------------------------------------------------------ From

    static String extraerEmail(String from) {
        Matcher m = EMAIL_ENTRE_ANGULOS.matcher(from);
        if (m.find()) return m.group(1).toLowerCase();
        m = EMAIL_SUELTO.matcher(from);
        return m.find() ? m.group(1).toLowerCase() : "";
    }

    static String extraerNombre(String from) {
        int i = from.indexOf('<');
        if (i <= 0) return "";
        return from.substring(0, i).replace("\"", "").trim();
    }
}