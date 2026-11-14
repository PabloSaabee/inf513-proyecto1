package org.example.correo;

/**
 * Archivo que se envía adjunto en la respuesta (por ejemplo, el CSV de un reporte).
 *
 * @param nombre nombre del archivo, solo letras, números, guiones y punto: reporte_titulados.csv
 * @param mime   tipo de contenido: text/csv
 * @param datos  contenido en bytes
 */
public record Adjunto(String nombre, String mime, byte[] datos) {
}