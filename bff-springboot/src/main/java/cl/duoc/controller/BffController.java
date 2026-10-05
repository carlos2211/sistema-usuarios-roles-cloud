package cl.duoc.controller;

import java.net.URI;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Controller
public class BffController {

    private final RestTemplate restTemplate;
    private final String functionsBaseUrl;

    public BffController(
        RestTemplate restTemplate,
        @Value("${azure.functions.base-url}")
        String functionsBaseUrl
    ) {
        this.restTemplate = restTemplate;
        this.functionsBaseUrl = functionsBaseUrl;
    }

    @RequestMapping(
        value = {
            "/api/usuarios",
            "/api/usuarios/{id}"
        },
        method = {
            org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST,
            org.springframework.web.bind.annotation.RequestMethod.PUT,
            org.springframework.web.bind.annotation.RequestMethod.DELETE
        },
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> usuarios(
        @PathVariable(required = false) String id,
        @RequestBody(required = false) String body,
        HttpMethod method
    ) {
        return reenviar("usuarios", id, body, method);
    }

    @RequestMapping(
        value = "/api/roles",
        method = org.springframework.web.bind.annotation.RequestMethod.POST,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> roles(
        @RequestBody String body
    ) {
        return reenviar("roles", null, body, HttpMethod.POST);
    }

    @RequestMapping(
        value = {
            "/api/permisos",
            "/api/permisos/{id}"
        },
        method = {
            org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST,
            org.springframework.web.bind.annotation.RequestMethod.PUT,
            org.springframework.web.bind.annotation.RequestMethod.DELETE
        },
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> permisos(
        @PathVariable(required = false) String id,
        @RequestBody(required = false) String body,
        HttpMethod method
    ) {
        return reenviar("permisos", id, body, method);
    }

    @RequestMapping(
        value = "/api/asignaciones",
        method = org.springframework.web.bind.annotation.RequestMethod.POST,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> asignaciones(
        @RequestBody String body
    ) {
        return reenviar("asignaciones", null, body, HttpMethod.POST);
    }

    // Consultas sobre lo que registran los consumidores de eventos
    // (Event Grid). Se reenvían los filtros del query string, por ejemplo
    // /api/auditoria?subject=usuarios/5
    @RequestMapping(
        value = "/api/auditoria",
        method = org.springframework.web.bind.annotation.RequestMethod.GET,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> auditoria(HttpServletRequest request) {
        return reenviar("auditoria", null, null, HttpMethod.GET, request.getQueryString());
    }

    @RequestMapping(
        value = "/api/notificaciones",
        method = org.springframework.web.bind.annotation.RequestMethod.GET,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> notificaciones(HttpServletRequest request) {
        return reenviar("notificaciones", null, null, HttpMethod.GET, request.getQueryString());
    }

    private ResponseEntity<String> reenviar(
        String recurso,
        String id,
        String body,
        HttpMethod method
    ) {
        return reenviar(recurso, id, body, method, null);
    }

    private ResponseEntity<String> reenviar(
        String recurso,
        String id,
        String body,
        HttpMethod method,
        String queryString
    ) {
        String url = functionsBaseUrl + "/" + recurso;

        if (id != null && !id.isBlank()) {
            url += "/" + id;
        }

        if (queryString != null && !queryString.isBlank()) {
            url += "?" + queryString;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> solicitud =
            new HttpEntity<>(body, headers);

        try {
            // URI.create evita volver a codificar el query string, que ya
            // viene codificado desde el cliente.
            ResponseEntity<String> respuesta = restTemplate.exchange(
                URI.create(url),
                method,
                solicitud,
                String.class
            );

            // Solo se reenvía estado y cuerpo: copiar las cabeceras de
            // transporte de Azure Functions (Transfer-Encoding, Server...)
            // duplica Transfer-Encoding y el ingress de Azure Container
            // Apps rechaza la respuesta como error de protocolo.
            return ResponseEntity
                .status(respuesta.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(respuesta.getBody());

        } catch (HttpStatusCodeException error) {
            return ResponseEntity
                .status(error.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(error.getResponseBodyAsString());

        } catch (Exception error) {
            return ResponseEntity
                .status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"mensaje\":\"No fue posible comunicarse "
                    + "con las Azure Functions\"}"
                );
        }
    }
}