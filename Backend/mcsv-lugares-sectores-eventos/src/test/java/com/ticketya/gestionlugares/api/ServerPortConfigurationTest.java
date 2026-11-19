package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.ticketya.gestionlugares.outbox.EventoCancelacionOutboxBackfill;
import com.ticketya.gestionlugares.outbox.OutboxScheduler;
import com.ticketya.gestionlugares.service.EventoFinalizationScheduler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"PORT=8182", "spring.profiles.active=test", "spring.data.mongodb.auto-index-creation=false",
                "spring.data.mongodb.uri=mongodb://127.0.0.1:1/port-test?directConnection=true"})
class ServerPortConfigurationTest {

    @MockBean private EventoCancelacionOutboxBackfill backfill;
    @MockBean private OutboxScheduler outboxScheduler;
    @MockBean private EventoFinalizationScheduler finalizationScheduler;

    @Autowired
    private Environment environment;

    @Test
    void variablePortSobrescribeElPuertoPredeterminado() {
        assertEquals("8182", environment.getProperty("server.port"));
    }
}
