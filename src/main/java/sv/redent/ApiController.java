package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.*;
import org.springframework.core.env.Environment;
import java.util.*;
import java.time.LocalDate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static sv.redent.Json.*;

/**
 * Controlador API REST para facturación electrónica local,
 * integración con Ministerio de Hacienda de El Salvador y gestión de catálogos.
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final Store store;
    private final CatalogService catalog;
    private final DocumentService docs;
    private final MhWorkflow flow;
    private final MhClient mh;
    private final PdfService pdf;
    private final MailService mail;
    private final PasswordEncoder encoder;
    private final SchemaService schemas;
    private final Environment env;
    private final MhCatalogService mhCatalogs;

    @Autowired
    public ApiController(Store store,
                         CatalogService catalog,
                         DocumentService docs,
                         MhWorkflow flow,
                         MhClient mh,
                         PdfService pdf,
                         MailService mail,
                         PasswordEncoder encoder,
                         SchemaService schemas,
                         Environment env,
                         @Autowired(required = false) MhCatalogService mhCatalogs) {
        this.store = store;
        this.catalog = catalog;
        this.docs = docs;
        this.flow = flow;
        this.mh = mh;
        this.pdf = pdf;
        this.mail = mail;
        this.encoder = encoder;
        this.schemas = schemas;
        this.env = env;
        this.mhCatalogs = mhCatalogs;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken t) {
        return Map.of("token", t.getToken(), "header", t.getHeaderName());
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication a) {
        String amb = mh.ambiente();
        String ambLabel = "01".equals(amb) ? "PRODUCCIÓN" : "PRUEBAS (00)";
        return Map.of(
            "usuario", a.getName(),
            "permisos", a.getAuthorities(),
            "ambiente", ambLabel,
            "ambienteCodigo", amb,
            "mhHabilitado", mh.enabled(),
            "mhBaseUrl", mh.baseUrl(),
            "perfil", Arrays.asList(env.getActiveProfiles().length > 0 ? env.getActiveProfiles() : env.getDefaultProfiles())
        );
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of(
            "estados", store.db().queryForList("SELECT status,COUNT(*) AS cantidad,COALESCE(SUM(total),0) AS total FROM document WHERE LENGTH(kind)=2 GROUP BY status"),
            "clientes", store.db().queryForObject("SELECT COUNT(*) FROM catalog WHERE kind='clientes' AND active=TRUE", Integer.class),
            "productos", store.db().queryForObject("SELECT COUNT(*) FROM catalog WHERE kind='productos' AND active=TRUE", Integer.class)
        );
    }

    @GetMapping("/empresa")
    public JsonNode company() {
        return store.company();
    }

    @PutMapping("/empresa")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> company(@RequestBody ObjectNode c) {
        String nit = required(c, "nit").replace("-", "");
        if (!nit.matches("[0-9]{9}|[0-9]{14}")) {
            throw new IllegalArgumentException("NIT de 9 o 14 dígitos sin guiones");
        }
        c.put("nit", nit);

        if (!required(c, "codEstableMH").matches("[MBSP][0-9]{3}") || !required(c, "codPuntoVentaMH").matches("P[0-9]{3}")) {
            throw new IllegalArgumentException("Establecimiento y punto MH: formato M001 y P001; use los asignados por Hacienda");
        }
        required(c, "nombre");
        required(c, "correo");
        required(c, "nrc");

        if (mhCatalogs != null && c.hasNonNull("codActividad")) {
            mhCatalogs.validateActividad(c.path("codActividad").asText());
        }

        var previous = store.company();
        if (previous.has("nit") && !previous.path("nit").asText().equals(nit) &&
            store.db().queryForObject("SELECT COUNT(*) FROM document WHERE json IS NOT NULL", Integer.class) > 0) {
            throw new IllegalStateException("No cambie el emisor de una base con documentos preparados");
        }

        int n = store.db().update("UPDATE settings SET json=? WHERE name='empresa'", c.toString());
        if (n == 0) {
            store.db().update("INSERT INTO settings(name,json) VALUES('empresa',?)", c.toString());
        }

        String amb = c.path("ambiente").asText(mh.ambiente());
        for (String t : List.of("01", "03", "05", "06", "11", "14")) {
            String scope = amb + ":" + t + ":" + c.path("codEstableMH").asText() + c.path("codPuntoVentaMH").asText() + ":" + LocalDate.now(DteBuilder.ZONE).getYear();
            if (store.db().queryForObject("SELECT COUNT(*) FROM sequence_number WHERE scope=?", Integer.class, scope) == 0) {
                long last = c.path("ultimoCorrelativo").path(t).asLong(0);
                if (last < 0 || last > 999999999999998L) {
                    throw new IllegalArgumentException("Correlativo inicial inválido");
                }
                store.db().update("INSERT INTO sequence_number(scope,last_number) VALUES(?,?)", scope, last);
            }
        }
        store.audit("CONFIGURAR_EMPRESA", "empresa", "Datos fiscales guardados");
        return Map.of("ok", true);
    }

    @GetMapping("/catalogos/{kind}")
    public Object list(@PathVariable String kind, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) {
        catalog.kind(kind);
        if (page < 0) throw new IllegalArgumentException("Página inválida");
        return store.db().queryForList("SELECT id,code,name,json,active,revision FROM catalog WHERE kind=? AND LOWER(name) LIKE ? ORDER BY name LIMIT 100 OFFSET ?", kind, "%" + q.toLowerCase(Locale.ROOT) + "%", page * 100);
    }

    @PostMapping("/catalogos/{kind}")
    public Object createCatalog(@PathVariable String kind, @RequestBody JsonNode n) {
        return catalog.save(kind, null, n);
    }

    @PutMapping("/catalogos/{kind}/{id}")
    public Object updateCatalog(@PathVariable String kind, @PathVariable long id, @RequestBody JsonNode n) {
        return catalog.save(kind, id, n);
    }

    @PostMapping("/importar/{kind}/preview")
    public Object importPreview(@PathVariable String kind, @RequestParam MultipartFile file) throws Exception {
        if (file.isEmpty()) throw new IllegalArgumentException("Seleccione archivo Excel");
        try (var in = file.getInputStream()) {
            return catalog.preview(kind, in);
        }
    }

    @PostMapping("/importar/{kind}/confirmar")
    public Object importCommit(@PathVariable String kind, @RequestBody JsonNode n) {
        return catalog.commit(kind, n.path("filas"));
    }

    @GetMapping("/documentos")
    public Object documents(@RequestParam(defaultValue = "0") int page) {
        if (page < 0) throw new IllegalArgumentException("Página inválida");
        return store.db().queryForList("SELECT id,kind,status,generation,control_number,total,created_at,created_by,revision FROM document ORDER BY id DESC LIMIT 100 OFFSET ?", page * 100);
    }

    @GetMapping("/documentos/{id}")
    public Object document(@PathVariable long id) {
        return store.doc(id, false);
    }

    @PostMapping("/documentos")
    public Object create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode n) {
        return docs.create(key, n);
    }

    @PutMapping("/documentos/{id}")
    public Object update(@PathVariable long id, @RequestParam int revision, @RequestBody JsonNode n) {
        return docs.update(id, revision, n);
    }

    @GetMapping("/documentos/{id}/preview")
    public Object preview(@PathVariable long id) {
        return docs.preview(id);
    }

    @PostMapping("/documentos/{id}/preparar")
    public Object prepare(@PathVariable long id) {
        return docs.prepare(id);
    }

    @PostMapping("/documentos/{id}/transmitir")
    public Object send(@PathVariable long id) {
        return flow.send(id);
    }

    @PostMapping("/documentos/{id}/consultar")
    public Object query(@PathVariable long id) {
        return flow.query(id);
    }

    @PostMapping("/documentos/{id}/correo")
    public Object email(@PathVariable long id) throws Exception {
        mail.send(id);
        return Map.of("ok", true);
    }

    @GetMapping("/documentos/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long id) throws Exception {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "inline; filename=redent-" + id + ".pdf")
            .body(pdf.render(id));
    }

    @GetMapping("/documentos/{id}/archivos")
    public ResponseEntity<byte[]> files(@PathVariable long id) throws Exception {
        var d = store.doc(id, false);
        if (d.get("json") == null) throw new IllegalStateException("Prepare el documento");
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var pair : Map.of("dte.json", "json", "firma.jws", "signed_jws", "respuesta-mh.json", "response_json").entrySet()) {
                if (d.get(pair.getValue()) != null) {
                    zip.putNextEntry(new ZipEntry(pair.getKey()));
                    zip.write(d.get(pair.getValue()).toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            zip.putNextEntry(new ZipEntry("representacion.pdf"));
            zip.write(pdf.render(id));
            zip.closeEntry();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header("Content-Disposition", "attachment; filename=redent-" + id + ".zip")
            .body(output.toByteArray());
    }

    @GetMapping("/esquemas")
    public Object schemaVersions() {
        return SchemaService.FILES;
    }

    @GetMapping("/esquemas/{type}")
    public Object schema(@PathVariable String type) {
        return schemas.schema(type);
    }

    @PostMapping("/validar/{type}")
    public Object validate(@PathVariable String type, @RequestBody JsonNode n) {
        var errors = schemas.errors(type, n);
        return Map.of(
            "valido", errors.isEmpty(),
            "errores", errors,
            "alcance", "Validación estructural contra JSON Schema oficial del Ministerio de Hacienda"
        );
    }

    // Endpoints de Catálogos Oficiales del Ministerio de Hacienda de El Salvador
    @GetMapping("/mh-catalogos")
    public Object mhCatalogs() {
        if (mhCatalogs == null) return List.of();
        return mhCatalogs.getAvailableCatalogs();
    }

    @GetMapping("/mh-catalogos/{cat}")
    public Object mhCatalog(@PathVariable String cat) {
        if (mhCatalogs == null) return List.of();
        return mhCatalogs.getCatalog(cat);
    }

    @GetMapping("/mh-catalogos/departamentos")
    public Object mhDepartamentos() {
        if (mhCatalogs == null) return List.of();
        return mhCatalogs.getDepartamentos();
    }

    @GetMapping("/mh-catalogos/municipios")
    public Object mhMunicipios(@RequestParam(required = false) String departamento) {
        if (mhCatalogs == null) return List.of();
        return mhCatalogs.getMunicipios(departamento);
    }

    @GetMapping("/mh-catalogos/distritos")
    public Object mhDistritos(@RequestParam(required = false) String departamento, @RequestParam(required = false) String municipio) {
        if (mhCatalogs == null) return List.of();
        return mhCatalogs.getDistritos(departamento, municipio);
    }

    @GetMapping("/mh-catalogos/actividades")
    public Object mhActividades(@RequestParam(defaultValue = "") String q) {
        if (mhCatalogs == null) return List.of();
        return mhCatalogs.searchActividades(q);
    }

    @GetMapping("/usuarios")
    @PreAuthorize("hasRole('ADMIN')")
    public Object users() {
        return store.db().queryForList("SELECT username,role,enabled FROM app_user ORDER BY username");
    }

    @PostMapping("/usuarios")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Object user(@RequestBody JsonNode n) {
        store.db().queryForList("SELECT username FROM app_user ORDER BY username FOR UPDATE");
        if (store.db().queryForObject("SELECT COUNT(*) FROM app_user", Integer.class) >= 3) {
            throw new IllegalStateException("Esta instalación admite tres cuentas");
        }
        String user = required(n, "usuario"), password = required(n, "password"), role = required(n, "rol");
        if (!user.matches("[A-Za-z0-9_.-]{3,80}") || password.length() < 12 || !Set.of("ADMIN", "OPERADOR", "CONSULTA").contains(role)) {
            throw new IllegalArgumentException("Usuario, rol o contraseña inválidos (mínimo 12 caracteres)");
        }
        store.db().update("INSERT INTO app_user(username,password,role) VALUES(?,?,?)", user, encoder.encode(password), role);
        store.audit("CREAR_USUARIO", user, role);
        return Map.of("ok", true);
    }

    @PostMapping("/password")
    @Transactional
    public Object password(@RequestBody JsonNode n, Authentication a) {
        String old = required(n, "actual"), next = required(n, "nueva");
        String hash = store.db().queryForObject("SELECT password FROM app_user WHERE username=?", String.class, a.getName());
        if (!encoder.matches(old, hash) || next.length() < 12) {
            throw new IllegalArgumentException("Contraseña actual incorrecta o nueva menor a 12 caracteres");
        }
        store.db().update("UPDATE app_user SET password=? WHERE username=?", encoder.encode(next), a.getName());
        store.audit("CAMBIAR_PASSWORD", a.getName(), "Cambio solicitado por titular");
        return Map.of("ok", true);
    }

    @GetMapping("/auditoria")
    @PreAuthorize("hasRole('ADMIN')")
    public Object audit(@RequestParam(defaultValue = "0") int page) {
        if (page < 0) throw new IllegalArgumentException("Página inválida");
        return store.db().queryForList("SELECT * FROM audit ORDER BY id DESC LIMIT 100 OFFSET ?", page * 100);
    }

    @GetMapping("/documentos/{id}/intentos")
    public Object attempts(@PathVariable long id) {
        return store.db().queryForList("SELECT * FROM attempt WHERE document_id=? ORDER BY id", id);
    }
}
