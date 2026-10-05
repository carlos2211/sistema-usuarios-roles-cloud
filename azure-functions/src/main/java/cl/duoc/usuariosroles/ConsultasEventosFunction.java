package cl.duoc.usuariosroles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API REST de solo lectura sobre lo que registran los consumidores de
 * eventos:
 *
 * GET /api/auditoria?subject=usuarios/5&tipo=Usuario.Creado&limite=50
 * GET /api/notificaciones?destinatario=correo@empresa.cl&limite=50
 */
public class ConsultasEventosFunction {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int LIMITE_POR_DEFECTO = 50;
    private static final int LIMITE_MAXIMO = 200;

    @FunctionName("auditoria")
    public HttpResponseMessage auditoria(
            @HttpTrigger(name = "request", methods = {
                    HttpMethod.GET
            }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auditoria") HttpRequestMessage<Optional<String>> request,

            ExecutionContext context) {

        StringBuilder sql = new StringBuilder(
                "SELECT ID_EVENTO, TIPO_EVENTO, SUBJECT, FECHA_EVENTO, DATOS, FECHA_REGISTRO " +
                "FROM AUDITORIA_EVENTOS WHERE 1 = 1");
        List<String> parametros = new ArrayList<>();

        filtro(request, "subject", "SUBJECT", sql, parametros);
        filtro(request, "tipo", "TIPO_EVENTO", sql, parametros);
        sql.append(" ORDER BY FECHA_EVENTO DESC FETCH FIRST ? ROWS ONLY");

        return consultar(request, context, sql.toString(), parametros, result -> {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("idEvento", result.getString("ID_EVENTO"));
            fila.put("tipo", result.getString("TIPO_EVENTO"));
            fila.put("subject", result.getString("SUBJECT"));
            fila.put("fechaEvento", result.getTimestamp("FECHA_EVENTO").toString());
            String datos = result.getString("DATOS");
            fila.put("datos", datos != null ? MAPPER.readTree(datos) : null);
            fila.put("fechaRegistro", result.getTimestamp("FECHA_REGISTRO").toString());
            return fila;
        });
    }

    @FunctionName("notificaciones")
    public HttpResponseMessage notificaciones(
            @HttpTrigger(name = "request", methods = {
                    HttpMethod.GET
            }, authLevel = AuthorizationLevel.ANONYMOUS, route = "notificaciones") HttpRequestMessage<Optional<String>> request,

            ExecutionContext context) {

        StringBuilder sql = new StringBuilder(
                "SELECT ID_NOTIFICACION, ID_EVENTO, TIPO_EVENTO, DESTINATARIO, ASUNTO, " +
                "MENSAJE, FECHA_CREACION FROM NOTIFICACIONES WHERE 1 = 1");
        List<String> parametros = new ArrayList<>();

        filtro(request, "destinatario", "DESTINATARIO", sql, parametros);
        sql.append(" ORDER BY FECHA_CREACION DESC FETCH FIRST ? ROWS ONLY");

        return consultar(request, context, sql.toString(), parametros, result -> {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("idNotificacion", result.getInt("ID_NOTIFICACION"));
            fila.put("idEvento", result.getString("ID_EVENTO"));
            fila.put("tipoEvento", result.getString("TIPO_EVENTO"));
            fila.put("destinatario", result.getString("DESTINATARIO"));
            fila.put("asunto", result.getString("ASUNTO"));
            fila.put("mensaje", result.getString("MENSAJE"));
            fila.put("fechaCreacion", result.getTimestamp("FECHA_CREACION").toString());
            return fila;
        });
    }

    private interface MapeoFila {
        Map<String, Object> mapear(ResultSet result) throws Exception;
    }

    private void filtro(
            HttpRequestMessage<Optional<String>> request,
            String parametro,
            String columna,
            StringBuilder sql,
            List<String> parametros) {

        String valor = request.getQueryParameters().get(parametro);

        if (valor != null && !valor.isBlank()) {
            sql.append(" AND ").append(columna).append(" = ?");
            parametros.add(valor.trim());
        }
    }

    private HttpResponseMessage consultar(
            HttpRequestMessage<Optional<String>> request,
            ExecutionContext context,
            String sql,
            List<String> parametros,
            MapeoFila mapeo) {

        try {
            int limite = limite(request.getQueryParameters().get("limite"));
            List<Map<String, Object>> filas = new ArrayList<>();

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                int indice = 1;

                for (String valor : parametros) {
                    statement.setString(indice++, valor);
                }

                statement.setInt(indice, limite);

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        filas.add(mapeo.mapear(result));
                    }
                }
            }

            return respuesta(request, HttpStatus.OK, filas);

        } catch (IllegalArgumentException error) {
            return respuesta(request, HttpStatus.BAD_REQUEST, mensaje(error.getMessage()));

        } catch (Exception error) {
            context.getLogger().severe("Error consultando eventos: " + error.getMessage());

            return respuesta(
                    request,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    mensaje("Error al consultar Oracle"));
        }
    }

    private int limite(String valor) {
        if (valor == null || valor.isBlank()) {
            return LIMITE_POR_DEFECTO;
        }

        try {
            int limite = Integer.parseInt(valor.trim());

            if (limite <= 0 || limite > LIMITE_MAXIMO) {
                throw new NumberFormatException();
            }

            return limite;

        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "El límite debe ser un número entre 1 y " + LIMITE_MAXIMO);
        }
    }

    private Map<String, Object> mensaje(String texto) {
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("mensaje", texto);
        return resultado;
    }

    private HttpResponseMessage respuesta(
            HttpRequestMessage<Optional<String>> request,
            HttpStatus estado,
            Object contenido) {
        try {
            String json = MAPPER.writeValueAsString(contenido);

            return request
                    .createResponseBuilder(estado)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .build();

        } catch (Exception error) {
            return request
                    .createResponseBuilder(
                            HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .body(
                            "{\"mensaje\":\"Error generando la respuesta JSON\"}")
                    .build();
        }
    }
}
