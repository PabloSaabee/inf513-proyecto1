package org.example.correo;

import org.example.config.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente POP3 con sockets (RFC 1939), puerto 110.
 *
 * Uso:
 *   try (ClientePOP3 pop = ClientePOP3.conectar()) {
 *       int total = pop.contar();
 *       String cab = pop.cabeceras(1);
 *       pop.eliminar(1);
 *   }   // close() envía QUIT: recién ahí el servidor borra los marcados con DELE
 */
public class ClientePOP3 implements AutoCloseable {

    private static final int TIMEOUT_MS = 30_000;

    private final Socket socket;
    private final BufferedReader entrada;
    private final OutputStream salida;
    private final boolean debug;

    public ClientePOP3(String servidor, int puerto, boolean debug) throws IOException {
        this.debug = debug;
        socket = new Socket();
        socket.connect(new InetSocketAddress(servidor, puerto), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        salida = socket.getOutputStream();
        leerRespuesta();                                   // saludo +OK del servidor
    }

    /** Conecta e inicia sesión con los datos de config.properties. */
    public static ClientePOP3 conectar() throws IOException {
        ClientePOP3 pop = new ClientePOP3(
                Config.get("mail.servidor"),
                Config.getInt("mail.pop3.puerto"),
                Config.getBoolean("mail.debug", false));
        try {
            pop.login(Config.get("mail.usuario"), Config.get("mail.password"));
        } catch (IOException e) {
            pop.cerrarSocket();
            throw e;
        }
        return pop;
    }

    public void login(String usuario, String password) throws IOException {
        comando("USER " + usuario);
        comando("PASS " + password);
    }

    /** STAT → cantidad de mensajes en el buzón. */
    public int contar() throws IOException {
        String r = comando("STAT");                        // +OK 3 4520
        String[] partes = r.split("\\s+");
        return Integer.parseInt(partes[1]);
    }

    /** LIST → números de los mensajes que hay en el buzón. */
    public List<Integer> listar() throws IOException {
        comando("LIST");
        List<Integer> numeros = new ArrayList<>();
        for (String linea : leerMultilinea()) {
            String[] partes = linea.trim().split("\\s+");
            if (!partes[0].isEmpty()) numeros.add(Integer.parseInt(partes[0]));
        }
        return numeros;
    }

    /**
     * UIDL → número de mensaje y su identificador único.
     * El número cambia entre sesiones; el UID no. Así se sabe qué correos son nuevos
     * sin descargar sus cabeceras.
     */
    public Map<Integer, String> uidl() throws IOException {
        comando("UIDL");
        Map<Integer, String> uids = new LinkedHashMap<>();
        for (String linea : leerMultilinea()) {
            String[] partes = linea.trim().split("\\s+", 2);
            if (partes.length == 2) uids.put(Integer.parseInt(partes[0]), partes[1].trim());
        }
        return uids;
    }

    /**
     * TOP n 0 → solo las cabeceras del mensaje (sin cuerpo ni adjuntos).
     * Si el servidor no soporta TOP, se usa RETR.
     */
    public String cabeceras(int numero) throws IOException {
        try {
            comando("TOP " + numero + " 0");
        } catch (IOException e) {
            comando("RETR " + numero);
        }
        return String.join("\r\n", leerMultilinea());
    }

    /** RETR n → mensaje completo (cabeceras + cuerpo). */
    public String mensaje(int numero) throws IOException {
        comando("RETR " + numero);
        return String.join("\r\n", leerMultilinea());
    }

    /** DELE n → marca el mensaje para borrar (se borra al hacer QUIT). */
    public void eliminar(int numero) throws IOException {
        comando("DELE " + numero);
    }

    @Override
    public void close() {
        try {
            comando("QUIT");
        } catch (IOException ignored) {
            // si el servidor ya cerró la conexión no hay nada más que hacer
        } finally {
            cerrarSocket();
        }
    }

    // ------------------------------------------------------------------ internos

    /** Envía un comando y devuelve la línea +OK; lanza IOException si responde -ERR. */
    private String comando(String cmd) throws IOException {
        if (debug) System.out.println("POP3 > " + (cmd.startsWith("PASS ") ? "PASS ******" : cmd));
        salida.write((cmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
        salida.flush();
        return leerRespuesta();
    }

    private String leerRespuesta() throws IOException {
        String linea = entrada.readLine();
        if (linea == null) throw new IOException("El servidor POP3 cerró la conexión");
        if (debug) System.out.println("POP3 < " + linea);
        if (!linea.startsWith("+OK")) throw new IOException("POP3: " + linea);
        return linea;
    }

    /** Lee una respuesta de varias líneas hasta la línea que solo tiene ".". */
    private List<String> leerMultilinea() throws IOException {
        List<String> lineas = new ArrayList<>();
        String linea;
        while ((linea = entrada.readLine()) != null) {
            if (linea.equals(".")) return lineas;
            // "byte-stuffing": una línea que empieza con "." llega como ".."
            lineas.add(linea.startsWith("..") ? linea.substring(1) : linea);
        }
        throw new IOException("Conexión POP3 cortada antes de terminar la respuesta");
    }

    private void cerrarSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}