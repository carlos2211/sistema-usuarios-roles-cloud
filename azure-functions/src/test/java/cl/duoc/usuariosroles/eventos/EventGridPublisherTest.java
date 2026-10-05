package cl.duoc.usuariosroles.eventos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba el envío a Event Grid contra un servidor HTTP local que imita
 * el endpoint del topic.
 */
class EventGridPublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer servidor;
    private final AtomicReference<String> claveRecibida = new AtomicReference<>();
    private final AtomicReference<String> cuerpoRecibido = new AtomicReference<>();
    private final AtomicInteger estadoRespuesta = new AtomicInteger(200);

    @BeforeEach
    void iniciarServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/events", intercambio -> {
            claveRecibida.set(intercambio.getRequestHeaders().getFirst("aeg-sas-key"));
            cuerpoRecibido.set(new String(
                intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            intercambio.sendResponseHeaders(estadoRespuesta.get(), -1);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void detenerServidor() {
        servidor.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/api/events";
    }

    @Test
    void enviaLosEventosConLaClaveDelTopic() throws IOException {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("idUsuario", 7);
        datos.put("correo", "ana@empresa.cl");

        boolean ok = EventGridPublisher.enviar(
            endpoint(),
            "clave-secreta",
            Arrays.asList(
                EventGridPublisher.crearEvento(TiposEvento.USUARIO_CREADO, "usuarios/7", datos),
                EventGridPublisher.crearEvento(TiposEvento.USUARIO_ROL_ASIGNADO, "usuarios/7", datos)));

        assertTrue(ok);
        assertEquals("clave-secreta", claveRecibida.get());

        JsonNode eventos = MAPPER.readTree(cuerpoRecibido.get());
        assertTrue(eventos.isArray());
        assertEquals(2, eventos.size());

        JsonNode primero = eventos.get(0);
        assertEquals("Usuario.Creado", primero.get("eventType").asText());
        assertEquals("usuarios/7", primero.get("subject").asText());
        assertEquals("1.0", primero.get("dataVersion").asText());
        assertEquals(7, primero.get("data").get("idUsuario").asInt());
        assertNotNull(primero.get("id").asText());
        assertNotNull(primero.get("eventTime").asText());
    }

    @Test
    void devuelveFalseSiEventGridRechazaLaPeticion() {
        estadoRespuesta.set(401);

        boolean ok = EventGridPublisher.enviar(
            endpoint(),
            "clave-incorrecta",
            Arrays.asList(EventGridPublisher.crearEvento(
                TiposEvento.ROL_CREADO, "roles/1", new LinkedHashMap<>())));

        assertFalse(ok);
    }

    @Test
    void noLanzaExcepcionSiElTopicNoResponde() {
        servidor.stop(0);

        boolean ok = EventGridPublisher.enviar(
            endpoint(),
            "clave",
            Arrays.asList(EventGridPublisher.crearEvento(
                TiposEvento.ROL_CREADO, "roles/1", new LinkedHashMap<>())));

        assertFalse(ok);
    }
}
