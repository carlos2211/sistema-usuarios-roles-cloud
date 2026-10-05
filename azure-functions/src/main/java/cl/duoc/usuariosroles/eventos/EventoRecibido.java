package cl.duoc.usuariosroles.eventos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Evento entregado por Event Grid a una función con EventGridTrigger
 * (esquema Event Grid: id, eventType, subject, eventTime, data).
 */
public final class EventoRecibido {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final String tipo;
    private final String subject;
    private final Instant fecha;
    private final JsonNode datos;

    private EventoRecibido(
            String id,
            String tipo,
            String subject,
            Instant fecha,
            JsonNode datos) {
        this.id = id;
        this.tipo = tipo;
        this.subject = subject;
        this.fecha = fecha;
        this.datos = datos;
    }

    public static EventoRecibido desdeJson(String json) {
        try {
            JsonNode evento = MAPPER.readTree(json);

            String id = texto(evento, "id");
            String tipo = texto(evento, "eventType");

            if (id == null || tipo == null) {
                throw new IllegalArgumentException(
                    "El evento no tiene 'id' o 'eventType': " + json);
            }

            String fecha = texto(evento, "eventTime");

            return new EventoRecibido(
                id,
                tipo,
                texto(evento, "subject"),
                fecha != null ? Instant.parse(fecha) : Instant.now(),
                evento.has("data") ? evento.get("data") : MissingNode.getInstance());

        } catch (IOException error) {
            throw new UncheckedIOException("Evento con JSON inválido", error);
        }
    }

    public String getId() {
        return id;
    }

    public String getTipo() {
        return tipo;
    }

    public String getSubject() {
        return subject;
    }

    public Timestamp getFechaComoTimestamp() {
        return Timestamp.from(fecha);
    }

    public JsonNode getDatos() {
        return datos;
    }

    /** "data" como texto JSON, o null si el evento no trae datos. */
    public String getDatosJson() {
        if (datos.isMissingNode() || datos.isNull()) {
            return null;
        }

        try {
            return MAPPER.writeValueAsString(datos);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Valor entero de "data", o -1 si no viene. */
    public int entero(String campo) {
        return datos.hasNonNull(campo) ? datos.get(campo).asInt() : -1;
    }

    /** Valor de texto de "data", o null si no viene. */
    public String texto(String campo) {
        return texto(datos, campo);
    }

    private static String texto(JsonNode nodo, String campo) {
        return nodo.hasNonNull(campo) ? nodo.get(campo).asText() : null;
    }

    @Override
    public String toString() {
        return tipo + " " + subject + " (" + id + ")";
    }
}
