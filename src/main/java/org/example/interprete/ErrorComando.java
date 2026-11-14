package org.example.interprete;

/**
 * Error esperado al procesar un comando (formato, parámetros, reglas de negocio).
 * Su mensaje se envía tal cual al remitente, así que debe ser claro y en español.
 * 'ayudaHtml' es opcional: por ejemplo, la sintaxis correcta del comando.
 */
public class ErrorComando extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String ayudaHtml;

    public ErrorComando(String mensaje) {
        this(mensaje, null);
    }

    public ErrorComando(String mensaje, String ayudaHtml) {
        super(mensaje);
        this.ayudaHtml = ayudaHtml;
    }

    public String getAyudaHtml() {
        return ayudaHtml;
    }
}