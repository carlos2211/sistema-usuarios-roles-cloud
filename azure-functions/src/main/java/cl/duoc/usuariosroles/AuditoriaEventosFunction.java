package cl.duoc.usuariosroles;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

import cl.duoc.usuariosroles.eventos.EventoRecibido;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Consumidor de eventos: guarda cada evento del topic en la tabla
 * AUDITORIA_EVENTOS (event store), sin modificarlo.
 *
 * Está suscrito a todos los tipos de evento. Usa el id del evento como
 * clave primaria, así una entrega repetida de Event Grid no genera
 * duplicados.
 */
public class AuditoriaEventosFunction {

    private static final int ORA_CLAVE_DUPLICADA = 1;

    @FunctionName("auditoria-eventos")
    public void ejecutar(
            @EventGridTrigger(name = "evento") String json,
            ExecutionContext context) throws SQLException {

        EventoRecibido evento = EventoRecibido.desdeJson(json);

        String sql = "INSERT INTO AUDITORIA_EVENTOS " +
                "(ID_EVENTO, TIPO_EVENTO, SUBJECT, FECHA_EVENTO, DATOS) " +
                "VALUES (?, ?, ?, ?, ?)";

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, evento.getId());
            statement.setString(2, evento.getTipo());
            statement.setString(3, evento.getSubject());
            statement.setTimestamp(4, evento.getFechaComoTimestamp());
            statement.setString(5, evento.getDatosJson());
            statement.executeUpdate();

            context.getLogger().info("Evento auditado: " + evento);

        } catch (SQLException error) {
            if (error.getErrorCode() == ORA_CLAVE_DUPLICADA) {
                context.getLogger().info("Evento ya auditado (reintento): " + evento);
                return;
            }

            // Se relanza para que Event Grid reintente la entrega.
            throw error;
        }
    }
}
