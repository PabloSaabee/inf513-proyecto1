package org.example.presentacion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Genera CSV que Excel abre bien en español (CU8-S1):
 *  - separador ";" (Excel en español usa la coma como decimal)
 *  - UTF-8 con BOM, para que las tildes se vean bien
 *  - protección contra inyección de fórmulas: un valor que empieza con = + - @ se antepone con '
 */
public final class Csv {

    private final StringBuilder sb = new StringBuilder();

    /** Agrega una tabla con su título; las tablas se separan con una línea vacía. */
    public Csv tabla(String titulo, List<String> cabecera, List<String[]> filas) {
        if (!sb.isEmpty()) sb.append("\r\n");
        sb.append(celda(titulo)).append("\r\n");
        fila(cabecera.toArray(String[]::new));
        for (String[] f : filas) fila(f);
        return this;
    }

    private void fila(String[] valores) {
        for (int i = 0; i < valores.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(celda(valores[i]));
        }
        sb.append("\r\n");
    }

    static String celda(String v) {
        if (v == null) return "";
        String t = v;
        if (t.length() > 1 && "=+-@\t\r".indexOf(t.charAt(0)) >= 0 && !t.matches("-?\\d+([.,]\\d+)?")) {
            t = "'" + t;                                                   // no deja que Excel lo tome como fórmula
        }
        if (t.contains(";") || t.contains("\"") || t.contains("\n") || t.contains("\r")) {
            t = "\"" + t.replace("\"", "\"\"") + "\"";
        }
        return t;
    }

    public byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});   // BOM
        out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}