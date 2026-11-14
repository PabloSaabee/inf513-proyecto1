package org.example.datos;

import org.example.correo.Correo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Acceso a la tabla correos_procesados.
 * Garantiza que un mismo correo no se procese dos veces.
 */
public class DCorreoProcesado {

    /** De una lista de UIDs del buzón, devuelve los que YA están registrados. */
    public Set<String> uidsRegistrados(Collection<String> uids) throws SQLException {
        Set<String> registrados = new HashSet<>();
        if (uids.isEmpty()) return registrados;

        String sql = "SELECT uid_pop3 FROM correos_procesados WHERE uid_pop3 = ANY (?)";
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setArray(1, cn.createArrayOf("varchar", uids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) registrados.add(rs.getString(1));
            }
        }
        return registrados;
    }

    /**
     * Registra el correo como 'pendiente'.
     * Busca al usuario por su email (queda NULL si no está registrado).
     *
     * @return id del registro, o null si ese Message-ID ya existía (correo repetido)
     */
    public Long registrarPendiente(Correo c) throws SQLException {
        String sql = """
                INSERT INTO correos_procesados
                       (message_id, uid_pop3, remitente, usuario_id, asunto, resultado, fecha_recepcion)
                VALUES (?, ?, ?,
                        (SELECT id FROM usuarios WHERE LOWER(email) = LOWER(?)),
                        ?, 'pendiente', CURRENT_TIMESTAMP)
                ON CONFLICT (message_id) DO NOTHING
                RETURNING id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, recortar(c.messageId(), 255));
            ps.setString(2, recortar(c.uid(), 100));
            ps.setString(3, recortar(c.remitente(), 120));
            ps.setString(4, c.remitente());
            ps.setString(5, recortar(c.asunto(), 500));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** Guarda el resultado final del procesamiento. */
    public void finalizar(long id, String comando, String parametros, boolean exito,
                          String mensajeRespuesta, boolean respuestaEnviada) throws SQLException {
        finalizar(id, comando, parametros, exito, mensajeRespuesta, respuestaEnviada, null);
    }

    /** asuntoSeguro: reemplaza el asunto guardado (para ocultar contraseñas); null lo deja igual. */
    public void finalizar(long id, String comando, String parametros, boolean exito,
                          String mensajeRespuesta, boolean respuestaEnviada, String asuntoSeguro) throws SQLException {
        String sql = """
                UPDATE correos_procesados
                   SET comando = ?, parametros = ?, resultado = ?, mensaje_respuesta = ?,
                       respuesta_enviada = ?, fecha_proceso = CURRENT_TIMESTAMP,
                       asunto = COALESCE(?, asunto)
                 WHERE id = ?
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, recortar(comando, 30));
            ps.setString(2, parametros);
            ps.setString(3, exito ? "exito" : "error");
            ps.setString(4, mensajeRespuesta);
            ps.setBoolean(5, respuestaEnviada);
            ps.setString(6, recortar(asuntoSeguro, 500));
            ps.setLong(7, id);
            ps.executeUpdate();
        }
    }

    private static String recortar(String texto, int max) {
        if (texto == null) return null;
        return texto.length() <= max ? texto : texto.substring(0, max);
    }
}