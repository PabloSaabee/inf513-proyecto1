package org.example.interprete;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Todos los comandos del sistema: nombre, parámetros, permiso, caso de uso y ayuda.
 * Un comando puede estar en el catálogo y aún no estar implementado:
 * se implementa registrándolo en el Despachador.
 *
 * Permisos: los mismos códigos de la tabla 'permisos' (matriz de la Tabla 6 del documento).
 */
public final class CatalogoComandos {

    private static final Map<String, DefComando> MAPA = new LinkedHashMap<>();

    /** Posiciones de parámetros con contraseñas: se guardan y se muestran como ******. */
    private static final Map<String, Set<Integer>> SECRETOS = Map.of(
            "INSUSU", Set.of(5),
            "INSEST", Set.of(5),
            "INSDOC", Set.of(5),
            "MODPAS", Set.of(0, 1));

    static {
        // ---------------------------------------------------------- General
        c("HELP", 0, 1, "General", "HELP  ó  HELP[\"*\"]", "HELP",
                "Lista los comandos que su rol puede usar");

        // ---------------------------------------------------------- CU1 · Usuarios
        c("LISUSU", 1, "CU1", "LISUSU[\"*\" | \"rol\"]", "LISUSU[\"tutor\"]",
                "Lista usuarios, todos o de un rol", "usuarios.gestionar");
        c("VERUSU", 1, "CU1", "VERUSU[\"ci\"]", "VERUSU[\"4715292\"]",
                "Muestra un usuario con sus roles", "usuarios.gestionar");
        c("INSUSU", 7, "CU1", "INSUSU[\"ci\",\"nombres\",\"apellidos\",\"email\",\"telefono\",\"password\",\"rol\"]",
                "INSUSU[\"7012345\",\"Rosa\",\"Paz\",\"rpaz@gmail.com\",\"70011223\",\"Clave2026*\",\"secretaria\"]",
                "Registra director, académico, secretaria o administrador", "usuarios.gestionar");
        c("INSEST", 7, "CU1", "INSEST[\"ci\",\"nombres\",\"apellidos\",\"email\",\"telefono\",\"password\",\"registro\"]",
                "INSEST[\"8123456\",\"Luis\",\"Rivas\",\"lrivas@gmail.com\",\"70011224\",\"Clave2026*\",\"219001122\"]",
                "Registra un estudiante", "usuarios.gestionar");
        c("INSDOC", 8, "CU1", "INSDOC[\"ci\",\"nombres\",\"apellidos\",\"email\",\"telefono\",\"password\",\"titulo\",\"areas\"]",
                "INSDOC[\"5123987\",\"Mario\",\"Soto\",\"msoto@gmail.com\",\"70011225\",\"Clave2026*\",\"Ing.\",\"1,3\"]",
                "Registra un docente con sus áreas (ids separados por coma)", "usuarios.gestionar");
        c("MODUSU", 5, "CU1", "MODUSU[\"ci\",\"nombres\",\"apellidos\",\"email\",\"telefono\"]",
                "MODUSU[\"8123456\",\"Luis Alberto\",\"Rivas\",\"lrivas@gmail.com\",\"70011299\"]",
                "Modifica los datos de un usuario", "usuarios.gestionar");
        c("DELUSU", 1, "CU1", "DELUSU[\"ci\"]", "DELUSU[\"8123456\"]",
                "Desactiva un usuario (no lo borra)", "usuarios.gestionar");
        c("ACTUSU", 1, "CU1", "ACTUSU[\"ci\"]", "ACTUSU[\"8123456\"]",
                "Reactiva un usuario inactivo o bloqueado", "usuarios.gestionar");
        c("ASIROL", 2, "CU1", "ASIROL[\"ci\",\"rol\"]", "ASIROL[\"5123987\",\"tribunal\"]",
                "Asigna un rol a un usuario", "usuarios.gestionar");
        c("QUIROL", 2, "CU1", "QUIROL[\"ci\",\"rol\"]", "QUIROL[\"5123987\",\"tribunal\"]",
                "Quita un rol a un usuario", "usuarios.gestionar");
        c("MODFOT", 2, "CU1", "MODFOT[\"ci\",\"url_foto\"]", "MODFOT[\"8123456\",\"https://drive.google.com/...\"]",
                "Registra la foto de un usuario", "usuarios.gestionar");
        c("VERPER", 1, "CU1", "VERPER[\"*\"]", "VERPER[\"*\"]",
                "Mi perfil: mis datos, roles y permisos", "perfil.propio");
        c("MODPAS", 2, "CU1", "MODPAS[\"actual\",\"nueva\"]", "MODPAS[\"MiClaveActual1*\",\"MiClaveNueva2026*\"]",
                "Cambia mi contraseña", "perfil.propio");

        // ---------------------------------------------------------- CU2 · Habilitación
        c("LISTRE", 1, "CU2", "LISTRE[\"*\"]", "LISTRE[\"*\"]",
                "Lista los tipos de requisito con su id", "perfil.propio");
        c("INSSOL", 1, "CU2", "INSSOL[\"*\"]", "INSSOL[\"*\"]",
                "Crea mi solicitud en la gestión activa", "habilitacion.solicitar");
        c("INSREQ", 2, "CU2", "INSREQ[\"tipo_id\",\"url_archivo\"]", "INSREQ[\"1\",\"https://drive.google.com/...\"]",
                "Carga un requisito a mi solicitud", "habilitacion.solicitar");
        c("ENVSOL", 1, "CU2", "ENVSOL[\"*\"]", "ENVSOL[\"*\"]",
                "Envía mi solicitud a verificación", "habilitacion.solicitar");
        c("VERSOL", 1, "CU2", "VERSOL[\"*\" | \"id\"]", "VERSOL[\"3\"]",
                "Ver mi solicitud, o una por id (verificadores)", "habilitacion.solicitar", "habilitacion.verificar");
        c("LISSOL", 1, "CU2", "LISSOL[\"estado\" | \"*\"]", "LISSOL[\"enviada\"]",
                "Bandeja de solicitudes por estado", "habilitacion.verificar");
        c("VALREQ", 3, "CU2", "VALREQ[\"id_req\",\"valido|observado\",\"observacion\"]", "VALREQ[\"5\",\"observado\",\"CI ilegible\"]",
                "Marca un requisito como válido u observado", "habilitacion.verificar");
        c("REGMAL", 3, "CU2", "REGMAL[\"id_sol\",\"porcentaje\",\"si|no\"]", "REGMAL[\"3\",\"100\",\"si\"]",
                "Registra % de malla aprobada y GRL inscrito", "habilitacion.verificar");
        c("RESSOL", 3, "CU2", "RESSOL[\"id_sol\",\"habilitada|observada|rechazada\",\"observacion\"]", "RESSOL[\"3\",\"habilitada\",\"-\"]",
                "Resuelve la solicitud", "habilitacion.verificar");

        // ---------------------------------------------------------- CU3 · Tutor y perfil
        c("INSPRO", 3, "CU3", "INSPRO[\"area_id\",\"titulo\",\"registro_companero\"]", "INSPRO[\"1\",\"Monitoreo SNMP\",\"-\"]",
                "Registra mi proyecto (\"-\" si no hay compañero)", "proyecto.registrar");
        c("LISPRO", 1, "CU3", "LISPRO[\"*\" | \"etapa\"]", "LISPRO[\"revision\"]",
                "Lista proyectos, filtrables por etapa", "dashboard.ver");
        c("VERPRO", 1, "CU3", "VERPRO[\"codigo\"]", "VERPRO[\"PG-2-2026-001\"]",
                "Detalle del proyecto: estudiantes, tutor, etapa", "perfil.propio");
        c("LISDOC", 1, "CU3", "LISDOC[\"area_id\" | \"*\"]", "LISDOC[\"1\"]",
                "Docentes de un área", "proyecto.registrar", "tutor.designar");
        c("SOLTUT", 2, "CU3", "SOLTUT[\"codigo\",\"ci_docente\" | \"DIRECCION\"]", "SOLTUT[\"PG-2-2026-001\",\"1000005\"]",
                "Propone un tutor o pide que Dirección lo designe", "proyecto.registrar");
        c("DESTUT", 2, "CU3", "DESTUT[\"codigo\",\"ci_docente\"]", "DESTUT[\"PG-2-2026-001\",\"1000005\"]",
                "Designa al tutor", "tutor.designar");
        c("LISTUT", 1, "CU3", "LISTUT[\"*\"]", "LISTUT[\"*\"]",
                "Mis tutorías o las pendientes de designar", "tutor.responder", "tutor.designar");
        c("RESTUT", 3, "CU3", "RESTUT[\"codigo\",\"aceptada|rechazada\",\"motivo\"]", "RESTUT[\"PG-2-2026-001\",\"aceptada\",\"-\"]",
                "Acepta o rechaza la tutoría", "tutor.responder");
        c("INSPRF", 4, 6, "CU3",
                "INSPRF[\"codigo\",\"titulo\",\"url_pdf\",\"objetivo_general\"(,\"objetivos_especificos\",\"introduccion\")]",
                "INSPRF[\"PG-2-2026-002\",\"Monitoreo SNMP\",\"https://drive.google.com/...\",\"Implementar un sistema de monitoreo\"]",
                "Registra una nueva versión del perfil (los dos últimos son opcionales)", "proyecto.registrar");
        c("LISPRF", 1, "CU3", "LISPRF[\"codigo\"]", "LISPRF[\"PG-2-2026-001\"]",
                "Historial de versiones del perfil", "proyecto.registrar", "tutor.responder");
        c("ENVPRF", 1, "CU3", "ENVPRF[\"codigo\"]", "ENVPRF[\"PG-2-2026-001\"]",
                "Envía el perfil a viabilidad", "proyecto.registrar");

        // ---------------------------------------------------------- CU4 · Viabilidad y tribunales
        c("LISVIA", 1, "CU4", "LISVIA[\"*\"]", "LISVIA[\"*\"]",
                "Perfiles pendientes de evaluar", "viabilidad.evaluar");
        c("EVAVIA", 4, "CU4", "EVAVIA[\"codigo\",\"aprobado|aprobado_obs|observado|no_viable\",\"observaciones\",\"url_informe\"]",
                "EVAVIA[\"PG-2-2026-001\",\"aprobado\",\"-\",\"-\"]",
                "Evalúa la viabilidad del perfil", "viabilidad.evaluar");
        c("LISCAN", 1, "CU4", "LISCAN[\"codigo\"]", "LISCAN[\"PG-2-2026-001\"]",
                "Candidatos a tribunal del área (sin el tutor)", "tribunal.designar");
        c("DESTRI", 4, "CU4", "DESTRI[\"codigo\",\"ci1\",\"ci2\",\"ci3\"]", "DESTRI[\"PG-2-2026-001\",\"1000006\",\"1000007\",\"1000008\"]",
                "Designa los 3 tribunales", "tribunal.designar");
        c("REETRI", 4, "CU4", "REETRI[\"codigo\",\"posicion\",\"ci_nuevo\",\"motivo\"]", "REETRI[\"PG-2-2026-001\",\"2\",\"1000009\",\"Viaje\"]",
                "Reemplazo excepcional de un tribunal", "tribunal.designar");
        c("LISTRI", 1, "CU4", "LISTRI[\"*\" | \"codigo\"]", "LISTRI[\"*\"]",
                "Mis designaciones o los tribunales de un proyecto", "tribunal.responder", "tribunal.designar", "perfil.propio");
        c("RESTRI", 3, "CU4", "RESTRI[\"codigo\",\"aceptada|rechazada\",\"motivo\"]", "RESTRI[\"PG-2-2026-001\",\"aceptada\",\"-\"]",
                "Acepta o rechaza la designación", "tribunal.responder");

        // ---------------------------------------------------------- CU5 · Revisión y plazos
        c("ENVREV", 3, "CU5", "ENVREV[\"codigo\",\"url_pdf\",\"url_carta\"]", "ENVREV[\"PG-2-2026-001\",\"https://drive.google.com/...\",\"-\"]",
                "Envía una versión y abre una ronda de revisión", "revision.enviar");
        c("LISRON", 1, "CU5", "LISRON[\"codigo\"]", "LISRON[\"PG-2-2026-001\"]",
                "Rondas del proyecto con estado y vencimiento", "revision.enviar", "revision.revisar", "revision.plazos");
        c("LISVER", 1, "CU5", "LISVER[\"codigo\"]", "LISVER[\"PG-2-2026-001\"]",
                "Historial de versiones del documento", "revision.enviar", "revision.revisar");
        c("CONREV", 2, "CU5", "CONREV[\"codigo\",\"url_carta\"]", "CONREV[\"PG-2-2026-001\",\"-\"]",
                "Confirma la recepción del documento", "revision.revisar");
        c("OBSREV", 3, "CU5", "OBSREV[\"codigo\",\"observaciones\",\"url_doc\"]", "OBSREV[\"PG-2-2026-001\",\"Corregir cap. 2\",\"-\"]",
                "Registra observaciones", "revision.revisar");
        c("VBOREV", 1, "CU5", "VBOREV[\"codigo\"]", "VBOREV[\"PG-2-2026-001\"]",
                "Da visto bueno", "revision.revisar");
        c("MODPLA", 3, "CU5", "MODPLA[\"gestion\",\"dias\",\"fecha_limite_perfil\"]", "MODPLA[\"2-2026\",\"15\",\"2026-10-30\"]",
                "Define plazo de revisión y fecha límite de perfil", "revision.plazos");
        c("AMPPLA", 3, "CU5", "AMPPLA[\"codigo\",\"dias\",\"motivo\"]", "AMPPLA[\"PG-2-2026-001\",\"5\",\"Feriado largo\"]",
                "Amplía días hábiles a la ronda actual", "revision.plazos");
        c("LISPLA", 1, "CU5", "LISPLA[\"*\"]", "LISPLA[\"*\"]",
                "Control de plazos: rondas abiertas y días restantes", "revision.plazos");
        c("NOTREV", 1, "CU5", "NOTREV[\"codigo\"]", "NOTREV[\"PG-2-2026-001\"]",
                "Recuerda a los tribunales pendientes", "revision.plazos");
        c("INSFER", 2, "CU5", "INSFER[\"fecha\",\"descripcion\"]", "INSFER[\"2026-12-25\",\"Navidad\"]",
                "Registra un feriado", "revision.plazos");
        c("LISFER", 1, "CU5", "LISFER[\"anio\" | \"*\"]", "LISFER[\"2026\"]",
                "Lista feriados", "perfil.propio");

        // ---------------------------------------------------------- CU6 · Evaluaciones
        c("INSAMB", 2, "CU6", "INSAMB[\"nombre\",\"ubicacion\"]", "INSAMB[\"Aula 236-12\",\"Módulo 236\"]",
                "Registra un ambiente", "evaluacion.programar");
        c("LISAMB", 1, "CU6", "LISAMB[\"*\"]", "LISAMB[\"*\"]",
                "Lista ambientes", "evaluacion.programar");
        c("PROEVA", 5, "CU6", "PROEVA[\"codigo\",\"predefensa|defensa\",\"fecha\",\"hora\",\"ambiente_id\"]",
                "PROEVA[\"PG-2-2026-001\",\"predefensa\",\"2026-11-20\",\"10:00\",\"1\"]",
                "Programa una evaluación (valida choques ±2 h)", "evaluacion.programar");
        c("REPEVA", 5, "CU6", "REPEVA[\"id_eval\",\"fecha\",\"hora\",\"ambiente_id\",\"motivo\"]",
                "REPEVA[\"4\",\"2026-11-21\",\"15:00\",\"2\",\"Choque de horario\"]",
                "Reprograma una evaluación", "evaluacion.programar");
        c("LISEVA", 1, "CU6", "LISEVA[\"estado\" | \"*\"]", "LISEVA[\"programada\"]",
                "Lista evaluaciones por estado", "evaluacion.programar", "evaluacion.calificar");
        c("VEREVA", 1, "CU6", "VEREVA[\"id_eval\"]", "VEREVA[\"4\"]",
                "Detalle de una evaluación con notas", "evaluacion.programar", "evaluacion.calificar", "evaluacion.consultar");
        c("CALEVA", 3, "CU6", "CALEVA[\"id_eval\",\"nota\",\"observacion\"]", "CALEVA[\"4\",\"85\",\"-\"]",
                "Registra mi nota (0 a 100)", "evaluacion.calificar");
        c("ACTEVA", 3, "CU6", "ACTEVA[\"id_eval\",\"url_acta\",\"observaciones\"]", "ACTEVA[\"4\",\"https://drive.google.com/...\",\"-\"]",
                "Registra el acta y calcula el resultado (aprueba con 51)", "evaluacion.programar");

        // ---------------------------------------------------------- CU7 · Monitoreo
        c("SEGPRO", 1, "CU7", "SEGPRO[\"*\" | \"codigo\"]", "SEGPRO[\"*\"]",
                "Mi seguimiento: etapas, próximos pasos y línea de tiempo", "seguimiento.ver_propio");
        c("DASIND", 1, "CU7", "DASIND[\"gestion\" | \"*\"]", "DASIND[\"2-2026\"]",
                "Indicadores, proyectos por etapa y vencimientos", "dashboard.ver");
        c("LISCAL", 1, "CU7", "LISCAL[\"anio-mes\" | \"*\"]", "LISCAL[\"2026-11\"]",
                "Calendario del mes", "dashboard.ver");
        c("INSEVE", 4, "CU7", "INSEVE[\"titulo\",\"descripcion\",\"aaaa-mm-dd hh:mm\",\"aaaa-mm-dd hh:mm\" | \"-\"]",
                "INSEVE[\"Reunión\",\"-\",\"2026-11-10 10:00\",\"2026-11-10 12:00\"]",
                "Registra un evento en el calendario", "calendario.gestionar");
        c("DELEVE", 1, "CU7", "DELEVE[\"id\"]", "DELEVE[\"3\"]",
                "Elimina un evento del calendario", "calendario.gestionar");
        c("LISNOT", 1, "CU7", "LISNOT[\"*\" | \"no_leidas\"]", "LISNOT[\"no_leidas\"]",
                "Mis notificaciones (las muestra y las marca como leídas)", "notificaciones.ver");

        // ---------------------------------------------------------- CU8 · Reportes
        String sint = "[\"gestion\" | \"*\",\"area_id\" | \"*\"]";
        String ej = "[\"2-2026\",\"*\"]";
        c("REPRES", 2, "CU8", "REPRES" + sint, "REPRES" + ej, "Resumen general", "reportes.ver");
        c("REPARE", 2, "CU8", "REPARE" + sint, "REPARE" + ej, "Proyectos por área y etapa", "reportes.ver");
        c("REPVIA", 2, "CU8", "REPVIA" + sint, "REPVIA" + ej, "Resultados de viabilidad", "reportes.ver");
        c("REPREV", 2, "CU8", "REPREV" + sint, "REPREV" + ej, "Revisiones y plazos", "reportes.ver");
        c("REPDEF", 2, "CU8", "REPDEF" + sint, "REPDEF" + ej, "Predefensas y defensas", "reportes.ver");
        c("REPDUR", 2, "CU8", "REPDUR" + sint, "REPDUR" + ej, "Duración promedio por etapa", "reportes.ver");
        c("REPDOC", 2, "CU8", "REPDOC" + sint, "REPDOC" + ej, "Carga docente", "reportes.ver");
        c("REPTIT", 2, "CU8", "REPTIT" + sint, "REPTIT" + ej, "Listado de titulados", "reportes.ver");
    }

    private CatalogoComandos() { }

    /** Comando con cantidad fija de parámetros. */
    private static void c(String nombre, int n, String cu, String sintaxis, String ejemplo,
                          String descripcion, String... permisos) {
        c(nombre, n, n, cu, sintaxis, ejemplo, descripcion, permisos);
    }

    private static void c(String nombre, int min, int max, String cu, String sintaxis, String ejemplo,
                          String descripcion, String... permisos) {
        if (MAPA.containsKey(nombre)) throw new IllegalStateException("Comando repetido en el catálogo: " + nombre);
        MAPA.put(nombre, new DefComando(nombre, min, max, cu, sintaxis, ejemplo, descripcion, List.of(permisos)));
    }

    public static DefComando buscar(String nombre) {
        return MAPA.get(nombre);
    }

    public static Set<Integer> parametrosSecretos(String nombre) {
        return SECRETOS.getOrDefault(nombre, Set.of());
    }

    public static Collection<DefComando> todos() {
        return Collections.unmodifiableCollection(MAPA.values());
    }
}