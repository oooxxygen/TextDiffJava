package com.textdiff;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticServingTest {
    @Autowired TestRestTemplate rest;

    @Test
    void rootServesFrontend() {
        ResponseEntity<String> r = rest.getForEntity("/", String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody() != null && r.getBody().contains("<"));
    }

    @Test
    void apiNotShadowedByStatic() {
        ResponseEntity<String> r = rest.getForEntity("/api/health", String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().contains("ok"));
    }
}
