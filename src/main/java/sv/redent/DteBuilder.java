package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static sv.redent.Json.*;
import static sv.redent.Money.*;

/**
 * Generador y validador de Documentos Tributarios Electrónicos (DTE)
 * Cumplimiento estricto de la normativa de la Dirección General de Impuestos Internos
 * del Ministerio de Hacienda de El Salvador (V2.0 y esquemas JSON oficiales).
 */
@Service
public class DteBuilder {
    public static final ZoneId ZONE = ZoneId.of("America/El_Salvador");

    private final SchemaService schemas;
    private final MhCatalogService catalogs;
    private final String defaultAmbiente;

    @Autowired
    public DteBuilder(SchemaService schemas,
                      @Autowired(required = false) MhCatalogService catalogs,
                      @Value("${redent.mh.ambiente:00}") String defaultAmbiente) {
        this.schemas = schemas;
        this.catalogs = catalogs;
        this.defaultAmbiente = (defaultAmbiente != null && !defaultAmbiente.isBlank()) ? defaultAmbiente : "00";
    }

    public DteBuilder(SchemaService schemas, MhCatalogService catalogs) {
        this(schemas, catalogs, "00");
    }

    public DteBuilder(SchemaService schemas) {
        this(schemas, null, "00");
    }

    public ObjectNode build(String type, JsonNode input, JsonNode company, String generation, String control, JsonNode related) {
        boolean cf = type.equals("01"), ccf = type.equals("03"), note = Set.of("05", "06").contains(type);
        boolean export = type.equals("11"), excluded = type.equals("14");
        if (!Set.of("01", "03", "05", "06", "11", "14").contains(type)) {
            throw new IllegalArgumentException("Tipo DTE '" + type + "' no soportado por la normativa");
        }

        String targetAmbiente = company.hasNonNull("ambiente") ? company.path("ambiente").asText() : defaultAmbiente;
        if (!Set.of("00", "01").contains(targetAmbiente)) {
            targetAmbiente = "00";
        }

        var now = ZonedDateTime.now(ZONE);
        ObjectNode id = obj(
            "version", schemas.version(type),
            "ambiente", targetAmbiente,
            "tipoDte", type,
            "numeroControl", control,
            "codigoGeneracion", generation,
            "tipoModelo", 1,
            "tipoOperacion", 1,
            "tipoContingencia", null,
            "motivoContin", null,
            "fecEmi", now.toLocalDate().toString(),
            "horEmi", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            "tipoMoneda", "USD",
            "fusion", null
        );

        // Modelo de Contingencia
        if (input.path("contingencia").asBoolean()) {
            int reason = input.path("tipoContingencia").asInt();
            if (catalogs != null) {
                catalogs.validateTipoContingencia(reason);
            } else if (reason < 1 || reason > 5) {
                throw new IllegalArgumentException("Seleccione motivo de contingencia válido (1 a 5 según CAT-005)");
            }
            id.put("tipoModelo", 2).put("tipoOperacion", 2).put("tipoContingencia", reason);
            id.set("motivoContin", input.has("motivoContin") ? input.get("motivoContin") : MAPPER.nullNode());
        }

        ObjectNode root = obj();
        root.set("identificacion", schemas.section(type, "identificacion", id));

        // Validación y construcción de Emisor
        ObjectNode emitter = company.deepCopy();
        validateEmitter(emitter, export, input);
        root.set("emisor", schemas.section(type, "emisor", emitter));

        // Validación y construcción de Receptor
        JsonNode recipient = input.path("cliente");
        ArrayNode relation = arr();

        if (note) {
            if (related == null) {
                throw new IllegalArgumentException("La nota requiere documento relacionado aceptado");
            }
            String rt = related.at("/identificacion/tipoDte").asText();
            if (!Set.of("01", "03").contains(rt)) {
                throw new IllegalArgumentException("Esta entrega genera notas sobre CCF (03) o Factura (01); otros casos requieren revisión fiscal");
            }
            ObjectNode r = related.path("receptor").deepCopy();
            if (r.has("nit") && (!r.hasNonNull("tipoDocumento") || r.path("tipoDocumento").asText().isBlank())) {
                r.put("tipoDocumento", "36").put("numDocumento", r.path("nit").asText());
            }
            recipient = r;
            relation.add(obj(
                "tipoDocumento", rt,
                "tipoGeneracion", 2,
                "numeroDocumento", related.at("/identificacion/codigoGeneracion"),
                "fechaEmision", related.at("/identificacion/fecEmi")
            ));
        }

        // Para Factura 01: Si no se especifica receptor, aplicar Consumidor Final genérico
        if (cf && (recipient == null || recipient.isMissingNode() || recipient.isNull() || recipient.isEmpty())) {
            recipient = obj(
                "tipoDocumento", null,
                "numDocumento", null,
                "nrc", null,
                "nombre", "Consumidor Final",
                "codActividad", null,
                "descActividad", null,
                "direccion", null,
                "telefono", null,
                "correo", null
            );
        }

        validateRecipient(type, recipient);
        root.set("receptor", schemas.section(type, "receptor", recipient));

        if (schemas.schema(type).path("properties").has("documentoRelacionado")) {
            root.set("documentoRelacionado", note ? relation : MAPPER.nullNode());
        }
        for (String s : List.of("otrosDocumentos", "ventaTercero", "compraTercero", "apendice")) {
            if (schemas.schema(type).path("properties").has(s)) {
                root.set(s, MAPPER.nullNode());
            }
        }

        // Construcción y validación de Cuerpo del Documento
        ArrayNode lines = arr();
        BigDecimal taxable = BigDecimal.ZERO, exempt = BigDecimal.ZERO, nonSubject = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO, discounts = BigDecimal.ZERO;

        JsonNode items = input.path("items");
        if (!items.isArray() || items.isEmpty() || items.size() > 2000) {
            throw new IllegalArgumentException("Incluya de 1 a 2000 líneas");
        }

        boolean relatedIsCcf = note && related != null && "03".equals(related.at("/identificacion/tipoDte").asText());
        int index = 0;

        for (JsonNode item : items) {
            BigDecimal qty = decimal(item, "cantidad");
            BigDecimal price = decimal(item, "precio");
            BigDecimal discount = decimal(item, "descuento");

            if (qty.signum() <= 0 || price.signum() < 0 || discount.signum() < 0 ||
                qty.scale() > 8 || price.scale() > 8 || discount.scale() > 2) {
                throw new IllegalArgumentException("Cantidad positiva; precio no negativo (máximo 8 decimales), descuento máximo 2 decimales");
            }
            if (discount.compareTo(qty.multiply(price)) > 0) {
                throw new IllegalArgumentException("Descuento mayor al importe de la línea");
            }

            String tax = item.path("operacion").asText("GRAVADA");
            if (!Set.of("GRAVADA", "EXENTA", "NO_SUJETA").contains(tax)) {
                throw new IllegalArgumentException("Operación tributaria inválida: " + tax);
            }
            if ((export || excluded) && !tax.equals("GRAVADA")) {
                throw new IllegalArgumentException("En exportación o sujeto excluido utilice la operación estándar del documento");
            }

            int itemType = item.path("tipoItem").asInt(1);
            int unitMeasure = item.path("uniMedida").asInt(59);
            if (catalogs != null) {
                catalogs.validateTipoItem(itemType);
                catalogs.validateUnidadMedida(unitMeasure);
            }

            boolean taxed = tax.equals("GRAVADA") && !export && !excluded;

            // Factura 01 serializa importes con IVA incluido. CCF 03 serializa importes netos.
            if (cf && taxed) {
                price = price.multiply(BigDecimal.ONE.add(IVA)).setScale(8, RoundingMode.HALF_UP);
                discount = round(discount.multiply(BigDecimal.ONE.add(IVA)));
            }

            BigDecimal amount = round(qty.multiply(price).subtract(discount));
            BigDecimal lineVat = taxed ? (cf ? round(amount.multiply(IVA).divide(BigDecimal.ONE.add(IVA), 12, RoundingMode.HALF_UP)) : round(amount.multiply(IVA))) : BigDecimal.ZERO;

            BigDecimal g = tax.equals("GRAVADA") ? amount : BigDecimal.ZERO;
            BigDecimal e = tax.equals("EXENTA") ? amount : BigDecimal.ZERO;
            BigDecimal n = tax.equals("NO_SUJETA") ? amount : BigDecimal.ZERO;

            taxable = taxable.add(g);
            exempt = exempt.add(e);
            nonSubject = nonSubject.add(n);
            vat = vat.add(lineVat);
            discounts = discounts.add(discount);

            // En notas sobre CCF los tributos deben contener ["20"] si es gravado
            List<String> lineTaxes = null;
            if (taxed && (ccf || (note && relatedIsCcf))) {
                lineTaxes = List.of("20");
            }

            ObjectNode row = obj(
                "numItem", ++index,
                "tipoItem", itemType,
                "numeroDocumento", note ? related.at("/identificacion/codigoGeneracion") : null,
                "cantidad", qty,
                "codigo", item.path("codigo").asText(null),
                "codTributo", null,
                "uniMedida", unitMeasure,
                "descripcion", required(item, "descripcion"),
                "precioUni", price,
                "montoDescu", discount,
                "ventaNoSuj", n,
                "ventaExenta", e,
                "ventaGravada", g,
                "tributos", lineTaxes,
                "psv", 0,
                "noGravado", 0,
                "ivaItem", lineVat,
                "compra", amount,
                "ivaPerci", 0,
                "totalIva", lineVat,
                "ivaRete", 0
            );
            lines.add(schemas.section(type, "cuerpoDocumento", row));
        }

        if (ccf || (note && relatedIsCcf)) {
            vat = round(taxable.multiply(IVA));
        }

        BigDecimal subtotal = taxable.add(exempt).add(nonSubject);
        BigDecimal operation = subtotal.add((ccf || (note && relatedIsCcf)) ? vat : BigDecimal.ZERO);

        // Retenciones
        BigDecimal retention = decimal(input, excluded ? "retencionRenta" : "retencionIva");
        if (retention.signum() < 0 || retention.scale() > 2 || retention.compareTo(excluded ? subtotal : vat) > 0) {
            throw new IllegalArgumentException("Retención fuera del importe permitido");
        }
        if (export && retention.signum() != 0) {
            throw new IllegalArgumentException("Retención no aplicable en este flujo de exportación");
        }
        if (note && retention.signum() != 0) {
            throw new IllegalArgumentException("Notas con retención requieren revisión específica; flujo bloqueado");
        }

        // Flete y seguro en exportación
        BigDecimal seguro = BigDecimal.ZERO;
        BigDecimal flete = BigDecimal.ZERO;
        if (export) {
            JsonNode x = input.path("exportacion");
            seguro = decimal(x, "seguro");
            flete = decimal(x, "flete");
            if (seguro.signum() < 0 || flete.signum() < 0 || seguro.scale() > 2 || flete.scale() > 2) {
                throw new IllegalArgumentException("Flete y seguro deben ser importes positivos con máximo 2 decimales");
            }
            boolean fleteSeguroPermitido = x.path("permitirFleteSeguro").asBoolean(false) || x.path("confirmado").asBoolean(false);
            if ((seguro.signum() != 0 || flete.signum() != 0) && !fleteSeguroPermitido) {
                throw new IllegalArgumentException("Flete y seguro separados requieren validación fiscal adicional; flujo bloqueado");
            }
            operation = operation.add(seguro).add(flete);
        }

        BigDecimal payable = round(operation.subtract(retention));
        if (payable.signum() < 0 || payable.compareTo(new BigDecimal("999999999.99")) > 0) {
            throw new IllegalArgumentException("Total fuera del rango soportado");
        }

        // Control de Factura >= $25,000 según Art. 119-G del Código Tributario
        if (cf && payable.compareTo(new BigDecimal("25000.00")) >= 0) {
            if (!recipient.hasNonNull("numDocumento") || recipient.path("numDocumento").asText().isBlank()) {
                throw new IllegalArgumentException("Operaciones iguales o superiores a $25,000.00 requieren número de documento del receptor según normativa MH");
            }
        }

        int condition = input.path("condicionOperacion").asInt(1);
        if (catalogs != null) {
            catalogs.validateCondicionOperacion(condition);
        } else if (condition != 1 && condition != 2) {
            throw new IllegalArgumentException("Seleccione contado (1) o crédito (2)");
        }

        String paymentMethod = input.path("formaPago").asText("01");
        if (catalogs != null) {
            catalogs.validateFormaPago(paymentMethod);
        }

        ObjectNode pay = obj(
            "codigo", paymentMethod,
            "montoPago", payable,
            "referencia", null,
            "plazo", null,
            "periodo", null
        );

        if (condition == 2) {
            int days = input.path("diasCredito").asInt();
            if (days <= 0) {
                throw new IllegalArgumentException("Indique días de crédito");
            }
            pay.put("plazo", "01").put("periodo", days);
        }

        // Resumen
        List<ObjectNode> tributosResumen = null;
        if ((ccf || (note && relatedIsCcf)) && vat.signum() > 0) {
            tributosResumen = List.of(obj(
                "codigo", "20",
                "descripcion", "Impuesto al Valor Agregado 13%",
                "valor", vat
            ));
        }

        ObjectNode summary = obj(
            "totalNoSuj", nonSubject,
            "totalExenta", exempt,
            "totalGravada", taxable,
            "subTotalVentas", subtotal,
            "descuNoSuj", 0,
            "descuExenta", 0,
            "descuGravada", 0,
            "porcentajeDescuento", 0,
            "totalDescu", discounts,
            "tributos", tributosResumen,
            "subTotal", subtotal,
            "ivaPerci", 0,
            "ivaRete", excluded ? BigDecimal.ZERO : retention,
            "reteRenta", excluded ? retention : BigDecimal.ZERO,
            "montoTotalOperacion", operation,
            "totalNoGravado", 0,
            "totalPagar", payable,
            "totalLetras", words(payable),
            "totalIva", vat,
            "saldoFavor", 0,
            "condicionOperacion", condition,
            "pagos", List.of(pay),
            "numPagoElectronico", null,
            "observaciones", input.path("observaciones").asText(null),
            "totalCompra", subtotal,
            "descu", 0,
            "seguro", seguro,
            "flete", flete,
            "totalNoOnerosas", 0,
            "codIncoterms", null,
            "descIncoterms", null,
            "codigoRetencionMH", null
        );

        if (export) {
            JsonNode x = input.path("exportacion");
            String incoterm = x.path("codIncoterms").asText("01");
            if (catalogs != null) {
                catalogs.validateIncoterm(incoterm);
            }
            summary.set("codIncoterms", x.has("codIncoterms") ? x.get("codIncoterms") : MAPPER.nullNode());
            summary.set("descIncoterms", x.has("descIncoterms") ? x.get("descIncoterms") : MAPPER.nullNode());
        }

        root.set("cuerpoDocumento", lines);
        root.set("resumen", schemas.section(type, "resumen", summary));

        // Validación estricta con JSON Schema oficial de Hacienda
        schemas.validate(type, root);
        return root;
    }

    private void validateEmitter(ObjectNode emitter, boolean export, JsonNode input) {
        String nit = required(emitter, "nit").replace("-", "");
        if (!nit.matches("[0-9]{9}|[0-9]{14}")) {
            throw new IllegalArgumentException("NIT del emisor debe tener 9 o 14 dígitos");
        }
        emitter.put("nit", nit);

        String codEstableMH = required(emitter, "codEstableMH");
        String codPuntoVentaMH = required(emitter, "codPuntoVentaMH");
        if (codEstableMH.length() != 4 || codPuntoVentaMH.length() != 4) {
            throw new IllegalArgumentException("Códigos de establecimiento y punto de venta MH deben tener 4 caracteres");
        }

        if (catalogs != null) {
            if (emitter.hasNonNull("codActividad")) {
                catalogs.validateActividad(emitter.path("codActividad").asText());
            }
            JsonNode dir = emitter.path("direccion");
            if (dir.isObject()) {
                catalogs.validateDireccion(
                    dir.path("departamento").asText(null),
                    dir.path("municipio").asText(null),
                    dir.path("distrito").asText(null)
                );
            }
        }

        if (export) {
            JsonNode x = input.path("exportacion");
            for (String k : List.of("tipoItemExpor", "recintoFiscal", "tipoRegimen", "regimen")) {
                emitter.set(k, x.path(k));
            }
        }
    }

    private void validateRecipient(String type, JsonNode recipient) {
        if (recipient == null || recipient.isNull() || recipient.isMissingNode()) return;

        if (type.equals("03")) { // CCF
            required(recipient, "nit");
            required(recipient, "nrc");
            required(recipient, "nombre");
            required(recipient, "codActividad");
            required(recipient, "descActividad");
            if (catalogs != null) {
                catalogs.validateActividad(recipient.path("codActividad").asText());
                JsonNode dir = recipient.path("direccion");
                if (dir.isObject()) {
                    catalogs.validateDireccion(
                        dir.path("departamento").asText(null),
                        dir.path("municipio").asText(null),
                        dir.path("distrito").asText(null)
                    );
                }
            }
        } else if (type.equals("14")) { // Sujeto Excluido
            required(recipient, "nombre");
            required(recipient, "tipoDocumento");
            required(recipient, "numDocumento");
        } else if (type.equals("11")) { // Exportación
            required(recipient, "nombre");
            required(recipient, "codPais");
            required(recipient, "nombrePais");
            required(recipient, "complemento");
            if (recipient.hasNonNull("codPais")) {
                String cp = recipient.path("codPais").asText();
                if (!cp.matches("[0-9]{4}")) {
                    throw new IllegalArgumentException("Código de país debe contener 4 dígitos según CAT-020 del MH");
                }
            }
        }
    }
}
