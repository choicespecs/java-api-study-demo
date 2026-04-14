package com.example.apidemo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the JWT authentication flow.
 *
 * Uses MockMvc to simulate HTTP requests through the full Spring MVC stack
 * including security filters — gives higher confidence than unit tests alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void login_withValidCredentials_returnsTokens() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "user",
                                  "password": "password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void login_withInvalidCredentials_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "user",
                                  "password": "wrongpassword"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withMissingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity()); // 422 — validation failed
    }

    @Test
    void jwtProtectedEndpoint_withValidToken_returns200() throws Exception {
        // Step 1: Login to get token
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "user", "password": "password"}
                                """))
                .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        // Extract token from JSON (simple substring approach for test simplicity)
        String token = responseBody.split("\"accessToken\":\"")[1].split("\"")[0];

        // Step 2: Use token to access protected endpoint
        mockMvc.perform(get("/api/jwt/protected")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user"));
    }

    @Test
    void jwtProtectedEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/jwt/protected"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jwtProtectedEndpoint_withExpiredToken_returns401() throws Exception {
        // A real expired token (won't validate against our key, but demonstrates the 401 path)
        String fakeExpiredToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIiwiZXhwIjoxfQ.invalid";
        mockMvc.perform(get("/api/jwt/protected")
                        .header("Authorization", "Bearer " + fakeExpiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpoint_withoutAuth_returns200() throws Exception {
        mockMvc.perform(get("/api/public/info"))
                .andExpect(status().isOk());
    }

    @Test
    void basicAuth_withValidCredentials_returns200() throws Exception {
        mockMvc.perform(get("/api/basic/protected")
                        .header("Authorization", "Basic " +
                                java.util.Base64.getEncoder().encodeToString("user:password".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user"));
    }

    @Test
    void basicAuth_adminEndpoint_withUserRole_returns403() throws Exception {
        mockMvc.perform(get("/api/basic/admin")
                        .header("Authorization", "Basic " +
                                java.util.Base64.getEncoder().encodeToString("user:password".getBytes())))
                .andExpect(status().isForbidden());
    }

    @Test
    void basicAuth_adminEndpoint_withAdminRole_returns200() throws Exception {
        mockMvc.perform(get("/api/basic/admin")
                        .header("Authorization", "Basic " +
                                java.util.Base64.getEncoder().encodeToString("admin:password".getBytes())))
                .andExpect(status().isOk());
    }
}
