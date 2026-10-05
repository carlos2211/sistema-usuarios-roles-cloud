package cl.duoc.usuariosroles.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Consultas de apoyo para los consumidores y procesadores de eventos:
 * completan los datos que no viajan en el evento (correo del usuario,
 * nombre del rol).
 */
public final class Directorio {

    private Directorio() {
    }

    /** Nombre y correo de un usuario. */
    public static final class Persona {
        private final String nombre;
        private final String correo;

        public Persona(String nombre, String correo) {
            this.nombre = nombre;
            this.correo = correo;
        }

        public String getNombre() {
            return nombre;
        }

        public String getCorreo() {
            return correo;
        }
    }

    /** Búsquedas que necesitan los consumidores; facilita las pruebas. */
    public interface Busqueda {
        /** @return la persona o null si el usuario no existe */
        Persona usuario(int idUsuario) throws SQLException;

        /** @return el nombre del rol o null si no existe */
        String nombreRol(int idRol) throws SQLException;
    }

    /** Implementación sobre Oracle usando una conexión abierta. */
    public static Busqueda oracle(Connection connection) {
        return new Busqueda() {
            @Override
            public Persona usuario(int idUsuario) throws SQLException {
                String sql = "SELECT NOMBRE, CORREO FROM USUARIOS WHERE ID_USUARIO = ?";

                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, idUsuario);

                    try (ResultSet result = statement.executeQuery()) {
                        return result.next()
                            ? new Persona(result.getString("NOMBRE"), result.getString("CORREO"))
                            : null;
                    }
                }
            }

            @Override
            public String nombreRol(int idRol) throws SQLException {
                String sql = "SELECT NOMBRE FROM ROLES WHERE ID_ROL = ?";

                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, idRol);

                    try (ResultSet result = statement.executeQuery()) {
                        return result.next() ? result.getString("NOMBRE") : null;
                    }
                }
            }
        };
    }
}
