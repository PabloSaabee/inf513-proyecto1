package org.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Lee src/main/resources/config.properties una sola vez y entrega sus valores.
 */
public final class Config {

    private static final String ARCHIVO = "config.properties";
    private static final Properties PROPS = cargar();

    private Config() { }

    private static Properties cargar() {
        Properties p = new Properties();
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream(ARCHIVO)) {
            if (in == null) {
                throw new IllegalStateException("No se encontró " + ARCHIVO + " en src/main/resources");
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + ARCHIVO + ": " + e.getMessage(), e);
        }
        return p;
    }

    /** Devuelve el valor de la clave; falla con un mensaje claro si no existe. */
    public static String get(String clave) {
        String valor = PROPS.getProperty(clave);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Falta la clave '" + clave + "' en " + ARCHIVO);
        }
        return valor.trim();
    }

    /** Valor opcional: si la clave no existe devuelve el valor por defecto. */
    public static String get(String clave, String porDefecto) {
        String valor = PROPS.getProperty(clave);
        return (valor == null || valor.isBlank()) ? porDefecto : valor.trim();
    }

    /** true / false; si la clave no existe devuelve el valor por defecto. */
    public static boolean getBoolean(String clave, boolean porDefecto) {
        String valor = PROPS.getProperty(clave);
        return (valor == null || valor.isBlank()) ? porDefecto : Boolean.parseBoolean(valor.trim());
    }

    public static int getInt(String clave) {
        try {
            return Integer.parseInt(get(clave));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("La clave '" + clave + "' debe ser un número entero", e);
        }
    }
}