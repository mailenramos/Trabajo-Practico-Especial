package com.ticketya.usuarios.seguridad;

import com.ticketya.usuarios.config.JwtProperties;
import com.ticketya.usuarios.modelo.Usuario;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Emite JWT HS256 con sub, email, roles, iss, iat y exp. No incluye datos sensibles. */
@Service
public class TokenService {

    private final SecretKey clave;
    private final JwtProperties propiedades;
    private final Clock clock;

    public TokenService(JwtProperties propiedades, Clock clock) {
        this.propiedades = propiedades;
        this.clock = clock;
        this.clave = Keys.hmacShaKeyFor(propiedades.secret().getBytes(StandardCharsets.UTF_8));
    }

    public TokenEmitido emitir(Usuario usuario) {
        Instant ahora = clock.instant();
        Instant vence = ahora.plus(propiedades.expiracion());
        List<String> roles = usuario.getRoles().stream().map(Enum::name).sorted().toList();
        String token = Jwts.builder()
                .subject(usuario.getId().toString())
                .issuer(propiedades.issuer())
                .claim("email", usuario.getCorreo())
                .claim("roles", roles)
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(vence))
                .signWith(clave, Jwts.SIG.HS256)
                .compact();
        return new TokenEmitido(token, propiedades.expiracion().toSeconds());
    }

    public record TokenEmitido(String token, long expiraEnSegundos) {
    }
}
