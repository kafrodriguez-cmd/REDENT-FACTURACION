package sv.redent;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;

@Repository
public class Store {
    private final JdbcTemplate jdbc;
    public JdbcTemplate db() { return jdbc; }
    public Store(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Map<String,Object> doc(long id, boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM document WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Documento no encontrado");
        return rows.get(0);
    }
    public JsonNode company() {
        var rows=jdbc.queryForList("SELECT json FROM settings WHERE name='empresa'",String.class);
        return rows.isEmpty()?Json.obj():Json.parse(rows.get(0));
    }
    public String actor() {
        var a=SecurityContextHolder.getContext().getAuthentication();
        return a==null?"sistema":a.getName();
    }
    public void audit(String action,Object id,String detail) {
        jdbc.update("INSERT INTO audit(actor,action,entity,detail) VALUES(?,?,?,?)",actor(),action,id.toString(),detail.substring(0,Math.min(detail.length(),1000)));
    }
    public long next(String scope) {
        // Serialize annual series creation, then keep the row lock until commit.
        jdbc.queryForList("SELECT name FROM settings WHERE name='empresa' FOR UPDATE");
        var rows=jdbc.queryForList("SELECT last_number FROM sequence_number WHERE scope=? FOR UPDATE",scope);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO sequence_number(scope,last_number) VALUES(?,0)",scope);
            rows=jdbc.queryForList("SELECT last_number FROM sequence_number WHERE scope=? FOR UPDATE",scope);
        }
        long n=((Number)rows.get(0).get("last_number")).longValue()+1;
        jdbc.update("UPDATE sequence_number SET last_number=? WHERE scope=?",n,scope);
        return n;
    }
}
