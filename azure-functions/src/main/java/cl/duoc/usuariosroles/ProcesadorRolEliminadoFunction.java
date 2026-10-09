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
 * Procesador de eventos: cuando se elimina un rol (Rol.Eliminado) actualiza
 * a los usuarios que lo tenían, quitándoles esa asignación en
 * USUARIOS_ROLES, y publica un UsuarioRol.Revocado por cada uno.
 *
 * USUARIOS_ROLES no tiene llave foránea con borrado en cascada hacia
 * ROLES: la limpieza es responsabilidad de este procesador (consistencia
 * eventual). Es idempotente: si el evento llega dos veces, la segunda vez
 * ya no quedan asignaciones y no se publica nada.
 */
public class ProcesadorRolEliminadoFunction {

    @FunctionName("procesador-rol-eliminado")
    public void ejecutar(
            @EventGridTrigger(name = "evento") String json,
            ExecutionContext context) throws SQLException {

        EventoRecibido evento = EventoRecibido.desdeJson(json);

        if (!TiposEvento.ROL_ELIMINADO.equals(evento.getTipo())) {
            context.getLogger().warning("Evento no esperado: " + evento);
            return;
        }

        int idRol = evento.entero("idRol");
        String nombreRol = evento.texto("nombre");
        List<Integer> usuarios = quitarRolAUsuarios(idRol);

        context.getLogger().info(
                "Rol " + idRol + " (" + nombreRol + ") eliminado; se quitó a los usuarios " + usuarios);

        List<Map<String, Object>> eventos = new ArrayList<>();

        for (Integer idUsuario : usuarios) {
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("idUsuario", idUsuario);
            datos.put("idRol", idRol);
            datos.put("nombreRol", nombreRol);
            datos.put("motivo", "ROL_ELIMINADO");
            datos.put("eventoOrigen", evento.getId());

            eventos.add(EventGridPublisher.crearEvento(
                    TiposEvento.USUARIO_ROL_REVOCADO,
                    TiposEvento.subjectUsuario(idUsuario),
                    datos));
        }

        EventGridPublisher.publicarTodos(eventos);
    }

    /**
     * Elimina en una sola transacción las asignaciones del rol y devuelve
     * los usuarios que lo tenían.
     */
    private List<Integer> quitarRolAUsuarios(int idRol) throws SQLException {
        String sqlUsuarios = "SELECT ID_USUARIO FROM USUARIOS_ROLES " +
                "WHERE ID_ROL = ? ORDER BY ID_USUARIO FOR UPDATE";
        String sqlEliminar = "DELETE FROM USUARIOS_ROLES WHERE ID_ROL = ?";

        List<Integer> usuarios = new ArrayList<>();

        try (Connection connection = OracleConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement statement = connection.prepareStatement(sqlUsuarios)) {
                    statement.setInt(1, idRol);

                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            usuarios.add(result.getInt("ID_USUARIO"));
                        }
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement(sqlEliminar)) {
                    statement.setInt(1, idRol);
                    statement.executeUpdate();
                }

                connection.commit();

            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }

        return usuarios;
    }
}
