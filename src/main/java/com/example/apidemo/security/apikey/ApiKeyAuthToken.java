package com.example.apidemo.security.apikey;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Custom Authentication token for API Key authentication.
 *
 * Spring Security's SecurityContext holds an Authentication object.
 * We create a custom one to represent "authenticated via API key"
 * rather than username/password.
 */
public class ApiKeyAuthToken extends AbstractAuthenticationToken {

    private final String owner;

    public ApiKeyAuthToken(String owner, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.owner = owner;
        setAuthenticated(true); // Mark as authenticated — key was already validated
    }

    @Override
    public Object getCredentials() {
        return null; // No credentials after authentication
    }

    @Override
    public Object getPrincipal() {
        return owner; // The "principal" is the key owner's identifier
    }
}
