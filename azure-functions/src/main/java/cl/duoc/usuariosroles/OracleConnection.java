package cl.duoc.usuariosroles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class OracleConnection {

    private OracleConnection() {
    }

    public static Connection getConnection() throws SQLException {
        String usuario = obtenerVariable("ORACLE_DB_USER");
        String contrasena = obtenerVariable("ORACLE_DB_PASSWORD");
        String alias = obtenerVariable("ORACLE_TNS_ALIAS");
        String walletPath = obtenerVariable("ORACLE_WALLET_PATH");

        System.setProperty("oracle.net.tns_admin", walletPath);

        String url = "jdbc:oracle:thin:@" + alias;

        return DriverManager.getConnection(
            url,
            usuario,
            contrasena
        );
    }

    private static String obtenerVariable(String nombre) {
        String valor = System.getenv(nombre);

        if (valor == null || valor.trim().isEmpty()) {
            throw new IllegalStateException(
                "Falta configurar la variable: " + nombre
            );
        }

        return valor.trim();
    }
}