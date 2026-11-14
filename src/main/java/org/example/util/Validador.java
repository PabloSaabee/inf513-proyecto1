package org.example.util;

import org.example.interprete.ErrorComando;

import java.util.regex.Pattern;

/**
 * Validaciones de los parámetros que llegan en el asunto.
 * Cada método devuelve el valor limpio o lanza ErrorComando con un mensaje claro.
 */
public final class Validador {

    private static final Pattern CI = Pattern.compile("^\\d{4,12}(-[0-9A-Z]{1,3})?$");
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern TELEFONO = Pattern.compile("^\\+?\\d{7,15}$");
    private static final Pattern NOMBRE = Pattern.compile("^[\\p{L}][\\p{L} .'\\-]{1,79}$");
    private static final Pattern REGISTRO = Pattern.compile("^\\d{6,12}$");
    private static final Pattern URL = Pattern.compile("^https?://\\S{4,}$", Pattern.CASE_INSENSITIVE);

    private Validador() { }

    public static String ci(String v) {
        String ci = v.trim().toUpperCase();
        if (!CI.matcher(ci).matches()) {
            throw new ErrorComando("El CI «" + v + "» no es válido: solo números (4 a 12), con complemento opcional como 1234567-1B.");
        }
        return ci;
    }

    public static String email(String v) {
        String e = v.trim().toLowerCase();
        if (e.length() > 120 || !EMAIL.matcher(e).matches()) {
            throw new ErrorComando("El email «" + v + "» no es válido.");
        }
        return e;
    }

    /** Teléfono opcional: "-" o vacío devuelve null. */
    public static String telefono(String v) {
        String t = v.trim().replace(" ", "");
        if (t.isEmpty() || t.equals("-")) return null;
        if (!TELEFONO.matcher(t).matches()) {
            throw new ErrorComando("El teléfono «" + v + "» no es válido: 7 a 15 dígitos, o \"-\" si no tiene.");
        }
        return t;
    }

    public static String nombre(String v, String campo) {
        String n = v.trim().replaceAll("\\s+", " ");
        if (!NOMBRE.matcher(n).matches()) {
            throw new ErrorComando("El campo " + campo + " «" + v + "» no es válido: solo letras y espacios (2 a 80).");
        }
        return n;
    }

    public static String registro(String v) {
        String r = v.trim();
        if (!REGISTRO.matcher(r).matches()) {
            throw new ErrorComando("El registro universitario «" + v + "» no es válido: 6 a 12 dígitos.");
        }
        return r;
    }

    public static String url(String v, String campo) {
        String u = v.trim();
        if (u.length() > 255 || !URL.matcher(u).matches()) {
            throw new ErrorComando("El campo " + campo + " debe ser un enlace que empiece con http:// o https:// (máx. 255).");
        }
        return u;
    }

    /** Texto libre opcional con largo máximo: "-" o vacío devuelve null. */
    public static String textoOpcional(String v, int max, String campo) {
        String t = v.trim();
        if (t.isEmpty() || t.equals("-")) return null;
        if (t.length() > max) throw new ErrorComando("El campo " + campo + " admite hasta " + max + " caracteres.");
        return t;
    }

    /** Fecha en formato aaaa-mm-dd: 2026-11-20. */
    public static java.time.LocalDate fecha(String v, String campo) {
        try {
            return java.time.LocalDate.parse(v.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ErrorComando("El campo " + campo + " debe ser una fecha con formato aaaa-mm-dd (por ejemplo 2026-11-20).");
        }
    }

    /** Hora en formato hh:mm (24 horas): 10:00, 15:30. */
    public static java.time.LocalTime hora(String v, String campo) {
        try {
            String h = v.trim();
            if (h.length() == 4 && h.charAt(1) == ':') h = "0" + h;        // 9:00 → 09:00
            return java.time.LocalTime.parse(h);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ErrorComando("El campo " + campo + " debe ser una hora con formato hh:mm (por ejemplo 10:00 o 15:30).");
        }
    }

    /** "aaaa-mm-dd hh:mm" o solo "aaaa-mm-dd" (se toma 00:00). */
    public static java.time.LocalDateTime fechaHora(String v, String campo) {
        String t = v.trim().replace('T', ' ');
        String[] partes = t.split("\\s+");
        java.time.LocalDate f = fecha(partes[0], campo);
        java.time.LocalTime h = partes.length > 1 ? hora(partes[1], campo) : java.time.LocalTime.MIDNIGHT;
        return java.time.LocalDateTime.of(f, h);
    }

    public static int entero(String v, String campo) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ErrorComando("El campo " + campo + " debe ser un número entero («" + v + "»).");
        }
    }

    /** RNF-01: 8 o más caracteres con mayúsculas, minúsculas, números y símbolos. */
    public static String contrasena(String v) {
        if (v.length() < 8 || v.length() > 72
                || !v.matches(".*[A-ZÁÉÍÓÚÑ].*") || !v.matches(".*[a-záéíóúñ].*")
                || !v.matches(".*\\d.*") || !v.matches(".*[^\\p{L}\\d].*")) {
            throw new ErrorComando("La contraseña debe tener entre 8 y 72 caracteres, con mayúsculas, minúsculas, "
                    + "números y al menos un símbolo (por ejemplo: Clave2026*).");
        }
        return v;
    }
}