package org.example.negocio;

import org.example.config.Config;
import org.example.correo.ClienteSMTP;
import org.example.datos.DNotificacion;
import org.example.datos.DNotificacion.Destinatario;
import org.example.presentacion.RespuestaHtml;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Notificaciones del sistema (CU7-S1): cada acción relevante avisa a los involucrados.
 * Se guardan en la tabla notificaciones (LISNOT) y además se envían por correo,
 * salvo que mail.notificar=false en config.properties.
 *
 * Una falla al notificar nunca hace fallar el comando que la originó.
 */
public class NNotificacion {

    private final DNotificacion dNotificacion = new DNotificacion();

    /** Avisa a un usuario. */
    public void aUsuario(long usuarioId, String titulo, String mensaje, String comandoSugerido) {
        try {
            Destinatario d = dNotificacion.destinatario(usuarioId);
            if (d != null) enviar(d, titulo, mensaje, comandoSugerido);
        } catch (SQLException e) {
            System.err.println("[Notificación] No se pudo registrar: " + e.getMessage());
        }
    }

    /** Avisa a todos los usuarios activos con alguno de esos roles. @return cuántos fueron avisados */
    public int aRoles(String titulo, String mensaje, String comandoSugerido, String... roles) {
        try {
            List<Destinatario> lista = dNotificacion.conRoles(roles);
            for (Destinatario d : lista) enviar(d, titulo, mensaje, comandoSugerido);
            return lista.size();
        } catch (SQLException e) {
            System.err.println("[Notificación] No se pudo registrar: " + e.getMessage());
            return 0;
        }
    }

    private void enviar(Destinatario d, String titulo, String mensaje, String comandoSugerido) throws SQLException {
        long id = dNotificacion.insertar(d.id(), titulo, mensaje, comandoSugerido);
        if (!Config.getBoolean("mail.notificar", true)) return;
        try {
            ClienteSMTP.enviar(d.email(), "Notificación: " + titulo,
                    RespuestaHtml.aviso(titulo, mensaje, comandoSugerido));
            dNotificacion.marcarEnviada(id);
        } catch (IOException e) {
            System.err.println("[Notificación] No se pudo enviar a " + d.email() + ": " + e.getMessage());
        }
    }
}