package org.example.negocio;

import java.util.Set;

/**
 * El usuario que envió el correo, con sus roles y permisos.
 * Se obtiene del From del correo (usuarios.email).
 */
public record UsuarioSesion(long id, String ci, String nombres, String apellidos, String email,
                            String estado, Set<String> roles, Set<String> permisos) {

    public String nombreCompleto() {
        return nombres + " " + apellidos;
    }

    public boolean tieneRol(String rol) {
        return roles.contains(rol);
    }

    public boolean tienePermiso(String codigo) {
        return permisos.contains(codigo);
    }
}