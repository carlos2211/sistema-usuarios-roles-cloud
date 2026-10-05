package cl.duoc.usuariosroles;

import cl.duoc.usuariosroles.eventos.Directorio;
import cl.duoc.usuariosroles.eventos.EventoRecibido;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reglas de negocio de los consumidores y procesadores, sin Oracle.
 */
class ConsumidoresEventosTest {

    private static final Directorio.Busqueda DIRECTORIO = new Directorio.Busqueda() {
        @Override
        public Directorio.Persona usuario(int idUsuario) {
            return idUsuario == 5 ? new Directorio.Persona("Ana", "ana@empresa.cl") : null;
        }

        @Override
        public String nombreRol(int idRol) {
            return idRol == 2 ? "SUPERVISOR" : null;
        }
    };

    private static EventoRecibido evento(String tipo, String data) {
        return EventoRecibido.desdeJson("{\"id\":\"e1\",\"eventType\":\"" + tipo
            + "\",\"subject\":\"usuarios/5\",\"eventTime\":\"2026-10-02T10:00:00Z\",\"data\":" + data + "}");
    }

    @Test
    void usuarioCreadoGeneraCorreoDeBienvenida() throws Exception {
        NotificacionesEventosFunction.Notificacion aviso = NotificacionesEventosFunction.construir(
            evento("Usuario.Creado", "{\"idUsuario\":5,\"nombre\":\"Ana\",\"correo\":\"ana@empresa.cl\"}"),
            DIRECTORIO,
            "seguridad@empresa.cl");

        assertEquals("ana@empresa.cl", aviso.destinatario);
        assertTrue(aviso.asunto.startsWith("Bienvenido/a"));
        assertTrue(aviso.mensaje.contains("Hola Ana"));
    }

    @Test
    void rolAsignadoBuscaCorreoYNombreDelRol() throws Exception {
        NotificacionesEventosFunction.Notificacion aviso = NotificacionesEventosFunction.construir(
            evento("UsuarioRol.Asignado", "{\"idUsuario\":5,\"idRol\":2}"),
            DIRECTORIO,
            "seguridad@empresa.cl");

        assertEquals("ana@empresa.cl", aviso.destinatario);
        assertEquals("Se te asignó el rol SUPERVISOR", aviso.asunto);
    }

    @Test
    void revocacionPorDesactivacionNoRepiteElAviso() throws Exception {
        assertNull(NotificacionesEventosFunction.construir(
            evento("UsuarioRol.Revocado", "{\"idUsuario\":5,\"idRol\":2,\"motivo\":\"USUARIO_DESACTIVADO\"}"),
            DIRECTORIO,
            "seguridad@empresa.cl"));
    }

    @Test
    void revocacionManualSiAvisa() throws Exception {
        NotificacionesEventosFunction.Notificacion aviso = NotificacionesEventosFunction.construir(
            evento("UsuarioRol.Revocado", "{\"idUsuario\":5,\"idRol\":2,\"motivo\":\"MANUAL\"}"),
            DIRECTORIO,
            "seguridad@empresa.cl");

        assertEquals("Se te quitó el rol SUPERVISOR", aviso.asunto);
    }

    @Test
    void alertaDeSeguridadVaAlCorreoDeSeguridad() throws Exception {
        NotificacionesEventosFunction.Notificacion aviso = NotificacionesEventosFunction.construir(
            evento("Alerta.Seguridad", "{\"regla\":\"ROL_CRITICO\",\"detalle\":\"Se asignó ADMINISTRADOR\"}"),
            DIRECTORIO,
            "soc@empresa.cl");

        assertEquals("soc@empresa.cl", aviso.destinatario);
        assertEquals("[Alerta de seguridad] ROL_CRITICO", aviso.asunto);
        assertEquals("Se asignó ADMINISTRADOR", aviso.mensaje);
    }

    @Test
    void eventosSinAvisoDevuelvenNull() throws Exception {
        assertNull(NotificacionesEventosFunction.construir(
            evento("Permiso.Creado", "{\"idPermiso\":1}"), DIRECTORIO, "x"));
    }

    @Test
    void rolesCriticosPorDefectoYConfigurados() {
        Set<String> porDefecto = ProcesadorSeguridadFunction.rolesCriticos(null);
        assertTrue(ProcesadorSeguridadFunction.esCritico("administrador", porDefecto));
        assertFalse(ProcesadorSeguridadFunction.esCritico("SUPERVISOR", porDefecto));

        Set<String> configurados = ProcesadorSeguridadFunction.rolesCriticos(" administrador , Supervisor ,");
        assertTrue(ProcesadorSeguridadFunction.esCritico("SUPERVISOR", configurados));
        assertEquals(2, configurados.size());
    }
}
