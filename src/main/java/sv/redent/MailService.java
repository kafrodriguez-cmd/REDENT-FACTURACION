package sv.redent;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MailService {
    private final Store store;private final PdfService pdf;private final JavaMailSender mail;private final boolean enabled;private final String from;
    public MailService(Store store,PdfService pdf,JavaMailSender mail,@Value("${redent.mail.enabled}")boolean enabled,@Value("${spring.mail.username}")String from){this.store=store;this.pdf=pdf;this.mail=mail;this.enabled=enabled;this.from=from;}
    public void send(long id) throws Exception {
        if(!enabled||from.isBlank())throw new IllegalStateException("Configure SMTP y habilite redent.mail.enabled en el servidor");
        var d=store.doc(id,false);
        if(!"ACEPTADO".equals(d.get("status")) || ((String)d.get("kind")).length()!=2)throw new IllegalStateException("Solo se envían DTE aceptados");
        var json=Json.parse((String)d.get("json"));String to=Json.required(json.path("receptor"),"correo");
        var m=mail.createMimeMessage();var h=new MimeMessageHelper(m,true,StandardCharsets.UTF_8.name());
        h.setFrom(from);h.setTo(to);h.setSubject("REDENT — Documento de PRUEBAS "+d.get("control_number"));
        h.setText("Adjuntamos la representación y los archivos electrónicos del documento de PRUEBAS. No tiene validez tributaria.");
        h.addAttachment("documento.pdf",new ByteArrayResource(pdf.render(id)),"application/pdf");
        h.addAttachment("dte.json",new ByteArrayResource(((String)d.get("json")).getBytes(StandardCharsets.UTF_8)),"application/json");
        h.addAttachment("firma.jws",new ByteArrayResource(((String)d.get("signed_jws")).getBytes(StandardCharsets.UTF_8)),"text/plain");
        h.addAttachment("respuesta-mh.json",new ByteArrayResource(((String)d.get("response_json")).getBytes(StandardCharsets.UTF_8)),"application/json");
        var k=new GeneratedKeyHolder();store.db().update(c->{var p=c.prepareStatement("INSERT INTO email_delivery(document_id,recipient,status) VALUES(?,?,'ENVIANDO')",new String[]{"id"});p.setLong(1,id);p.setString(2,to);return p;},k);
        long delivery=Objects.requireNonNull(k.getKey()).longValue();
        try {mail.send(m);store.db().update("UPDATE email_delivery SET status='ENVIADO' WHERE id=?",delivery);store.audit("ENVIAR_CORREO",id,"Entrega "+delivery);}
        catch(Exception e){store.db().update("UPDATE email_delivery SET status='INCIERTO' WHERE id=?",delivery);throw new IllegalStateException("SMTP no confirmó la entrega; revise el buzón antes de repetir");}
    }
}
