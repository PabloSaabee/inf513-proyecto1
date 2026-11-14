package org.example.correo;

/**
 * Datos de un correo recibido que necesita el sistema.
 *
 * @param numero          número del mensaje en el buzón POP3 en esta sesión (sirve para DELE)
 * @param uid             identificador UIDL del buzón (no cambia entre sesiones)
 * @param messageId       cabecera Message-ID (evita procesar dos veces el mismo correo)
 * @param remitente       solo la dirección, en minúsculas: pablosabea@gmail.com
 * @param nombreRemitente nombre que aparece en el From (puede estar vacío)
 * @param asunto          asunto ya decodificado: LISUSU["*"]
 * @param fecha           cabecera Date tal como llegó
 * @param automatico      true si es un rebote o respuesta automática (no se le responde)
 */
public record Correo(int numero,
                     String uid,
                     String messageId,
                     String remitente,
                     String nombreRemitente,
                     String asunto,
                     String fecha,
                     boolean automatico) {
}