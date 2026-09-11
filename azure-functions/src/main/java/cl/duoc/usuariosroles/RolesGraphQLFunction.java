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

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * Función serverless para la administración de roles, expuesta mediante
 * GraphQL (a diferencia de {@link UsuariosFunction}, que expone una API
 * REST). Un único endpoint HTTP POST recibe consultas/mutaciones GraphQL
 * en el cuerpo de la petición.
 */
public class RolesGraphQLFunction {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GraphQL GRAPHQL = construirGraphQL();

    @FunctionName("roles")
    public HttpResponseMessage ejecutar(
            @HttpTrigger(name = "request", methods = {
                    HttpMethod.POST
            }, authLevel = AuthorizationLevel.ANONYMOUS, route = "roles") HttpRequestMessage<Optional<String>> request,

            ExecutionContext context) {

        try {
            JsonNode body = leerBody(request);

            String query = body.hasNonNull("query")
                    ? body.get("query").asText()
                    : null;

            if (query == null || query.isBlank()) {
                return respuesta(
                        request,
                        HttpStatus.BAD_REQUEST,
                        mensaje("El campo 'query' es obligatorio"));
            }

            Map<String, Object> variables = new LinkedHashMap<>();
            if (body.hasNonNull("variables") && body.get("variables").isObject()) {
                variables = MAPPER.convertValue(body.get("variables"), Map.class);
            }

            ExecutionInput entrada = ExecutionInput.newExecutionInput()
                    .query(query)
                    .variables(variables)
                    .build();

            ExecutionResult resultado = GRAPHQL.execute(entrada);

            return respuesta(
                    request,
                    HttpStatus.OK,
                    resultado.toSpecification());

        } catch (IllegalArgumentException error) {
            return respuesta(
                    request,
                    HttpStatus.BAD_REQUEST,
                    mensaje(error.getMessage()));

        } catch (Exception error) {
            context.getLogger().severe(
                    "Error interno: " + error.getMessage());

            return respuesta(
                    request,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    mensaje("Error interno del servidor"));
        }
    }

    private static GraphQL construirGraphQL() {
        try (InputStream recurso = RolesGraphQLFunction.class
                .getClassLoader()
                .getResourceAsStream("schema-roles.graphqls")) {

            SchemaParser parser = new SchemaParser();
            TypeDefinitionRegistry registro = parser.parse(
                    new InputStreamReader(recurso, StandardCharsets.UTF_8));

            RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                    .type("Query", builder -> builder
                            .dataFetcher("roles", listarRolesFetcher())
                            .dataFetcher("rol", obtenerRolFetcher()))
                    .type("Mutation", builder -> builder
                            .dataFetcher("crearRol", crearRolFetcher())
                            .dataFetcher("actualizarRol", actualizarRolFetcher())
                            .dataFetcher("eliminarRol", eliminarRolFetcher()))
                    .build();

            GraphQLSchema schema = new SchemaGenerator()
                    .makeExecutableSchema(registro, wiring);

            return GraphQL.newGraphQL(schema).build();

        } catch (Exception error) {
            throw new IllegalStateException(
                    "No fue posible construir el esquema GraphQL", error);
        }
    }

    private static DataFetcher<List<Map<String, Object>>> listarRolesFetcher() {
        return entorno -> {
            String sql = "SELECT ID_ROL, NOMBRE, DESCRIPCION, ESTADO, " +
                    "FECHA_CREACION FROM ROLES ORDER BY ID_ROL";

            List<Map<String, Object>> roles = new ArrayList<>();

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    roles.add(mapearRol(result));
                }
            }

            return roles;
        };
    }

    private static DataFetcher<Map<String, Object>> obtenerRolFetcher() {
        return entorno -> {
            int id = entorno.getArgument("id");
            return obtenerPorId(id);
        };
    }

    private static DataFetcher<Map<String, Object>> crearRolFetcher() {
        return entorno -> {
            String nombre = entorno.<String>getArgument("nombre").trim().toUpperCase();
            String descripcion = entorno.getArgument("descripcion");
            String estado = Optional
                    .ofNullable((String) entorno.getArgument("estado"))
                    .map(valor -> valor.trim().toUpperCase())
                    .orElse("ACTIVO");

            validarEstado(estado);

            String sql = "INSERT INTO ROLES (NOMBRE, DESCRIPCION, ESTADO) " +
                    "VALUES (?, ?, ?)";

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, nombre);
                statement.setString(2, descripcion);
                statement.setString(3, estado);
                statement.executeUpdate();

            } catch (SQLException error) {
                throw traducirError(error, "Ya existe un rol con ese nombre");
            }

            return obtenerPorNombre(nombre);
        };
    }

    private static DataFetcher<Map<String, Object>> actualizarRolFetcher() {
        return entorno -> {
            int id = entorno.getArgument("id");
            String nombre = entorno.<String>getArgument("nombre").trim().toUpperCase();
            String descripcion = entorno.getArgument("descripcion");
            String estado = entorno.<String>getArgument("estado").trim().toUpperCase();

            validarEstado(estado);

            String sql = "UPDATE ROLES SET NOMBRE = ?, DESCRIPCION = ?, " +
                    "ESTADO = ? WHERE ID_ROL = ?";

            int filas;

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, nombre);
                statement.setString(2, descripcion);
                statement.setString(3, estado);
                statement.setInt(4, id);

                filas = statement.executeUpdate();

            } catch (SQLException error) {
                throw traducirError(error, "Ya existe un rol con ese nombre");
            }

            if (filas == 0) {
                throw new IllegalArgumentException("Rol no encontrado");
            }

            return obtenerPorId(id);
        };
    }

    private static DataFetcher<Boolean> eliminarRolFetcher() {
        return entorno -> {
            int id = entorno.getArgument("id");

            String sql = "DELETE FROM ROLES WHERE ID_ROL = ?";

            int filas;

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, id);
                filas = statement.executeUpdate();
            }

            return filas > 0;
        };
    }

    private static Map<String, Object> obtenerPorId(int id) throws SQLException {
        String sql = "SELECT ID_ROL, NOMBRE, DESCRIPCION, ESTADO, " +
                "FECHA_CREACION FROM ROLES WHERE ID_ROL = ?";

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);

            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapearRol(result) : null;
            }
        }
    }

    private static Map<String, Object> obtenerPorNombre(String nombre) throws SQLException {
        String sql = "SELECT ID_ROL, NOMBRE, DESCRIPCION, ESTADO, " +
                "FECHA_CREACION FROM ROLES WHERE NOMBRE = ?";

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nombre);

            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapearRol(result) : null;
            }
        }
    }

    private static Map<String, Object> mapearRol(ResultSet result) throws SQLException {
        Map<String, Object> rol = new LinkedHashMap<>();

        rol.put("idRol", result.getInt("ID_ROL"));
        rol.put("nombre", result.getString("NOMBRE"));
        rol.put("descripcion", result.getString("DESCRIPCION"));
        rol.put("estado", result.getString("ESTADO"));
        rol.put("fechaCreacion", result.getTimestamp("FECHA_CREACION").toString());

        return rol;
    }

    private static void validarEstado(String estado) {
        if (!"ACTIVO".equals(estado) && !"INACTIVO".equals(estado)) {
            throw new IllegalArgumentException(
                    "El estado debe ser ACTIVO o INACTIVO");
        }
    }

    private static RuntimeException traducirError(SQLException error, String mensajeDuplicado) {
        if (error.getErrorCode() == 1) {
            return new IllegalArgumentException(mensajeDuplicado);
        }

        if (error.getErrorCode() == 2290) {
            return new IllegalArgumentException(
                    "El estado debe ser ACTIVO o INACTIVO");
        }

        return new IllegalStateException(
                "Error al realizar la operación en Oracle", error);
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
