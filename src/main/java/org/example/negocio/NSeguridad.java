package org.example.negocio;

import org.example.datos.DUsuario;
import org.example.interprete.DefComando;
import org.example.interprete.ErrorComando;

import java.sql.SQLException;

/**
 * Identifica al remitente y verifica sus permisos (matriz rol_permiso).
 */
public class NSeguridad {

    private final DUsuario dUsuario = new DUsuario();

    /** Devuelve el usuario activo dueño del correo, o lanza ErrorComando con el motivo. */
    public UsuarioSesion identificar(String email) throws SQLException {
        UsuarioSesion u = dUsuario.buscarSesionPorEmail(email);
        if (u == null) {
            throw new ErrorComando("El correo " + email + " no está registrado en el sistema. "
                    + "Solicite su registro al administrador.");
        }
        switch (u.estado()) {
            case "inactivo" -> throw new ErrorComando("Su cuenta está inactiva. Comuníquese con el administrador.");
            case "bloqueado" -> throw new ErrorComando("Su cuenta está bloqueada. Comuníquese con el administrador.");
            default -> { }
        }
        return u;
    }

    /** true si el comando no pide permiso o si el usuario tiene al menos uno de los requeridos. */
    public boolean puedeUsar(UsuarioSesion u, DefComando def) {
        return def.permisos().isEmpty() || def.permisos().stream().anyMatch(u::tienePermiso);
    }

    public void exigirPermiso(UsuarioSesion u, DefComando def) {
        if (!puedeUsar(u, def)) {
            throw new ErrorComando("No tiene permiso para ejecutar " + def.nombre()
                    + ". Sus roles: " + String.join(", ", u.roles())
                    + ". Envíe HELP para ver los comandos que puede usar.");
        }
    }
}