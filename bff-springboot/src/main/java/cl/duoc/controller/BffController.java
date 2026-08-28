package cl.duoc.controller;

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
        value = {
            "/api/roles",
            "/api/roles/{id}"
        },
        method = {
            org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST,
            org.springframework.web.bind.annotation.RequestMethod.PUT,
            org.springframework.web.bind.annotation.RequestMethod.DELETE
        },
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> roles(
        @PathVariable(required = false) String id,
        @RequestBody(required = false) String body,
        HttpMethod method
    ) {
        return reenviar("roles", id, body, method);
    }

    private ResponseEntity<String> reenviar(
        String recurso,
        String id,
        String body,
        HttpMethod method
    ) {
        String url = functionsBaseUrl + "/" + recurso;

        if (id != null && !id.isBlank()) {
            url += "/" + id;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> solicitud =
            new HttpEntity<>(body, headers);

        try {
            return restTemplate.exchange(
                url,
                method,
                solicitud,
                String.class
            );

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