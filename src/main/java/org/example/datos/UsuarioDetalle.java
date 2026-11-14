package org.example.datos;

import java.util.List;

/**
 * Datos completos de un usuario para VERUSU y VERPER.
 * Los campos de estudiante o docente quedan en null si no corresponde.
 */
public record UsuarioDetalle(long id, String ci, String nombres, String apellidos, String email,
                             String telefono, String foto, String estado, int intentosFallidos,
                             String ultimoAcceso, String registradoEn, List<String> roles,
                             String registroUniversitario, String carrera, String malla, String grl,
                             boolean esDocente, String titulo, String areas) {

    public String nombreCompleto() {
        return nombres + " " + apellidos;
    }

    public boolean esEstudiante() {
        return registroUniversitario != null;
    }
}