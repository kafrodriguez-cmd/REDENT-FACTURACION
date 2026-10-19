package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static sv.redent.Json.*;

/**
 * Servicio de ciclo de vida de documentos tributarios electrónicos (DTE) y eventos.
 * Implementa idempotencia, bloqueo transaccional, control de correlativos y validación de esquemas.
 */
@Service
public class DocumentService {
    private final Store store;
    private final DteBuilder builder;
    private final SchemaService schemas;

    public DocumentService(Store store, DteBuilder builder, SchemaService schemas) {
        this.store = store;
        this.builder = builder;
        this.schemas = schemas;
    }

    @Transactional
    public Map<String, Object> create(String key, JsonNode input) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}")) {
            throw new IllegalArgumentException("Idempotency-Key obligatorio (8–100 caracteres)");
        }
        String hash = hash(input.toString());
        var existing = store.db().queryForList("SELECT id,request_hash FROM document WHERE request_key=?", key);
        if (!existing.isEmpty()) {
            if (!hash.equals(existing.get(0).get("request_hash"))) {
                throw new IllegalStateException("Clave de idempotencia utilizada con otros datos");
            }
            return store.doc(((Number) existing.get(0).get("id")).longValue(), false);
        }

        String type = required(input, "tipo");
        schemas.schema(type);
        ObjectNode snapshot = input.deepCopy();

        if (type.length() == 2) {
            long client = input.path("clienteId").asLong();
            if (client > 0) {
                var rows = store.db().queryForList("SELECT json FROM catalog WHERE id=? AND kind='clientes' AND active=TRUE", String.class, client);
                if (rows.isEmpty()) throw new IllegalArgumentException("Cliente no encontrado o inactivo");
                snapshot.set("cliente", parse(rows.get(0)));
            }
            // Para Factura (01) y Notas (05, 06) el cliente es opcional en borrador (se aplica Consumidor Final si no se especifica)
            if (!snapshot.path("cliente").isObject() && !Set.of("01", "05", "06").contains(type)) {
                throw new IllegalArgumentException("Seleccione un cliente para este tipo de documento");
            }
        }

        Long related = input.path("documentoId").asLong() > 0 ? input.path("documentoId").asLong() : null;
        var kh = new GeneratedKeyHolder();
        store.db().update(c -> {
            var ps = c.prepareStatement("INSERT INTO document(kind,status,request_key,request_hash,input_json,related_id,created_by) VALUES(?,'BORRADOR',?,?,?,?,?)", new String[]{"id"});
            ps.setString(1, type);
            ps.setString(2, key);
            ps.setString(3, hash);
            ps.setString(4, snapshot.toString());
            ps.setObject(5, related);
            ps.setString(6, store.actor());
            return ps;
        }, kh);

        long id = Objects.requireNonNull(kh.getKey()).longValue();
        store.audit("CREAR_BORRADOR", id, type);
        return store.doc(id, false);
    }

    @Transactional
    public Map<String, Object> update(long id, int revision, JsonNode input) {
        var d = store.doc(id, true);
        if (!"BORRADOR".equals(d.get("status"))) {
            throw new IllegalStateException("Solo se puede editar un borrador");
        }
        if (!d.get("kind").equals(input.path("tipo").asText())) {
            throw new IllegalArgumentException("No cambie el tipo del borrador");
        }
        if (!Objects.equals(d.get("related_id"), input.path("documentoId").asLong() > 0 ? Long.valueOf(input.path("documentoId").asLong()) : null)) {
            throw new IllegalArgumentException("No cambie el documento relacionado; cree otro borrador");
        }
        int n = store.db().update("UPDATE document SET input_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=?", input.toString(), id, revision);
        if (n != 1) {
            throw new IllegalStateException("Otra persona modificó este borrador; recargue la página");
        }
        store.audit("EDITAR_BORRADOR", id, "Revisión " + revision);
        return store.doc(id, false);
    }

    public JsonNode preview(long id) {
        var d = store.doc(id, false);
        if (d.get("json") != null) return parse((String) d.get("json"));
        return generate(d, "00000000-0000-4000-8000-000000000001", "000000000000000");
    }

    @Transactional
    public Map<String, Object> prepare(long id) {
        var d = store.doc(id, true);
        if (!"BORRADOR".equals(d.get("status"))) return d;

        String type = (String) d.get("kind");
        JsonNode company = store.company();
        String generation = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        String targetAmbiente = company.path("ambiente").asText("00");

        if (d.get("related_id") != null) {
            store.doc(((Number) d.get("related_id")).longValue(), true);
        }

        long seq = type.length() == 2 ?
            store.next(targetAmbiente + ":" + type + ":" + required(company, "codEstableMH") + required(company, "codPuntoVentaMH") + ":" + LocalDate.now(DteBuilder.ZONE).getYear()) : 0;

        JsonNode doc = generate(d, generation, String.format("%015d", seq));
        schemas.validate(type, doc);

        var total = decimal(doc.path("resumen"), "totalPagar");
        if (type.equals("05")) {
            long original = ((Number) d.get("related_id")).longValue();
            var sum = store.db().queryForObject("SELECT COALESCE(SUM(total),0) FROM document WHERE related_id=? AND kind='05' AND status NOT IN ('BORRADOR','RECHAZADO','INVALIDADO')", java.math.BigDecimal.class, original);
            var originalTotal = (java.math.BigDecimal) store.doc(original, false).get("total");
            if (total.add(sum).compareTo(originalTotal) > 0) {
                throw new IllegalArgumentException("Notas de crédito acumuladas superan el total del documento original");
            }
        }

        String control = type.length() == 2 ? doc.at("/identificacion/numeroControl").asText() : null;
        store.db().update(
            "UPDATE document SET status='PREPARADO',json=?,generation=?,control_number=?,fiscal_year=?,total=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            doc.toString(), generation, control, type.length() == 2 ? Integer.valueOf(doc.at("/identificacion/fecEmi").asText().substring(0, 4)) : null, total, id);

        store.audit("PREPARAR", id, "Contenido congelado; SHA256 " + hash(doc.toString()));
        return store.doc(id, false);
    }

    private JsonNode generate(Map<String, Object> d, String generation, String seq) {
        String type = (String) d.get("kind");
        JsonNode input = parse((String) d.get("input_json"));
        JsonNode company = store.company();
        required(company, "nit");
        String targetAmbiente = company.path("ambiente").asText("00");

        JsonNode related = null;
        if (d.get("related_id") != null) {
            var r = store.doc(((Number) d.get("related_id")).longValue(), false);
            if (!"ACEPTADO".equals(r.get("status"))) {
                throw new IllegalArgumentException("El documento relacionado debe estar aceptado por Hacienda");
            }
            related = parse((String) r.get("json"));
        }

        if (type.length() == 2) {
            String control = "DTE-" + type + "-" + required(company, "codEstableMH") + required(company, "codPuntoVentaMH") + "-" + seq;
            return builder.build(type, input, company, generation, control, related);
        }

        var now = ZonedDateTime.now(DteBuilder.ZONE);
        String date = now.toLocalDate().toString();
        String time = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        if (type.equals("INVALIDACION")) {
            if (related == null) throw new IllegalArgumentException("Indique documentoId aceptado");
            long target = ((Number) d.get("related_id")).longValue();
            int pending = store.db().queryForObject(
                "SELECT count(*) FROM document WHERE related_id=? AND id<>? AND (kind='INVALIDACION' OR kind IN ('05','06')) AND status NOT IN ('BORRADOR','RECHAZADO','INVALIDADO')",
                Integer.class, target, d.get("id"));
            if (pending > 0) {
                throw new IllegalStateException("Hay eventos o notas vigentes relacionados; revisar antes de invalidar");
            }
            var r = store.doc(target, false);
            JsonNode rec = related.path("receptor");
            ObjectNode doc = obj(
                "tipoDte", related.at("/identificacion/tipoDte"),
                "codigoGeneracion", r.get("generation"),
                "selloRecibido", r.get("seal"),
                "numeroControl", r.get("control_number"),
                "fecEmi", related.at("/identificacion/fecEmi"),
                "codigoGeneracionR", input.path("codigoGeneracionR").isMissingNode() ? null : input.get("codigoGeneracionR"),
                "tipoDocumento", rec.has("nit") ? "36" : rec.path("tipoDocumento"),
                "numDocumento", rec.has("nit") ? rec.get("nit") : rec.path("numDocumento"),
                "nombre", rec.path("nombre"),
                "telefono", rec.path("telefono"),
                "correo", rec.path("correo")
            );

            return obj(
                "identificacion", obj("version", schemas.version(type), "ambiente", targetAmbiente, "codigoGeneracion", generation, "fecEmi", date, "horEmi", time, "fusion", null),
                "emisor", schemas.section(type, "emisor", company),
                "documento", doc,
                "motivo", input.path("motivo")
            );
        }

        ArrayNode details = arr();
        JsonNode ids = input.path("documentos");
        if (!ids.isArray() || ids.isEmpty()) {
            throw new IllegalArgumentException("Seleccione documentos de contingencia");
        }
        Set<Long> unique = new HashSet<>();
        JsonNode motive = input.path("motivo");
        LocalDateTime from = LocalDateTime.parse(required(motive, "fInicio") + "T" + required(motive, "hInicio"));
        LocalDateTime until = LocalDateTime.parse(required(motive, "fFin") + "T" + required(motive, "hFin"));
        if (until.isBefore(from) || until.isAfter(now.toLocalDateTime())) {
            throw new IllegalArgumentException("Intervalo de fechas de contingencia inválido");
        }

        for (JsonNode selected : ids) {
            long docId = selected.asLong();
            if (!unique.add(docId)) throw new IllegalArgumentException("Documento duplicado en lote de contingencia");
            var r = store.doc(docId, false);
            JsonNode j = parse(Objects.toString(r.get("json"), "{}"));
            if (!"PREPARADO".equals(r.get("status")) || j.at("/identificacion/tipoOperacion").asInt() != 2) {
                throw new IllegalArgumentException("Documento no preparado en contingencia: " + docId);
            }
            LocalDateTime emission = LocalDateTime.parse(j.at("/identificacion/fecEmi").asText() + "T" + j.at("/identificacion/horEmi").asText());
            if (emission.isBefore(from) || emission.isAfter(until)) {
                throw new IllegalArgumentException("Documento fuera del intervalo de contingencia: " + docId);
            }
            details.add(obj("noItem", details.size() + 1, "tipoDoc", r.get("kind"), "codigoGeneracion", r.get("generation")));
        }

        ObjectNode emitter = company.deepCopy();
        for (String k : List.of("nombreResponsable", "tipoDocResponsable", "numeroDocResponsable")) {
            emitter.set(k, input.path("responsable").path(k));
        }

        return obj(
            "identificacion", obj("version", schemas.version(type), "ambiente", targetAmbiente, "codigoGeneracion", generation, "fTransmision", date, "hTransmision", time),
            "emisor", schemas.section(type, "emisor", emitter),
            "detalleDTE", details,
            "motivo", motive
        );
    }

    @Transactional
    public Map<String, Object> claim(long id) {
        var d = store.doc(id, true);
        if (!"PREPARADO".equals(d.get("status"))) {
            throw new IllegalStateException("Solo se transmite un documento PREPARADO. Si está INCIERTO, consulte su estado");
        }
        JsonNode doc = parse((String) d.get("json"));
        if (doc.at("/identificacion/tipoOperacion").asInt() == 2) {
            boolean declared = store.db().queryForList("SELECT json FROM document WHERE kind='CONTINGENCIA' AND status='ACEPTADO'", String.class).stream()
                .map(Json::parse).anyMatch(e -> {
                    for (JsonNode row : e.path("detalleDTE")) {
                        if (row.path("codigoGeneracion").asText().equals(d.get("generation"))) return true;
                    }
                    return false;
                });
            if (!declared) {
                throw new IllegalStateException("Primero transmita y obtenga aceptación del evento de contingencia correspondiente ante el MH");
            }
        }
        schemas.validate((String) d.get("kind"), doc);
        store.db().update("UPDATE document SET status='FIRMANDO' WHERE id=?", id);
        return d;
    }

    @Transactional
    public void signingFailed(long id) {
        store.db().update("UPDATE document SET status='PREPARADO' WHERE id=? AND status='FIRMANDO'", id);
    }

    @Transactional
    public long sending(long id, String signed) {
        int n = store.db().update("UPDATE document SET status='ENVIANDO',signed_jws=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='FIRMANDO'", signed, id);
        if (n != 1) throw new IllegalStateException("Estado concurrente inesperado");
        var k = new GeneratedKeyHolder();
        store.db().update(c -> {
            var p = c.prepareStatement("INSERT INTO attempt(document_id,operation) VALUES(?,'TRANSMITIR')", new String[]{"id"});
            p.setLong(1, id);
            return p;
        }, k);
        store.audit("TRANSMITIR", id, "Solicitud registrada antes de llamar al MH");
        return Objects.requireNonNull(k.getKey()).longValue();
    }

    @Transactional
    public void result(long id, Long attempt, JsonNode response, boolean query) {
        var d = store.doc(id, true);
        String kind = (String) d.get("kind");
        String state = response.path("estado").asText();
        String seal = response.path("selloRecibido").asText("");
        boolean isDte = kind.length() == 2;
        String amb = response.path("ambiente").asText("00");

        boolean accepted = (isDte ? state.equals("PROCESADO") : state.equals("RECIBIDO") || state.equals("PROCESADO")) && !seal.isBlank();
        if (isDte) {
            accepted = accepted && response.path("codigoGeneracion").asText().equals(d.get("generation"));
        }

        String next = accepted ? "ACEPTADO" : !query && state.equals("RECHAZADO") ? "RECHAZADO" : "INCIERTO";
        if (query && Set.of("ACEPTADO", "INVALIDADO").contains(d.get("status"))) {
            next = (String) d.get("status");
        }

        store.db().update("UPDATE document SET status=?,response_json=?,seal=COALESCE(?,seal),updated_at=CURRENT_TIMESTAMP WHERE id=?", next, response.toString(), accepted ? seal : null, id);
        if (accepted && kind.equals("INVALIDACION")) {
            store.db().update("UPDATE document SET status='INVALIDADO',updated_at=CURRENT_TIMESTAMP WHERE id=?", d.get("related_id"));
        }
        if (attempt != null) {
            store.db().update("UPDATE attempt SET finished_at=CURRENT_TIMESTAMP,outcome=?,response_json=? WHERE id=?", next, response.toString(), attempt);
        } else {
            store.db().update("INSERT INTO attempt(document_id,operation,finished_at,outcome,response_json) VALUES(?,'CONSULTAR',CURRENT_TIMESTAMP,?,?)", id, next, response.toString());
        }
        store.audit(query ? "CONSULTAR_MH" : "RESPUESTA_MH", id, next);
    }
}
