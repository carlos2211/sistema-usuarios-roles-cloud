package cl.duoc.usuariosroles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class OracleConnection {

    /**
     * Archivos de la wallet de Oracle, empaquetados como recursos del JAR
     * (src/main/resources/wallet). Se extraen a un directorio temporal en
     * cada arranque en frío porque el sistema de archivos de la Function
     * App es de solo lectura, salvo /tmp.
     */
    private static final String[] ARCHIVOS_WALLET = {
        "cwallet.sso",
        "ewallet.p12",
        "ewallet.pem",
        "keystore.jks",
        "ojdbc.properties",
        "sqlnet.ora",
        "tnsnames.ora",
        "truststore.jks"
    };

    private static volatile String directorioWallet;

    private OracleConnection() {
    }

    public static Connection getConnection() throws SQLException {
        String usuario = obtenerVariable("ORACLE_DB_USER");
        String contrasena = obtenerVariable("ORACLE_DB_PASSWORD");
        String alias = obtenerVariable("ORACLE_TNS_ALIAS");

        System.setProperty(
            "oracle.net.tns_admin",
            obtenerDirectorioWallet());

        String url = "jdbc:oracle:thin:@" + alias;

        return DriverManager.getConnection(
            url,
            usuario,
            contrasena
        );
    }

    private static synchronized String obtenerDirectorioWallet() {
        if (directorioWallet != null) {
            return directorioWallet;
        }

        try {
            Path destino = Files.createTempDirectory("oracle-wallet");

            for (String archivo : ARCHIVOS_WALLET) {
                try (InputStream recurso = OracleConnection.class
                        .getClassLoader()
                        .getResourceAsStream("wallet/" + archivo)) {

                    if (recurso == null) {
                        throw new IllegalStateException(
                            "No se encontró el recurso de la wallet: "
                                + archivo);
                    }

                    Files.copy(
                        recurso,
                        destino.resolve(archivo),
                        StandardCopyOption.REPLACE_EXISTING);
                }
            }

            reemplazarDirectorioEnSqlnet(destino);

            directorioWallet = destino.toString();
            return directorioWallet;

        } catch (IOException error) {
            throw new UncheckedIOException(
                "No fue posible preparar la wallet de Oracle", error);
        }
    }

    /**
     * sqlnet.ora trae "DIRECTORY=?/network/admin", un marcador que solo se
     * resuelve con un cliente Oracle completo. Para el driver JDBC thin hay
     * que apuntarlo directamente al directorio donde quedó extraída la
     * wallet en esta instancia.
     */
    private static void reemplazarDirectorioEnSqlnet(
            Path directorio) throws IOException {

        Path sqlnet = directorio.resolve("sqlnet.ora");

        String contenido = Files.readString(sqlnet, StandardCharsets.UTF_8);

        String rutaEscapada = directorio
            .toString()
            .replace("\\", "\\\\");

        contenido = contenido.replace(
            "\"?/network/admin\"",
            "\"" + rutaEscapada + "\"");

        Files.writeString(sqlnet, contenido, StandardCharsets.UTF_8);
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