package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;
import java.util.Set;

import static sv.redent.Json.*;

/**
 * Cliente de comunicación oficial con la API del Ministerio de Hacienda de El Salvador
 * y el componente firmador local del contribuyente.
 */
@Component
public class MhClient {
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final boolean enabled;
    private final String base;
    private final String signer;
    private final String password;
    private final String signerPassword;
    private final String ambiente;

    private String token;
    private Instant expiration = Instant.EPOCH;

    public MhClient(@Value("${redent.mh.enabled:false}") boolean enabled,
                    @Value("${redent.mh.base-url:https://apitest.dtes.mh.gob.sv}") String base,
                    @Value("${redent.mh.signer-url:http://127.0.0.1:8113/firmardocumento/}") String signer,
                    @Value("${redent.mh.api-password:}") String password,
                    @Value("${redent.mh.signer-password:}") String signerPassword,
                    @Value("${redent.mh.ambiente:00}") String ambiente) {
        this.enabled = enabled;
        this.base = base.replaceAll("/+$", "");
        this.signer = signer;
        this.password = password;
        this.signerPassword = signerPassword;
        this.ambiente = (ambiente != null && !ambiente.isBlank()) ? ambiente : "00";

        if (!this.base.equals("https://apitest.dtes.mh.gob.sv") && !this.base.equals("https://api.dtes.mh.gob.sv")) {
            throw new IllegalStateException("Solo se permiten los endpoints oficiales del Ministerio de Hacienda (https://apitest.dtes.mh.gob.sv o https://api.dtes.mh.gob.sv)");
        }

        URI u = URI.create(signer);
        if (!"http".equals(u.getScheme()) || !Set.of("127.0.0.1", "localhost").contains(u.getHost())) {
            throw new IllegalStateException("El firmador debe residir en el mismo servidor local (127.0.0.1 o localhost)");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String ambiente() {
        return ambiente;
    }

    public String baseUrl() {
        return base;
    }

    public void requireReady() {
        if (!enabled) {
            throw new IllegalStateException("Transmisión desactivada. Configure el acceso de pruebas y MH_ENABLED=true en el servidor");
        }
        if (password.isBlank() || signerPassword.isBlank()) {
            throw new IllegalStateException("Faltan credenciales de acceso MH (MH_API_PASSWORD) o clave del firmador (MH_SIGNER_PASSWORD)");
        }
    }

    public String sign(JsonNode doc) {
        requireReady();
        JsonNode r = request(signer, "application/json",
            obj("nit", doc.at("/emisor/nit"), "activo", true, "passwordPri", signerPassword, "dteJson", doc).toString(), null);
        String jws = r.path("body").asText("");
        if (!r.path("status").asText().equals("OK") || jws.split("\\.").length != 3) {
            throw new IllegalStateException("El firmador no devolvió un JWS válido; revise certificado y clave localmente: " + r.path("descripcionMsg").asText(r.toString()));
        }
        try {
            JsonNode content = parse(new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]), StandardCharsets.UTF_8));
            if (!content.equals(doc)) {
                throw new IllegalStateException("El contenido firmado devuelto por el firmador difiere del DTE original");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Contenido JWS devuelto por el firmador es inválido");
        }
        return jws;
    }

    public synchronized String authenticate(String nit) {
        requireReady();
        if (token != null && Instant.now().isBefore(expiration.minusSeconds(60))) {
            return token;
        }
        JsonNode r = request(base + "/seguridad/auth", "application/x-www-form-urlencoded",
            "user=" + URLEncoder.encode(nit, StandardCharsets.UTF_8) + "&pwd=" + URLEncoder.encode(password, StandardCharsets.UTF_8), null);
        String t = r.at("/body/token").asText("");
        if (t.isBlank()) {
            throw new IllegalStateException("MH no autorizó la autenticación; revise habilitación y credenciales de pruebas/producción: " + r.path("descripcionMsg").asText(r.toString()));
        }
        token = t.startsWith("Bearer ") ? t : "Bearer " + t;
        expiration = Instant.now().plusSeconds(900);
        try {
            JsonNode payload = parse(new String(Base64.getUrlDecoder().decode(token.substring(7).split("\\.")[1]), StandardCharsets.UTF_8));
            if (payload.path("exp").asLong() > 0) {
                expiration = Instant.ofEpochSecond(payload.path("exp").asLong());
            }
        } catch (Exception ignored) {
            // Tiempo corto por defecto si el token no tiene payload JWT decodificable
        }
        return token;
    }

    public JsonNode transmit(String kind, long envio, JsonNode doc, String jws, String token) {
        String endpoint;
        JsonNode body;
        String amb = doc.at("/identificacion/ambiente").asText(this.ambiente);

        if (kind.equals("CONTINGENCIA")) {
            endpoint = "/fesv/contingencia";
            body = obj("nit", doc.at("/emisor/nit"), "documento", jws);
        } else {
            endpoint = kind.equals("INVALIDACION") ? "/fesv/anulardte" : "/fesv/recepciondte";
            var envelope = obj("ambiente", amb, "idEnvio", envio, "version", doc.at("/identificacion/version"), "documento", jws);
            if (kind.length() == 2) {
                envelope.put("tipoDte", kind).set("codigoGeneracion", doc.at("/identificacion/codigoGeneracion"));
            }
            body = envelope;
        }
        return request(base + endpoint, "application/json", body.toString(), token);
    }

    public JsonNode query(JsonNode doc) {
        String nit = doc.at("/emisor/nit").asText();
        return request(base + "/fesv/recepcion/consultadte", "application/json",
            obj("nitEmisor", nit, "tdte", doc.at("/identificacion/tipoDte"), "codigoGeneracion", doc.at("/identificacion/codigoGeneracion")).toString(),
            authenticate(nit));
    }

    public String publicConsultationUrl(String generation, String fecEmi, String docAmbiente) {
        String amb = (docAmbiente != null && !docAmbiente.isBlank()) ? docAmbiente : this.ambiente;
        return "https://admin.factura.gob.sv/consultaPublica?ambiente=" + amb + "&codGen=" + generation + "&fechaEmi=" + fecEmi;
    }

    private JsonNode request(String url, String contentType, String body, String auth) {
        try {
            var b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .header("User-Agent", "REDENT-Local/1.0 (El Salvador DTE)")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (auth != null) b.header("Authorization", auth);

            var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 401) {
                synchronized (this) {
                    token = null;
                    expiration = Instant.EPOCH;
                }
            }
            if (r.body().length() > 5000000) {
                throw new IllegalStateException("Respuesta de MH/firmador excede el límite permitido");
            }
            JsonNode json;
            try {
                json = parse(r.body());
            } catch (Exception e) {
                throw new IllegalStateException("MH/firmador devolvió una respuesta no JSON (HTTP " + r.statusCode() + "): " + r.body().substring(0, Math.min(200, r.body().length())));
            }
            if (r.statusCode() >= 500 || r.statusCode() == 401 || r.statusCode() == 403 || r.statusCode() == 429) {
                throw new IllegalStateException("MH/firmador respondió HTTP " + r.statusCode() + " (" + json.path("descripcionMsg").asText(json.path("mensaje").asText("")) + "); no se reenvió automáticamente");
            }
            return json;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Comunicación interrumpida");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo confirmar la comunicación con MH/firmador: " + e.getMessage());
        }
    }
}
