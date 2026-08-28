package cl.duoc.usuariosroles;

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
import java.util.Optional;

public class DiagnosticoFunction {

    @FunctionName("probar-conexion-oracle")
    public HttpResponseMessage diagnosticar(
            @HttpTrigger(name = "request", methods = {
                    HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "diagnostico/oracle") HttpRequestMessage<Optional<String>> request,
            ExecutionContext context) {

        String sql = "SELECT CURRENT_TIMESTAMP AS FECHA_SERVIDOR FROM DUAL";

        try (
                Connection conexion = OracleConnection.getConnection();
                PreparedStatement sentencia = conexion.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();

            String fechaServidor = resultado.getString("FECHA_SERVIDOR");

            String respuesta = String.format(
                    "{\"conexion\":\"OK\","
                            + "\"baseDatos\":\"usuariosroles\","
                            + "\"fechaServidor\":\"%s\"}",
                    fechaServidor);

            return request
                    .createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(respuesta)
                    .build();

        } catch (Exception error) {
            context.getLogger().severe(
                    "Error conectando con Oracle: "
                            + error.getMessage());

            String mensajeOriginal = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();

            String mensajeSeguro = mensajeOriginal
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", " ")
                    .replace("\n", " ");

            String respuesta = String.format(
                    "{\"conexion\":\"ERROR\","
                            + "\"mensaje\":\"%s\"}",
                    mensajeSeguro);

            return request
                    .createResponseBuilder(
                            HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .body(respuesta)
                    .build();
        }
    }
}