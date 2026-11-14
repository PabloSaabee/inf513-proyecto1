package org.example.interprete;

import java.util.List;

/**
 * Definición de un comando en el catálogo.
 *
 * @param nombre      LISUSU
 * @param minParams   cantidad mínima de parámetros
 * @param maxParams   cantidad máxima de parámetros
 * @param cu          caso de uso al que pertenece (CU1..CU8, o "General")
 * @param sintaxis    LISUSU["*" | "rol"]
 * @param ejemplo     LISUSU["tutor"]
 * @param descripcion texto corto para HELP
 * @param permisos    códigos de la tabla permisos; basta con tener UNO. Vacío = cualquier usuario activo
 */
public record DefComando(String nombre, int minParams, int maxParams, String cu,
                         String sintaxis, String ejemplo, String descripcion, List<String> permisos) {

    public boolean aceptaCantidad(int n) {
        return n >= minParams && n <= maxParams;
    }

    public String cantidadEsperada() {
        if (minParams == maxParams) return minParams + (minParams == 1 ? " parámetro" : " parámetros");
        return "entre " + minParams + " y " + maxParams + " parámetros";
    }
}