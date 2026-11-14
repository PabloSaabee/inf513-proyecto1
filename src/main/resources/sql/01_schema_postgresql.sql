-- =====================================================================
--  SISTEMA DE SEGUIMIENTO A TITULADOS  ·  Versión vía E-Mail
--  Script 01: Esquema de la base de datos (PostgreSQL)
--  Servidor : db.tecnoweb.org.bo   Base: db_grupoXXsa
--  Origen   : Documento Final (Grupo 03) – Diseño físico, Tablas 32 a 64
--             (MySQL 8 → convertido a PostgreSQL)
--
--  Conversión aplicada:
--    bigint unsigned auto_increment  → BIGSERIAL
--    smallint/tinyint unsigned (PK)  → SMALLSERIAL
--    tinyint(1)                      → BOOLEAN
--    enum(...)                       → VARCHAR + CHECK (IN ...)
--    datetime                        → TIMESTAMP
--    longtext (bitácora)             → JSONB  (valida JSON por sí mismo)
--
--  Contenido: 33 tablas del documento + correos_procesados (propia de la
--             versión e-mail), 2 triggers, 1 función de días hábiles, 1 vista.
-- =====================================================================

-- ---------------------------------------------------------------------
--  0. LIMPIEZA (permite volver a ejecutar el script)
-- ---------------------------------------------------------------------
DROP VIEW     IF EXISTS v_dashboard_proyectos CASCADE;
DROP TABLE    IF EXISTS correos_procesados, historial_etapas, eventos_calendario, calificaciones, evaluaciones,
    revisiones_tribunal, rondas_revision, documento_versiones, documentos,
    designaciones_tribunal, evaluaciones_viabilidad, perfiles,
    asignaciones_tutor, proyecto_estudiante, proyectos,
    requisitos_presentados, solicitudes_habilitacion, tipos_requisito,
    notificaciones, bitacora, docente_area, docentes, estudiantes,
    archivos, rol_permiso, usuario_rol, usuarios, permisos, roles,
    ambientes, feriados, gestiones, areas_conocimiento, carreras CASCADE;
DROP FUNCTION IF EXISTS fn_sumar_dias_habiles(DATE, INTEGER) CASCADE;
DROP FUNCTION IF EXISTS fn_trg_max_dos_estudiantes() CASCADE;
DROP FUNCTION IF EXISTS fn_trg_max_tres_tribunales() CASCADE;

-- =====================================================================
--  1. CATÁLOGOS
-- =====================================================================

-- Tabla 38. carreras
CREATE TABLE carreras (
                          id      SMALLSERIAL  PRIMARY KEY,
                          codigo  VARCHAR(10)  NOT NULL UNIQUE,
                          nombre  VARCHAR(120) NOT NULL
);

-- Tabla 34. areas_conocimiento
CREATE TABLE areas_conocimiento (
                                    id      SMALLSERIAL PRIMARY KEY,
                                    nombre  VARCHAR(80) NOT NULL UNIQUE
);

-- Tabla 49. gestiones
CREATE TABLE gestiones (
                           id                   SMALLSERIAL PRIMARY KEY,
                           codigo               VARCHAR(10) NOT NULL UNIQUE,          -- ej. 2-2026
                           fecha_inicio         DATE        NOT NULL,
                           fecha_fin            DATE        NOT NULL,
                           fecha_limite_perfil  DATE,
                           plazo_revision_dias  SMALLINT    NOT NULL DEFAULT 15,
                           activa               BOOLEAN     NOT NULL DEFAULT FALSE,
                           CONSTRAINT chk_gestion_fechas CHECK (fecha_fin > fecha_inicio),
                           CONSTRAINT chk_gestion_plazo  CHECK (plazo_revision_dias BETWEEN 1 AND 255)
);

-- Tabla 48. feriados
CREATE TABLE feriados (
                          fecha        DATE         PRIMARY KEY,
                          descripcion  VARCHAR(100) NOT NULL
);

-- Tabla 32. ambientes
CREATE TABLE ambientes (
                           id         SMALLSERIAL  PRIMARY KEY,
                           nombre     VARCHAR(80)  NOT NULL UNIQUE,
                           ubicacion  VARCHAR(150)
);

-- =====================================================================
--  2. SEGURIDAD (CU1)
-- =====================================================================

-- Tabla 58. roles
CREATE TABLE roles (
                       id           SMALLSERIAL PRIMARY KEY,
                       nombre       VARCHAR(30)  NOT NULL UNIQUE,
                       descripcion  VARCHAR(150)
);

-- Tabla 53. permisos
CREATE TABLE permisos (
                          id           SMALLSERIAL  PRIMARY KEY,
                          codigo       VARCHAR(60)  NOT NULL UNIQUE,
                          descripcion  VARCHAR(150),
                          caso_uso     VARCHAR(5)   NOT NULL                          -- CU1..CU8
);

-- Tabla 63. usuarios
CREATE TABLE usuarios (
                          id                  BIGSERIAL    PRIMARY KEY,
                          ci                  VARCHAR(15)  NOT NULL UNIQUE,
                          nombres             VARCHAR(80)  NOT NULL,
                          apellidos           VARCHAR(80)  NOT NULL,
                          email               VARCHAR(120) NOT NULL UNIQUE,           -- remitente de los correos
                          telefono            VARCHAR(20),
                          foto_ruta           VARCHAR(255),
                          password            VARCHAR(255) NOT NULL,                  -- hash bcrypt
                          estado              VARCHAR(10)  NOT NULL DEFAULT 'activo'
                              CHECK (estado IN ('activo','inactivo','bloqueado')),
                          intentos_fallidos   SMALLINT     NOT NULL DEFAULT 0,
                          password_expira_en  DATE,
                          ultimo_acceso       TIMESTAMP,
                          remember_token      VARCHAR(100),
                          created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                          updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- Tabla 64. usuario_rol  (N:N)
CREATE TABLE usuario_rol (
                             usuario_id   BIGINT    NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
                             rol_id       SMALLINT  NOT NULL REFERENCES roles(id)    ON DELETE CASCADE,
                             asignado_en  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             PRIMARY KEY (usuario_id, rol_id)
);

-- Tabla 59. rol_permiso  (N:N, matriz de acceso)
CREATE TABLE rol_permiso (
                             rol_id      SMALLINT NOT NULL REFERENCES roles(id)    ON DELETE CASCADE,
                             permiso_id  SMALLINT NOT NULL REFERENCES permisos(id) ON DELETE CASCADE,
                             PRIMARY KEY (rol_id, permiso_id)
);

-- Tabla 33. archivos
CREATE TABLE archivos (
                          id               BIGSERIAL    PRIMARY KEY,
                          nombre_original  VARCHAR(255) NOT NULL,
                          ruta             VARCHAR(255) NOT NULL UNIQUE,
                          mime             VARCHAR(80)  NOT NULL,
                          tamano_bytes     INTEGER      NOT NULL CHECK (tamano_bytes >= 0),
                          sha256           CHAR(64)     NOT NULL,
                          subido_por       BIGINT       NOT NULL REFERENCES usuarios(id),
                          created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Tabla 44. estudiantes  (especialización 1:1 de usuarios)
CREATE TABLE estudiantes (
                             usuario_id              BIGINT       PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
                             registro_universitario  VARCHAR(12)  NOT NULL UNIQUE,
                             carrera_id              SMALLINT     NOT NULL REFERENCES carreras(id),
                             porcentaje_malla        NUMERIC(5,2) NOT NULL DEFAULT 0
                                 CHECK (porcentaje_malla BETWEEN 0 AND 100),
                             grl_inscrito            BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Tabla 40. docentes  (especialización 1:1 de usuarios)
CREATE TABLE docentes (
                          usuario_id    BIGINT      PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
                          titulo        VARCHAR(80),
                          item_docente  VARCHAR(20)
);

-- Tabla 41. docente_area  (N:N)
CREATE TABLE docente_area (
                              docente_id  BIGINT   NOT NULL REFERENCES docentes(usuario_id)  ON DELETE CASCADE,
                              area_id     SMALLINT NOT NULL REFERENCES areas_conocimiento(id) ON DELETE CASCADE,
                              PRIMARY KEY (docente_id, area_id)
);

-- Tabla 36. bitacora
CREATE TABLE bitacora (
                          id             BIGSERIAL    PRIMARY KEY,
                          usuario_id     BIGINT       REFERENCES usuarios(id) ON DELETE SET NULL,
                          accion         VARCHAR(40)  NOT NULL,
                          tabla          VARCHAR(60),
                          registro_id    BIGINT,
                          ip             VARCHAR(45)  NOT NULL,      -- en la versión e-mail: dirección del remitente
                          user_agent     VARCHAR(255),               -- en la versión e-mail: "EMAIL" / asunto
                          datos_antes    JSONB,
                          datos_despues  JSONB,
                          created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Tabla 51. notificaciones
CREATE TABLE notificaciones (
                                id             BIGSERIAL    PRIMARY KEY,
                                usuario_id     BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
                                titulo         VARCHAR(150) NOT NULL,
                                mensaje        VARCHAR(500) NOT NULL,
                                url            VARCHAR(255),
                                leida_en       TIMESTAMP,
                                enviada_email  BOOLEAN      NOT NULL DEFAULT FALSE,
                                created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================================
--  3. HABILITACIÓN (CU2)
-- =====================================================================

-- Tabla 62. tipos_requisito
CREATE TABLE tipos_requisito (
                                 id           SMALLSERIAL PRIMARY KEY,
                                 nombre       VARCHAR(60) NOT NULL UNIQUE,
                                 obligatorio  BOOLEAN     NOT NULL DEFAULT TRUE
);

-- Tabla 61. solicitudes_habilitacion
CREATE TABLE solicitudes_habilitacion (
                                          id                  BIGSERIAL   PRIMARY KEY,
                                          estudiante_id       BIGINT      NOT NULL REFERENCES estudiantes(usuario_id),
                                          gestion_id          SMALLINT    NOT NULL REFERENCES gestiones(id),
                                          estado              VARCHAR(12) NOT NULL DEFAULT 'borrador'
                                              CHECK (estado IN ('borrador','enviada','observada','habilitada','rechazada')),
                                          observacion         TEXT,
                                          verificado_por      BIGINT      REFERENCES usuarios(id),
                                          fecha_envio         TIMESTAMP,
                                          fecha_verificacion  TIMESTAMP,
                                          CONSTRAINT uq_solicitud_gestion UNIQUE (estudiante_id, gestion_id)   -- una por gestión (CU2-E3)
);

-- Tabla 56. requisitos_presentados  (débil de solicitud)
CREATE TABLE requisitos_presentados (
                                        id                 BIGSERIAL    PRIMARY KEY,
                                        solicitud_id       BIGINT       NOT NULL REFERENCES solicitudes_habilitacion(id) ON DELETE CASCADE,
                                        tipo_requisito_id  SMALLINT     NOT NULL REFERENCES tipos_requisito(id),
                                        archivo_id         BIGINT       NOT NULL REFERENCES archivos(id),
                                        estado             VARCHAR(10)  NOT NULL DEFAULT 'pendiente'
                                            CHECK (estado IN ('pendiente','valido','observado')),
                                        observacion        VARCHAR(255)
);

-- =====================================================================
--  4. PROYECTO (CU3, CU4)
-- =====================================================================

-- Tabla 54. proyectos
CREATE TABLE proyectos (
                           id            BIGSERIAL    PRIMARY KEY,
                           codigo        VARCHAR(20)  NOT NULL UNIQUE,                 -- PG-2-2026-001
                           gestion_id    SMALLINT     NOT NULL REFERENCES gestiones(id),
                           area_id       SMALLINT     REFERENCES areas_conocimiento(id),
                           titulo        VARCHAR(255),
                           etapa_actual  VARCHAR(12)  NOT NULL DEFAULT 'habilitacion'
                               CHECK (etapa_actual IN ('habilitacion','tutor','perfil','viabilidad','tribunales',
                                                       'revision','predefensa','defensa','concluido','cancelado')),
                           created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- Tabla 55. proyecto_estudiante  (N:N, máx. 2 por trigger)
CREATE TABLE proyecto_estudiante (
                                     proyecto_id     BIGINT  NOT NULL REFERENCES proyectos(id) ON DELETE CASCADE,
                                     estudiante_id   BIGINT  NOT NULL REFERENCES estudiantes(usuario_id),
                                     solicitud_id    BIGINT  NOT NULL REFERENCES solicitudes_habilitacion(id),
                                     es_responsable  BOOLEAN NOT NULL DEFAULT FALSE,
                                     PRIMARY KEY (proyecto_id, estudiante_id)
);

-- Tabla 35. asignaciones_tutor
CREATE TABLE asignaciones_tutor (
                                    id                BIGSERIAL    PRIMARY KEY,
                                    proyecto_id       BIGINT       NOT NULL REFERENCES proyectos(id) ON DELETE CASCADE,
                                    docente_id        BIGINT       REFERENCES docentes(usuario_id),          -- NULL si se pide a Dirección
                                    origen            VARCHAR(25)  NOT NULL
                                        CHECK (origen IN ('propuesto_estudiante','designado_direccion')),
                                    estado            VARCHAR(20)  NOT NULL DEFAULT 'solicitada'
                                        CHECK (estado IN ('solicitada','pendiente_respuesta','aceptada','rechazada','reemplazada')),
                                    motivo_respuesta  VARCHAR(255),
                                    designado_por     BIGINT       REFERENCES usuarios(id),
                                    fecha_solicitud   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    fecha_respuesta   TIMESTAMP
);

-- Tabla 52. perfiles  (versión del proyecto)
CREATE TABLE perfiles (
                          id                      BIGSERIAL    PRIMARY KEY,
                          proyecto_id             BIGINT       NOT NULL REFERENCES proyectos(id) ON DELETE CASCADE,
                          version                 SMALLINT     NOT NULL DEFAULT 1,
                          titulo                  VARCHAR(255) NOT NULL,
                          introduccion            TEXT         NOT NULL,
                          planteamiento_problema  TEXT,
                          objetivo_general        TEXT         NOT NULL,
                          objetivos_especificos   TEXT         NOT NULL,
                          justificacion           TEXT,
                          alcance                 TEXT,
                          archivo_id              BIGINT       REFERENCES archivos(id),
                          estado                  VARCHAR(12)  NOT NULL DEFAULT 'borrador'
                              CHECK (estado IN ('borrador','enviado','en_revision','aprobado',
                                                'aprobado_obs','observado','no_viable')),
                          registrado_por          BIGINT       NOT NULL REFERENCES usuarios(id),
                          created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          CONSTRAINT uq_perfil_version UNIQUE (proyecto_id, version)
);

-- Tabla 46. evaluaciones_viabilidad
CREATE TABLE evaluaciones_viabilidad (
                                         id             BIGSERIAL   PRIMARY KEY,
                                         perfil_id      BIGINT      NOT NULL REFERENCES perfiles(id) ON DELETE CASCADE,
                                         revisante_id   BIGINT      NOT NULL REFERENCES docentes(usuario_id),
                                         resultado      VARCHAR(12) NOT NULL
                                             CHECK (resultado IN ('aprobado','aprobado_obs','observado','no_viable')),
                                         observaciones  TEXT,
                                         archivo_id     BIGINT      REFERENCES archivos(id),
                                         fecha          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Tabla 39. designaciones_tribunal  (autorrelación reemplaza_a_id)
CREATE TABLE designaciones_tribunal (
                                        id                 BIGSERIAL    PRIMARY KEY,
                                        proyecto_id        BIGINT       NOT NULL REFERENCES proyectos(id) ON DELETE CASCADE,
                                        docente_id         BIGINT       NOT NULL REFERENCES docentes(usuario_id),
                                        posicion           SMALLINT     NOT NULL CHECK (posicion BETWEEN 1 AND 3),
                                        estado             VARCHAR(12)  NOT NULL DEFAULT 'pendiente'
                                            CHECK (estado IN ('pendiente','aceptada','rechazada','reemplazada')),
                                        motivo             VARCHAR(255),
                                        reemplaza_a_id     BIGINT       REFERENCES designaciones_tribunal(id),
                                        autorizado_por     BIGINT       NOT NULL REFERENCES usuarios(id),
                                        fecha_designacion  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        fecha_respuesta    TIMESTAMP
);

-- =====================================================================
--  5. REVISIÓN Y VERSIONADO (CU5)
-- =====================================================================

-- Tabla 42. documentos
CREATE TABLE documentos (
                            id           BIGSERIAL    PRIMARY KEY,
                            proyecto_id  BIGINT       NOT NULL REFERENCES proyectos(id) ON DELETE CASCADE,
                            tipo         VARCHAR(20)  NOT NULL
                                CHECK (tipo IN ('borrador_proyecto','carta_correcciones','documento_revision',
                                                'carta_recepcion','acta_predefensa','acta_defensa',
                                                'documento_final','otro')),
                            titulo       VARCHAR(150) NOT NULL,
                            created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Tabla 43. documento_versiones  (inmutables, RF-11)
CREATE TABLE documento_versiones (
                                     id            BIGSERIAL    PRIMARY KEY,
                                     documento_id  BIGINT       NOT NULL REFERENCES documentos(id) ON DELETE CASCADE,
                                     numero        SMALLINT     NOT NULL,
                                     archivo_id    BIGINT       NOT NULL REFERENCES archivos(id),
                                     comentario    VARCHAR(255),
                                     created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                     CONSTRAINT uq_documento_version UNIQUE (documento_id, numero)
);

-- Tabla 60. rondas_revision
CREATE TABLE rondas_revision (
                                 id                             BIGSERIAL   PRIMARY KEY,
                                 proyecto_id                    BIGINT      NOT NULL REFERENCES proyectos(id) ON DELETE CASCADE,
                                 numero                         SMALLINT    NOT NULL,
                                 version_id                     BIGINT      NOT NULL REFERENCES documento_versiones(id),
                                 carta_correcciones_version_id  BIGINT      REFERENCES documento_versiones(id),
                                 fecha_envio                    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 plazo_dias_habiles             SMALLINT    NOT NULL DEFAULT 15,
                                 fecha_vencimiento              DATE        NOT NULL,
                                 estado                         VARCHAR(17) NOT NULL DEFAULT 'en_revision'
                                     CHECK (estado IN ('en_revision','con_observaciones','aprobada','vencida')),
                                 CONSTRAINT uq_ronda_numero UNIQUE (proyecto_id, numero)
);

-- Tabla 57. revisiones_tribunal  (una por tribunal por ronda)
CREATE TABLE revisiones_tribunal (
                                     id                             BIGSERIAL   PRIMARY KEY,
                                     ronda_id                       BIGINT      NOT NULL REFERENCES rondas_revision(id) ON DELETE CASCADE,
                                     designacion_id                 BIGINT      NOT NULL REFERENCES designaciones_tribunal(id),
                                     fecha_recepcion                TIMESTAMP,
                                     carta_recepcion_archivo_id     BIGINT      REFERENCES archivos(id),
                                     observaciones                  TEXT,
                                     documento_revision_archivo_id  BIGINT      REFERENCES archivos(id),
                                     visto_bueno                    BOOLEAN     NOT NULL DEFAULT FALSE,
                                     fecha_revision                 TIMESTAMP,
                                     estado                         VARCHAR(19) NOT NULL DEFAULT 'pendiente_recepcion'
                                         CHECK (estado IN ('pendiente_recepcion','recibido','observado',
                                                           'visto_bueno','vencido')),
                                     CONSTRAINT uq_revision_ronda_tribunal UNIQUE (ronda_id, designacion_id)
);

-- =====================================================================
--  6. EVALUACIÓN (CU6)
-- =====================================================================

-- Tabla 45. evaluaciones  (predefensa / defensa)
CREATE TABLE evaluaciones (
                              id               BIGSERIAL    PRIMARY KEY,
                              proyecto_id      BIGINT       NOT NULL REFERENCES proyectos(id) ON DELETE CASCADE,
                              tipo             VARCHAR(10)  NOT NULL CHECK (tipo IN ('predefensa','defensa')),
                              intento          SMALLINT     NOT NULL DEFAULT 1,
                              fecha_hora       TIMESTAMP    NOT NULL,
                              ambiente_id      SMALLINT     NOT NULL REFERENCES ambientes(id),
                              estado           VARCHAR(12)  NOT NULL DEFAULT 'programada'
                                  CHECK (estado IN ('programada','realizada','suspendida','reprogramada')),
                              resultado        VARCHAR(12)
                                  CHECK (resultado IN ('aprobado','aprobado_obs','reprobado','repetir')),
                              nota_final       NUMERIC(5,2) CHECK (nota_final BETWEEN 0 AND 100),
                              observaciones    TEXT,
                              acta_archivo_id  BIGINT       REFERENCES archivos(id),
                              programado_por   BIGINT       NOT NULL REFERENCES usuarios(id),
                              CONSTRAINT uq_evaluacion_intento UNIQUE (proyecto_id, tipo, intento)
);

-- Tabla 37. calificaciones  (clave compuesta)
CREATE TABLE calificaciones (
                                evaluacion_id   BIGINT       NOT NULL REFERENCES evaluaciones(id) ON DELETE CASCADE,
                                designacion_id  BIGINT       NOT NULL REFERENCES designaciones_tribunal(id),
                                nota            NUMERIC(5,2) NOT NULL CHECK (nota BETWEEN 0 AND 100),
                                observacion     VARCHAR(255),
                                PRIMARY KEY (evaluacion_id, designacion_id)
);

-- =====================================================================
--  7. MONITOREO Y CALENDARIO (CU7)
-- =====================================================================

-- Tabla 47. eventos_calendario
CREATE TABLE eventos_calendario (
                                    id           BIGSERIAL    PRIMARY KEY,
                                    gestion_id   SMALLINT     NOT NULL REFERENCES gestiones(id),
                                    titulo       VARCHAR(150) NOT NULL,
                                    descripcion  VARCHAR(255),
                                    inicio       TIMESTAMP    NOT NULL,
                                    fin          TIMESTAMP,
                                    creado_por   BIGINT       NOT NULL REFERENCES usuarios(id),
                                    CONSTRAINT chk_evento_fechas CHECK (fin IS NULL OR fin > inicio)
);

-- Tabla 50. historial_etapas  (línea de tiempo)
CREATE TABLE historial_etapas (
                                  id           BIGSERIAL    PRIMARY KEY,
                                  proyecto_id  BIGINT       NOT NULL REFERENCES proyectos(id) ON DELETE CASCADE,
                                  etapa        VARCHAR(30)  NOT NULL,
                                  estado       VARCHAR(10)  NOT NULL
                                      CHECK (estado IN ('iniciada','completada','observada','rechazada')),
                                  comentario   VARCHAR(255),
                                  usuario_id   BIGINT       NOT NULL REFERENCES usuarios(id),
                                  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================================
--  7b. VERSIÓN E-MAIL: registro de correos recibidos y procesados
--      - message_id UNIQUE evita procesar dos veces el mismo correo (POP3)
--      - sirve como registro de todas las órdenes recibidas por el sistema
-- =====================================================================
CREATE TABLE correos_procesados (
                                    id                 BIGSERIAL    PRIMARY KEY,
                                    message_id         VARCHAR(255) NOT NULL UNIQUE,      -- cabecera Message-ID del correo
                                    uid_pop3           VARCHAR(100),                      -- UIDL del buzón: detecta correos nuevos sin leerlos
                                    remitente          VARCHAR(120) NOT NULL,             -- MAIL FROM
                                    usuario_id         BIGINT       REFERENCES usuarios(id) ON DELETE SET NULL,  -- NULL si el remitente no está registrado
                                    asunto             VARCHAR(500) NOT NULL,             -- asunto completo, ej. LISUSU["*"]
                                    comando            VARCHAR(30),                       -- ej. LISUSU (NULL si no se pudo interpretar)
                                    parametros         TEXT,                              -- parámetros extraídos del asunto
                                    resultado          VARCHAR(10)  NOT NULL DEFAULT 'pendiente'
                                        CHECK (resultado IN ('pendiente','exito','error')),
                                    mensaje_respuesta  TEXT,                              -- texto o motivo del error enviado al remitente
                                    respuesta_enviada  BOOLEAN      NOT NULL DEFAULT FALSE,
                                    fecha_recepcion    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    fecha_proceso      TIMESTAMP
);

-- =====================================================================
--  8. ÍNDICES DE APOYO (claves foráneas más consultadas)
-- =====================================================================
CREATE INDEX idx_solicitudes_estado      ON solicitudes_habilitacion(estado);
CREATE INDEX idx_proyectos_etapa         ON proyectos(etapa_actual);
CREATE INDEX idx_proyectos_gestion       ON proyectos(gestion_id);
CREATE INDEX idx_pe_estudiante           ON proyecto_estudiante(estudiante_id);
CREATE INDEX idx_tutor_proyecto          ON asignaciones_tutor(proyecto_id);
CREATE INDEX idx_tutor_docente           ON asignaciones_tutor(docente_id);
CREATE INDEX idx_perfiles_proyecto       ON perfiles(proyecto_id);
CREATE INDEX idx_tribunal_proyecto       ON designaciones_tribunal(proyecto_id);
CREATE INDEX idx_tribunal_docente        ON designaciones_tribunal(docente_id);
CREATE INDEX idx_rondas_proyecto         ON rondas_revision(proyecto_id);
CREATE INDEX idx_evaluaciones_fecha      ON evaluaciones(fecha_hora);
CREATE INDEX idx_historial_proyecto      ON historial_etapas(proyecto_id);
CREATE INDEX idx_notificaciones_usuario  ON notificaciones(usuario_id);
CREATE INDEX idx_bitacora_usuario        ON bitacora(usuario_id);
CREATE INDEX idx_correos_remitente       ON correos_procesados(remitente);
CREATE INDEX idx_correos_fecha           ON correos_procesados(fecha_recepcion);
CREATE INDEX idx_correos_uid             ON correos_procesados(uid_pop3);

-- =====================================================================
--  9. FUNCIÓN: sumar días hábiles (salta sábados, domingos y feriados)
-- =====================================================================
CREATE OR REPLACE FUNCTION fn_sumar_dias_habiles(p_fecha DATE, p_dias INTEGER)
    RETURNS DATE
    LANGUAGE plpgsql
AS $$
DECLARE
    v_fecha    DATE    := p_fecha;
    v_contados INTEGER := 0;
BEGIN
    WHILE v_contados < p_dias LOOP
            v_fecha := v_fecha + 1;
            IF EXTRACT(ISODOW FROM v_fecha) < 6                          -- lunes(1) a viernes(5)
                AND NOT EXISTS (SELECT 1 FROM feriados f WHERE f.fecha = v_fecha) THEN
                v_contados := v_contados + 1;
            END IF;
        END LOOP;
    RETURN v_fecha;
END;
$$;

-- =====================================================================
-- 10. TRIGGERS
-- =====================================================================

-- trg_max_dos_estudiantes: un proyecto no puede tener más de dos estudiantes
CREATE OR REPLACE FUNCTION fn_trg_max_dos_estudiantes()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT COUNT(*) FROM proyecto_estudiante
        WHERE proyecto_id = NEW.proyecto_id) >= 2 THEN
        RAISE EXCEPTION 'El proyecto % ya tiene dos estudiantes', NEW.proyecto_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_max_dos_estudiantes
    BEFORE INSERT ON proyecto_estudiante
    FOR EACH ROW EXECUTE FUNCTION fn_trg_max_dos_estudiantes();

-- trg_max_tres_tribunales: no más de tres tribunales vigentes por proyecto
CREATE OR REPLACE FUNCTION fn_trg_max_tres_tribunales()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT COUNT(*) FROM designaciones_tribunal
        WHERE proyecto_id = NEW.proyecto_id
          AND estado IN ('pendiente','aceptada')) >= 3 THEN
        RAISE EXCEPTION 'El proyecto % ya tiene tres tribunales vigentes', NEW.proyecto_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_max_tres_tribunales
    BEFORE INSERT ON designaciones_tribunal
    FOR EACH ROW EXECUTE FUNCTION fn_trg_max_tres_tribunales();

-- =====================================================================
-- 11. VISTA: v_dashboard_proyectos
--     Proyectos con estudiantes, etapa, próximo vencimiento y próxima evaluación
-- =====================================================================
CREATE OR REPLACE VIEW v_dashboard_proyectos AS
SELECT p.id,
       p.codigo,
       g.codigo                                   AS gestion,
       a.nombre                                   AS area,
       p.titulo,
       p.etapa_actual,
       (SELECT STRING_AGG(u.nombres || ' ' || u.apellidos, ', ' ORDER BY pe.es_responsable DESC)
        FROM proyecto_estudiante pe
                 JOIN usuarios u ON u.id = pe.estudiante_id
        WHERE pe.proyecto_id = p.id)             AS estudiantes,
       (SELECT ut.nombres || ' ' || ut.apellidos
        FROM asignaciones_tutor t
                 JOIN usuarios ut ON ut.id = t.docente_id
        WHERE t.proyecto_id = p.id AND t.estado = 'aceptada'
        ORDER BY t.fecha_respuesta DESC NULLS LAST
        LIMIT 1)                                 AS tutor,
       (SELECT MIN(r.fecha_vencimiento)
        FROM rondas_revision r
        WHERE r.proyecto_id = p.id
          AND r.estado = 'en_revision')          AS proximo_vencimiento,
       (SELECT MIN(e.fecha_hora)
        FROM evaluaciones e
        WHERE e.proyecto_id = p.id
          AND e.estado = 'programada'
          AND e.fecha_hora >= CURRENT_TIMESTAMP) AS proxima_evaluacion,
       p.created_at,
       p.updated_at
FROM proyectos p
         JOIN gestiones g               ON g.id = p.gestion_id
         LEFT JOIN areas_conocimiento a ON a.id = p.area_id;

-- Fin del script 01