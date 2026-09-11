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
 * Función serverless para gestionar las asignaciones de roles a usuarios
 * (USUARIOS_ROLES) y de permisos a roles (ROLES_PERMISOS), expuesta
 * mediante GraphQL (mismo patrón que {@link RolesGraphQLFunction}).
 */
public class AsignacionesGraphQLFunction {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GraphQL GRAPHQL = construirGraphQL();

    @FunctionName("asignaciones")
    public HttpResponseMessage ejecutar(
            @HttpTrigger(name = "request", methods = {
                    HttpMethod.POST
            }, authLevel = AuthorizationLevel.ANONYMOUS, route = "asignaciones") HttpRequestMessage<Optional<String>> request,

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
        try (InputStream recurso = AsignacionesGraphQLFunction.class
                .getClassLoader()
                .getResourceAsStream("schema-asignaciones.graphqls")) {

            SchemaParser parser = new SchemaParser();
            TypeDefinitionRegistry registro = parser.parse(
                    new InputStreamReader(recurso, StandardCharsets.UTF_8));

            RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                    .type("Query", builder -> builder
                            .dataFetcher("rolesDeUsuario", rolesDeUsuarioFetcher())
                            .dataFetcher("permisosDeRol", permisosDeRolFetcher()))
                    .type("Mutation", builder -> builder
                            .dataFetcher("asignarRolAUsuario", asignarRolAUsuarioFetcher())
                            .dataFetcher("quitarRolAUsuario", quitarRolAUsuarioFetcher())
                            .dataFetcher("asignarPermisoARol", asignarPermisoARolFetcher())
                            .dataFetcher("quitarPermisoARol", quitarPermisoARolFetcher()))
                    .build();

            GraphQLSchema schema = new SchemaGenerator()
                    .makeExecutableSchema(registro, wiring);

            return GraphQL.newGraphQL(schema).build();

        } catch (Exception error) {
            throw new IllegalStateException(
                    "No fue posible construir el esquema GraphQL", error);
        }
    }

    private static DataFetcher<List<Map<String, Object>>> rolesDeUsuarioFetcher() {
        return entorno -> {
            int idUsuario = entorno.getArgument("idUsuario");

            String sql = "SELECT R.ID_ROL, R.NOMBRE, R.DESCRIPCION, R.ESTADO, " +
                    "R.FECHA_CREACION FROM ROLES R " +
                    "JOIN USUARIOS_ROLES UR ON UR.ID_ROL = R.ID_ROL " +
                    "WHERE UR.ID_USUARIO = ? ORDER BY R.ID_ROL";

            List<Map<String, Object>> roles = new ArrayList<>();

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, idUsuario);

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Map<String, Object> rol = new LinkedHashMap<>();
                        rol.put("idRol", result.getInt("ID_ROL"));
                        rol.put("nombre", result.getString("NOMBRE"));
                        rol.put("descripcion", result.getString("DESCRIPCION"));
                        rol.put("estado", result.getString("ESTADO"));
                        rol.put("fechaCreacion", result.getTimestamp("FECHA_CREACION").toString());
                        roles.add(rol);
                    }
                }
            }

            return roles;
        };
    }

    private static DataFetcher<List<Map<String, Object>>> permisosDeRolFetcher() {
        return entorno -> {
            int idRol = entorno.getArgument("idRol");

            String sql = "SELECT P.ID_PERMISO, P.NOMBRE, P.DESCRIPCION, P.ESTADO, " +
                    "P.FECHA_CREACION FROM PERMISOS P " +
                    "JOIN ROLES_PERMISOS RP ON RP.ID_PERMISO = P.ID_PERMISO " +
                    "WHERE RP.ID_ROL = ? ORDER BY P.ID_PERMISO";

            List<Map<String, Object>> permisos = new ArrayList<>();

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, idRol);

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Map<String, Object> permiso = new LinkedHashMap<>();
                        permiso.put("idPermiso", result.getInt("ID_PERMISO"));
                        permiso.put("nombre", result.getString("NOMBRE"));
                        permiso.put("descripcion", result.getString("DESCRIPCION"));
                        permiso.put("estado", result.getString("ESTADO"));
                        permiso.put("fechaCreacion", result.getTimestamp("FECHA_CREACION").toString());
                        permisos.add(permiso);
                    }
                }
            }

            return permisos;
        };
    }

    private static DataFetcher<Map<String, Object>> asignarRolAUsuarioFetcher() {
        return entorno -> {
            int idUsuario = entorno.getArgument("idUsuario");
            int idRol = entorno.getArgument("idRol");

            String sql = "INSERT INTO USUARIOS_ROLES (ID_USUARIO, ID_ROL) VALUES (?, ?)";

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, idUsuario);
                statement.setInt(2, idRol);
                statement.executeUpdate();

            } catch (SQLException error) {
                throw traducirErrorAsignacion(error, "El usuario ya tiene asignado ese rol");
            }

            return obtenerAsignacionUsuarioRol(idUsuario, idRol);
        };
    }

    private static DataFetcher<Boolean> quitarRolAUsuarioFetcher() {
        return entorno -> {
            int idUsuario = entorno.getArgument("idUsuario");
            int idRol = entorno.getArgument("idRol");

            String sql = "DELETE FROM USUARIOS_ROLES WHERE ID_USUARIO = ? AND ID_ROL = ?";

            int filas;

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, idUsuario);
                statement.setInt(2, idRol);
                filas = statement.executeUpdate();
            }

            return filas > 0;
        };
    }

    private static DataFetcher<Map<String, Object>> asignarPermisoARolFetcher() {
        return entorno -> {
            int idRol = entorno.getArgument("idRol");
            int idPermiso = entorno.getArgument("idPermiso");

            String sql = "INSERT INTO ROLES_PERMISOS (ID_ROL, ID_PERMISO) VALUES (?, ?)";

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, idRol);
                statement.setInt(2, idPermiso);
                statement.executeUpdate();

            } catch (SQLException error) {
                throw traducirErrorAsignacion(error, "El rol ya tiene asignado ese permiso");
            }

            return obtenerAsignacionRolPermiso(idRol, idPermiso);
        };
    }

    private static DataFetcher<Boolean> quitarPermisoARolFetcher() {
        return entorno -> {
            int idRol = entorno.getArgument("idRol");
            int idPermiso = entorno.getArgument("idPermiso");

            String sql = "DELETE FROM ROLES_PERMISOS WHERE ID_ROL = ? AND ID_PERMISO = ?";

            int filas;

            try (
                    Connection connection = OracleConnection.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, idRol);
                statement.setInt(2, idPermiso);
                filas = statement.executeUpdate();
            }

            return filas > 0;
        };
    }

    private static Map<String, Object> obtenerAsignacionUsuarioRol(
            int idUsuario, int idRol) throws SQLException {

        String sql = "SELECT ID_USUARIO, ID_ROL, FECHA_ASIGNACION " +
                "FROM USUARIOS_ROLES WHERE ID_USUARIO = ? AND ID_ROL = ?";

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);
            statement.setInt(2, idRol);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }

                Map<String, Object> asignacion = new LinkedHashMap<>();
                asignacion.put("idUsuario", result.getInt("ID_USUARIO"));
                asignacion.put("idRol", result.getInt("ID_ROL"));
                asignacion.put(
                        "fechaAsignacion",
                        result.getTimestamp("FECHA_ASIGNACION").toString());
                return asignacion;
            }
        }
    }

    private static Map<String, Object> obtenerAsignacionRolPermiso(
            int idRol, int idPermiso) throws SQLException {

        String sql = "SELECT ID_ROL, ID_PERMISO, FECHA_ASIGNACION " +
                "FROM ROLES_PERMISOS WHERE ID_ROL = ? AND ID_PERMISO = ?";

        try (
                Connection connection = OracleConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idRol);
            statement.setInt(2, idPermiso);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }

                Map<String, Object> asignacion = new LinkedHashMap<>();
                asignacion.put("idRol", result.getInt("ID_ROL"));
                asignacion.put("idPermiso", result.getInt("ID_PERMISO"));
                asignacion.put(
                        "fechaAsignacion",
                        result.getTimestamp("FECHA_ASIGNACION").toString());
                return asignacion;
            }
        }
    }

    private static RuntimeException traducirErrorAsignacion(
            SQLException error, String mensajeDuplicado) {

        if (error.getErrorCode() == 1) {
            return new IllegalArgumentException(mensajeDuplicado);
        }

        if (error.getErrorCode() == 2291) {
            return new IllegalArgumentException(
                    "El usuario, rol o permiso indicado no existe");
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
