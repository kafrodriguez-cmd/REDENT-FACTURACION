package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Servicio de Catálogos Oficiales del Ministerio de Hacienda de El Salvador (CAT-001 al CAT-034).
 * Provee consulta, validación estricta y metadatos de clasificación tributaria y geográfica.
 */
@Service
public class MhCatalogService {

    private final Map<String, List<Map<String, Object>>> catalogs = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> departamentos = new ArrayList<>();
    private final List<Map<String, Object>> municipios = new ArrayList<>();
    private final List<Map<String, Object>> distritos = new ArrayList<>();

    // Mapeo bidireccional entre numeraciones de catálogos en distintas versiones del MH
    private static final Map<String, List<String>> ALIASES = Map.of(
        "008", List.of("008", "014"),
        "009", List.of("009", "011"),
        "011", List.of("011", "022", "009"),
        "014", List.of("014", "017", "008"),
        "016", List.of("016"),
        "017", List.of("017", "014", "018"),
        "018", List.of("018", "031"),
        "022", List.of("022", "011"),
        "024", List.of("024", "006"),
        "031", List.of("031", "018")
    );

    public MhCatalogService(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("catalogos/catalogos.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            var it = root.fields();
            while (it.hasNext()) {
                var entry = it.next();
                String key = entry.getKey();
                JsonNode arrayNode = entry.getValue();
                if (arrayNode.isArray()) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (JsonNode item : arrayNode) {
                        Map<String, Object> map = new LinkedHashMap<>();
                        item.fields().forEachRemaining(f -> {
                            if (f.getValue().isNumber()) {
                                map.put(f.getKey(), f.getValue().numberValue());
                            } else if (f.getValue().isBoolean()) {
                                map.put(f.getKey(), f.getValue().booleanValue());
                            } else {
                                map.put(f.getKey(), f.getValue().asText());
                            }
                        });
                        list.add(Collections.unmodifiableMap(map));
                    }
                    if (key.equals("departamentos")) {
                        departamentos.addAll(list);
                    } else if (key.equals("municipios")) {
                        municipios.addAll(list);
                    } else if (key.equals("distritos")) {
                        distritos.addAll(list);
                    } else {
                        catalogs.put(key, Collections.unmodifiableList(list));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cargar el archivo oficial de catálogos MH catalogos.json", e);
        }
    }

    public List<Map<String, Object>> getCatalog(String key) {
        String normalizedKey = key.replaceFirst("^CAT-", "");
        if (normalizedKey.length() == 1 || normalizedKey.length() == 2) {
            normalizedKey = String.format("%03d", Integer.parseInt(normalizedKey));
        }
        if (catalogs.containsKey(normalizedKey) && !catalogs.get(normalizedKey).isEmpty()) {
            return catalogs.get(normalizedKey);
        }
        if (ALIASES.containsKey(normalizedKey)) {
            for (String alias : ALIASES.get(normalizedKey)) {
                if (catalogs.containsKey(alias) && !catalogs.get(alias).isEmpty()) {
                    return catalogs.get(alias);
                }
            }
        }
        return catalogs.getOrDefault(normalizedKey, List.of());
    }

    public List<Map<String, Object>> getDepartamentos() {
        return Collections.unmodifiableList(departamentos);
    }

    public List<Map<String, Object>> getMunicipios(String depCode) {
        if (depCode == null || depCode.isBlank()) return Collections.unmodifiableList(municipios);
        return municipios.stream()
            .filter(m -> depCode.equals(m.get("departamento")))
            .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getDistritos(String depCode, String munCode) {
        return distritos.stream()
            .filter(d -> (depCode == null || depCode.isBlank() || depCode.equals(d.get("departamento"))) &&
                         (munCode == null || munCode.isBlank() || munCode.equals(d.get("municipio"))))
            .collect(Collectors.toList());
    }

    public List<Map<String, Object>> searchActividades(String q) {
        List<Map<String, Object>> act = getCatalog("019");
        if (q == null || q.isBlank()) return act.stream().limit(50).collect(Collectors.toList());
        String term = q.toLowerCase(Locale.ROOT).trim();
        return act.stream()
            .filter(m -> String.valueOf(m.get("codigo")).contains(term) ||
                         String.valueOf(m.get("descripcion")).toLowerCase(Locale.ROOT).contains(term))
            .limit(50)
            .collect(Collectors.toList());
    }

    public boolean isValidCode(String catKey, String code) {
        if (code == null || code.isBlank()) return false;
        List<Map<String, Object>> list = getCatalog(catKey);
        if (list.isEmpty()) return true;
        String paddedCode = code.length() == 1 ? "0" + code : code;
        return list.stream().anyMatch(m -> {
            String c = String.valueOf(m.get("codigo"));
            return code.equalsIgnoreCase(c) || paddedCode.equalsIgnoreCase(c);
        });
    }

    public String getDescripcion(String catKey, String code) {
        List<Map<String, Object>> list = getCatalog(catKey);
        String paddedCode = code != null && code.length() == 1 ? "0" + code : code;
        for (Map<String, Object> m : list) {
            String c = String.valueOf(m.get("codigo"));
            if (code != null && (code.equalsIgnoreCase(c) || paddedCode.equalsIgnoreCase(c))) {
                return String.valueOf(m.get("descripcion"));
            }
        }
        return null;
    }

    public void validateActividad(String code) {
        if (code == null || code.isBlank()) return;
        if (!isValidCode("019", code)) {
            if (!code.matches("[0-9]{5,6}")) {
                throw new IllegalArgumentException("Actividad económica '" + code + "' no es un código válido según CAT-019 del MH");
            }
        }
    }

    public void validateDireccion(String dep, String mun, String dist) {
        if (dep == null || dep.isBlank()) return;
        if (departamentos.stream().noneMatch(d -> dep.equals(d.get("codigo")))) {
            throw new IllegalArgumentException("Código de departamento '" + dep + "' no existe en el catálogo geográfico oficial del MH");
        }
        if (mun != null && !mun.isBlank()) {
            if (municipios.stream().noneMatch(m -> dep.equals(m.get("departamento")) && mun.equals(m.get("codigo")))) {
                if (!mun.matches("[0-9]{2}")) {
                    throw new IllegalArgumentException("Código de municipio '" + mun + "' inválido para el departamento " + dep);
                }
            }
        }
        if (dist != null && !dist.isBlank()) {
            if (distritos.stream().noneMatch(d -> dep.equals(d.get("departamento")) && (mun == null || mun.isBlank() || mun.equals(d.get("municipio"))) && dist.equals(d.get("codigo")))) {
                if (!dist.matches("[0-9]{2}")) {
                    throw new IllegalArgumentException("Código de distrito '" + dist + "' inválido según la división oficial del MH");
                }
            }
        }
    }

    public void validateTipoDocumento(String code) {
        if (code != null && !code.isBlank() && !isValidCode("011", code) && !isValidCode("022", code)) {
            if (!Set.of("36", "13", "02", "03", "37").contains(code)) {
                throw new IllegalArgumentException("Tipo de documento '" + code + "' no válido según CAT-011/CAT-022 del MH");
            }
        }
    }

    public void validateFormaPago(String code) {
        if (code != null && !code.isBlank() && !isValidCode("014", code) && !isValidCode("017", code)) {
            if (!Set.of("01", "02", "03", "04", "05", "08", "14", "99").contains(code)) {
                throw new IllegalArgumentException("Forma de pago '" + code + "' no válida según CAT-014 del MH");
            }
        }
    }

    public void validateUnidadMedida(int code) {
        String c = String.valueOf(code);
        if (!isValidCode("008", c) && !isValidCode("014", c)) {
            if (code <= 0 || code > 999) {
                throw new IllegalArgumentException("Unidad de medida " + code + " no válida según CAT-008 del MH");
            }
        }
    }

    public void validateTipoItem(int code) {
        if (code < 1 || code > 4) {
            throw new IllegalArgumentException("Tipo de ítem " + code + " no válido según CAT-009 del MH (1=Bien, 2=Servicio, 3=Ambos, 4=Otros)");
        }
    }

    public void validateCondicionOperacion(int code) {
        if (code < 1 || code > 3) {
            throw new IllegalArgumentException("Condición de operación " + code + " no válida según CAT-016 (1=Contado, 2=Crédito, 3=Otro)");
        }
    }

    public void validateTipoContingencia(int code) {
        if (code < 1 || code > 5) {
            throw new IllegalArgumentException("Tipo de contingencia " + code + " fuera del catálogo oficial CAT-005 (1 a 5)");
        }
    }

    public void validateTipoInvalidacion(int code) {
        if (code < 1 || code > 3) {
            throw new IllegalArgumentException("Tipo de invalidación " + code + " no válido según CAT-024 del MH (1=Rescisión, 2=Por error, 3=No realizada)");
        }
    }

    public void validateIncoterm(String code) {
        if (code != null && !code.isBlank() && !isValidCode("018", code) && !isValidCode("031", code)) {
            if (!Set.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11",
                        "EXW", "FCA", "FAS", "FOB", "CFR", "CIF", "CPT", "CIP", "DAP", "DPU", "DDP").contains(code.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Incoterm '" + code + "' no reconocido en CAT-018 del MH");
            }
        }
    }

    public Set<String> getAvailableCatalogs() {
        Set<String> keys = new TreeSet<>(catalogs.keySet());
        keys.add("departamentos");
        keys.add("municipios");
        keys.add("distritos");
        return keys;
    }
}
