package org.example.util;

import org.example.config.Config;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Archivos de log locales para la auditoría técnica:
 *
 *   logs/access.log  una línea por correo procesado:
 *                    fecha | remitente | comando (contraseñas ocultas) | ÉXITO/ERROR/IGNORADO | detalle
 *   logs/error.log   cada excepción capturada: fecha | dónde | mensaje, y la traza completa
 *
 * La carpeta se configura con log.directorio en config.properties (por defecto "logs",
 * relativa a la carpeta desde donde se ejecuta el programa). Un fallo al escribir el log
 * nunca detiene el sistema: solo se avisa por consola.
 */
public final class Registro {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path DIRECTORIO = Path.of(Config.get("log.directorio", "logs"));
    private static final Path ACCESO = DIRECTORIO.resolve("access.log");
    private static final Path ERROR = DIRECTORIO.resolve("error.log");

    private Registro() { }

    /** access.log: un correo procesado. resultado = ÉXITO, ERROR o IGNORADO. */
    public static void acceso(String remitente, String comando, String resultado, String detalle) {
        escribir(ACCESO, ahora() + " | " + limpio(remitente) + " | " + limpio(comando) + " | "
                + resultado + " | " + limpio(detalle));
    }

    /** error.log: una excepción con su traza completa. */
    public static void error(String donde, Throwable e) {
        StringWriter traza = new StringWriter();
        e.printStackTrace(new PrintWriter(traza));
        escribir(ERROR, ahora() + " | " + donde + " | " + limpio(String.valueOf(e.getMessage()))
                + System.lineSeparator() + traza.toString().stripTrailing());
    }

    /** error.log: un error sin excepción (por ejemplo, una respuesta que no se pudo enviar). */
    public static void error(String donde, String mensaje) {
        escribir(ERROR, ahora() + " | " + donde + " | " + limpio(mensaje));
    }

    // ------------------------------------------------------------------ utilidades

    private static synchronized void escribir(Path archivo, String linea) {
        try {
            Files.createDirectories(DIRECTORIO);
            Files.writeString(archivo, linea + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Registro] No se pudo escribir " + archivo + ": " + e.getMessage());
        }
    }

    private static String ahora() {
        return LocalDateTime.now().format(FECHA);
    }

    /** Una sola línea por registro: sin saltos de línea ni el separador '|'. */
    private static String limpio(String texto) {
        if (texto == null || texto.isBlank()) return "-";
        return texto.replaceAll("[\\r\\n]+", " ").replace('|', '/').strip();
    }
}