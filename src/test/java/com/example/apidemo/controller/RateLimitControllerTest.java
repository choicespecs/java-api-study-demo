package com.example.apidemo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void standardEndpoint_firstRequest_returnsRateLimitHeaders() throws Exception {
        mockMvc.perform(get("/api/rate/standard"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Rate-Limit-Limit"))
                .andExpect(header().exists("X-Rate-Limit-Remaining"));
    }

    @Test
    void strictEndpoint_exceedingLimit_returns429() throws Exception {
        // The strict bucket allows 5 requests — send 6 to trigger 429
        // Note: bucket state persists per-JVM, so this test depends on order.
        // In production tests, reset bucket state between tests.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/rate/strict"));
        }
        mockMvc.perform(get("/api/rate/strict"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
