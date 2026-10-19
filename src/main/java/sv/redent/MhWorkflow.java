package sv.redent;

import org.springframework.stereotype.Service;
import java.util.*;
import static sv.redent.Json.*;

@Service
public class MhWorkflow {
    private final MhClient mh;private final DocumentService docs;private final Store store;
    public MhWorkflow(MhClient mh,DocumentService docs,Store store) {this.mh=mh;this.docs=docs;this.store=store;}
    public Map<String,Object> send(long id) {
        mh.requireReady();var d=docs.claim(id);var json=parse((String)d.get("json"));String signed,token;
        try {token=mh.authenticate(json.at("/emisor/nit").asText());signed=mh.sign(json);}
        catch(RuntimeException e) {docs.signingFailed(id);throw e;}
        long attempt=docs.sending(id,signed);
        try {docs.result(id,attempt,mh.transmit((String)d.get("kind"),attempt,json,signed,token),false);}
        catch(RuntimeException e) {docs.result(id,attempt,obj("estado","INCIERTO","mensaje",e.getMessage()),false);}
        return store.doc(id,false);
    }
    public Map<String,Object> query(long id) {
        var d=store.doc(id,false);
        if(((String)d.get("kind")).length()!=2) throw new IllegalStateException("La API de consulta DTE no confirma eventos. Revise el evento en el portal MH; no lo reenvíe a ciegas");
        if(!Set.of("INCIERTO","ACEPTADO","INVALIDADO","RECHAZADO").contains(d.get("status"))) throw new IllegalStateException("Documento sin transmisión terminada");
        docs.result(id,null,mh.query(parse((String)d.get("json"))),true);return store.doc(id,false);
    }
}
