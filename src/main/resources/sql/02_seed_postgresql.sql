-- =====================================================================
--  SISTEMA DE SEGUIMIENTO A TITULADOS  ·  Versión vía E-Mail
--  Script 02: Datos iniciales (PostgreSQL)
--  Ejecutar DESPUÉS de 01_schema_postgresql.sql
--
--  Contraseña de todos los usuarios de prueba: Titulados2026*
--  Usuarios REALES (envían comandos por correo):
--    id 1  · Pablo Arandia  · pabl@gmail.com              · administrador
--    id 2  · Ronald Campos  · ronald@gmail.com              · director
--    id 10 · Joaquin Rioja  · pjar@ficct.uagrm.edu.bo  · estudiante
--  El resto de usuarios son ficticios (solo datos de prueba).
--  (hash bcrypt; en la versión e-mail el usuario se identifica por su
--   correo remitente, que debe existir en usuarios.email)
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
--  Catálogos
-- ---------------------------------------------------------------------
INSERT INTO carreras (codigo, nombre) VALUES
    ('RED', 'Ingeniería en Redes y Telecomunicaciones');

INSERT INTO areas_conocimiento (nombre) VALUES
                                            ('Redes de Computadoras'),
                                            ('Telecomunicaciones'),
                                            ('Seguridad Informática'),
                                            ('Sistemas Inalámbricos'),
                                            ('Internet de las Cosas'),
                                            ('Computación en la Nube');

INSERT INTO gestiones (codigo, fecha_inicio, fecha_fin, fecha_limite_perfil, plazo_revision_dias, activa) VALUES
                                                                                                              ('1-2026', '2026-02-02', '2026-07-10', '2026-04-30', 15, FALSE),
                                                                                                              ('2-2026', '2026-08-03', '2026-12-18', '2026-11-27', 15, TRUE);

INSERT INTO feriados (fecha, descripcion) VALUES
                                              ('2026-01-01', 'Año Nuevo'),
                                              ('2026-01-22', 'Creación del Estado Plurinacional'),
                                              ('2026-02-16', 'Carnaval'),
                                              ('2026-02-17', 'Carnaval'),
                                              ('2026-04-03', 'Viernes Santo'),
                                              ('2026-05-01', 'Día del Trabajo'),
                                              ('2026-06-04', 'Corpus Christi'),
                                              ('2026-06-22', 'Año Nuevo Andino Amazónico (compensado)'),
                                              ('2026-08-06', 'Día de la Independencia'),
                                              ('2026-09-24', 'Aniversario de Santa Cruz'),
                                              ('2026-11-02', 'Todos los Difuntos'),
                                              ('2026-12-25', 'Navidad');

INSERT INTO ambientes (nombre, ubicacion) VALUES
                                              ('Auditorio FICCT', 'Módulo 236, planta baja'),
                                              ('Aula 236-10',     'Módulo 236, piso 1'),
                                              ('Lab. Redes 1',    'Módulo 236, piso 2'),
                                              ('Sala de Defensas','Dirección de Carrera');

INSERT INTO tipos_requisito (nombre, obligatorio) VALUES
                                                      ('Histórico académico',      TRUE),
                                                      ('Carnet de identidad',      TRUE),
                                                      ('Currículum vitae',         TRUE),
                                                      ('Boleta de inscripción GRL',TRUE);

-- ---------------------------------------------------------------------
--  Roles (actores del documento, Tabla 5)
-- ---------------------------------------------------------------------
INSERT INTO roles (id, nombre, descripcion) VALUES
                                                (1, 'administrador', 'Gestiona usuarios, roles, fotos; consulta dashboard y reportes'),
                                                (2, 'director',      'Director de Carrera: designa tutores y tribunales, programa evaluaciones'),
                                                (3, 'academico',     'Coordinador académico: verifica requisitos, define plazos, programa evaluaciones'),
                                                (4, 'secretaria',    'Verifica requisitos de habilitación y consulta el dashboard'),
                                                (5, 'tutor',         'Acepta o rechaza tutorías y sigue a sus tutorados'),
                                                (6, 'revisante',     'Evalúa la viabilidad del perfil'),
                                                (7, 'tribunal',      'Revisa el proyecto, da visto bueno y califica'),
                                                (8, 'estudiante',    'Solicita habilitación, registra proyecto y perfil, envía versiones');
SELECT setval('roles_id_seq', 8);

-- ---------------------------------------------------------------------
--  Permisos por caso de uso
-- ---------------------------------------------------------------------
INSERT INTO permisos (id, codigo, descripcion, caso_uso) VALUES
                                                             ( 1, 'usuarios.gestionar',       'Registrar, modificar, listar y desactivar usuarios',      'CU1'),
                                                             ( 2, 'perfil.propio',            'Ver y actualizar sus propios datos, foto y contraseña',   'CU1'),
                                                             ( 3, 'habilitacion.solicitar',   'Cargar requisitos y enviar solicitud',                    'CU2'),
                                                             ( 4, 'habilitacion.verificar',   'Verificar requisitos, malla y GRL; habilitar/observar',   'CU2'),
                                                             ( 5, 'proyecto.registrar',       'Registrar proyecto, compañero, tutor y perfil',           'CU3'),
                                                             ( 6, 'tutor.designar',           'Designar tutor desde Dirección',                          'CU3'),
                                                             ( 7, 'tutor.responder',          'Aceptar o rechazar tutoría',                              'CU3'),
                                                             ( 8, 'viabilidad.evaluar',       'Evaluar viabilidad del perfil',                           'CU4'),
                                                             ( 9, 'tribunal.designar',        'Designar y reemplazar tribunales',                        'CU4'),
                                                             (10, 'tribunal.responder',       'Aceptar o rechazar designación de tribunal',              'CU4'),
                                                             (11, 'revision.enviar',          'Enviar versiones y correcciones a tribunales',            'CU5'),
                                                             (12, 'revision.revisar',         'Confirmar recepción, observar o dar visto bueno',         'CU5'),
                                                             (13, 'revision.plazos',          'Definir, ampliar plazos y enviar recordatorios',          'CU5'),
                                                             (14, 'evaluacion.programar',     'Programar evaluaciones, registrar actas y ambientes',     'CU6'),
                                                             (15, 'evaluacion.calificar',     'Registrar calificación de predefensa y defensa',          'CU6'),
                                                             (16, 'evaluacion.consultar',     'Consultar sus evaluaciones y resultados',                 'CU6'),
                                                             (17, 'seguimiento.ver_propio',   'Consultar su seguimiento / el de sus tutorados',          'CU7'),
                                                             (18, 'dashboard.ver',            'Consultar dashboard de indicadores y calendario',         'CU7'),
                                                             (19, 'calendario.gestionar',     'Registrar o eliminar eventos del calendario',             'CU7'),
                                                             (20, 'notificaciones.ver',       'Consultar notificaciones',                                'CU7'),
                                                             (21, 'reportes.ver',             'Ver reportes y estadísticas, exportar',                   'CU8');
SELECT setval('permisos_id_seq', 21);

-- Matriz de acceso (Tabla 6 del documento)
INSERT INTO rol_permiso (rol_id, permiso_id) VALUES
                                                 -- administrador: CU1, CU7, CU8
                                                 (1,1),(1,2),(1,18),(1,20),(1,21),
                                                 -- director: CU3, CU4, CU6, CU7, CU8
                                                 (2,2),(2,6),(2,9),(2,14),(2,18),(2,19),(2,20),(2,21),
                                                 -- academico (coordinador): CU2, CU5, CU6, CU7, CU8
                                                 (3,2),(3,4),(3,13),(3,14),(3,18),(3,19),(3,20),(3,21),
                                                 -- secretaria: CU2, CU7
                                                 (4,2),(4,4),(4,18),(4,20),
                                                 -- tutor: CU3, CU7
                                                 (5,2),(5,7),(5,17),(5,20),
                                                 -- revisante: CU4
                                                 (6,2),(6,8),(6,20),
                                                 -- tribunal: CU4, CU5, CU6
                                                 (7,2),(7,10),(7,12),(7,15),(7,20),
                                                 -- estudiante: CU2, CU3, CU5, CU6 (consulta), CU7
                                                 (8,2),(8,3),(8,5),(8,11),(8,16),(8,17),(8,20);

-- ---------------------------------------------------------------------
--  Usuarios de prueba
--  hash bcrypt de: Titulados2026*
-- ---------------------------------------------------------------------
INSERT INTO usuarios (id, ci, nombres, apellidos, email, telefono, password, estado, password_expira_en) VALUES
                                                                                                             ( 1, '1000001', 'Pablo',        'Arandia',            'pablo@gmail.com',      '70000001', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             ( 2, '1000002', 'Ronald',       'Campos',             'ronald@gmail.com',      '70000002', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             ( 3, '1000003', 'María Elena',  'Suárez Vaca',        'msuarez@uagrm.edu.bo',      '70000003', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             ( 4, '1000004', 'Rosa',         'Antelo Justiniano',  'rantelo@uagrm.edu.bo',      '70000004', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             ( 5, '1000005', 'Carlos',       'Méndez Rojas',       'cmendez@uagrm.edu.bo',      '70000005', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             ( 6, '1000006', 'Luis',         'Rojas Peña',         'lrojas@uagrm.edu.bo',       '70000006', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             ( 7, '1000007', 'Patricia',     'Áñez Durán',         'panez@uagrm.edu.bo',        '70000007', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             ( 8, '1000008', 'Fernando',     'Salvatierra Paz',    'fsalvatierra@uagrm.edu.bo', '70000008', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             ( 9, '1000009', 'Gabriela',     'Torrico Ríos',       'gtorrico@uagrm.edu.bo',     '70000009', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             (10, '4715292', 'Joaquin',      'Rioja',              'pjara@ficct.uagrm.edu.bo', '71055123', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             (11, '5123456', 'Ana',          'Gutiérrez Lima',     'anagutierrez@uagrm.edu.bo', '71055124', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30'),
                                                                                                             (12, '6234567', 'Diego',        'Vargas Cuéllar',     'dvargas@uagrm.edu.bo',      '71055125', '$2b$10$ewCKrmrwCuMqWK3sxOnXqOA6uHHZ2ihJsHryf6.lU9rgX2ZsF3EFy', 'activo', '2027-06-30');
SELECT setval('usuarios_id_seq', 12);

INSERT INTO usuario_rol (usuario_id, rol_id) VALUES
                                                 (1,1),           -- administrador
                                                 (2,2),           -- director de carrera
                                                 (3,3),           -- académico (coordinador)
                                                 (4,4),           -- secretaria
                                                 (5,5),(5,6),     -- docente: tutor y revisante
                                                 (6,5),(6,7),     -- docente: tutor y tribunal
                                                 (7,7),           -- docente: tribunal
                                                 (8,7),           -- docente: tribunal
                                                 (9,6),(9,7),     -- docente: revisante y tribunal
                                                 (10,8),(11,8),(12,8);  -- estudiantes

INSERT INTO docentes (usuario_id, titulo, item_docente) VALUES
                                                            (5, 'Lic.', 'D-0105'),
                                                            (6, 'Ing.', 'D-0106'),
                                                            (7, 'Ing.', 'D-0107'),
                                                            (8, 'Ing.', 'D-0108'),
                                                            (9, 'M.Sc.','D-0109');

INSERT INTO docente_area (docente_id, area_id) VALUES
                                                   (5,1),(5,3),
                                                   (6,1),(6,2),
                                                   (7,1),(7,4),
                                                   (8,1),(8,3),
                                                   (9,2),(9,5),(9,1);

INSERT INTO estudiantes (usuario_id, registro_universitario, carrera_id, porcentaje_malla, grl_inscrito) VALUES
                                                                                                             (10, '218003374', 1, 100.00, TRUE),
                                                                                                             (11, '222007737', 1, 100.00, TRUE),
                                                                                                             (12, '221044221', 1,  95.00, FALSE);

-- ---------------------------------------------------------------------
--  Caso de ejemplo: proyecto PG-2-2026-001 (Joaquin y Ana) en etapa viabilidad
-- ---------------------------------------------------------------------
INSERT INTO archivos (id, nombre_original, ruta, mime, tamano_bytes, sha256, subido_por) VALUES
                                                                                             (1, 'historico_juan.pdf', 'requisitos/10/historico.pdf', 'application/pdf', 120000, repeat('a',64), 10),
                                                                                             (2, 'ci_juan.pdf',        'requisitos/10/ci.pdf',        'application/pdf',  80000, repeat('b',64), 10),
                                                                                             (3, 'cv_juan.pdf',        'requisitos/10/cv.pdf',        'application/pdf',  95000, repeat('c',64), 10),
                                                                                             (4, 'boleta_grl_juan.pdf','requisitos/10/boleta.pdf',    'application/pdf',  60000, repeat('d',64), 10),
                                                                                             (5, 'perfil_v1.pdf',      'perfiles/1/v1.pdf',           'application/pdf', 450000, repeat('e',64), 10);
SELECT setval('archivos_id_seq', 5);

INSERT INTO solicitudes_habilitacion (id, estudiante_id, gestion_id, estado, verificado_por, fecha_envio, fecha_verificacion) VALUES
                                                                                                                                  (1, 10, 2, 'habilitada', 4, '2026-08-10 09:00', '2026-08-12 10:30'),
                                                                                                                                  (2, 11, 2, 'habilitada', 4, '2026-08-10 11:00', '2026-08-12 11:00'),
                                                                                                                                  (3, 12, 2, 'enviada',    NULL, '2026-08-20 15:00', NULL);
SELECT setval('solicitudes_habilitacion_id_seq', 3);

INSERT INTO requisitos_presentados (solicitud_id, tipo_requisito_id, archivo_id, estado) VALUES
                                                                                             (1,1,1,'valido'),(1,2,2,'valido'),(1,3,3,'valido'),(1,4,4,'valido');

INSERT INTO proyectos (id, codigo, gestion_id, area_id, titulo, etapa_actual) VALUES
    (1, 'PG-2-2026-001', 2, 1, 'Sistema de monitoreo de red con SNMP para la FICCT', 'viabilidad');
SELECT setval('proyectos_id_seq', 1);

INSERT INTO proyecto_estudiante (proyecto_id, estudiante_id, solicitud_id, es_responsable) VALUES
                                                                                               (1, 10, 1, TRUE),
                                                                                               (1, 11, 2, FALSE);

INSERT INTO asignaciones_tutor (proyecto_id, docente_id, origen, estado, designado_por, fecha_solicitud, fecha_respuesta) VALUES
    (1, 5, 'propuesto_estudiante', 'aceptada', NULL, '2026-08-15 10:00', '2026-08-16 08:30');

INSERT INTO perfiles (proyecto_id, version, titulo, introduccion, objetivo_general, objetivos_especificos, archivo_id, estado, registrado_por) VALUES
    (1, 1, 'Sistema de monitoreo de red con SNMP para la FICCT',
     'La red de la facultad no cuenta con monitoreo centralizado.',
     'Implementar un sistema de monitoreo de red basado en SNMP.',
     '1. Relevar la red. 2. Configurar SNMP. 3. Desarrollar el panel.',
     5, 'enviado', 10);

INSERT INTO historial_etapas (proyecto_id, etapa, estado, comentario, usuario_id, created_at) VALUES
                                                                                                  (1, 'habilitacion', 'completada', 'Solicitud habilitada',           4,  '2026-08-12 10:30'),
                                                                                                  (1, 'tutor',        'completada', 'Tutor Lic. Méndez aceptó',       5,  '2026-08-16 08:30'),
                                                                                                  (1, 'perfil',       'completada', 'Perfil v1 enviado a viabilidad', 10, '2026-09-01 18:00'),
                                                                                                  (1, 'viabilidad',   'iniciada',   'En evaluación de viabilidad',    10, '2026-09-01 18:00');

INSERT INTO eventos_calendario (gestion_id, titulo, descripcion, inicio, fin, creado_por) VALUES
    (2, 'Reunión de tribunales',    'Coordinación de revisiones',         '2026-11-10 10:00', '2026-11-10 12:00', 2);

INSERT INTO notificaciones (usuario_id, titulo, mensaje) VALUES
    (9, 'Perfil por evaluar', 'El perfil del proyecto PG-2-2026-001 está pendiente de viabilidad.');

-- ---------------------------------------------------------------------
--  Versión e-mail: ejemplos de correos procesados
-- ---------------------------------------------------------------------
INSERT INTO correos_procesados (message_id, remitente, usuario_id, asunto, comando, parametros,
                                resultado, mensaje_respuesta, respuesta_enviada, fecha_recepcion, fecha_proceso) VALUES
                                                                                                                     ('<ejemplo-001@uagrm.edu.bo>', 'pablosabea@gmail.com', 1, 'LISUSU["*"]', 'LISUSU', '["*"]',
                                                                                                                      'exito', 'Listando los usuarios', TRUE, '2026-10-30 09:00', '2026-10-30 09:00:05'),
                                                                                                                     ('<ejemplo-002@gmail.com>', 'desconocido@gmail.com', NULL, 'LISUSU["*"]', 'LISUSU', '["*"]',
                                                                                                                      'error', 'Error: el remitente no está registrado en el sistema', TRUE, '2026-10-30 09:10', '2026-10-30 09:10:03'),
                                                                                                                     ('<ejemplo-003@uagrm.edu.bo>', 'pjarandiar126@ficct.uagrm.edu.bo', 10, 'HOLA', NULL, NULL,
                                                                                                                      'error', 'Error: comando no reconocido. Envíe HELP para ver los comandos', TRUE, '2026-10-30 09:20', '2026-10-30 09:20:02');

COMMIT;

-- Fin del script 02