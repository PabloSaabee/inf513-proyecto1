package org.example.correo;

import org.example.config.Config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Cliente SMTP con sockets, puerto 25.
 * Envía la respuesta en HTML (UTF-8, base64) para que tildes, ñ y tablas lleguen bien.
 *
 * Diálogo:
 *   EHLO → (STARTTLS → EHLO) → (AUTH) → MAIL FROM → RCPT TO → DATA → . → QUIT
 * STARTTLS y AUTH se usan solo si el servidor los ofrece y están activos en config.properties.
 * AUTH evita el error "550 Relaying denied" cuando el programa corre fuera de la facultad.
 *
 * Uso:
 *   ClienteSMTP.enviar("pablosabea@gmail.com", "Respuesta: LISUSU", "<h1>Hola</h1>");
 */
public final class ClienteSMTP {

    private static final int TIMEOUT_MS = 30_000;
    private static final DateTimeFormatter FECHA_RFC =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private ClienteSMTP() { }

    /** Envía un correo HTML desde la cuenta del grupo. */
    public static void enviar(String para, String asunto, String html) throws IOException {
        enviar(para, asunto, html, null);
    }

    /**
     * @param enRespuestaA Message-ID del correo original (puede ser null).
     *                     Si se pasa, Gmail muestra la respuesta en el mismo hilo.
     */
    public static void enviar(String para, String asunto, String html, String enRespuestaA) throws IOException {
        enviar(para, asunto, html, enRespuestaA, List.of());
    }

    /** Igual, con archivos adjuntos (multipart/mixed). */
    public static void enviar(String para, String asunto, String html, String enRespuestaA, List<Adjunto> adjuntos)
            throws IOException {
        String servidor = Config.get("mail.servidor");
        int puerto = Config.getInt("mail.smtp.puerto");
        String de = Config.get("mail.direccion");
        String dominio = dominio(de);
        String nombreEhlo = Config.get("mail.smtp.ehlo", servidor);

        Sesion s = new Sesion(Config.getBoolean("mail.debug", false));
        try {
            s.conectar(servidor, puerto);
            s.esperar(220);                                          // saludo

            List<String> capacidades = s.saludar(nombreEhlo);

            if (Config.getBoolean("mail.smtp.starttls", true) && ofrece(capacidades, "STARTTLS")) {
                s.comando("STARTTLS", 220);
                s.activarTls(servidor, puerto, Config.getBoolean("mail.smtp.confiar.certificado", true));
                capacidades = s.saludar(nombreEhlo);                 // tras TLS se saluda de nuevo
            }

            if (Config.getBoolean("mail.smtp.auth", true)) {
                autenticar(s, capacidades);
            }

            s.comando("MAIL FROM:<" + de + ">", 250);
            try {
                s.comando("RCPT TO:<" + para + ">", 250, 251);
            } catch (IOException e) {
                if (e.getMessage().contains("550") && e.getMessage().toLowerCase().contains("relay")) {
                    throw new IOException(e.getMessage()
                            + "  → El servidor no permite enviar hacia afuera desde esta IP sin autenticarse."
                            + " Revise mail.smtp.auth o ejecute el programa desde la red de la facultad.", e);
                }
                throw e;
            }
            s.comando("DATA", 354);
            s.escribirCrudo(armarMensaje(de, para, asunto, html, enRespuestaA, dominio, adjuntos));
            s.comando(".", 250);                                     // fin del mensaje
            s.comando("QUIT", 221);
        } finally {
            s.cerrar();
        }
    }

    // ------------------------------------------------------------------ autenticación

    private static void autenticar(Sesion s, List<String> capacidades) throws IOException {
        String metodos = capacidades.stream()
                .map(c -> c.toUpperCase(Locale.ROOT))
                .filter(c -> c.startsWith("AUTH ") || c.startsWith("AUTH="))
                .findFirst().orElse("");
        if (metodos.isEmpty()) {
            s.log("(el servidor no ofrece AUTH: se envía sin autenticar)");
            return;
        }
        String usuario = Config.get("mail.usuario");
        String password = Config.get("mail.password");
        Base64.Encoder b64 = Base64.getEncoder();

        if (metodos.contains("PLAIN")) {
            String token = b64.encodeToString(("\0" + usuario + "\0" + password).getBytes(StandardCharsets.UTF_8));
            s.comandoSecreto("AUTH PLAIN " + token, "AUTH PLAIN ******", 235);
        } else if (metodos.contains("LOGIN")) {
            s.comando("AUTH LOGIN", 334);
            s.comandoSecreto(b64.encodeToString(usuario.getBytes(StandardCharsets.UTF_8)), "****** (usuario)", 334);
            s.comandoSecreto(b64.encodeToString(password.getBytes(StandardCharsets.UTF_8)), "****** (contraseña)", 235);
        } else {
            s.log("(el servidor solo ofrece " + metodos + ", no soportado: se envía sin autenticar)");
        }
    }

    private static boolean ofrece(List<String> capacidades, String nombre) {
        return capacidades.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).startsWith(nombre));
    }

    // ------------------------------------------------------------------ armado del mensaje

    private static String armarMensaje(String de, String para, String asunto, String html,
                                       String enRespuestaA, String dominio, List<Adjunto> adjuntos) {
        StringBuilder m = new StringBuilder();
        m.append("From: Sistema Seguimiento a Titulados <").append(de).append(">\r\n");
        m.append("To: <").append(para).append(">\r\n");
        m.append("Subject: ").append(codificarCabecera(asunto)).append("\r\n");
        m.append("Date: ").append(ZonedDateTime.now().format(FECHA_RFC)).append("\r\n");
        m.append("Message-ID: <").append(UUID.randomUUID()).append('@').append(dominio).append(">\r\n");
        if (enRespuestaA != null && !enRespuestaA.isBlank()) {
            m.append("In-Reply-To: ").append(enRespuestaA).append("\r\n");
            m.append("References: ").append(enRespuestaA).append("\r\n");
        }
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Language: es\r\n");                   // evita el aviso "parece estar en inglés"
        if (adjuntos == null || adjuntos.isEmpty()) {
            m.append("Content-Type: text/html; charset=UTF-8\r\n");
            m.append("Content-Transfer-Encoding: base64\r\n");
            m.append("\r\n");                                        // fin de cabeceras
            m.append(base64(html.getBytes(StandardCharsets.UTF_8))).append("\r\n");
            return m.toString();
        }
        // multipart/mixed: primera parte el HTML, luego cada adjunto
        String limite = "----=_Grupo03SA_" + UUID.randomUUID().toString().replace("-", "");
        m.append("Content-Type: multipart/mixed; boundary=\"").append(limite).append("\"\r\n");
        m.append("\r\n");
        m.append("Este es un mensaje MIME de varias partes.\r\n");
        m.append("--").append(limite).append("\r\n");
        m.append("Content-Type: text/html; charset=UTF-8\r\n");
        m.append("Content-Transfer-Encoding: base64\r\n\r\n");
        m.append(base64(html.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        for (Adjunto a : adjuntos) {
            String nombre = a.nombre().replaceAll("[^A-Za-z0-9._-]", "_");
            m.append("--").append(limite).append("\r\n");
            m.append("Content-Type: ").append(a.mime()).append("; name=\"").append(nombre).append("\"\r\n");
            m.append("Content-Disposition: attachment; filename=\"").append(nombre).append("\"\r\n");
            m.append("Content-Transfer-Encoding: base64\r\n\r\n");
            m.append(base64(a.datos())).append("\r\n");
        }
        m.append("--").append(limite).append("--\r\n");
        return m.toString();
    }

    /** base64 en líneas de 76: ninguna línea empieza con "." y no se pasa el límite de 998. */
    private static String base64(byte[] datos) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(datos);
    }

    /**
     * Codifica el asunto como =?UTF-8?B?...?= (RFC 2047) si tiene caracteres no ASCII.
     * Lo parte en trozos para no superar 75 caracteres por palabra codificada,
     * sin cortar una letra con tilde por la mitad.
     */
    static String codificarCabecera(String texto) {
        if (texto.chars().allMatch(c -> c >= 32 && c < 127)) return texto;

        StringBuilder resultado = new StringBuilder();
        StringBuilder trozo = new StringBuilder();
        int bytesTrozo = 0;
        for (int i = 0; i < texto.length(); ) {
            int cp = texto.codePointAt(i);
            String letra = new String(Character.toChars(cp));
            int bytes = letra.getBytes(StandardCharsets.UTF_8).length;
            if (bytesTrozo + bytes > 45) {                           // 45 bytes → 60 en base64
                agregarPalabra(resultado, trozo.toString());
                trozo.setLength(0);
                bytesTrozo = 0;
            }
            trozo.append(letra);
            bytesTrozo += bytes;
            i += Character.charCount(cp);
        }
        agregarPalabra(resultado, trozo.toString());
        return resultado.toString();
    }

    private static void agregarPalabra(StringBuilder sb, String trozo) {
        if (trozo.isEmpty()) return;
        if (!sb.isEmpty()) sb.append("\r\n ");                        // continuación de cabecera
        sb.append("=?UTF-8?B?")
                .append(Base64.getEncoder().encodeToString(trozo.getBytes(StandardCharsets.UTF_8)))
                .append("?=");
    }

    private static String dominio(String direccion) {
        int at = direccion.indexOf('@');
        return at >= 0 ? direccion.substring(at + 1) : "localhost";
    }

    // ------------------------------------------------------------------ diálogo SMTP

    private static final class Sesion {
        private final boolean debug;
        private Socket socket;
        private BufferedReader in;
        private OutputStream out;

        Sesion(boolean debug) { this.debug = debug; }

        void conectar(String servidor, int puerto) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(servidor, puerto), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            abrirFlujos();
        }

        /** Convierte la conexión en cifrada (tras responder 220 a STARTTLS). */
        void activarTls(String servidor, int puerto, boolean confiarCualquierCertificado) throws IOException {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                // El servidor de la facultad puede usar un certificado propio (autofirmado)
                ctx.init(null, confiarCualquierCertificado ? new TrustManager[]{CONFIAR_TODO} : null, null);
                SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(socket, servidor, puerto, true);
                ssl.setSoTimeout(TIMEOUT_MS);
                ssl.startHandshake();
                socket = ssl;
                abrirFlujos();
                log("(conexión cifrada con TLS)");
            } catch (GeneralSecurityException e) {
                throw new IOException("No se pudo iniciar TLS: " + e.getMessage(), e);
            }
        }

        /** EHLO (o HELO si el servidor es antiguo). Devuelve las capacidades anunciadas. */
        List<String> saludar(String dominio) throws IOException {
            try {
                List<String> lineas = comandoLineas("EHLO " + dominio, 250);
                return lineas.size() > 1 ? lineas.subList(1, lineas.size()) : List.of();
            } catch (IOException e) {
                comando("HELO " + dominio, 250);
                return List.of();
            }
        }

        void comando(String cmd, int... codigosOk) throws IOException {
            comandoSecreto(cmd, cmd, codigosOk);
        }

        /** Igual que comando(), pero en la consola muestra 'visible' (para no imprimir claves). */
        void comandoSecreto(String cmd, String visible, int... codigosOk) throws IOException {
            log("SMTP > " + visible);
            enviarLinea(cmd);
            esperar(codigosOk);
        }

        private List<String> comandoLineas(String cmd, int... codigosOk) throws IOException {
            log("SMTP > " + cmd);
            enviarLinea(cmd);
            return esperar(codigosOk);
        }

        void escribirCrudo(String datos) throws IOException {
            log("SMTP > [cabeceras y cuerpo: " + datos.length() + " caracteres]");
            out.write(datos.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /** Lee la respuesta (varias líneas "250-..." hasta "250 ...") y valida el código. */
        List<String> esperar(int... codigosOk) throws IOException {
            List<String> textos = new ArrayList<>();
            String linea;
            do {
                linea = in.readLine();
                if (linea == null) throw new IOException("El servidor SMTP cerró la conexión");
                log("SMTP < " + linea);
                textos.add(linea.length() > 4 ? linea.substring(4).trim() : "");
            } while (linea.length() > 3 && linea.charAt(3) == '-');

            int codigo;
            try {
                codigo = Integer.parseInt(linea.substring(0, 3));
            } catch (RuntimeException e) {
                throw new IOException("Respuesta SMTP inválida: " + linea);
            }
            for (int ok : codigosOk) if (codigo == ok) return textos;
            throw new IOException("SMTP: " + linea);
        }

        void log(String texto) {
            if (debug) System.out.println(texto);
        }

        void cerrar() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
        }

        private void enviarLinea(String cmd) throws IOException {
            out.write((cmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private void abrirFlujos() throws IOException {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            out = socket.getOutputStream();
        }
    }

    /** Acepta cualquier certificado (solo para el servidor de la facultad, que puede ser autofirmado). */
    private static final X509TrustManager CONFIAR_TODO = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}