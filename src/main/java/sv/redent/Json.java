package sv.redent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private Json() {}
    public static JsonNode parse(String s) { try { return MAPPER.readTree(s); } catch (Exception e) { throw new IllegalArgumentException("JSON inválido"); } }
    public static ObjectNode obj() { return MAPPER.createObjectNode(); }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }
    public static ObjectNode obj(Object... kv) {
        ObjectNode o = obj();
        for (int i=0;i<kv.length;i+=2) o.set(kv[i].toString(),MAPPER.valueToTree(kv[i+1]));
        return o;
    }
    public static BigDecimal decimal(JsonNode n, String key) {
        JsonNode v=n.path(key);
        if (v.isMissingNode() || v.isNull()) return BigDecimal.ZERO;
        try { return new BigDecimal(v.asText()); } catch (Exception e) { throw new IllegalArgumentException("Número inválido: "+key); }
    }
    public static String required(JsonNode n, String key) {
        String s=n.path(key).asText("").trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Falta "+key);
        return s;
    }
    public static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
