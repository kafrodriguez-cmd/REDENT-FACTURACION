package sv.redent;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static sv.redent.Json.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:redenttest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","redent.admin-password=PruebaSoloLocal2026!"})
@AutoConfigureMockMvc @ActiveProfiles("test") @WithMockUser(username="admin",roles="ADMIN")
class WorkflowTest {
    @Autowired DocumentService docs;@Autowired Store store;@Autowired ApiController api;@Autowired SchemaService schemas;
    @Autowired MhWorkflow flow;@Autowired MockMvc mvc;@Autowired CatalogService catalog;@Autowired PdfService pdf;
    @MockitoBean MhClient mh;
    @BeforeEach void clean(){for(String t:List.of("email_delivery","attempt","audit","document","catalog","sequence_number","settings"))store.db().update("DELETE FROM "+t);api.company(Fixtures.company());}
    long draft(String type){return ((Number)docs.create(UUID.randomUUID().toString(),Fixtures.input(type)).get("id")).longValue();}
    void accept(long id){var d=store.doc(id,false);docs.result(id,null,obj("estado","PROCESADO","ambiente","00","codigoGeneracion",d.get("generation"),"selloRecibido","2026000000000000000000000000000000000000"),false);}
    @Test void repeatedRequestAndPrepareDoNotDuplicate(){
        String key=UUID.randomUUID().toString();var a=docs.create(key,Fixtures.input("01"));var b=docs.create(key,Fixtures.input("01"));assertThat(a.get("id")).isEqualTo(b.get("id"));
        long id=((Number)a.get("id")).longValue();var prepared=docs.prepare(id);assertThat(docs.prepare(id).get("generation")).isEqualTo(prepared.get("generation"));
        assertThatThrownBy(()->docs.create(key,Fixtures.input("03"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->docs.update(id,0,Fixtures.input("01"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentPreparationsUseUniqueNumbers()throws Exception {
        List<Long> ids=new ArrayList<>();for(int i=0;i<12;i++)ids.add(draft("01"));var pool=Executors.newFixedThreadPool(4);
        try {var futures=new ArrayList<Future<Map<String,Object>>>();for(long id:ids)futures.add(pool.submit(()->docs.prepare(id)));Set<Object> controls=new HashSet<>();for(var f:futures)controls.add(f.get(20,TimeUnit.SECONDS).get("control_number"));assertThat(controls).hasSize(12);}
        finally{pool.shutdownNow();}
    }
    @Test void unknownNetworkOutcomeIsNotResent(){
        long id=draft("01");docs.prepare(id);when(mh.authenticate(anyString())).thenReturn("Bearer test");when(mh.sign(any())).thenReturn("header.payload.signature");when(mh.transmit(anyString(),anyLong(),any(),anyString(),anyString())).thenThrow(new IllegalStateException("timeout"));
        assertThat(flow.send(id).get("status")).isEqualTo("INCIERTO");assertThatThrownBy(()->flow.send(id)).isInstanceOf(IllegalStateException.class);
        verify(mh,times(1)).transmit(anyString(),anyLong(),any(),anyString(),anyString());
        var d=store.doc(id,false);when(mh.query(any())).thenReturn(obj("estado","PROCESADO","ambiente","00","codigoGeneracion",d.get("generation"),"selloRecibido","TEST-SEAL"));
        assertThat(flow.query(id).get("status")).isEqualTo("ACEPTADO");
    }
    @Test void wrongDocumentInResponseIsNeverAccepted(){long id=draft("01");docs.prepare(id);docs.result(id,null,obj("estado","PROCESADO","ambiente","00","codigoGeneracion","WRONG","selloRecibido","TEST"),false);assertThat(store.doc(id,false).get("status")).isEqualTo("INCIERTO");}
    @Test void invalidationOnlyChangesOriginalAfterAcceptance(){
        long id=draft("03");docs.prepare(id);accept(id);
        var n=obj("tipo","INVALIDACION","documentoId",id,"codigoGeneracionR",null,"motivo",obj("tipoAnulacion",2,"motivoAnulacion","Operación rescindida en prueba","nombreResponsable","RESPONSABLE FICTICIO","tipDocResponsable","13","numDocResponsable","01234567-8","nombreSolicita","SOLICITANTE FICTICIO","tipDocSolicita","13","numDocSolicita","01234567-8"));
        long event=((Number)docs.create(UUID.randomUUID().toString(),n).get("id")).longValue();docs.prepare(event);
        assertThat(schemas.errors("INVALIDACION",parse((String)store.doc(event,false).get("json")))).isEmpty();assertThat(store.doc(id,false).get("status")).isEqualTo("ACEPTADO");
        docs.result(event,null,obj("estado","RECIBIDO","selloRecibido","TEST-EVENT-SEAL"),false);assertThat(store.doc(id,false).get("status")).isEqualTo("INVALIDADO");
    }
    @Test void creditNotesCannotExceedOriginal(){
        long original=draft("03");docs.prepare(original);accept(original);
        var input=Fixtures.input("05").put("documentoId",original);
        long first=((Number)docs.create(UUID.randomUUID().toString(),input).get("id")).longValue();docs.prepare(first);
        long second=((Number)docs.create(UUID.randomUUID().toString(),input).get("id")).longValue();assertThatThrownBy(()->docs.prepare(second)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void contingencyBlocksSendingUntilEventAccepted(){
        var input=Fixtures.input("01").put("contingencia",true).put("tipoContingencia",3).put("motivoContin","Falla de internet en prueba");
        long id=((Number)docs.create(UUID.randomUUID().toString(),input).get("id")).longValue();docs.prepare(id);
        assertThatThrownBy(()->docs.claim(id)).isInstanceOf(IllegalStateException.class);
        var doc=parse((String)store.doc(id,false).get("json"));String date=doc.at("/identificacion/fecEmi").asText(),time=doc.at("/identificacion/horEmi").asText();
        var n=obj("tipo","CONTINGENCIA","documentos",List.of(id),"responsable",obj("nombreResponsable","RESPONSABLE FICTICIO","tipoDocResponsable","13","numeroDocResponsable","01234567-8"),"motivo",obj("fInicio",date,"fFin",date,"hInicio",time,"hFin",time,"tipoContingencia",3,"motivoContingencia","Falla de internet en prueba"));
        long event=((Number)docs.create(UUID.randomUUID().toString(),n).get("id")).longValue();docs.prepare(event);docs.result(event,null,obj("estado","RECIBIDO","selloRecibido","TEST"),false);assertThat(docs.claim(id).get("id")).isEqualTo(id);
    }
    @Test void catalogImportIsAtomicAndRejectsDuplicateIdentity(){
        var one=Fixtures.client();var two=Fixtures.client().put("codigo","DIFFERENT");
        assertThatThrownBy(()->catalog.commit("clientes",arr().add(one).add(two))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(store.db().queryForObject("SELECT COUNT(*) FROM catalog",Integer.class)).isZero();
    }
    @Test void staleDraftEditIsRejected(){long id=draft("01");docs.update(id,0,Fixtures.input("01"));assertThatThrownBy(()->docs.update(id,0,Fixtures.input("01"))).isInstanceOf(IllegalStateException.class);}
    @Test void csrfAndReadOnlyRoleAreEnforced()throws Exception {
        mvc.perform(post("/api/documentos").contentType("application/json").content(Fixtures.input("01").toString()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(post("/api/documentos").with(user("reader").roles("CONSULTA")).with(csrf()).contentType("application/json").content(Fixtures.input("01").toString()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(get("/api/empresa").with(user("reader").roles("CONSULTA"))).andExpect(status().isOk());
    }
    @Test void producesMultipagePdfWithoutDroppingLongDescriptions()throws Exception {
        var n=Fixtures.input("01");var items=arr();for(int i=0;i<30;i++){var item=((com.fasterxml.jackson.databind.node.ObjectNode)n.at("/items/0")).deepCopy();item.put("descripcion","Producto ficticio de descripción extensa para comprobar paginación y continuidad "+i);items.add(item);}n.set("items",items);
        long id=((Number)docs.create(UUID.randomUUID().toString(),n).get("id")).longValue();docs.prepare(id);byte[] bytes=pdf.render(id);
        try(var p=org.apache.pdfbox.Loader.loadPDF(bytes)){String text=new org.apache.pdfbox.text.PDFTextStripper().getText(p);assertThat(p.getNumberOfPages()).isGreaterThan(1);assertThat(text).contains("continuidad 29","SIN VALIDEZ TRIBUTARIA","TOTALES");}
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/qa"));java.nio.file.Files.write(java.nio.file.Path.of("target/qa/multipagina.pdf"),bytes);
    }
}
