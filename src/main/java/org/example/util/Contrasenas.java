package org.example.util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Hash bcrypt de contraseñas (RNF-01). Compatible con los hash de PHP/Laravel ($2y$)
 * y de Python ($2b$): jBCrypt solo conoce $2a$, pero el algoritmo es el mismo.
 */
public final class Contrasenas {

    private static final int COSTO = 10;

    private Contrasenas() { }

    public static String hash(String clave) {
        return BCrypt.hashpw(clave, BCrypt.gensalt(COSTO));
    }

    public static boolean coincide(String clave, String hash) {
        if (hash == null || hash.length() < 7 || !hash.startsWith("$2")) return false;
        String normalizado = "$2a$" + hash.substring(4);              // $2b$ / $2y$ → $2a$
        try {
            return BCrypt.checkpw(clave, normalizado);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}