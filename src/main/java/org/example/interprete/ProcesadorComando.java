package org.example.interprete;

import org.example.correo.Correo;

/**
 * Lo que hace el sistema con cada correo nuevo.
 * Fase 3: ProcesadorEco (solo confirma que recibió el comando).
 * Fase 4: Despachador (interpreta y ejecuta el comando).
 */
@FunctionalInterface
public interface ProcesadorComando {
    Respuesta procesar(Correo correo);
}