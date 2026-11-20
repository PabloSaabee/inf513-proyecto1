package org.example.correo;

import org.example.config.Config;

import java.io.IOException;

/**
 * Herramienta de emergencia: borra TODOS los correos del buzón de una sola vez,
 * sin leerlos, sin procesarlos y sin responder (por ejemplo, después de una inundación de correos).
 *
 * POP3 no tiene un comando "borrar todo": se marca cada mensaje con DELE y el servidor
 * los elimina recién al cerrar con QUIT. A diferencia del monitor, aquí no se descargan
 * las cabeceras (TOP), por eso es mucho más rápido.
 *
 * Uso: detener Main y ejecutar esta clase (en IntelliJ: clic derecho → Run 'VaciarBuzon.main()').
 * ¡Cuidado! También borra los correos legítimos que estén en el buzón.
 */
public final class VaciarBuzon {

    private VaciarBuzon() { }

    public static void main(String[] args) throws IOException {
        String buzon = Config.get("mail.direccion");
        long inicio = System.currentTimeMillis();
        int total;
        // Sin modo depuración: imprimir cada DELE en consola lo haría más lento
        try (ClientePOP3 pop = new ClientePOP3(Config.get("mail.servidor"), Config.getInt("mail.pop3.puerto"), false)) {
            pop.login(Config.get("mail.usuario"), Config.get("mail.password"));
            total = pop.contar();
            System.out.println("Buzón " + buzon + ": " + total + " correo(s)");
            for (int n = 1; n <= total; n++) {
                pop.eliminar(n);
                if (n % 100 == 0) System.out.println("  marcados " + n + " de " + total + "...");
            }
            if (total > 0) System.out.println("Aplicando el borrado (QUIT)...");
        }   // QUIT: recién aquí el servidor borra los mensajes marcados
        System.out.printf("Listo: %d correo(s) borrado(s) en %.1f s%n", total,
                (System.currentTimeMillis() - inicio) / 1000.0);
    }
}