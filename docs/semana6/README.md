# Semana 6 — Arquitectura orientada a eventos

DSY2207 Desarrollo Cloud Native II · Actividad formativa "Conociendo sistemas cloud con arquitectura basada en eventos" · Grupo 19 (Carlos Orrego – Alberto Diaz)

Diseño de la evolución del Sistema de Gestión de Usuarios y Roles hacia una arquitectura orientada a eventos (EDA) en Azure. Las piezas de las Semanas 4 y 5 (Functions REST/GraphQL, BFF y Oracle) pasan a ser **productoras de eventos** que publican en **Azure Event Grid**, y nuevos procesadores y consumidores reaccionan de forma asíncrona.

- Diagrama: [arquitectura-eda.png](arquitectura-eda.png) (fuente vectorial: [arquitectura-eda.svg](arquitectura-eda.svg))
- Informe en formato DuocUC: [../Exp3_S6_Grupo19.docx](../Exp3_S6_Grupo19.docx)

![Arquitectura orientada a eventos](arquitectura-eda.png)

## 1. Caso y requisitos funcionales

### Caso definido por el grupo

Se desarrollará el **Sistema de Gestión de Usuarios y Roles** para una **cadena de retail con 40 tiendas a lo largo de Chile y cerca de 3.000 colaboradores** (cajeros, bodegueros, supervisores, jefes de tienda y personal de oficina central). Hoy los accesos a los sistemas internos de la empresa (punto de venta, inventario, RR.HH. y reportería) se piden por correo y se controlan en planillas, lo que genera tres problemas: los nuevos ingresos esperan días para trabajar, las personas desvinculadas mantienen accesos activos y, ante una auditoría, no existe registro de quién otorgó cada permiso.

El sistema centraliza la administración de usuarios, roles y permisos. Sobre la base construida en las semanas anteriores (funciones serverless REST y GraphQL en Azure, BFF en Spring Boot y base de datos Oracle) se incorpora una **arquitectura orientada a eventos**: cada cambio relevante se publica como evento en Azure Event Grid y distintos procesadores y consumidores reaccionan de forma asíncrona (notifican, recalculan permisos, auditan y detectan riesgos), sin que la respuesta al administrador tenga que esperar esas tareas.

### Requisitos funcionales

| ID | Requisito funcional | Pieza que lo resuelve |
|---|---|---|
| RF01 | Administrar usuarios: crear, listar, consultar, actualizar y desactivar/eliminar. | UsuariosFunction (REST) |
| RF02 | Administrar el catálogo de permisos. | PermisosFunction (REST) |
| RF03 | Administrar roles (crear, actualizar, eliminar, consultar). | RolesGraphQLFunction |
| RF04 | Asignar y quitar roles a usuarios, y permisos a roles. | AsignacionesGraphQLFunction |
| RF05 | Enviar un correo de bienvenida cuando se crea un usuario. | Consumidor de notificaciones |
| RF06 | Notificar por correo al usuario cuando se le asigna o quita un rol. | Consumidor de notificaciones |
| RF07 | Mantener precalculados los permisos efectivos de cada usuario para que los sistemas de la empresa validen accesos en milisegundos. | Procesador de permisos + Redis |
| RF08 | Al desactivar un usuario (desvinculación), invalidar de inmediato todos sus accesos. | Procesador de desactivación |
| RF09 | Registrar de forma inmutable cada cambio (qué, quién, cuándo) y permitir consultar el historial de un usuario. | Consumidor de auditoría + Cosmos DB |
| RF10 | Alertar asignaciones riesgosas: rol crítico (ej. ADMIN) o más de 5 cambios sobre un mismo usuario en 10 minutos. | Procesador de seguridad |
| RF11 | Actualizar el portal de administración en tiempo real ante cambios y alertas. | Consumidor de tiempo real + SignalR |
| RF12 | Autenticar a los administradores y proteger el acceso a la API. | Entra ID + API Management |

## 2. Piezas del sistema
El sistema se organiza en ocho capas (numeradas igual que en el diagrama) y un conjunto de servicios transversales. Para cada pieza se indica su finalidad, su funcionalidad y un ejemplo de uso dentro del caso.

### 1. Clientes

**Portal de administración** — _Azure Static Web Apps · Nueva_

- **Finalidad:** Dar a los administradores de TI y a los jefes de tienda una interfaz web para gestionar usuarios, roles y permisos sin usar herramientas técnicas.
- **Funcionalidad:** Aplicación de página única (SPA) servida desde Static Web Apps. Inicia sesión contra Entra ID, consume la API a través de API Management y mantiene una conexión WebSocket con SignalR para recibir cambios en vivo.
- **Ejemplo de uso:** Una jefa de tienda en Temuco crea al nuevo cajero, le asigna el rol CAJERO y ve en el mismo panel cómo cambia su estado a “permisos listos” cuando el procesador termina.

**Postman / aplicaciones internas** — _Clientes HTTP · Existente_

- **Finalidad:** Permitir pruebas de la API y la integración de otros sistemas de la empresa.
- **Funcionalidad:** Envía peticiones REST y GraphQL a la misma API pública, con el mismo token que usa el portal.
- **Ejemplo de uso:** El equipo de RR.HH. llama a POST /api/usuarios desde su sistema de contratación para crear automáticamente la cuenta del nuevo colaborador.

### 2. Entrada y seguridad

**Microsoft Entra ID** — _Proveedor de identidad (OAuth 2.0 / OpenID Connect) · Nueva_

- **Finalidad:** Autenticar a quien usa el sistema y emitir los tokens que prueban su identidad.
- **Funcionalidad:** Gestiona el inicio de sesión de los administradores (con MFA) y emite tokens JWT que API Management valida en cada petición.
- **Ejemplo de uso:** Un supervisor inicia sesión en el portal; Entra ID le entrega un token válido por una hora que acompaña todas sus llamadas a la API.

**Azure API Management** — _API Gateway · Nueva_

- **Finalidad:** Ser la única puerta de entrada a la API, aplicando seguridad y control de tráfico antes de llegar al backend.
- **Funcionalidad:** Valida el JWT, aplica CORS, límites de peticiones por cliente (rate limiting) y registra métricas de uso. Publica una URL estable aunque cambie la infraestructura interna.
- **Ejemplo de uso:** Si un script mal configurado de una tienda envía 1.000 peticiones por minuto, API Management lo limita sin afectar al resto de los usuarios.

### 3. Backend for Frontend

**BFF Spring Boot** — _Contenedor Docker en Azure Container Apps · Existente_

- **Finalidad:** Ofrecer al portal un único backend adaptado a sus pantallas, ocultando cuántas funciones serverless hay detrás.
- **Funcionalidad:** Recibe las peticiones de API Management y las enruta a la función correspondiente: /usuarios y /permisos hacia las funciones REST; /roles y /asignaciones hacia las funciones GraphQL. Se construye como imagen Docker y escala por número de peticiones en Container Apps.
- **Ejemplo de uso:** El portal pide la ficha de un usuario y el BFF combina el CRUD de usuarios (REST) con la consulta rolesDeUsuario (GraphQL) en una sola respuesta.

### 4. Productores de eventos (Azure Functions en Java)

Son las cuatro funciones serverless construidas en las semanas anteriores. Su lógica CRUD se mantiene; lo que cambia es que, **después de confirmar (commit) la transacción en Oracle**, cada función publica un evento en Event Grid mediante el binding de salida @EventGridOutput. Así, el resto del sistema se entera del cambio sin que la función conozca a quién le interesa.

**UsuariosFunction** — _Azure Function HTTP · API REST /api/usuarios · Existente (se agrega publicación de eventos)_

- **Finalidad:** Administrar el ciclo de vida de los usuarios (RF01).
- **Funcionalidad:** CRUD de usuarios sobre la tabla USUARIOS. Publica Usuario.Creado, Usuario.Actualizado y Usuario.Desactivado.
- **Ejemplo de uso:** Al desactivar a un bodeguero desvinculado, responde 200 OK al administrador y publica Usuario.Desactivado.

**PermisosFunction** — _Azure Function HTTP · API REST /api/permisos · Existente (se agrega publicación de eventos)_

- **Finalidad:** Administrar el catálogo de permisos (RF02).
- **Funcionalidad:** CRUD sobre la tabla PERMISOS. Publica Permiso.Creado, Permiso.Actualizado y Permiso.Eliminado.
- **Ejemplo de uso:** Se crea el permiso ANULAR_VENTA y queda disponible para asociarlo a roles.

**RolesGraphQLFunction** — _Azure Function HTTP · API GraphQL /api/roles · Existente (se agrega publicación de eventos)_

- **Finalidad:** Administrar roles con consultas flexibles (RF03).
- **Funcionalidad:** Queries roles y rol(id); mutations crearRol, actualizarRol y eliminarRol. Publica Rol.Creado, Rol.Actualizado y Rol.Eliminado.
- **Ejemplo de uso:** Se desactiva el rol TEMPORAL_NAVIDAD con actualizarRol; el evento Rol.Actualizado hace que se recalculen los permisos de todos los usuarios que lo tenían.

**AsignacionesGraphQLFunction** — _Azure Function HTTP · API GraphQL /api/asignaciones · Existente (se agrega publicación de eventos)_

- **Finalidad:** Relacionar usuarios con roles y roles con permisos (RF04).
- **Funcionalidad:** Mutations asignarRolAUsuario, quitarRolAUsuario, asignarPermisoARol y quitarPermisoARol. Publica UsuarioRol.Asignado / Revocado y RolPermiso.Asignado / Revocado.
- **Ejemplo de uso:** Se asigna el rol SUPERVISOR a un cajero ascendido; el evento UsuarioRol.Asignado dispara, en paralelo, el recálculo de permisos, la auditoría y el correo de aviso.

**Oracle Autonomous Database** — _Base de datos relacional en la nube (OCI) · Existente_

- **Finalidad:** Ser la fuente de verdad transaccional del sistema.
- **Funcionalidad:** Almacena USUARIOS, ROLES, PERMISOS, USUARIOS_ROLES y ROLES_PERMISOS con sus restricciones (claves únicas, foráneas y checks de estado). Solo las funciones productoras escriben en ella vía JDBC.
- **Ejemplo de uso:** La restricción UQ_USUARIOS_CORREO impide crear dos cuentas con el mismo correo aunque dos administradores lo intenten a la vez.

### 5. Bus de eventos

**Azure Event Grid (Custom Topic evt-usuarios-roles)** — _Servicio de enrutamiento de eventos (publicación/suscripción) · Nueva_

- **Finalidad:** Desacoplar a los productores de los procesadores y consumidores: quien publica no sabe quién escucha.
- **Funcionalidad:** Recibe los eventos en formato CloudEvents 1.0 y los entrega a cada suscripción según filtros por tipo de evento. Reintenta automáticamente con espera exponencial (hasta 24 horas) y, si la entrega sigue fallando, deja el evento en el contenedor de dead-letter. Agregar un nuevo consumidor es solo crear otra suscripción, sin tocar las funciones existentes.
- **Ejemplo de uso:** Un evento UsuarioRol.Asignado se entrega a cuatro suscriptores a la vez. Si el servicio de correo está caído, Event Grid reintenta solo esa entrega; las demás ya se completaron.

**Ejemplo de evento publicado** (formato CloudEvents):

```json
{
  "specversion": "1.0",
  "type": "UsuarioRol.Asignado",
  "source": "/functions/asignaciones",
  "subject": "usuarios/152",
  "id": "9f1c2e7a-4b1d-4c61-9a0e-2f6b8d3c1a77",
  "time": "2026-10-01T14:32:05Z",
  "data": { "idUsuario": 152, "idRol": 3, "nombreRol": "SUPERVISOR", "realizadoPor": "admin.temuco@empresa.cl" }
}
```

### 6. Procesadores de eventos

Siguiendo la guía de la semana, los **procesadores** ejecutan lógica de negocio sobre el evento y **pueden generar nuevos eventos** (flechas punteadas de vuelta a Event Grid en el diagrama). Todos son Azure Functions con disparador EventGridTrigger, por lo que escalan solos según la cantidad de eventos.

**Procesador de permisos** — _Azure Function · EventGridTrigger · Nueva_

- **Finalidad:** Mantener actualizados los permisos efectivos de cada usuario (RF07).
- **Funcionalidad:** Escucha UsuarioRol.*, RolPermiso.*, Rol.Actualizado/Eliminado y Permiso.Actualizado/Eliminado. Consulta en Oracle (solo lectura) los roles del usuario y sus permisos, calcula la unión y la guarda en Redis. Luego publica Permisos.Recalculados.
- **Ejemplo de uso:** Al quitar el permiso ANULAR_VENTA del rol CAJERO, recalcula los permisos de los 1.200 cajeros; el punto de venta deja de mostrar ese botón en segundos.

**Procesador de desactivación** — _Azure Function · EventGridTrigger · Nueva_

- **Finalidad:** Cortar todos los accesos de una persona desvinculada (RF08).
- **Funcionalidad:** Escucha Usuario.Desactivado. Elimina de Redis los permisos efectivos del usuario, de modo que cualquier sistema que consulte su acceso reciba “sin permisos”, y publica Accesos.Revocados.
- **Ejemplo de uso:** RR.HH. desactiva a un colaborador a las 18:00; a las 18:00:02 ya no puede abrir caja en ninguna tienda.

**Procesador de seguridad** — _Azure Function · EventGridTrigger · Nueva_

- **Finalidad:** Detectar asignaciones riesgosas antes de que causen daño (RF10).
- **Funcionalidad:** Escucha UsuarioRol.Asignado y RolPermiso.Asignado. Evalúa reglas: si el rol es crítico (ADMIN) o si el historial en el Event Store muestra más de 5 cambios sobre el mismo usuario en 10 minutos, publica Alerta.Seguridad.
- **Ejemplo de uso:** Una cuenta comprometida empieza a asignarse roles; a la sexta asignación se genera la alerta y el equipo de seguridad la recibe por correo y en el portal.

### 7. Consumidores de eventos

Los **consumidores** reaccionan al evento con una acción directa y final (guardar, notificar, actualizar la interfaz) y, a diferencia de los procesadores, no generan nuevos eventos.

**Consumidor de auditoría** — _Azure Function · EventGridTrigger · Nueva_

- **Finalidad:** Dejar registro inmutable de todo lo que ocurre en el sistema (RF09).
- **Funcionalidad:** Está suscrito a todos los tipos de evento y guarda cada uno, sin modificarlo, en Cosmos DB. Usa el id del evento como clave, así un reintento de Event Grid no genera duplicados.
- **Ejemplo de uso:** Ante una auditoría interna se obtiene el historial completo del usuario 152: quién lo creó, qué roles tuvo, quién se los dio y cuándo.

**Consumidor de notificaciones** — _Azure Function · EventGridTrigger · Nueva_

- **Finalidad:** Informar a las personas de los cambios que las afectan (RF05, RF06).
- **Funcionalidad:** Escucha Usuario.Creado, UsuarioRol.Asignado/Revocado y Alerta.Seguridad. Arma el correo según una plantilla y lo envía con Azure Communication Services.
- **Ejemplo de uso:** Un nuevo cajero recibe su correo de bienvenida pocos segundos después de que la jefa de tienda lo registra.

**Consumidor de tiempo real** — _Azure Function · EventGridTrigger · Nueva_

- **Finalidad:** Mantener el portal sincronizado sin recargar la página (RF11).
- **Funcionalidad:** Escucha Permisos.Recalculados, Accesos.Revocados y Alerta.Seguridad y los envía mediante Azure SignalR Service a los administradores conectados.
- **Ejemplo de uso:** Mientras un administrador revisa la lista de usuarios, aparece una alerta roja con la asignación sospechosa recién detectada.

### 8. Almacenes y servicios de salida

**Azure Cache for Redis** — _Caché en memoria · Nueva_

- **Finalidad:** Responder en milisegundos “¿qué puede hacer este usuario?” sin consultar Oracle en cada operación.
- **Funcionalidad:** Guarda por usuario el conjunto de permisos efectivos (clave permisos:{idUsuario}). Solo lo escriben el procesador de permisos y el de desactivación.
- **Ejemplo de uso:** El punto de venta verifica si el cajero tiene ANULAR_VENTA con una lectura en Redis de menos de 5 ms.

**Azure Cosmos DB (Event Store)** — _Base NoSQL con partición por subject · Nueva_

- **Finalidad:** Almacenar el historial completo de eventos para auditoría, análisis y reglas de seguridad.
- **Funcionalidad:** Contenedor “eventos” particionado por subject (por ejemplo usuarios/152), de modo que el historial de un usuario se consulta en una sola partición. Los registros se escriben una vez y no se modifican.
- **Ejemplo de uso:** El procesador de seguridad consulta cuántos eventos UsuarioRol.Asignado tuvo usuarios/152 en los últimos 10 minutos.

**Azure Communication Services (Email)** — _Servicio administrado de correo · Nueva_

- **Finalidad:** Enviar correos transaccionales sin mantener un servidor de correo propio.
- **Funcionalidad:** Recibe del consumidor de notificaciones el destinatario, asunto y cuerpo, y entrega el correo con seguimiento de estado.
- **Ejemplo de uso:** Envío del correo “Se te asignó el rol SUPERVISOR en la tienda Temuco Centro”.

**Azure SignalR Service** — _Servicio administrado de WebSocket · Nueva_

- **Finalidad:** Empujar mensajes al navegador en tiempo real.
- **Funcionalidad:** Mantiene las conexiones abiertas de los portales y reenvía los mensajes que publica el consumidor de tiempo real, sin que las Functions tengan que manejar conexiones.
- **Ejemplo de uso:** Diez administradores conectados ven al mismo tiempo que un rol fue modificado.

### Servicios transversales

**Blob Storage (dead-letter)** — _Azure Storage · Nueva_

- **Finalidad:** No perder ningún evento aunque un suscriptor falle por mucho tiempo.
- **Funcionalidad:** Event Grid guarda aquí los eventos cuya entrega agotó los reintentos, para revisarlos y reprocesarlos.
- **Ejemplo de uso:** Si el servicio de correo falla un día completo, los avisos pendientes quedan en el contenedor y se reenvían cuando se corrige el problema.

**Azure Key Vault** — _Gestión de secretos · Nueva_

- **Finalidad:** Sacar las credenciales del código y de los archivos de configuración.
- **Funcionalidad:** Guarda la cadena de conexión y la wallet de Oracle, las claves de Event Grid, Redis y Cosmos DB. Las Functions y el BFF las leen con identidad administrada.
- **Ejemplo de uso:** Se rota la contraseña de Oracle sin volver a desplegar ninguna función.

**Application Insights + Azure Monitor** — _Observabilidad · Nueva_

- **Finalidad:** Saber qué está pasando y detectar errores en un sistema distribuido y asíncrono.
- **Funcionalidad:** Centraliza logs, métricas y trazas de BFF, Functions y Event Grid. Usa el id del evento como identificador de correlación para seguir un cambio de punta a punta, y dispara alertas (por ejemplo, eventos en dead-letter).
- **Ejemplo de uso:** Se sigue en una sola traza el recorrido de una asignación: petición HTTP, commit en Oracle, evento publicado y los cuatro suscriptores que lo procesaron.

**GitHub + GitHub Actions + Azure Container Registry** — _Repositorio colaborativo y CI/CD · GitHub existente; CI/CD y ACR nuevos_

- **Finalidad:** Trabajar en equipo de forma ordenada y desplegar de forma automática y repetible.
- **Funcionalidad:** El código vive en GitHub con ramas y pull requests. Al integrar en main, GitHub Actions compila con Maven, ejecuta pruebas, publica la imagen del BFF en ACR y despliega las Functions en Azure.
- **Ejemplo de uso:** Alberto abre un pull request con el procesador de seguridad, Carlos lo revisa y, al aprobarlo, la nueva función queda desplegada sin pasos manuales.

## 3. Eventos y justificación

### Catálogo de eventos

| Evento | Productor | Suscriptores |
|---|---|---|
| Usuario.Creado | UsuariosFunction | Auditoría, Notificaciones |
| Usuario.Actualizado | UsuariosFunction | Auditoría |
| Usuario.Desactivado | UsuariosFunction | Proc. desactivación, Auditoría, Notificaciones |
| Rol.Creado | RolesGraphQLFunction | Auditoría |
| Rol.Actualizado / Rol.Eliminado | RolesGraphQLFunction | Proc. permisos, Auditoría |
| Permiso.Creado | PermisosFunction | Auditoría |
| Permiso.Actualizado / Permiso.Eliminado | PermisosFunction | Proc. permisos, Auditoría |
| UsuarioRol.Asignado | AsignacionesGraphQLFunction | Proc. permisos, Proc. seguridad, Auditoría, Notificaciones |
| UsuarioRol.Revocado | AsignacionesGraphQLFunction | Proc. permisos, Auditoría, Notificaciones |
| RolPermiso.Asignado | AsignacionesGraphQLFunction | Proc. permisos, Proc. seguridad, Auditoría |
| RolPermiso.Revocado | AsignacionesGraphQLFunction | Proc. permisos, Auditoría |
| Permisos.Recalculados | Procesador de permisos | Tiempo real, Auditoría |
| Accesos.Revocados | Procesador de desactivación | Tiempo real, Auditoría |
| Alerta.Seguridad | Procesador de seguridad | Notificaciones, Tiempo real, Auditoría |

### Flujo de ejemplo: asignar el rol SUPERVISOR a un usuario

- El administrador ejecuta la mutation asignarRolAUsuario desde el portal. La petición pasa por API Management (valida el token) y el BFF, y llega a AsignacionesGraphQLFunction.
- La función inserta la fila en USUARIOS_ROLES, hace commit en Oracle, publica UsuarioRol.Asignado y responde al administrador. Aquí termina la parte síncrona.
- Event Grid entrega el evento en paralelo al procesador de permisos, al procesador de seguridad, al consumidor de auditoría y al consumidor de notificaciones.
- El procesador de permisos actualiza Redis y publica Permisos.Recalculados; el consumidor de tiempo real lo envía al portal por SignalR.
- El usuario recibe el correo de aviso y el evento queda guardado en Cosmos DB. Si alguno de estos pasos falla, Event Grid lo reintenta sin afectar a los demás.

### ¿Por qué una arquitectura orientada a eventos en este caso?

- **Escalabilidad:** un cambio de rol masivo (ej. 1.200 cajeros) se procesa con muchas instancias de la función en paralelo, sin bloquear la API.
- **Flexibilidad:** para conectar un sistema nuevo (por ejemplo, el control de acceso físico a bodegas) basta con agregar una suscripción a Event Grid, sin modificar las funciones existentes.
- **Resiliencia:** si el correo o Redis fallan, la gestión de usuarios sigue funcionando; Event Grid guarda y reintenta los eventos pendientes.
- **Reactividad:** los accesos de una persona desvinculada se cortan en segundos y las alertas de seguridad llegan en tiempo real.
- **Trazabilidad:** el Event Store guarda el historial completo de cambios que la empresa necesita para sus auditorías.