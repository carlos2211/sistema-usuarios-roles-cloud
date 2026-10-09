package cl.duoc.usuariosroles;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

import cl.duoc.usuariosroles.eventos.EventGridPublisher;
import cl.duoc.usuariosroles.eventos.EventoRecibido;
import cl.duoc.usuariosroles.eventos.TiposEvento;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Procesador de eventos: cuando se crea un usuario (Usuario.Creado) le
 * asigna automáticamente el rol por defecto y publica UsuarioRol.Asignado.
 *
 * El rol se configura por nombre en la variable ROL_POR_DEFECTO
 * (por defecto: USUARIO). Es idempotente: si el evento llega dos veces, la
 * segunda vez el usuario ya tiene el rol y no se publica nada.
 */
public class ProcesadorRolPorDefectoFunction {

    static final String ROL_POR_DEFECTO = "USUARIO";
    private static final int ORA_CLAVE_DUPLICADA = 1;
    private static final int ORA_LLAVE_PADRE_NO_EXISTE = 2291;

    @FunctionName("procesador-rol-por-defecto")
    public void ejecutar(
            @EventGridTrigger(name = "evento") String json,
            ExecutionContext context) throws SQLException {

        EventoRecibido evento = EventoRecibido.desdeJson(json);

        if (!TiposEvento.USUARIO_CREADO.equals(evento.getTipo())) {
            context.getLogger().warning("Evento no esperado: " + evento);
            return;
        }

        int idUsuario = evento.entero("idUsuario");
        String nombreRol = nombreRolPorDefecto(System.getenv("ROL_POR_DEFECTO"));

        Map<String, Object> asignacion;

        try (Connection connection = OracleConnection.getConnection()) {
            Integer idRol = buscarIdRol(connection, nombreRol);

            if (idRol == null) {
                context.getLogger().severe(
                        "No existe el rol por defecto " + nombreRol + "; el usuario "
                                + idUsuario + " quedó sin rol");
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO USUARIOS_ROLES (ID_USUARIO, ID_ROL) VALUES (?, ?)")) {
                statement.setInt(1, idUsuario);
                statement.setInt(2, idRol);
                statement.executeUpdate();

            } catch (SQLException error) {
                if (error.getErrorCode() == ORA_CLAVE_DUPLICADA) {
                    context.getLogger().info(
                            "El usuario " + idUsuario + " ya tenía el rol " + nombreRol + " (reintento)");
                    return;
                }

                if (error.getErrorCode() == ORA_LLAVE_PADRE_NO_EXISTE) {
                    context.getLogger().warning(
                            "El usuario " + idUsuario + " ya no existe; no se asigna rol");
                    return;
                }

                throw error;
            }

            asignacion = new LinkedHashMap<>();
            asignacion.put("idUsuario", idUsuario);
            asignacion.put("idRol", idRol);
            asignacion.put("nombreRol", nombreRol);
            asignacion.put("motivo", "ROL_POR_DEFECTO");
            asignacion.put("eventoOrigen", evento.getId());
        }

        context.getLogger().info("Rol por defecto " + nombreRol + " asignado al usuario " + idUsuario);

        EventGridPublisher.publicar(
                TiposEvento.USUARIO_ROL_ASIGNADO,
                TiposEvento.subjectUsuario(idUsuario),
                asignacion);
    }

    static String nombreRolPorDefecto(String configuracion) {
        return configuracion == null || configuracion.isBlank()
                ? ROL_POR_DEFECTO
                : configuracion.trim().toUpperCase(Locale.ROOT);
    }

    private static Integer buscarIdRol(Connection connection, String nombre) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ID_ROL FROM ROLES WHERE NOMBRE = ? AND ESTADO = 'ACTIVO'")) {
            statement.setString(1, nombre);

            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("ID_ROL") : null;
            }
        }
    }
}
