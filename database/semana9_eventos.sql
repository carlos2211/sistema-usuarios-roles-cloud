-- =====================================================================
-- Semana 9 · Evaluación final
-- Ejecutar en Oracle después de usuarios_roles.sql y semana8_eventos.sql
-- =====================================================================

-- Requisito: "Cuando se elimine un rol, automáticamente se deberá
-- actualizar a los usuarios pertenecientes a ese rol y quitárselo".
--
-- Hasta la semana 8, FK_UR_ROL tenía ON DELETE CASCADE: Oracle borraba
-- las asignaciones al eliminar el rol, sin pasar por ningún evento.
-- Desde ahora lo hace la función "procesador-rol-eliminado" al recibir el
-- evento Rol.Eliminado de Azure Event Grid (consistencia eventual).
--
-- Mientras el evento se procesa (unos segundos), USUARIOS_ROLES puede
-- tener filas de un rol ya eliminado. No se ven en el sistema porque las
-- consultas de roles de un usuario hacen JOIN con ROLES. La existencia
-- del rol al asignarlo la valida la función de asignaciones.
ALTER TABLE USUARIOS_ROLES DROP CONSTRAINT FK_UR_ROL;

-- El procesador busca las asignaciones por rol; la PK (ID_USUARIO, ID_ROL)
-- no sirve para buscar solo por ID_ROL.
CREATE INDEX IX_USUARIOS_ROLES_ROL
    ON USUARIOS_ROLES (ID_ROL);

COMMIT;
