package cl.duoc.usuariosroles.eventos;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publica eventos en el Custom Topic de Azure Event Grid usando su API
 * REST (esquema Event Grid). Lo usan las funciones productoras después de
 * confirmar el cambio en Oracle.
 *
 * Si la publicación falla solo se registra el error: el cambio en la base
 * de datos ya quedó confirmado y no debe revertirse ni devolver error al
 * cliente por un problema del bus de eventos.
 */
public final class EventGridPublisher {

    public static final String VARIABLE_ENDPOINT = "EVENTGRID_TOPIC_ENDPOINT";
    public static final String VARIABLE_CLAVE = "EVENTGRID_TOPIC_KEY";

    private static final Logger LOGGER =
        Logger.getLogger(EventGridPublisher.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENTE = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private EventGridPublisher() {
    }

    /**
     * Publica un único evento.
     *
     * @param tipo    tipo de evento, por ejemplo {@link TiposEvento#USUARIO_CREADO}
     * @param subject recurso afectado, por ejemplo "usuarios/15"
     * @param datos   contenido del evento (sin datos sensibles)
     * @return true si Event Grid aceptó el evento
     */
    public static boolean publicar(
            String tipo,
            String subject,
            Map<String, Object> datos) {

        return publicarTodos(
            Collections.singletonList(crearEvento(tipo, subject, datos)));
    }

    /**
     * Publica varios eventos en una sola petición a Event Grid.
     */
    public static boolean publicarTodos(List<Map<String, Object>> eventos) {
        String endpoint = System.getenv(VARIABLE_ENDPOINT);
        String clave = System.getenv(VARIABLE_CLAVE);

        if (vacio(endpoint) || vacio(clave)) {
            LOGGER.warning(
                "Event Grid no configurado (" + VARIABLE_ENDPOINT + " / "
                    + VARIABLE_CLAVE + "); no se publicaron "
                    + eventos.size() + " evento(s)");
            return false;
        }

        return enviar(endpoint.trim(), clave.trim(), eventos);
    }

    /**
     * Construye un evento con el esquema de Event Grid.
     */
    public static Map<String, Object> crearEvento(
            String tipo,
            String subject,
            Map<String, Object> datos) {

        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("id", UUID.randomUUID().toString());
        evento.put("eventType", tipo);
        evento.put("subject", subject);
        evento.put("eventTime", Instant.now().toString());
        evento.put("data", datos);
        evento.put("dataVersion", "1.0");
        return evento;
    }

    static boolean enviar(
            String endpoint,
            String clave,
            List<Map<String, Object>> eventos) {

        if (eventos.isEmpty()) {
            return true;
        }

        try {
            HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("aeg-sas-key", clave)
                .POST(HttpRequest.BodyPublishers.ofString(
                    MAPPER.writeValueAsString(eventos)))
                .build();

            HttpResponse<String> respuesta = CLIENTE.send(
                peticion,
                HttpResponse.BodyHandlers.ofString());

            if (respuesta.statusCode() / 100 != 2) {
                LOGGER.severe(
                    "Event Grid rechazó " + tipos(eventos) + ": HTTP "
                        + respuesta.statusCode() + " " + respuesta.body());
                return false;
            }

            LOGGER.info("Eventos publicados en Event Grid: " + tipos(eventos));
            return true;

        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.SEVERE, "Publicación interrumpida", error);
            return false;

        } catch (Exception error) {
            LOGGER.log(
                Level.SEVERE,
                "No fue posible publicar " + tipos(eventos) + " en Event Grid",
                error);
            return false;
        }
    }

    private static List<Object> tipos(List<Map<String, Object>> eventos) {
        List<Object> tipos = new ArrayList<>();
        for (Map<String, Object> evento : eventos) {
            tipos.add(evento.get("eventType") + " (" + evento.get("subject") + ")");
        }
        return tipos;
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
