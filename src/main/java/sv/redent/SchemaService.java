package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class SchemaService {
    public static final Map<String,String> FILES=Map.of(
        "01","fe-f-v2.json","03","fe-ccf-v4.json","05","fe-nc-v4.json","06","fe-nd-v4.json",
        "11","fe-fex-v3.json","14","fe-fse-v2.json","INVALIDACION","invalidacion-schema-v3.json",
        "CONTINGENCIA","contingencia-schema-v4.json");
    private final Map<String,JsonNode> nodes=new HashMap<>();
    private final Map<String,JsonSchema> compiled=new HashMap<>();
    public SchemaService() {
        FILES.forEach((type,file)->{
            try(var in=getClass().getResourceAsStream("/schemas/"+file)) {
                JsonNode n=Json.MAPPER.readTree(in); nodes.put(type,n);
                compiled.put(type,JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(n,
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()));
            } catch(Exception e) { throw new IllegalStateException("Esquema no disponible: "+file,e); }
        });
    }
    public JsonNode schema(String type) {
        if(!nodes.containsKey(type)) throw new IllegalArgumentException("Tipo no soportado");
        return nodes.get(type);
    }
    public int version(String type) { return schema(type).at("/properties/identificacion/properties/version/const").asInt(); }
    public List<String> errors(String type,JsonNode doc) {
        schema(type);
        return compiled.get(type).validate(doc).stream().map(ValidationMessage::getMessage).sorted().toList();
    }
    public void validate(String type,JsonNode doc) {
        var errors=errors(type,doc);
        if(!errors.isEmpty()) throw new ValidationException(errors);
    }
    // Generated sections contain only fields declared for this exact DTE version.
    public ObjectNode section(String type,String section,JsonNode source) {
        JsonNode shape=schema(type).path("properties").path(section);
        if(shape.has("items")) shape=shape.path("items");
        ObjectNode out=Json.obj();
        shape.path("properties").fieldNames().forEachRemaining(k->out.set(k,source.has(k)?source.get(k):Json.MAPPER.nullNode()));
        return out;
    }
    public static class ValidationException extends IllegalArgumentException {
        public final List<String> errors;
        public ValidationException(List<String> errors) { super("El documento no cumple el esquema oficial");this.errors=errors; }
    }
}
