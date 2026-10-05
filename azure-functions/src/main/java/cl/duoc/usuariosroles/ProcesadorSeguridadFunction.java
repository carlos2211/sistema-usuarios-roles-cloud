package cl.duoc.usuariosroles;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

import cl.duoc.usuariosroles.eventos.Directorio;
import cl.duoc.usuariosroles.eventos.EventGridPublisher;
import cl.duoc.usuariosroles.eventos.EventoRecibido;
import cl.duoc.usuariosroles.eventos.TiposEvento;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Procesador de eventos: revisa cada asignación de rol
 * (UsuarioRol.Asignado) y, si el rol es crítico, publica un evento
 * Alerta.Seguridad que reciben auditoría y notificaciones.
 *
 * Los roles críticos se configuran en la variable ROLES_CRITICOS
 * (separados por coma). Por defecto: ADMINISTRADOR.
 */
public class ProcesadorSeguridadFunction {

    static final String ROLES_CRITICOS_POR_DEFECTO = "ADMINISTRADOR";

    @FunctionName("procesador-seguridad")
    public void ejecutar(
            @EventGridTrigger(name = "evento") String json,
            ExecutionContext context) throws SQLException {

        EventoRecibido evento = EventoRecibido.desdeJson(json);

        if (!TiposEvento.USUARIO_ROL_ASIGNADO.equals(evento.getTipo())) {
            context.getLogger().warning("Evento no esperado: " + evento);
            return;
        }

        int idUsuario = evento.entero("idUsuario");
        int idRol = evento.entero("idRol");

        String nombreRol;
        Directorio.Persona persona;

        try (Connection connection = OracleConnection.getConnection()) {
            Directorio.Busqueda busqueda = Directorio.oracle(connection);
            nombreRol = busqueda.nombreRol(idRol);
            persona = busqueda.usuario(idUsuario);
        }

        if (nombreRol == null || !esCritico(nombreRol, rolesCriticos(System.getenv("ROLES_CRITICOS")))) {
            context.getLogger().info("Asignación sin riesgo: rol " + nombreRol + " a usuario " + idUsuario);
            return;
        }

        String correo = persona != null ? persona.getCorreo() : "desconocido";

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("idUsuario", idUsuario);
        datos.put("correoUsuario", correo);
        datos.put("idRol", idRol);
        datos.put("nombreRol", nombreRol);
        datos.put("regla", "ROL_CRITICO");
        datos.put("detalle", "Se asignó el rol crítico " + nombreRol + " al usuario "
                + idUsuario + " (" + correo + "). Verifique que la asignación esté autorizada.");
        datos.put("eventoOrigen", evento.getId());

        context.getLogger().warning("Alerta de seguridad: " + datos.get("detalle"));

        EventGridPublisher.publicar(
                TiposEvento.ALERTA_SEGURIDAD,
                TiposEvento.subjectUsuario(idUsuario),
                datos);
    }

    static Set<String> rolesCriticos(String configuracion) {
        String valor = configuracion == null || configuracion.isBlank()
                ? ROLES_CRITICOS_POR_DEFECTO
                : configuracion;

        return Arrays.stream(valor.split(","))
                .map(rol -> rol.trim().toUpperCase(Locale.ROOT))
                .filter(rol -> !rol.isEmpty())
                .collect(Collectors.toSet());
    }

    static boolean esCritico(String nombreRol, Set<String> rolesCriticos) {
        return rolesCriticos.contains(nombreRol.trim().toUpperCase(Locale.ROOT));
    }
}
