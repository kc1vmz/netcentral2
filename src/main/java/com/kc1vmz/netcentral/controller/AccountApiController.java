package com.kc1vmz.netcentral.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.accessor.AccountAccessor;
import com.kc1vmz.netcentral.account.Account;
import com.kc1vmz.netcentral.account.AccountEdit;
import com.kc1vmz.netcentral.account.Registration;

import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1")
public class AccountApiController {
    private final AccountAccessor accounts;
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;

    public AccountApiController(AccountAccessor accounts, AuthenticationManager manager,
            SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) {
        this.accounts = accounts;
        this.manager = manager;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    public record Login(String username, String password) {
        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }

    public record CreateAccount(String emailAddress, String password, String role, Account.Callsign callsign,
            String firstName, String lastName) {
        @Override
        public String toString() {
            return "CreateAccount[redacted]";
        }
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName(), "token",
                token.getToken());
    }

    @PostMapping("/login")
    public Account login(@RequestBody Login login, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = manager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
        if (request.getSession(false) != null)
            request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        csrfTokens.saveToken(null, request, response);
        contexts.saveContext(context, request, response);
        return accounts.current();
    }

    @PostMapping("/login/token")
    public boolean tokenCheck() {
        accounts.current();
        return true;
    }

    @PostMapping("/logout")
    public Account logout(HttpServletRequest request, HttpServletResponse response) {
        Account account = accounts.current();
        csrfTokens.saveToken(null, request, response);
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        new CookieClearingLogoutHandler("SessionID").logout(request, response, null);
        return account;
    }

    @PostMapping("/users/register")
    public Account register(@RequestBody Registration request) {
        return accounts.register(request);
    }

    @GetMapping("/users")
    public List<Account> list() {
        return accounts.list();
    }

    @GetMapping("/users/{id}")
    public Account get(@PathVariable String id) {
        return accounts.get(id);
    }

    @PostMapping("/users")
    public Account create(@RequestBody CreateAccount request) {
        Registration registration = new Registration(request.emailAddress(), request.password(), request.password(),
                request.callsign() == null ? null : request.callsign().callsign(), request.firstName(),
                request.lastName());
        return accounts.create(registration, request.role() == null ? "USER" : request.role());
    }

    @PutMapping("/users/{id}")
    public Account update(@PathVariable String id, @RequestBody AccountEdit request) {
        return accounts.update(id, request);
    }

    @DeleteMapping("/users/{id}")
    public void delete(@PathVariable String id) {
        accounts.delete(id);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalid(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason()));
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> unauthenticated() {
        return Map.of("message", "Invalid username or password");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden() {
        return Map.of("message", "Not permitted");
    }
}
