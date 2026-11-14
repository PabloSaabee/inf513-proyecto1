package org.example.interprete;

import org.example.correo.Correo;
import org.example.negocio.UsuarioSesion;
import org.example.presentacion.RespuestaHtml;

/**
 * El correo ya viene con las contraseñas del asunto ocultas (******).
 *
 * Todo lo que necesita un comando para ejecutarse: el correo, quién lo envió y sus parámetros.
 * Incluye atajos para armar la respuesta.
 */
public record Contexto(Correo correo, UsuarioSesion usuario, ComandoParseado comando, DefComando definicion) {

    /** Parámetro i (empieza en 0), ya sin espacios. */
    public String p(int i) {
        return comando.parametro(i);
    }

    /** true si el parámetro es "*" (todos / sin filtro). */
    public boolean esTodos(int i) {
        return "*".equals(p(i));
    }

    /** true si el parámetro opcional vino vacío ("-" o ""). */
    public boolean esVacio(int i) {
        String v = p(i);
        return v.isEmpty() || v.equals("-");
    }

    /** Respuesta de éxito: titulo (texto), contenido (HTML), resumen (una línea para la tabla). */
    public Respuesta exito(String titulo, String contenidoHtml, String resumen) {
        return new Respuesta(true, comando.nombre(),
                comando.parametrosTexto(CatalogoComandos.parametrosSecretos(comando.nombre())), resumen,
                RespuestaHtml.exito(titulo, contenidoHtml, correo), correo.asunto());
    }
}