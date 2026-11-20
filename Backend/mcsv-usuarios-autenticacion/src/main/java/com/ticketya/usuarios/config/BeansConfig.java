package com.ticketya.usuarios.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration
public class BeansConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** BCrypt genera un salt aleatorio por contraseña y lo guarda dentro del hash. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
