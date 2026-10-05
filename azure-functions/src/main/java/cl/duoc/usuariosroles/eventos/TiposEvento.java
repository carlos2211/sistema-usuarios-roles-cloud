package cl.duoc.usuariosroles.eventos;

/**
 * Catálogo de tipos de evento que circulan por el topic de Event Grid.
 * Las suscripciones filtran por estos mismos valores
 * (ver infra/event-grid/configurar-event-grid.sh).
 */
public final class TiposEvento {

    public static final String USUARIO_CREADO = "Usuario.Creado";
    public static final String USUARIO_ACTUALIZADO = "Usuario.Actualizado";
    public static final String USUARIO_DESACTIVADO = "Usuario.Desactivado";
    public static final String USUARIO_ELIMINADO = "Usuario.Eliminado";

    public static final String ROL_CREADO = "Rol.Creado";
    public static final String ROL_ACTUALIZADO = "Rol.Actualizado";
    public static final String ROL_ELIMINADO = "Rol.Eliminado";

    public static final String PERMISO_CREADO = "Permiso.Creado";
    public static final String PERMISO_ACTUALIZADO = "Permiso.Actualizado";
    public static final String PERMISO_ELIMINADO = "Permiso.Eliminado";

    public static final String USUARIO_ROL_ASIGNADO = "UsuarioRol.Asignado";
    public static final String USUARIO_ROL_REVOCADO = "UsuarioRol.Revocado";
    public static final String ROL_PERMISO_ASIGNADO = "RolPermiso.Asignado";
    public static final String ROL_PERMISO_REVOCADO = "RolPermiso.Revocado";

    public static final String ALERTA_SEGURIDAD = "Alerta.Seguridad";

    private TiposEvento() {
    }

    public static String subjectUsuario(int idUsuario) {
        return "usuarios/" + idUsuario;
    }

    public static String subjectRol(int idRol) {
        return "roles/" + idRol;
    }

    public static String subjectPermiso(int idPermiso) {
        return "permisos/" + idPermiso;
    }
}
