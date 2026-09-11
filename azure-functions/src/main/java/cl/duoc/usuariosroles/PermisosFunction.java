package cl.duoc.usuariosroles;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Función serverless para la administración de permisos, expuesta mediante
 * una API REST (mismo patrón que {@link UsuariosFunction}).
 */
public class PermisosFunction {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @FunctionName("permisos")
    public HttpResponseMessage ejecutar(
            @HttpTrigger(name = "request", methods = {
                    HttpMethod.GET,
                    HttpMethod.POST,
                    HttpMethod.PUT,
                    HttpMethod.DELETE
            }, authLevel = AuthorizationLevel.ANONYMOUS, route = "permisos/{id?}") HttpRequestMessage<Optional<String>> request,

            ExecutionContext context) {
        String id = obtenerIdDesdeRuta(request);

        try {
            switch (request.getHttpMethod()) {
                case GET:
                    return id == null || id.isBlank()
                            ? listar(request)
                            : obtener(request, convertirId(id));

                case POST:
                    return crear(request);

                case PUT:
                    return actualizar(
                            request,
                            convertirIdObligatorio(id));

                case DELETE:
                    return eliminar(
                            request,
                            convertirIdObligatorio(id));

                default:
                    return respuesta(
                            request,
                            HttpStatus.METHOD_NOT_ALLOWED,
                            mensaje("Método no permitido"));
            }

        } catch (IllegalArgumentException error) {
            return respuesta(
                    request,
                    HttpStatus.BAD_REQUEST,
                    mensaje(error.getMessage()));

        } catch (SQLException error) {
            context.getLogger().severe(
                    "Error Oracle: " + error.getMessage());

            if (error.getErrorCode() == 1) {
                return respuesta(
                        request,
                        HttpStatus.CONFLICT,
                        mensaje("Ya existe un permiso con ese nombre"));
            }

            if (error.getErrorCode() == 2290) {
                return respuesta(
                        request,
                        HttpStatus.BAD_REQUEST,
                        mensaje("El estado debe ser ACTIVO o INACTIVO"));
            }

            return respuesta(
                    request,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    mensaje("Error al realizar la operación en Oracle"));

        } catch (Exception error) {
            context.getLogger().severe(
                    "Error interno: " + error.getMessage());

            return respuesta(
                    request,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    mensaje("Error interno del servidor"));
        }
    }

    private HttpResponseMessage listar(
            HttpRequestMessage<Optional<String>> request) throws Exception {

        String sql = "SELECT ID_PERMISO, NOMBRE, DESCRIPCION, ESTADO, " +
                "FECHA_CREACION FROM PERMISOS ORDER BY ID_PERMISO";

        List<Map<String, Object>> permisos = new ArrayList<>();

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                permisos.add(mapearPermiso(result));
            }
        }

        return respuesta(request, HttpStatus.OK, permisos);
    }

    private HttpResponseMessage obtener(
            HttpRequestMessage<Optional<String>> request,
            int id) throws Exception {

        String sql = "SELECT ID_PERMISO, NOMBRE, DESCRIPCION, ESTADO, " +
                "FECHA_CREACION FROM PERMISOS WHERE ID_PERMISO = ?";

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return respuesta(
                            request,
                            HttpStatus.NOT_FOUND,
                            mensaje("Permiso no encontrado"));
                }

                return respuesta(
                        request,
                        HttpStatus.OK,
                        mapearPermiso(result));
            }
        }
    }

    private HttpResponseMessage crear(
            HttpRequestMessage<Optional<String>> request) throws Exception {

        JsonNode body = leerBody(request);

        String nombre = requerido(body, "nombre").toUpperCase();

        String descripcion = body.hasNonNull("descripcion")
                ? body.get("descripcion").asText().trim()
                : null;

        String estado = body.hasNonNull("estado")
                ? body.get("estado").asText().trim().toUpperCase()
                : "ACTIVO";

        validarEstado(estado);

        String sql = "INSERT INTO PERMISOS (NOMBRE, DESCRIPCION, ESTADO) " +
                "VALUES (?, ?, ?)";

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nombre);
            statement.setString(2, descripcion);
            statement.setString(3, estado);
            statement.executeUpdate();
        }

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("mensaje", "Permiso creado correctamente");
        resultado.put("nombre", nombre);

        return respuesta(
                request,
                HttpStatus.CREATED,
                resultado);
    }

    private HttpResponseMessage actualizar(
            HttpRequestMessage<Optional<String>> request,
            int id) throws Exception {

        JsonNode body = leerBody(request);

        String nombre = requerido(body, "nombre").toUpperCase();

        String descripcion = body.hasNonNull("descripcion")
                ? body.get("descripcion").asText().trim()
                : null;

        String estado = requerido(body, "estado").toUpperCase();

        validarEstado(estado);

        String sql = "UPDATE PERMISOS SET NOMBRE = ?, DESCRIPCION = ?, " +
                "ESTADO = ? WHERE ID_PERMISO = ?";

        int filas;

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nombre);
            statement.setString(2, descripcion);
            statement.setString(3, estado);
            statement.setInt(4, id);

            filas = statement.executeUpdate();
        }

        if (filas == 0) {
            return respuesta(
                    request,
                    HttpStatus.NOT_FOUND,
                    mensaje("Permiso no encontrado"));
        }

        return respuesta(
                request,
                HttpStatus.OK,
                mensaje("Permiso actualizado correctamente"));
    }

    private HttpResponseMessage eliminar(
            HttpRequestMessage<Optional<String>> request,
            int id) throws Exception {

        String sql = "DELETE FROM PERMISOS WHERE ID_PERMISO = ?";

        int filas;

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            filas = statement.executeUpdate();
        }

        if (filas == 0) {
            return respuesta(
                    request,
                    HttpStatus.NOT_FOUND,
                    mensaje("Permiso no encontrado"));
        }

        return respuesta(
                request,
                HttpStatus.OK,
                mensaje("Permiso eliminado correctamente"));
    }

    private Map<String, Object> mapearPermiso(
            ResultSet result) throws SQLException {

        Map<String, Object> permiso = new LinkedHashMap<>();

        permiso.put("idPermiso", result.getInt("ID_PERMISO"));
        permiso.put("nombre", result.getString("NOMBRE"));
        permiso.put("descripcion", result.getString("DESCRIPCION"));
        permiso.put("estado", result.getString("ESTADO"));
        permiso.put(
                "fechaCreacion",
                result.getTimestamp("FECHA_CREACION").toString());

        return permiso;
    }

    private JsonNode leerBody(
            HttpRequestMessage<Optional<String>> request) throws Exception {

        String contenido = request
                .getBody()
                .orElse("")
                .trim();

        if (contenido.isEmpty()) {
            throw new IllegalArgumentException(
                    "El cuerpo JSON es obligatorio");
        }

        return MAPPER.readTree(contenido);
    }

    private String requerido(
            JsonNode body,
            String campo) {
        if (!body.hasNonNull(campo) ||
                body.get(campo).asText().trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "El campo '" + campo + "' es obligatorio");
        }

        return body.get(campo).asText().trim();
    }

    private void validarEstado(String estado) {
        if (!"ACTIVO".equals(estado) &&
                !"INACTIVO".equals(estado)) {
            throw new IllegalArgumentException(
                    "El estado debe ser ACTIVO o INACTIVO");
        }
    }

    private String obtenerIdDesdeRuta(
            HttpRequestMessage<Optional<String>> request) {
        String path = request.getUri().getPath();
        String[] partes = path.split("/");

        for (int i = 0; i < partes.length; i++) {
            if ("permisos".equalsIgnoreCase(partes[i])) {
                if (i + 1 < partes.length &&
                        !partes[i + 1].isBlank()) {
                    return partes[i + 1];
                }

                return null;
            }
        }

        return null;
    }

    private int convertirId(String id) {
        try {
            int valor = Integer.parseInt(id);

            if (valor <= 0) {
                throw new NumberFormatException();
            }

            return valor;

        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "El ID debe ser un número positivo");
        }
    }

    private int convertirIdObligatorio(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Debe indicar el ID del permiso en la URL");
        }

        return convertirId(id);
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
