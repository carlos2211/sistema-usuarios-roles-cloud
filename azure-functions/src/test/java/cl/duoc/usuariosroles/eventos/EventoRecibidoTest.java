package cl.duoc.usuariosroles.eventos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventoRecibidoTest {

    @Test
    void leeUnEventoConElFormatoQueEntregaEventGrid() {
        // Event Grid entrega eventTime con 7 decimales.
        String json = "{\"id\":\"abc-1\",\"topic\":\"/subscriptions/x/topics/evt\","
            + "\"subject\":\"usuarios/15\",\"eventType\":\"Usuario.Desactivado\","
            + "\"eventTime\":\"2026-10-02T14:32:05.1234567Z\","
            + "\"data\":{\"idUsuario\":15,\"correo\":\"ana@empresa.cl\"},"
            + "\"dataVersion\":\"1.0\",\"metadataVersion\":\"1\"}";

        EventoRecibido evento = EventoRecibido.desdeJson(json);

        assertEquals("abc-1", evento.getId());
        assertEquals("Usuario.Desactivado", evento.getTipo());
        assertEquals("usuarios/15", evento.getSubject());
        assertEquals(15, evento.entero("idUsuario"));
        assertEquals("ana@empresa.cl", evento.texto("correo"));
        assertEquals(-1, evento.entero("idRol"));
        assertEquals("{\"idUsuario\":15,\"correo\":\"ana@empresa.cl\"}", evento.getDatosJson());
        assertEquals(2026, evento.getFechaComoTimestamp().toLocalDateTime().getYear());
    }

    @Test
    void sinDataGuardaNull() {
        EventoRecibido evento = EventoRecibido.desdeJson(
            "{\"id\":\"1\",\"eventType\":\"Rol.Eliminado\",\"subject\":\"roles/1\"}");

        assertNull(evento.getDatosJson());
    }

    @Test
    void rechazaEventosSinTipo() {
        assertThrows(IllegalArgumentException.class,
            () -> EventoRecibido.desdeJson("{\"id\":\"1\"}"));
    }
}
