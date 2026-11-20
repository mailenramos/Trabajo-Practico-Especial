package ar.edu.unicen.ms_fila_virtual;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MsFilaVirtualApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsFilaVirtualApplication.class, args);
    }
}
