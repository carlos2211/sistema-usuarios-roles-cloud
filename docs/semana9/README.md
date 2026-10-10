# Evaluación Final Transversal — Sistema de Gestión de Usuarios y Roles

DSY2207 Desarrollo Cloud Native II · Carlos Orrego

Sistema backend cloud native para administrar usuarios, roles y permisos. Las operaciones las hacen **funciones serverless en Java** (Azure Functions) con capas **REST y GraphQL**, orquestadas por un **BFF en Spring Boot**. Las reglas de negocio automáticas se resuelven con una **arquitectura orientada a eventos** sobre **Azure Event Grid**.

![Arquitectura final](arquitectura-final.png)

## Requisitos de la evaluación y cómo se cumplen

| Requisito | Cómo se resuelve |
|---|---|
| CRUD de usuarios y roles con funciones serverless | `usuarios` y `permisos` (API REST), `roles` y `asignaciones` (API GraphQL), en Java 11 sobre Azure Functions |
| Microservicio BFF en Spring Boot que orquesta las funciones | `bff-springboot`, contenedor Docker en Azure Container Apps; responde JSON |
| Al menos 2 funciones serverless con API REST | `usuarios` y `permisos` (más las consultas `auditoria` y `notificaciones`) |
| Al menos 1 componente con tecnologías de eventos | Azure Event Grid (topic `evt-usuarios-roles`, 6 suscripciones) y 6 funciones con `@EventGridTrigger` |
| **Al crear un usuario se le asigna automáticamente un rol por defecto** | `procesador-rol-por-defecto` escucha `Usuario.Creado`, asigna el rol `USUARIO` y publica `UsuarioRol.Asignado` |
| **Al eliminar un rol se les quita a los usuarios que lo tenían** | `procesador-rol-eliminado` escucha `Rol.Eliminado`, borra esas asignaciones y publica un `UsuarioRol.Revocado` por usuario |
| Base de datos Oracle | Oracle Autonomous Database (OCI), scripts en `database/` |
| Docker | El BFF se ejecuta como contenedor; imagen en Docker Hub |
| Git colaborativo | Repositorio `github.com/carlos2211/sistema-usuarios-roles-cloud` |

## Componentes

### BFF (Spring Boot)

[`bff-springboot`](../../bff-springboot). Punto de entrada único para los clientes. Reenvía cada ruta a la función serverless que corresponde y devuelve su respuesta en JSON.

| Ruta del BFF | Función | Tipo |
|---|---|---|
| `GET/POST /api/usuarios`, `GET/PUT/DELETE /api/usuarios/{id}` | `usuarios` | REST |
| `GET/POST /api/permisos`, `GET/PUT/DELETE /api/permisos/{id}` | `permisos` | REST |
| `POST /api/roles` | `roles` | GraphQL |
| `POST /api/asignaciones` | `asignaciones` | GraphQL |
| `GET /api/auditoria`, `GET /api/notificaciones` | `auditoria`, `notificaciones` | REST (consultas) |

### Funciones serverless (Azure Functions, Java 11)

[`azure-functions`](../../azure-functions). Las 13 funciones están en la Function App `funcionusuariosroles1`.

**Productoras de eventos (HTTP).** Guardan el cambio en Oracle y, después del commit, publican un evento en Event Grid con [`EventGridPublisher`](../../azure-functions/src/main/java/cl/duoc/usuariosroles/eventos/EventGridPublisher.java), que usa la API REST del topic con la clave `aeg-sas-key`.

| Función | API | Eventos que publica |
|---|---|---|
| `usuarios` | REST | `Usuario.Creado`, `Usuario.Actualizado`, `Usuario.Desactivado`, `Usuario.Eliminado` |
| `permisos` | REST | `Permiso.Creado`, `Permiso.Actualizado`, `Permiso.Eliminado` |
| `roles` | GraphQL | `Rol.Creado`, `Rol.Actualizado`, `Rol.Eliminado` (con el nombre del rol) |
| `asignaciones` | GraphQL | `UsuarioRol.Asignado`, `UsuarioRol.Revocado`, `RolPermiso.Asignado`, `RolPermiso.Revocado` |

**Consumidores y procesadores (`@EventGridTrigger`).** Los consumidores hacen una acción final. Los procesadores aplican lógica de negocio y publican eventos nuevos.

| Función | Tipo | Suscripción · filtro | Qué hace |
|---|---|---|---|
| `procesador-rol-por-defecto` | Procesador | `sub-rol-por-defecto` · `Usuario.Creado` | Asigna el rol por defecto (`ROL_POR_DEFECTO`, por defecto `USUARIO`) y publica `UsuarioRol.Asignado` con motivo `ROL_POR_DEFECTO` |
| `procesador-rol-eliminado` | Procesador | `sub-rol-eliminado` · `Rol.Eliminado` | Quita el rol a todos sus usuarios en una transacción y publica `UsuarioRol.Revocado` con motivo `ROL_ELIMINADO` por cada uno |
| `procesador-seguridad` | Procesador | `sub-seguridad` · `UsuarioRol.Asignado` | Si el rol es crítico (`ADMINISTRADOR`) publica `Alerta.Seguridad` |
| `procesador-desactivacion` | Procesador | `sub-desactivacion` · `Usuario.Desactivado` | Quita todos los roles del usuario desactivado |
| `auditoria-eventos` | Consumidor | `sub-auditoria` · todos | Guarda cada evento en `AUDITORIA_EVENTOS` (event store) |
| `notificaciones-eventos` | Consumidor | `sub-notificaciones` · `Usuario.Creado`, `Usuario.Desactivado`, `UsuarioRol.*`, `Alerta.Seguridad` | Arma el aviso para la persona afectada y lo registra en `NOTIFICACIONES` |

### Azure Event Grid

- **Custom Topic `evt-usuarios-roles`**, con esquema Event Grid.
- **6 suscripciones** de tipo Azure Function, cada una con filtro por tipo de evento.
- **Reintentos:** hasta 10 intentos durante 24 horas.
- **Dead-letter:** lo que no se pudo entregar queda en el contenedor `eventos-deadletter` del Storage de la Function App.

Todo se crea con [`infra/event-grid/configurar-event-grid.sh`](../../infra/event-grid/configurar-event-grid.sh).

### Base de datos Oracle

Los scripts se ejecutan en este orden:

1. [`usuarios_roles.sql`](../../database/usuarios_roles.sql): tablas `USUARIOS`, `ROLES`, `PERMISOS`, `USUARIOS_ROLES`, `ROLES_PERMISOS` y datos iniciales (roles `ADMINISTRADOR`, `SUPERVISOR`, `USUARIO`).
2. [`semana8_eventos.sql`](../../database/semana8_eventos.sql): `AUDITORIA_EVENTOS` (event store) y `NOTIFICACIONES`.
3. [`semana9_eventos.sql`](../../database/semana9_eventos.sql): quita el borrado en cascada de `FK_UR_ROL` y agrega un índice por `ID_ROL`.

**Decisión de diseño.** Hasta la semana 8, Oracle borraba las asignaciones al eliminar un rol (`ON DELETE CASCADE`). Para que el requisito lo cumpla un componente de eventos, ahora esa limpieza la hace `procesador-rol-eliminado`. Durante los segundos que tarda la entrega del evento pueden quedar asignaciones de un rol que ya no existe. Eso es **consistencia eventual**, y no se nota en el sistema por dos motivos:

- las consultas de roles de un usuario hacen JOIN con `ROLES`;
- la función de asignaciones valida que el rol exista antes de asignarlo.

## Flujos de los requisitos nuevos

**Usuario nuevo con rol por defecto**

1. `POST /api/usuarios` → la función `usuarios` inserta el usuario y publica `Usuario.Creado`.
2. Event Grid entrega el evento a `procesador-rol-por-defecto`, `auditoria-eventos` y `notificaciones-eventos`.
3. El procesador inserta `(idUsuario, USUARIO)` en `USUARIOS_ROLES` y publica `UsuarioRol.Asignado` con motivo `ROL_POR_DEFECTO`. El campo `eventoOrigen` apunta al `Usuario.Creado`.
4. La auditoría registra los dos eventos. El usuario recibe la bienvenida y el aviso de su rol.

**Rol eliminado**

1. `mutation { eliminarRol(id: N) }` → la función `roles` borra el rol y publica `Rol.Eliminado` con su nombre.
2. `procesador-rol-eliminado` busca los usuarios con ese rol, borra las asignaciones y publica un `UsuarioRol.Revocado` con motivo `ROL_ELIMINADO` por cada usuario.
3. Cada usuario recibe el aviso "ya no tienes el rol N porque fue eliminado del sistema".

Las dos funciones son **idempotentes**: si Event Grid reintenta una entrega, no duplican asignaciones ni eventos.

## Despliegue en la nube

| Componente | Servicio | URL |
|---|---|---|
| BFF | Azure Container Apps (`bff-usuarios-roles`) | https://bff-usuarios-roles.politesmoke-8ab26d2c.eastus.azurecontainerapps.io |
| Funciones | Azure Functions (`funcionusuariosroles1`) | https://funcionusuariosroles1-hqasc7d7b2b2d9b9.eastus-01.azurewebsites.net/api |
| Bus de eventos | Azure Event Grid (`evt-usuarios-roles`) | https://evt-usuarios-roles.eastus-1.eventgrid.azure.net/api/events |
| Base de datos | Oracle Autonomous Database (OCI) | `usuariosroles` |
| Imagen del BFF | Docker Hub | `dockertestcarlosorrego/usuarios-roles-bff:semana8-v2` |

La instrucción pedía usar Docker Lab (Play with Docker), que fue discontinuado en marzo de 2026. Por eso el contenedor del BFF se ejecuta en **Azure Container Apps**, junto al resto de los servicios.

**Orden de despliegue:**

1. Ejecutar los tres scripts SQL en Oracle.
2. Desplegar las funciones: `cd azure-functions && mvn clean package azure-functions:deploy`.
3. Configurar Event Grid desde Azure Cloud Shell (Bash): `bash infra/event-grid/configurar-event-grid.sh`.
4. Publicar el BFF:
   - construir la imagen con `cd bff-springboot`, `mvn clean package` y `docker build`;
   - subirla con `docker push`;
   - actualizar la Container App con `az containerapp update ... --image <imagen>`.

La wallet de Oracle (`azure-functions/src/main/resources/wallet/`) y `local.settings.json` contienen credenciales. No están en el repositorio ni en el ZIP: hay que descargar la wallet desde OCI y configurar `ORACLE_DB_USER`, `ORACLE_DB_PASSWORD` y `ORACLE_TNS_ALIAS`.

## Pruebas

**Automáticas.** `cd azure-functions && mvn test` ejecuta 16 pruebas unitarias:

- la publicación hacia Event Grid, contra un servidor HTTP local;
- la lectura de eventos;
- las reglas de notificaciones, del rol por defecto y de los roles críticos.

**Manuales.** La colección [`EFT-usuarios-roles.postman_collection.json`](EFT-usuarios-roles.postman_collection.json) prueba todo contra el BFF en la nube, organizada en carpetas:

0. Calentar el sistema.
1. Rol por defecto al crear un usuario.
2. Eliminación de un rol con dos usuarios afectados.
3. CRUD de usuarios, roles y permisos (REST y GraphQL).
4. Extras: alerta de seguridad y desactivación.

**En local, sin Azure.**

- Levantar Azurite.
- Levantar el simulador de Event Grid con `node infra/event-grid/simulador-local.js`; entrega los eventos con los mismos filtros que las 6 suscripciones reales.
- Levantar las funciones con `mvn azure-functions:run`.
- Levantar el BFF con `mvn spring-boot:run`.
