package org.example.datos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * Tabla archivos. En la versión e-mail los documentos llegan como ENLACE (Drive, OneDrive...):
 *   ruta = la URL · mime = "text/uri-list" · tamano_bytes = 0 · sha256 = hash de la URL
 * Si el mismo enlace ya está registrado, se reutiliza (ruta es UNIQUE).
 */
public class DArchivo {

    public long registrarEnlace(String url, String nombre, long usuarioId) throws SQLException {
        String sql = """
                INSERT INTO archivos (nombre_original, ruta, mime, tamano_bytes, sha256, subido_por)
                VALUES (?, ?, 'text/uri-list', 0, ?, ?)
                ON CONFLICT (ruta) DO UPDATE SET ruta = EXCLUDED.ruta
                RETURNING id
                """;
        try (Connection cn = ConexionBD.getConexion();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, nombre.length() > 255 ? nombre.substring(0, 255) : nombre);
            ps.setString(2, url);
            ps.setString(3, sha256(url));
            ps.setLong(4, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String sha256(String texto) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}