package com.kc1vmz.netcentral.account;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class LocalPrincipal implements UserDetails, CredentialsContainer {
    private final String id;
    private String password;
    private final String role;
    private final long version;

    public LocalPrincipal(String id, String password, String role, long version) {
        this.id = id;
        this.password = password;
        this.role = role;
        this.version = version;
    }

    @Override
    public String getUsername() {
        return id;
    }

    @Override
    public String getPassword() {
        return password;
    }

    public long version() {
        return version;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public void eraseCredentials() {
        password = null;
    }

    @Override
    public String toString() {
        return "LocalPrincipal[redacted]";
    }
}
