package sv.redent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;

class DteBuilderTest {
    final SchemaService schemas = new SchemaService();
    final MhCatalogService catalogs = new MhCatalogService(new ObjectMapper());
    final DteBuilder builder = new DteBuilder(schemas, catalogs, "00");
    final String generation = "A12B3456-7890-4ABC-8DEF-123456789ABC";

    @ParameterizedTest
    @ValueSource(strings = {"01", "03", "05", "06", "11", "14"})
    void buildsAllSixAgainstOfficialSchemas(String type) {
        var original = builder.build("03", Fixtures.input("03"), Fixtures.company(), generation, "DTE-03-M001P001-000000000000001", null);
        var doc = builder.build(type, Fixtures.input(type), Fixtures.company(), generation, "DTE-" + type + "-M001P001-000000000000002", original);
        assertThat(schemas.errors(type, doc)).isEmpty();
        assertThat(Json.decimal(doc.path("resumen"), "totalPagar")).isEqualByComparingTo(type.equals("11") || type.equals("14") ? "190.00" : "214.70");
        if (type.equals("01")) {
            assertThat(doc.at("/cuerpoDocumento/0/precioUni").decimalValue()).isEqualByComparingTo("113");
            assertThat(doc.at("/resumen/totalIva").decimalValue()).isEqualByComparingTo("24.70");
        }
        if (type.equals("03")) {
            assertThat(doc.at("/resumen/tributos/0/valor").decimalValue()).isEqualByComparingTo("24.70");
        }
    }

    @Test
    void factura01WithoutCustomerUsesConsumidorFinalAndValidates() {
        var input = Fixtures.input("01");
        ((com.fasterxml.jackson.databind.node.ObjectNode) input).set("cliente", Json.MAPPER.nullNode());
        var doc = builder.build("01", input, Fixtures.company(), generation, "DTE-01-M001P001-000000000000001", null);
        assertThat(schemas.errors("01", doc)).isEmpty();
        assertThat(doc.at("/receptor/nombre").asText()).isEqualTo("Consumidor Final");
        assertThat(doc.at("/receptor/tipoDocumento").isNull()).isTrue();
    }

    @Test
    void creditNoteOnFactura01ValidatesAgainstOfficialSchema() {
        var fact = builder.build("01", Fixtures.input("01"), Fixtures.company(), generation, "DTE-01-M001P001-000000000000001", null);
        var inputNc = Fixtures.input("05");
        var nc = builder.build("05", inputNc, Fixtures.company(), "B12B3456-7890-4ABC-8DEF-123456789ABD", "DTE-05-M001P001-000000000000001", fact);
        assertThat(schemas.errors("05", nc)).isEmpty();
        assertThat(nc.at("/documentoRelacionado/0/tipoDocumento").asText()).isEqualTo("01");
    }

    @Test
    void exportWithConfirmedFreightAndInsuranceCalculatesProperly() {
        var n = Fixtures.input("11");
        var x = (com.fasterxml.jackson.databind.node.ObjectNode) n.path("exportacion");
        x.put("flete", 25.50).put("seguro", 10.00).put("permitirFleteSeguro", true);
        var doc = builder.build("11", n, Fixtures.company(), generation, "DTE-11-M001P001-000000000000001", null);
        assertThat(schemas.errors("11", doc)).isEmpty();
        // 190.00 base + 25.50 flete + 10.00 seguro = 225.50
        assertThat(doc.at("/resumen/montoTotalOperacion").decimalValue()).isEqualByComparingTo("225.50");
        assertThat(doc.at("/resumen/totalPagar").decimalValue()).isEqualByComparingTo("225.50");
    }

    @Test
    void sujetoExcluidoWithRentRetentionCalculatesProperly() {
        var n = Fixtures.input("14");
        ((com.fasterxml.jackson.databind.node.ObjectNode) n).put("retencionRenta", 19.00); // 10% de 190.00
        var doc = builder.build("14", n, Fixtures.company(), generation, "DTE-14-M001P001-000000000000001", null);
        assertThat(schemas.errors("14", doc)).isEmpty();
        assertThat(doc.at("/resumen/reteRenta").decimalValue()).isEqualByComparingTo("19.00");
        assertThat(doc.at("/resumen/totalPagar").decimalValue()).isEqualByComparingTo("171.00");
    }

    @Test
    void rejectsNegativeQuantityAndExcessDiscount() {
        var input = Fixtures.input("01");
        ((com.fasterxml.jackson.databind.node.ObjectNode) input.at("/items/0")).put("cantidad", -1);
        assertThatThrownBy(() -> builder.build("01", input, Fixtures.company(), generation, "DTE-01-M001P001-000000000000001", null)).isInstanceOf(IllegalArgumentException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) input.at("/items/0")).put("cantidad", 1).put("descuento", 101);
        assertThatThrownBy(() -> builder.build("01", input, Fixtures.company(), generation, "DTE-01-M001P001-000000000000001", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exemptionDoesNotAddVat() {
        var input = Fixtures.input("01");
        ((com.fasterxml.jackson.databind.node.ObjectNode) input.at("/items/0")).put("operacion", "EXENTA");
        var d = builder.build("01", input, Fixtures.company(), generation, "DTE-01-M001P001-000000000000001", null);
        assertThat(d.at("/resumen/totalIva").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.at("/resumen/totalExenta").decimalValue()).isEqualByComparingTo("190");
    }

    @Test
    void preventsSilentlyIgnoringExportCharges() {
        var n = Fixtures.input("11");
        ((com.fasterxml.jackson.databind.node.ObjectNode) n.path("exportacion")).put("flete", 20);
        assertThatThrownBy(() -> builder.build("11", n, Fixtures.company(), generation, "DTE-11-M001P001-000000000000001", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spellsAmount() {
        assertThat(Money.words(new BigDecimal("214.70"))).isEqualTo("DOSCIENTOS CATORCE DÓLARES CON 70/100");
    }

    @Test
    void catalogsProvideOfficialData() {
        assertThat(catalogs.getDepartamentos()).hasSize(14);
        assertThat(catalogs.getMunicipios("06")).isNotEmpty(); // San Salvador
        assertThat(catalogs.isValidCode("014", "01")).isTrue(); // Efectivo
        assertThat(catalogs.isValidCode("014", "02")).isTrue(); // Tarjeta Débito
        assertThat(catalogs.isValidCode("014", "59")).isTrue(); // Unidad (59)
        assertThat(catalogs.isValidCode("019", "86203")).isTrue(); // Odontología
    }
}
