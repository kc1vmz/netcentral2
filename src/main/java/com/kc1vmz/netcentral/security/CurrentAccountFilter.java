package com.kc1vmz.netcentral.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kc1vmz.netcentral.accessor.AccountAccessor;
import com.kc1vmz.netcentral.account.LocalPrincipal;

public class CurrentAccountFilter extends OncePerRequestFilter {
    private final AccountAccessor accounts;

    public CurrentAccountFilter(AccountAccessor accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LocalPrincipal principal && !accounts.isCurrent(principal)) {
            SecurityContextHolder.clearContext();
            if (request.getSession(false) != null)
                request.getSession(false).invalidate();
        }
        chain.doFilter(request, response);
    }
}
