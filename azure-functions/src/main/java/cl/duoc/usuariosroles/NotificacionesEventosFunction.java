package cl.duoc.usuariosroles;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

import cl.duoc.usuariosroles.eventos.Directorio;
import cl.duoc.usuariosroles.eventos.EventoRecibido;
import cl.duoc.usuariosroles.eventos.TiposEvento;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Consumidor de eventos: arma el aviso que corresponde a cada cambio y lo
 * deja en la tabla NOTIFICACIONES (bandeja de salida de correos).
 *
 * Suscrito a: Usuario.Creado, Usuario.Desactivado, UsuarioRol.Asignado,
 * UsuarioRol.Revocado y Alerta.Seguridad.
 */
public class NotificacionesEventosFunction {

    private static final int ORA_CLAVE_DUPLICADA = 1;
    private static final String CORREO_SEGURIDAD_POR_DEFECTO = "seguridad@empresa.cl";

    /** Aviso a registrar. */
    static final class Notificacion {
        final String destinatario;
        final String asunto;
        final String mensaje;

        Notificacion(String destinatario, String asunto, String mensaje) {
            this.destinatario = destinatario;
            this.asunto = asunto;
            this.mensaje = mensaje;
        }
    }

    @FunctionName("notificaciones-eventos")
    public void ejecutar(
            @EventGridTrigger(name = "evento") String json,
            ExecutionContext context) throws SQLException {

        EventoRecibido evento = EventoRecibido.desdeJson(json);

        try (Connection connection = OracleConnection.getConnection()) {
            Notificacion notificacion = construir(
                    evento,
                    Directorio.oracle(connection),
                    correoSeguridad());

            if (notificacion == null) {
                context.getLogger().info("Evento sin notificación: " + evento);
                return;
            }

            String sql = "INSERT INTO NOTIFICACIONES " +
                    "(ID_EVENTO, TIPO_EVENTO, DESTINATARIO, ASUNTO, MENSAJE) " +
                    "VALUES (?, ?, ?, ?, ?)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, evento.getId());
                statement.setString(2, evento.getTipo());
                statement.setString(3, notificacion.destinatario);
                statement.setString(4, notificacion.asunto);
                statement.setString(5, notificacion.mensaje);
                statement.executeUpdate();
            }

            context.getLogger().info(
                    "Notificación para " + notificacion.destinatario + ": " + notificacion.asunto);

        } catch (SQLException error) {
            if (error.getErrorCode() == ORA_CLAVE_DUPLICADA) {
                context.getLogger().info("Notificación ya registrada (reintento): " + evento);
                return;
            }

            // Se relanza para que Event Grid reintente la entrega.
            throw error;
        }
    }

    /**
     * Arma el aviso para un evento, o null si el evento no requiere avisar.
     */
    static Notificacion construir(
            EventoRecibido evento,
            Directorio.Busqueda busqueda,
            String correoSeguridad) throws SQLException {

        switch (evento.getTipo()) {
            case TiposEvento.USUARIO_CREADO:
                return new Notificacion(
                        evento.texto("correo"),
                        "Bienvenido/a al Sistema de Gestión de Usuarios y Roles",
                        "Hola " + evento.texto("nombre") + ", tu cuenta " + evento.texto("correo")
                                + " fue creada. Un administrador te asignará los roles que necesitas.");

            case TiposEvento.USUARIO_DESACTIVADO:
                return new Notificacion(
                        evento.texto("correo"),
                        "Tu cuenta fue desactivada",
                        "Hola " + evento.texto("nombre") + ", tu cuenta fue desactivada y se revocaron"
                                + " todos tus roles. Si crees que es un error, contacta a tu jefatura.");

            case TiposEvento.USUARIO_ROL_ASIGNADO:
            case TiposEvento.USUARIO_ROL_REVOCADO:
                return avisoDeRol(evento, busqueda);

            case TiposEvento.ALERTA_SEGURIDAD:
                return new Notificacion(
                        correoSeguridad,
                        "[Alerta de seguridad] " + evento.texto("regla"),
                        evento.texto("detalle"));

            default:
                return null;
        }
    }

    private static Notificacion avisoDeRol(
            EventoRecibido evento,
            Directorio.Busqueda busqueda) throws SQLException {

        boolean asignado = TiposEvento.USUARIO_ROL_ASIGNADO.equals(evento.getTipo());

        // Al desactivar una cuenta el usuario ya recibe un único aviso;
        // no se le envía uno más por cada rol revocado.
        if (!asignado && "USUARIO_DESACTIVADO".equals(evento.texto("motivo"))) {
            return null;
        }

        Directorio.Persona persona = busqueda.usuario(evento.entero("idUsuario"));
        String rol = busqueda.nombreRol(evento.entero("idRol"));

        if (persona == null || rol == null) {
            return null;
        }

        return asignado
                ? new Notificacion(
                        persona.getCorreo(),
                        "Se te asignó el rol " + rol,
                        "Hola " + persona.getNombre() + ", desde ahora tienes el rol " + rol + ".")
                : new Notificacion(
                        persona.getCorreo(),
                        "Se te quitó el rol " + rol,
                        "Hola " + persona.getNombre() + ", ya no tienes el rol " + rol + ".");
    }

    private static String correoSeguridad() {
        String correo = System.getenv("SEGURIDAD_CORREO");
        return correo == null || correo.isBlank() ? CORREO_SEGURIDAD_POR_DEFECTO : correo.trim();
    }
}
