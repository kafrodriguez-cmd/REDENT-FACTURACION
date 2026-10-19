package sv.redent;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpMethod;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService users(JdbcTemplate db) {
        return name -> {
            var rows=db.queryForList("SELECT * FROM app_user WHERE username=?",name);
            if(rows.isEmpty()) throw new UsernameNotFoundException("Usuario no encontrado");
            var u=rows.get(0);
            return User.withUsername(name).password((String)u.get("password")).roles((String)u.get("role"))
                .disabled(!Boolean.TRUE.equals(u.get("enabled"))).build();
        };
    }
    @Bean SecurityFilterChain security(HttpSecurity h) throws Exception {
        return h.csrf(c -> c.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/","/index.html","/app.js","/style.css","/api/csrf","/error").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/**").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/password").authenticated()
                .requestMatchers("/api/**").hasAnyRole("ADMIN","OPERADOR").anyRequest().authenticated())
            .formLogin(f -> f.loginProcessingUrl("/login").successHandler((req,res,auth)->{res.setContentType("application/json");res.getWriter().write("{\"ok\":true}");})
                .failureHandler((req,res,e)->res.sendError(401,"Credenciales inválidas")).permitAll())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler((req,res,a)->res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,x)->res.sendError(401)))
            .headers(x -> x.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
            .build();
    }
    @Bean ApplicationRunner bootstrap(JdbcTemplate db,PasswordEncoder encoder,
        @Value("${redent.admin-user}") String user,@Value("${redent.admin-password}") String password,
        @Value("${redent.mh.ambiente}") String ambiente) {
        return args -> {
            // This delivery is intentionally restricted to MH's test environment.
            if (!"00".equals(ambiente)) throw new IllegalStateException("Entrega de pruebas: ambiente productivo bloqueado hasta homologación");
            if (db.queryForObject("SELECT count(*) FROM app_user",Integer.class)==0) {
                if (password.length()<12 || !user.matches("[A-Za-z0-9_.-]{3,80}"))
                    throw new IllegalStateException("Primer inicio: defina REDENT_ADMIN_PASSWORD (mínimo 12 caracteres) y usuario válido");
                db.update("INSERT INTO app_user(username,password,role) VALUES(?,?,'ADMIN')",user,encoder.encode(password));
            }
            db.update("UPDATE document SET status='INCIERTO' WHERE status='ENVIANDO'");
            db.update("UPDATE document SET status='PREPARADO' WHERE status='FIRMANDO'");
            db.update("UPDATE email_delivery SET status='INCIERTO' WHERE status='ENVIANDO'");
        };
    }
}
