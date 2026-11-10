package com.ticketya.gestionlugares;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GestionLugaresApplication {

    public static void main(String[] args) {
        SpringApplication.run(GestionLugaresApplication.class, args);
    }
}
