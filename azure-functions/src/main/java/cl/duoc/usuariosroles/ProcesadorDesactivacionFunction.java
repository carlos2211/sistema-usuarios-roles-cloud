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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Procesador de eventos: cuando un usuario pasa a INACTIVO
 * (Usuario.Desactivado) le revoca todos sus roles en Oracle y publica un
 * UsuarioRol.Revocado por cada uno, que a su vez reciben auditoría y
 * notificaciones.
 *
 * Es idempotente: si el evento llega dos veces, la segunda vez el usuario
 * ya no tiene roles y no se publica nada.
 */
public class ProcesadorDesactivacionFunction {

    @FunctionName("procesador-desactivacion")
    public void ejecutar(
            @EventGridTrigger(name = "evento") String json,
            ExecutionContext context) throws SQLException {

        EventoRecibido evento = EventoRecibido.desdeJson(json);

        if (!TiposEvento.USUARIO_DESACTIVADO.equals(evento.getTipo())) {
            context.getLogger().warning("Evento no esperado: " + evento);
            return;
        }

        int idUsuario = evento.entero("idUsuario");
        List<Integer> rolesRevocados = revocarRoles(idUsuario);

        context.getLogger().info(
                "Usuario " + idUsuario + " desactivado; roles revocados: " + rolesRevocados);

        List<Map<String, Object>> eventos = new ArrayList<>();

        for (Integer idRol : rolesRevocados) {
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("idUsuario", idUsuario);
            datos.put("idRol", idRol);
            datos.put("motivo", "USUARIO_DESACTIVADO");
            datos.put("eventoOrigen", evento.getId());

            eventos.add(EventGridPublisher.crearEvento(
                    TiposEvento.USUARIO_ROL_REVOCADO,
                    TiposEvento.subjectUsuario(idUsuario),
                    datos));
        }

        EventGridPublisher.publicarTodos(eventos);
    }

    /**
     * Elimina en una sola transacción todas las asignaciones del usuario y
     * devuelve los roles que tenía.
     */
    private List<Integer> revocarRoles(int idUsuario) throws SQLException {
        String sqlRoles = "SELECT ID_ROL FROM USUARIOS_ROLES " +
                "WHERE ID_USUARIO = ? ORDER BY ID_ROL FOR UPDATE";
        String sqlEliminar = "DELETE FROM USUARIOS_ROLES WHERE ID_USUARIO = ?";

        List<Integer> roles = new ArrayList<>();

        try (Connection connection = OracleConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement statement = connection.prepareStatement(sqlRoles)) {
                    statement.setInt(1, idUsuario);

                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            roles.add(result.getInt("ID_ROL"));
                        }
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement(sqlEliminar)) {
                    statement.setInt(1, idUsuario);
                    statement.executeUpdate();
                }

                connection.commit();

            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }

        return roles;
    }
}
