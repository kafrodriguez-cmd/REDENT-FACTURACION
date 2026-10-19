package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * Servicio de Generación de Representación Gráfica Oficial (PDF) de Documentos Tributarios Electrónicos
 * Cumplimiento estricto de las directrices y columnas de la normativa del Ministerio de Hacienda de El Salvador.
 */
@Service
public class PdfService {
    private final Store store;
    private final SchemaService schemas;
    private final MhCatalogService catalogs;

    private static final Map<String, String> NAMES = Map.of(
        "01", "FACTURA",
        "03", "COMPROBANTE DE CRÉDITO FISCAL",
        "05", "NOTA DE CRÉDITO",
        "06", "NOTA DE DÉBITO",
        "11", "FACTURA DE EXPORTACIÓN",
        "14", "FACTURA DE SUJETO EXCLUIDO",
        "INVALIDACION", "EVENTO DE INVALIDACIÓN",
        "CONTINGENCIA", "EVENTO DE CONTINGENCIA"
    );

    @Autowired
    public PdfService(Store store, SchemaService schemas, @Autowired(required = false) MhCatalogService catalogs) {
        this.store = store;
        this.schemas = schemas;
        this.catalogs = catalogs;
    }

    public byte[] render(long id) throws Exception {
        var row = store.doc(id, false);
        if (row.get("json") == null) {
            throw new IllegalStateException("Prepare el documento antes de generar su representación gráfica");
        }

        JsonNode d = Json.parse((String) row.get("json"));
        String kind = (String) row.get("kind");
        String docAmbiente = d.at("/identificacion/ambiente").asText("00");
        boolean isPruebas = !"01".equals(docAmbiente);

        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Layout l = new Layout(pdf);

            // Encabezado Emisor y Título
            JsonNode emisor = d.path("emisor");
            String nombreEmisor = emisor.path("nombre").asText("REDENT");
            String nombreComercial = emisor.path("nombreComercial").asText("");

            l.line(nombreEmisor, 14, true);
            if (!nombreComercial.isBlank() && !nombreComercial.equals(nombreEmisor)) {
                l.line("Nombre comercial: " + nombreComercial, 9, true);
            }
            if (emisor.hasNonNull("nit")) l.line("NIT: " + emisor.path("nit").asText(), 9, false);
            if (emisor.hasNonNull("nrc")) l.line("NRC: " + emisor.path("nrc").asText(), 9, false);
            if (emisor.hasNonNull("descActividad")) l.line("Giro / Actividad: " + emisor.path("descActividad").asText(), 8, false);

            JsonNode dirEmisor = emisor.path("direccion");
            if (dirEmisor.isObject()) {
                String comp = dirEmisor.path("complemento").asText("");
                String dep = dirEmisor.path("departamento").asText("");
                String mun = dirEmisor.path("municipio").asText("");
                String dist = dirEmisor.path("distrito").asText("");
                String dirStr = comp;
                if (!dist.isBlank() || !mun.isBlank() || !dep.isBlank()) {
                    dirStr += (dirStr.isBlank() ? "" : ", ") + "Dist. " + dist + ", Mun. " + mun + ", Depto. " + dep;
                }
                if (!dirStr.isBlank()) l.line("Dirección: " + dirStr, 8, false);
            }
            if (emisor.hasNonNull("telefono")) l.line("Teléfono: " + emisor.path("telefono").asText() + (emisor.hasNonNull("correo") ? "  ·  Correo: " + emisor.path("correo").asText() : ""), 8, false);

            l.space(8);
            l.divider();

            // Identificación del Documento Tributario Electrónico
            String docTitle = NAMES.getOrDefault(kind, "DOCUMENTO TRIBUTARIO ELECTRÓNICO");
            l.line(docTitle, 12, true);
            if (isPruebas) {
                l.line("AMBIENTE DE PRUEBAS · SIN VALIDEZ TRIBUTARIA", 9, true);
            } else {
                l.line("DOCUMENTO TRIBUTARIO ELECTRÓNICO (DTE)", 9, true);
            }

            l.line("Estado: " + row.get("status") + "   ·   Ambiente: " + (isPruebas ? "00 (Pruebas)" : "01 (Producción)"), 8, false);
            l.line("Código de generación (UUID): " + row.get("generation"), 8, true);
            if (row.get("control_number") != null) {
                l.line("Número de control: " + row.get("control_number"), 8, true);
            }

            String fecEmi = d.at("/identificacion/fecEmi").asText(d.at("/identificacion/fTransmision").asText(""));
            String horEmi = d.at("/identificacion/horEmi").asText(d.at("/identificacion/hTransmision").asText(""));
            l.line("Fecha y hora de emisión: " + fecEmi + " " + horEmi, 8, false);

            int tipoModelo = d.at("/identificacion/tipoModelo").asInt(1);
            int tipoOperacion = d.at("/identificacion/tipoOperacion").asInt(1);
            l.line("Modelo de facturación: " + (tipoModelo == 1 ? "Previo" : "Diferido") + "   ·   Tipo transmisión: " + (tipoOperacion == 1 ? "Normal" : "Contingencia"), 8, false);

            String seal = Objects.toString(row.get("seal"), isPruebas ? "Pendiente de recepción MH" : "Pendiente");
            l.line("Sello de recepción MH: " + seal, 8, false);

            // Código QR Oficial del MH
            if (kind.length() == 2 && fecEmi.length() >= 10) {
                String gen = Objects.toString(row.get("generation"), "");
                String qrUrl = "https://admin.factura.gob.sv/consultaPublica?ambiente=" + docAmbiente + "&codGen=" + gen + "&fechaEmi=" + fecEmi;
                try {
                    var bits = new QRCodeWriter().encode(qrUrl, BarcodeFormat.QR_CODE, 130, 130);
                    BufferedImage img = new BufferedImage(130, 130, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < 130; y++) {
                        for (int x = 0; x < 130; x++) {
                            img.setRGB(x, y, bits.get(x, y) ? 0 : 0xFFFFFF);
                        }
                    }
                    if (l.y < 160) l.newPage();
                    l.stream.drawImage(LosslessFactory.createFromImage(pdf, img), 460, l.y - 70, 75, 75);
                } catch (Exception ignored) {}
            }

            l.space(10);
            l.divider();

            // Datos del Receptor
            JsonNode receptor = d.path("receptor");
            if (receptor.isObject() && !receptor.isEmpty()) {
                l.heading("DATOS DEL RECEPTOR", 10);
                String nombreRec = receptor.path("nombre").asText("Consumidor Final");
                l.line("Nombre / Razón Social: " + nombreRec, 8, true);

                StringBuilder docsInfo = new StringBuilder();
                if (receptor.hasNonNull("tipoDocumento") && receptor.hasNonNull("numDocumento")) {
                    String td = receptor.path("tipoDocumento").asText();
                    String tdLabel = td.equals("13") ? "DUI" : td.equals("36") ? "NIT" : td.equals("03") ? "Pasaporte" : "Doc";
                    docsInfo.append(tdLabel).append(": ").append(receptor.path("numDocumento").asText()).append("   ");
                }
                if (receptor.hasNonNull("nrc") && !receptor.path("nrc").asText().isBlank()) {
                    docsInfo.append("NRC: ").append(receptor.path("nrc").asText()).append("   ");
                }
                if (receptor.hasNonNull("nit") && docsInfo.indexOf("NIT") == -1) {
                    docsInfo.append("NIT: ").append(receptor.path("nit").asText()).append("   ");
                }
                if (!docsInfo.isEmpty()) {
                    l.line(docsInfo.toString().trim(), 8, false);
                }

                if (receptor.hasNonNull("descActividad") && !receptor.path("descActividad").asText().isBlank()) {
                    l.line("Actividad económica: " + receptor.path("descActividad").asText(), 8, false);
                }

                JsonNode dirRec = receptor.path("direccion");
                if (dirRec.isObject()) {
                    String dirStr = dirRec.path("complemento").asText("");
                    if (!dirStr.isBlank()) l.line("Dirección: " + dirStr, 8, false);
                } else if (receptor.hasNonNull("complemento")) {
                    l.line("Dirección / Complemento: " + receptor.path("complemento").asText(), 8, false);
                }

                StringBuilder contact = new StringBuilder();
                if (receptor.hasNonNull("telefono")) contact.append("Teléfono: ").append(receptor.path("telefono").asText()).append("   ");
                if (receptor.hasNonNull("correo")) contact.append("Correo: ").append(receptor.path("correo").asText());
                if (!contact.isEmpty()) l.line(contact.toString().trim(), 8, false);

                l.space(4);
            }

            // Documentos Relacionados
            if (d.has("documentoRelacionado") && d.get("documentoRelacionado").isArray() && !d.get("documentoRelacionado").isEmpty()) {
                l.heading("DOCUMENTO RELACIONADO", 9);
                for (JsonNode rel : d.path("documentoRelacionado")) {
                    String tdoc = rel.path("tipoDocumento").asText();
                    String tdocName = NAMES.getOrDefault(tdoc, "DTE " + tdoc);
                    l.line("Tipo: " + tdocName + "  ·  N° / UUID: " + rel.path("numeroDocumento").asText() + "  ·  Emisión: " + rel.path("fechaEmision").asText(), 8, false);
                }
                l.space(4);
            }

            // Cuerpo del Documento (Tabla de Ítems)
            if (d.has("cuerpoDocumento") && d.get("cuerpoDocumento").isArray()) {
                l.heading("DETALLE DE OPERACIÓN", 10);
                l.divider();
                for (JsonNode item : d.path("cuerpoDocumento")) {
                    String itemNum = item.path("numItem").asText();
                    String desc = item.path("descripcion").asText();
                    String code = item.path("codigo").asText("");
                    String qty = item.path("cantidad").asText();
                    String price = item.path("precioUni").asText();
                    String disc = item.path("montoDescu").asText("0.00");
                    String grav = item.path("ventaGravada").asText("0.00");
                    String exen = item.path("ventaExenta").asText("0.00");
                    String nosuj = item.path("ventaNoSuj").asText("0.00");

                    l.line(itemNum + ". " + (code.isBlank() ? "" : "[" + code + "] ") + desc, 8, true);
                    String subline = "   Cant: " + qty + "   Precio Uni: $" + price + "   Desc: $" + disc + "   Gravada: $" + grav;
                    if (!"0.00".equals(exen)) subline += "   Exenta: $" + exen;
                    if (!"0.00".equals(nosuj)) subline += "   No Sujeta: $" + nosuj;
                    l.line(subline, 8, false);
                }
                l.divider();
            }

            // Resumen de Totales
            if (d.has("resumen")) {
                JsonNode res = d.path("resumen");
                l.heading("RESUMEN DE TOTALES (USD)", 10);

                if (res.hasNonNull("totalNoSuj") && res.path("totalNoSuj").asDouble() > 0) {
                    l.line("Total operaciones no sujetas: $" + res.path("totalNoSuj").asText(), 8, false);
                }
                if (res.hasNonNull("totalExenta") && res.path("totalExenta").asDouble() > 0) {
                    l.line("Total operaciones exentas: $" + res.path("totalExenta").asText(), 8, false);
                }
                l.line("Total operaciones gravadas: $" + res.path("totalGravada").asText(res.path("totalCompra").asText("0.00")), 8, false);

                if (res.hasNonNull("totalDescu") && res.path("totalDescu").asDouble() > 0) {
                    l.line("Total descuentos aplicados: -$" + res.path("totalDescu").asText(), 8, false);
                }
                l.line("Subtotal de ventas: $" + res.path("subTotal").asText(res.path("subTotalVentas").asText("0.00")), 8, false);

                // Desglose de IVA en CCF o indicación en Factura
                if (kind.equals("03") || kind.equals("05") || kind.equals("06")) {
                    if (res.has("tributos") && res.get("tributos").isArray()) {
                        for (JsonNode trib : res.path("tributos")) {
                            l.line(trib.path("descripcion").asText("IVA 13%") + ": $" + trib.path("valor").asText(), 8, false);
                        }
                    }
                } else if (kind.equals("01")) {
                    l.line("IVA incluido (13%): $" + res.path("totalIva").asText("0.00") + " (precios unitarios gravados incluyen IVA)", 7, false);
                }

                // Retenciones
                if (res.hasNonNull("ivaRete") && res.path("ivaRete").asDouble() > 0) {
                    l.line("Menos Retención IVA 1%: -$" + res.path("ivaRete").asText(), 8, false);
                }
                if (res.hasNonNull("reteRenta") && res.path("reteRenta").asDouble() > 0) {
                    l.line("Menos Retención Renta 10% (Art. 156-A): -$" + res.path("reteRenta").asText(), 8, false);
                }

                // Flete y seguro en exportación
                if (res.hasNonNull("flete") && res.path("flete").asDouble() > 0) {
                    l.line("Flete de exportación: $" + res.path("flete").asText(), 8, false);
                }
                if (res.hasNonNull("seguro") && res.path("seguro").asDouble() > 0) {
                    l.line("Seguro de exportación: $" + res.path("seguro").asText(), 8, false);
                }

                l.line("TOTAL A PAGAR: $" + res.path("totalPagar").asText("0.00"), 10, true);
                if (res.hasNonNull("totalLetras")) {
                    l.line("SON: " + res.path("totalLetras").asText(), 8, true);
                }

                // Condiciones y Formas de Pago
                int cond = res.path("condicionOperacion").asInt(1);
                String condStr = cond == 1 ? "Contado" : cond == 2 ? "Crédito" : "Otro";
                l.line("Condición de la operación: " + condStr, 8, false);

                if (res.has("pagos") && res.get("pagos").isArray()) {
                    for (JsonNode p : res.path("pagos")) {
                        String codPago = p.path("codigo").asText("01");
                        String descPago = catalogs != null ? catalogs.getDescripcion("014", codPago) : null;
                        if (descPago == null) {
                            descPago = codPago.equals("01") ? "Efectivo" : codPago.equals("02") ? "Tarjeta Débito" : codPago.equals("03") ? "Tarjeta Crédito" : "Otros";
                        }
                        String pagoStr = "Forma de pago: " + descPago + " · Monto: $" + p.path("montoPago").asText();
                        if (p.hasNonNull("periodo") && p.path("periodo").asInt() > 0) {
                            pagoStr += " · Plazo: " + p.path("periodo").asInt() + " días";
                        }
                        l.line(pagoStr, 8, false);
                    }
                }

                if (res.hasNonNull("observaciones") && !res.path("observaciones").asText().isBlank()) {
                    l.space(2);
                    l.line("Observaciones: " + res.path("observaciones").asText(), 8, false);
                }
            }

            // Eventos especiales (Invalidación y Contingencia)
            for (String s : List.of("documento", "motivo", "detalleDTE")) {
                if (d.hasNonNull(s)) {
                    l.heading(s.toUpperCase(Locale.ROOT), 9);
                    l.fields(d.get(s), schemas.schema(kind).at("/properties/" + s), "");
                }
            }

            // Leyendas Obligatorias de la Normativa de Hacienda
            l.space(12);
            l.divider();
            l.line("Documento Tributario Electrónico emitido de conformidad a la normativa de la Dirección General de Impuestos Internos del Ministerio de Hacienda de El Salvador.", 7, false);
            l.line("Consulte la autenticidad de este documento en https://admin.factura.gob.sv/consultaPublica", 7, false);

            l.finish();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static class Layout {
        final PDDocument doc;
        PDPageContentStream stream;
        float y;
        int page = 0;
        final PDFont normal, bold;

        Layout(PDDocument doc) throws IOException {
            this.doc = doc;
            this.normal = PDType0Font.load(doc, getClass().getResourceAsStream("/fonts/DejaVuSans.ttf"));
            this.bold = PDType0Font.load(doc, getClass().getResourceAsStream("/fonts/DejaVuSans-Bold.ttf"));
            newPage();
        }

        void newPage() throws IOException {
            if (stream != null) {
                footer();
                stream.close();
            }
            PDPage p = new PDPage(PDRectangle.A4);
            doc.addPage(p);
            stream = new PDPageContentStream(doc, p);
            y = 800;
            page++;
        }

        void footer() throws IOException {
            stream.beginText();
            stream.setFont(normal, 7);
            stream.newLineAtOffset(42, 22);
            stream.showText("REDENT · Sistema de Facturación Electrónica · Página " + page);
            stream.endText();
        }

        void finish() throws IOException {
            footer();
            stream.close();
        }

        void space(float h) throws IOException {
            if (y - h < 45) newPage();
            y -= h;
        }

        void divider() throws IOException {
            if (y - 5 < 45) newPage();
            stream.setLineWidth(0.5f);
            stream.moveTo(42, y);
            stream.lineTo(553, y);
            stream.stroke();
            y -= 6;
        }

        void heading(String text, int size) throws IOException {
            space(6);
            line(text, size, true);
            space(2);
        }

        String safe(String text) {
            if (text == null) return "";
            StringBuilder b = new StringBuilder();
            for (char c : text.replace('\n', ' ').replace('\r', ' ').toCharArray()) {
                try {
                    normal.encode(String.valueOf(c));
                    b.append(c);
                } catch (Exception e) {
                    b.append('?');
                }
            }
            return b.toString();
        }

        void line(String text, int size, boolean weight) throws IOException {
            StringBuilder current = new StringBuilder();
            PDFont font = weight ? bold : normal;
            for (String word : safe(text).split(" ")) {
                if (font.getStringWidth(current + word) * size / 1000 > 510 && !current.isEmpty()) {
                    draw(current.toString(), size, font);
                    current.setLength(0);
                }
                while (font.getStringWidth(word) * size / 1000 > 500) {
                    int cut = Math.min(65, word.length());
                    draw(word.substring(0, cut), size, font);
                    word = word.substring(cut);
                }
                current.append(word).append(' ');
            }
            if (!current.isEmpty()) draw(current.toString(), size, font);
        }

        void draw(String t, int size, PDFont f) throws IOException {
            if (y - size - 4 < 45) newPage();
            stream.beginText();
            stream.setFont(f, size);
            stream.newLineAtOffset(42, y);
            stream.showText(t);
            stream.endText();
            y -= size + 4;
        }

        void fields(JsonNode n, JsonNode schema, String prefix) throws IOException {
            if (n == null || n.isNull() || n.isMissingNode()) return;
            if (n.isArray()) {
                int i = 0;
                for (JsonNode e : n) fields(e, schema.path("items"), prefix + (++i) + ". ");
                return;
            }
            if (n.isObject()) {
                var it = n.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    JsonNode s = schema.path("properties").path(e.getKey());
                    String label = s.path("description").asText(e.getKey());
                    fields(e.getValue(), s, prefix + label + ": ");
                }
                return;
            }
            line(prefix + n.asText(), 8, false);
        }
    }
}
