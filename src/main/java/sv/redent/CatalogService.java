package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.apache.poi.ss.usermodel.*;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import static sv.redent.Json.*;

/**
 * Servicio de administración de catálogos locales (clientes y productos)
 * con validación contra la normativa oficial del Ministerio de Hacienda.
 */
@Service
public class CatalogService {
    private final Store store;
    private final MhCatalogService mhCatalogs;

    @Autowired
    public CatalogService(Store store, @Autowired(required = false) MhCatalogService mhCatalogs) {
        this.store = store;
        this.mhCatalogs = mhCatalogs;
    }

    public CatalogService(Store store) {
        this(store, null);
    }

    public void kind(String kind) {
        if (!Set.of("clientes", "productos").contains(kind)) {
            throw new IllegalArgumentException("Catálogo inválido");
        }
    }

    public ObjectNode normalize(String kind, JsonNode source) {
        kind(kind);
        ObjectNode n = source.deepCopy();
        n.put("codigo", required(n, "codigo")).put("nombre", required(n, "nombre"));
        if (n.path("codigo").asText().length() > 100 || n.path("nombre").asText().length() > 250) {
            throw new IllegalArgumentException("Código o nombre demasiado largo");
        }

        if (kind.equals("productos")) {
            BigDecimal price = decimal(n, "precio");
            if (price.signum() < 0 || price.scale() > 8) {
                throw new IllegalArgumentException("Precio inválido; máximo 8 decimales");
            }
            int unit = n.path("uniMedida").asInt(59);
            int itemType = n.path("tipoItem").asInt(1);
            if (mhCatalogs != null) {
                mhCatalogs.validateUnidadMedida(unit);
                mhCatalogs.validateTipoItem(itemType);
            }
            n.put("precio", price).put("uniMedida", unit).put("tipoItem", itemType);
            if (itemType < 1 || itemType > 4) {
                throw new IllegalArgumentException("Tipo de ítem soportado: 1 bien, 2 servicio");
            }
            String op = n.path("operacion").asText("GRAVADA");
            if (!Set.of("GRAVADA", "EXENTA", "NO_SUJETA").contains(op)) {
                throw new IllegalArgumentException("Operación tributaria inválida");
            }
            n.put("operacion", op);
        } else {
            for (String key : List.of("nit", "nrc", "tipoDocumento", "numDocumento", "codActividad", "descActividad", "nombreComercial", "telefono", "correo", "codPais", "nombrePais", "complemento")) {
                if (!n.hasNonNull(key) || n.path(key).asText().isBlank()) {
                    n.putNull(key);
                } else {
                    n.put(key, n.path(key).asText().trim());
                }
            }

            if (n.hasNonNull("nit")) {
                String nit = n.path("nit").asText().replace("-", "");
                if (!nit.matches("[0-9]{9}|[0-9]{14}")) {
                    throw new IllegalArgumentException("NIT debe contener 9 o 14 dígitos numéricos");
                }
                n.put("nit", nit);
                if (!n.hasNonNull("numDocumento")) {
                    n.put("tipoDocumento", "36").put("numDocumento", nit);
                }
            }

            if (n.hasNonNull("tipoDocumento")) {
                String td = n.path("tipoDocumento").asText();
                if (mhCatalogs != null) {
                    mhCatalogs.validateTipoDocumento(td);
                }
                if (td.equals("13") && n.hasNonNull("numDocumento")) {
                    String dui = n.path("numDocumento").asText().replace("-", "");
                    if (!dui.matches("[0-9]{9}")) {
                        throw new IllegalArgumentException("DUI debe contener 9 dígitos (formato ########-#)");
                    }
                }
            }

            if (n.hasNonNull("codActividad")) {
                String codAct = n.path("codActividad").asText();
                if (mhCatalogs != null) {
                    mhCatalogs.validateActividad(codAct);
                    if (!n.hasNonNull("descActividad") || n.path("descActividad").asText().isBlank()) {
                        String desc = mhCatalogs.getDescripcion("019", codAct);
                        if (desc != null) n.put("descActividad", desc);
                    }
                }
            }

            if (n.hasNonNull("correo") && !n.path("correo").asText().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new IllegalArgumentException("Correo electrónico inválido");
            }

            if (n.hasNonNull("tipoPersona")) {
                int person = n.path("tipoPersona").asInt();
                if (person != 1 && person != 2) {
                    throw new IllegalArgumentException("tipoPersona debe ser 1 (Natural) o 2 (Jurídica)");
                }
                n.put("tipoPersona", person);
            }

            if (!n.has("direccion")) {
                n.set("direccion", obj(
                    "departamento", n.path("departamento").asText(""),
                    "municipio", n.path("municipio").asText(""),
                    "distrito", n.path("distrito").asText(""),
                    "complemento", n.path("direccionComplemento").asText("")
                ));
            }

            if (mhCatalogs != null) {
                JsonNode dir = n.path("direccion");
                mhCatalogs.validateDireccion(
                    dir.path("departamento").asText(null),
                    dir.path("municipio").asText(null),
                    dir.path("distrito").asText(null)
                );
            }
        }
        return n;
    }

    @Transactional
    public Map<String, Object> save(String kind, Long id, JsonNode source) {
        ObjectNode n = normalize(kind, source);
        String code = n.path("codigo").asText();
        String identity = kind.equals("clientes") && n.hasNonNull("numDocumento") ?
            n.path("tipoDocumento").asText() + ":" + n.path("numDocumento").asText().replace("-", "") : null;

        if (id == null) {
            var k = new GeneratedKeyHolder();
            store.db().update(c -> {
                var p = c.prepareStatement("INSERT INTO catalog(kind,code,name,json,identity_key) VALUES(?,?,?,?,?)", new String[]{"id"});
                p.setString(1, kind);
                p.setString(2, code);
                p.setString(3, n.path("nombre").asText());
                p.setString(4, n.toString());
                p.setString(5, identity);
                return p;
            }, k);
            id = Objects.requireNonNull(k.getKey()).longValue();
        } else {
            int changed = store.db().update(
                "UPDATE catalog SET code=?,name=?,json=?,identity_key=?,active=?,revision=revision+1 WHERE id=? AND kind=? AND revision=?",
                code, n.path("nombre").asText(), n.toString(), identity, n.path("activo").asBoolean(true), id, kind, n.path("revision").asInt(-1));
            if (changed != 1) {
                throw new IllegalStateException("Registro cambiado por otra persona; recargue la página");
            }
        }
        store.audit("GUARDAR_CATALOGO", id, kind + " " + code);
        return Map.of("id", id);
    }

    public ObjectNode preview(String kind, InputStream stream) throws Exception {
        kind(kind);
        ArrayNode rows = arr(), errors = arr();
        Set<String> codes = new HashSet<>();

        try (Workbook book = WorkbookFactory.create(stream)) {
            Sheet sheet = book.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || sheet.getLastRowNum() > 5000) {
                throw new IllegalArgumentException("Encabezados en primera fila; máximo 5000 registros por archivo");
            }
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            List<String> headers = new ArrayList<>();
            for (Cell c : header) headers.add(fmt.formatCellValue(c).trim());
            if (!headers.containsAll(List.of("codigo", "nombre"))) {
                throw new IllegalArgumentException("Faltan columnas obligatorias 'codigo' y 'nombre'. Use la plantilla oficial");
            }
            if (new HashSet<>(headers).size() != headers.size()) {
                throw new IllegalArgumentException("Encabezados duplicados en el archivo");
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                ObjectNode n = obj();
                boolean any = false;
                try {
                    for (int j = 0; j < headers.size(); j++) {
                        Cell c = row.getCell(j);
                        String value = c == null ? "" : fmt.formatCellValue(c).trim();
                        any |= !value.isEmpty();
                        if (c != null && c.getCellType() == CellType.FORMULA) {
                            throw new IllegalArgumentException("Pegue valores; no se aceptan fórmulas");
                        }
                        String key = headers.get(j);
                        if (c != null && c.getCellType() == CellType.NUMERIC && Set.of("nit", "nrc", "numDocumento", "tipoDocumento", "departamento", "municipio", "distrito", "codActividad").contains(key)) {
                            throw new IllegalArgumentException(key + " debe ser texto para conservar ceros iniciales");
                        }
                        if (!value.isBlank()) n.put(key, value);
                    }
                    if (!any) continue;
                    n = normalize(kind, n);
                    String code = n.path("codigo").asText();
                    if (!codes.add(code) || store.db().queryForObject("SELECT count(*) FROM catalog WHERE kind=? AND code=?", Integer.class, kind, code) > 0) {
                        throw new IllegalArgumentException("Código duplicado: " + code);
                    }
                    rows.add(n);
                } catch (IllegalArgumentException e) {
                    errors.add(obj("fila", i + 1, "mensaje", e.getMessage()));
                }
            }
        }
        return obj("filas", rows, "errores", errors, "puedeImportar", errors.isEmpty() && !rows.isEmpty());
    }

    @Transactional
    public Map<String, Object> commit(String kind, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 5000) {
            throw new IllegalArgumentException("Importación vacía o demasiado grande");
        }
        for (JsonNode row : rows) save(kind, null, row);
        store.audit("IMPORTAR_EXCEL", kind, "Filas " + rows.size());
        return Map.of("importados", rows.size());
    }
}
