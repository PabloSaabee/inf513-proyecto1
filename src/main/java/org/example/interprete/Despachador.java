package org.example.interprete;

import org.example.correo.Correo;
import org.example.datos.DBitacora;
import org.example.negocio.NEvaluacion;
import org.example.negocio.NHabilitacion;
import org.example.negocio.NMonitoreo;
import org.example.negocio.NProyecto;
import org.example.negocio.NReporte;
import org.example.negocio.NRevision;
import org.example.negocio.NSeguridad;
import org.example.negocio.NTribunal;
import org.example.negocio.NUsuario;
import org.example.negocio.UsuarioSesion;
import org.example.presentacion.AyudaHtml;
import org.example.presentacion.RespuestaHtml;
import org.example.util.Registro;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recibe cada correo, valida el comando y lo envía a la clase de negocio que corresponde.
 *
 * Orden de validación (cada paso responde un error claro si falla):
 *   1. ¿El remitente está registrado y activo?
 *   2. ¿El asunto tiene formato de comando?
 *   3. ¿El comando existe en el catálogo?
 *   4. ¿Trae la cantidad correcta de parámetros?
 *   5. ¿El rol del remitente tiene permiso?
 *   6. ¿Está implementado? → se ejecuta.
 *
 * Para implementar un comando nuevo: escribir el método en su clase N... y registrarlo abajo.
 */
public class Despachador implements ProcesadorComando {

    /** Un comando implementado: recibe el contexto y devuelve la respuesta. */
    @FunctionalInterface
    public interface Accion {
        Respuesta ejecutar(Contexto ctx) throws SQLException;
    }

    private final Map<String, Accion> acciones = new HashMap<>();
    private final NSeguridad nSeguridad = new NSeguridad();
    private final DBitacora dBitacora = new DBitacora();

    public Despachador() {
        // ---- General
        registrar("HELP", this::ayuda);

        // ---- CU1 · Gestión de Usuarios
        NUsuario nUsuario = new NUsuario();
        registrar("LISUSU", nUsuario::listar);
        registrar("VERUSU", nUsuario::ver);
        registrar("INSUSU", nUsuario::insertar);
        registrar("INSEST", nUsuario::insertarEstudiante);
        registrar("INSDOC", nUsuario::insertarDocente);
        registrar("MODUSU", nUsuario::modificar);
        registrar("DELUSU", nUsuario::desactivar);
        registrar("ACTUSU", nUsuario::activar);
        registrar("ASIROL", nUsuario::asignarRol);
        registrar("QUIROL", nUsuario::quitarRol);
        registrar("MODFOT", nUsuario::cambiarFoto);
        registrar("VERPER", nUsuario::verPerfil);
        registrar("MODPAS", nUsuario::cambiarContrasena);

        // ---- CU2 · Habilitación Académica
        NHabilitacion nHabilitacion = new NHabilitacion();
        registrar("LISTRE", nHabilitacion::listarTipos);
        registrar("INSSOL", nHabilitacion::crear);
        registrar("INSREQ", nHabilitacion::cargarRequisito);
        registrar("ENVSOL", nHabilitacion::enviar);
        registrar("VERSOL", nHabilitacion::ver);
        registrar("LISSOL", nHabilitacion::listar);
        registrar("VALREQ", nHabilitacion::validarRequisito);
        registrar("REGMAL", nHabilitacion::registrarMalla);
        registrar("RESSOL", nHabilitacion::resolver);

        // ---- CU3 · Tutor y Perfil de Proyecto
        NProyecto nProyecto = new NProyecto();
        registrar("INSPRO", nProyecto::registrar);
        registrar("LISPRO", nProyecto::listar);
        registrar("VERPRO", nProyecto::ver);
        registrar("LISDOC", nProyecto::listarDocentes);
        registrar("SOLTUT", nProyecto::solicitarTutor);
        registrar("DESTUT", nProyecto::designarTutor);
        registrar("LISTUT", nProyecto::listarTutorias);
        registrar("RESTUT", nProyecto::responderTutoria);
        registrar("INSPRF", nProyecto::registrarPerfil);
        registrar("LISPRF", nProyecto::listarPerfiles);
        registrar("ENVPRF", nProyecto::enviarPerfil);

        // ---- CU4 · Viabilidad y Tribunales
        NTribunal nTribunal = new NTribunal();
        registrar("LISVIA", nTribunal::listarViabilidad);
        registrar("EVAVIA", nTribunal::evaluarViabilidad);
        registrar("LISCAN", nTribunal::listarCandidatos);
        registrar("DESTRI", nTribunal::designar);
        registrar("REETRI", nTribunal::reemplazar);
        registrar("LISTRI", nTribunal::listarTribunales);
        registrar("RESTRI", nTribunal::responder);

        // ---- CU5 · Revisión, Versionado y Plazos
        NRevision nRevision = new NRevision();
        registrar("ENVREV", nRevision::enviar);
        registrar("LISRON", nRevision::listarRondas);
        registrar("LISVER", nRevision::listarVersiones);
        registrar("CONREV", nRevision::confirmar);
        registrar("OBSREV", nRevision::observar);
        registrar("VBOREV", nRevision::vistoBueno);
        registrar("MODPLA", nRevision::definirPlazos);
        registrar("AMPPLA", nRevision::ampliar);
        registrar("LISPLA", nRevision::controlPlazos);
        registrar("NOTREV", nRevision::recordar);
        registrar("INSFER", nRevision::insertarFeriado);
        registrar("LISFER", nRevision::listarFeriados);

        // ---- CU6 · Predefensa y Defensa
        NEvaluacion nEvaluacion = new NEvaluacion();
        registrar("INSAMB", nEvaluacion::insertarAmbiente);
        registrar("LISAMB", nEvaluacion::listarAmbientes);
        registrar("PROEVA", nEvaluacion::programar);
        registrar("REPEVA", nEvaluacion::reprogramar);
        registrar("LISEVA", nEvaluacion::listar);
        registrar("VEREVA", nEvaluacion::ver);
        registrar("CALEVA", nEvaluacion::calificar);
        registrar("ACTEVA", nEvaluacion::registrarActa);

        // ---- CU7 · Monitoreo, Dashboard y Calendario
        NMonitoreo nMonitoreo = new NMonitoreo();
        registrar("SEGPRO", nMonitoreo::seguimiento);
        registrar("DASIND", nMonitoreo::dashboard);
        registrar("LISCAL", nMonitoreo::calendario);
        registrar("INSEVE", nMonitoreo::insertarEvento);
        registrar("DELEVE", nMonitoreo::eliminarEvento);
        registrar("LISNOT", nMonitoreo::notificaciones);

        // ---- CU8 · Reportes y Estadísticas
        NReporte nReporte = new NReporte();
        registrar("REPRES", nReporte::resumen);
        registrar("REPARE", nReporte::porArea);
        registrar("REPVIA", nReporte::viabilidad);
        registrar("REPREV", nReporte::revisiones);
        registrar("REPDEF", nReporte::evaluaciones);
        registrar("REPDUR", nReporte::duracion);
        registrar("REPDOC", nReporte::cargaDocente);
        registrar("REPTIT", nReporte::titulados);
    }

    private void registrar(String nombre, Accion accion) {
        if (CatalogoComandos.buscar(nombre) == null) {
            throw new IllegalStateException(nombre + " no está en CatalogoComandos");
        }
        acciones.put(nombre, accion);
    }

    @Override
    public Respuesta procesar(Correo correo) {
        ComandoParseado cmd = null;
        Correo visible = correo;               // el correo con las contraseñas ocultas
        try {
            UsuarioSesion usuario = nSeguridad.identificar(correo.remitente());     // 1
            cmd = AnalizadorAsunto.analizar(correo.asunto());                       // 2
            visible = ocultarSecretos(correo, cmd);

            DefComando def = CatalogoComandos.buscar(cmd.nombre());                 // 3
            if (def == null) {
                throw new ErrorComando("El comando " + cmd.nombre() + " no existe. "
                        + "Envíe HELP para ver los comandos que puede usar.");
            }
            if (!def.aceptaCantidad(cmd.parametros().size())) {                     // 4
                throw new ErrorComando(def.nombre() + " espera " + def.cantidadEsperada()
                        + " y recibió " + cmd.parametros().size() + ".", sintaxis(def));
            }
            nSeguridad.exigirPermiso(usuario, def);                                 // 5

            Accion accion = acciones.get(def.nombre());                             // 6
            if (accion == null) {
                throw new ErrorComando("El comando " + def.nombre() + " (" + def.cu()
                        + ") todavía está en desarrollo.");
            }
            Respuesta r = accion.ejecutar(new Contexto(visible, usuario, cmd, def));
            dBitacora.registrar(usuario.id(), def.nombre(), null, null, visible.asunto());
            return r;

        } catch (ErrorComando e) {
            return error(visible, cmd, e.getMessage(), e.getAyudaHtml());
        } catch (SQLException e) {
            // Errores de la base: triggers, CHECK, claves duplicadas...
            Registro.error("Despachador · SQL en " + (cmd == null ? "?" : cmd.nombre()) + " de " + correo.remitente(), e);
            return error(visible, cmd, "No se pudo completar la operación: " + mensajeBD(e), null);
        } catch (RuntimeException e) {
            System.err.println("[Despachador] Error inesperado: " + e);
            Registro.error("Despachador · " + (cmd == null ? "?" : cmd.nombre()) + " de " + correo.remitente(), e);
            return error(visible, cmd, "Error interno del sistema al procesar el comando.", null);
        }
    }

    // ------------------------------------------------------------------ HELP

    private Respuesta ayuda(Contexto ctx) {
        UsuarioSesion u = ctx.usuario();
        List<DefComando> disponibles = CatalogoComandos.todos().stream()
                .filter(d -> acciones.containsKey(d.nombre()))
                .filter(d -> nSeguridad.puedeUsar(u, d))
                .toList();
        String html = AyudaHtml.contenido(u.nombreCompleto(), List.copyOf(u.roles()), disponibles);
        return ctx.exito("Comandos disponibles", html, disponibles.size() + " comandos disponibles");
    }

    // ------------------------------------------------------------------ utilidades

    /** Copia del correo con el asunto sin contraseñas (si el comando tiene parámetros secretos). */
    private static Correo ocultarSecretos(Correo c, ComandoParseado cmd) {
        Set<Integer> secretos = CatalogoComandos.parametrosSecretos(cmd.nombre());
        if (secretos.isEmpty()) return c;
        return new Correo(c.numero(), c.uid(), c.messageId(), c.remitente(), c.nombreRemitente(),
                cmd.asuntoSeguro(secretos), c.fecha(), c.automatico());
    }

    private static Respuesta error(Correo correo, ComandoParseado cmd, String motivo, String ayuda) {
        Set<Integer> secretos = cmd == null ? Set.of() : CatalogoComandos.parametrosSecretos(cmd.nombre());
        return new Respuesta(false,
                cmd == null ? null : cmd.nombre(),
                cmd == null ? null : cmd.parametrosTexto(secretos),
                motivo,
                RespuestaHtml.error(motivo, ayuda, correo),
                correo.asunto());
    }

    private static String sintaxis(DefComando d) {
        return "<p style=\"margin:8px 0 4px\">Sintaxis: <code>" + RespuestaHtml.esc(d.sintaxis()) + "</code></p>"
                + "<p style=\"margin:0\">Ejemplo: <code>" + RespuestaHtml.esc(d.ejemplo()) + "</code></p>";
    }

    /** Mensaje de PostgreSQL sin el prefijo técnico "ERROR:" ni el "Detail/Where". */
    private static String mensajeBD(SQLException e) {
        String m = e.getMessage() == null ? "error de base de datos" : e.getMessage();
        m = m.replaceFirst("^ERROR:\\s*", "");
        int corte = m.indexOf("\n");
        return corte > 0 ? m.substring(0, corte).trim() : m.trim();
    }
}