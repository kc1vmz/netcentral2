package com.kc1vmz.netcentral.accessor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.account.Account;
import com.kc1vmz.netcentral.account.AccountEdit;
import com.kc1vmz.netcentral.account.LocalPrincipal;
import com.kc1vmz.netcentral.account.Registration;

@Service
public class AccountAccessor implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private static final RowMapper<Account> ACCOUNT = (row, number) -> new Account(
            row.getString("id"), row.getString("username"), row.getString("role"),
            row.getString("callsign") == null ? null : new Account.Callsign(row.getString("callsign")),
            row.getString("first_name"), row.getString("last_name"));

    public AccountAccessor(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public LocalPrincipal loadUserByUsername(String username) {
        String normalized = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        List<LocalPrincipal> result = jdbc.query(
                "SELECT * FROM local_account WHERE username = ?",
                (row, number) -> new LocalPrincipal(row.getString("id"), row.getString("password_hash"),
                        row.getString("role"), row.getLong("credential_version")),
                normalized);
        if (result.isEmpty())
            throw new UsernameNotFoundException("Invalid username or password");
        return result.getFirst();
    }

    public boolean isCurrent(LocalPrincipal principal) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_account WHERE id = ? AND credential_version = ?",
                Integer.class, principal.getUsername(), principal.version()) == 1;
    }

    public Account current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LocalPrincipal principal) || !isCurrent(principal)) {
            throw new AccessDeniedException("Sign in to continue");
        }
        return find(principal.getUsername());
    }

    public boolean administrator() {
        return Set.of("SYSADMIN", "ADMIN").contains(current().role());
    }

    @Transactional
    public Account register(Registration request) {
        String hash = hashPassword(request.password());
        if (!request.password().equals(request.password2()))
            throw bad("Passwords do not match");
        boolean initialized = lock();
        Account result = insert(request, initialized ? "USER" : "SYSADMIN", hash);
        jdbc.update("UPDATE account_lock SET bootstrapped = TRUE WHERE id = 1");
        return result;
    }

    @Transactional
    public Account create(Registration request, String role) {
        String hash = hashPassword(request.password());
        if (!request.password().equals(request.password2()))
            throw bad("Passwords do not match");
        lock();
        Account actor = current();
        checkRole(role);
        if (!actor.role().equals("SYSADMIN") && !(actor.role().equals("ADMIN") && role.equals("USER"))) {
            throw new AccessDeniedException("Not permitted to create that account");
        }
        return insert(request, role, hash);
    }

    private Account insert(Registration request, String role, String hash) {
        String name = username(request.username());
        uniqueUsername(name, "");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO local_account (id, username, password_hash, role, callsign, first_name, last_name) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, name, hash, role,
                callsign(request.callsign()), text(request.firstName(), 100), text(request.lastName(), 100));
        return find(id);
    }

    public List<Account> list() {
        Account actor = current();
        return actor.role().equals("USER") ? List.of(actor)
                : jdbc.query("SELECT * FROM local_account ORDER BY username", ACCOUNT);
    }

    public Account get(String id) {
        Account actor = current();
        if (!actor.id().equals(id) && actor.role().equals("USER"))
            throw new AccessDeniedException("Not permitted");
        return find(id);
    }

    @Transactional
    public Account update(String id, AccountEdit edit) {
        lock();
        Account actor = current();
        Account target = find(id);
        boolean self = actor.id().equals(id);
        String role = edit.role() == null ? target.role() : edit.role();
        checkRole(role);
        boolean manager = actor.role().equals("SYSADMIN")
                || (actor.role().equals("ADMIN") && target.role().equals("USER") && role.equals("USER"));
        if ((!self && !manager) || (!role.equals(target.role()) && !actor.role().equals("SYSADMIN"))) {
            throw new AccessDeniedException("Not permitted to change this account or role");
        }
        protectLastAdministrator(target, role);
        String name = edit.emailAddress() == null ? target.emailAddress() : username(edit.emailAddress());
        uniqueUsername(name, id);
        String hash = jdbc.queryForObject("SELECT password_hash FROM local_account WHERE id = ?", String.class, id);
        if (edit.password() != null && !edit.password().isEmpty()) {
            if (self && (edit.currentPassword() == null || !encoder.matches(edit.currentPassword(), hash))) {
                throw bad("Current password is incorrect");
            }
            hash = hashPassword(edit.password());
        }
        jdbc.update("UPDATE local_account SET username=?, password_hash=?, role=?, callsign=?, first_name=?, "
                + "last_name=?, credential_version=credential_version+1 WHERE id=?",
                name, hash, role, callsign(edit.callsign() == null ? null : edit.callsign().callsign()),
                text(edit.firstName(), 100), text(edit.lastName(), 100), id);
        return find(id);
    }

    @Transactional
    public void delete(String id) {
        lock();
        Account actor = current();
        Account target = find(id);
        if (!actor.id().equals(id) && !actor.role().equals("SYSADMIN")
                && !(actor.role().equals("ADMIN") && target.role().equals("USER"))) {
            throw new AccessDeniedException("Not permitted to delete this account");
        }
        protectLastAdministrator(target, null);
        jdbc.update("DELETE FROM local_account WHERE id=?", id);
    }

    private void protectLastAdministrator(Account target, String newRole) {
        if (target.role().equals("SYSADMIN") && !"SYSADMIN".equals(newRole)
                && jdbc.queryForObject("SELECT COUNT(*) FROM local_account WHERE role='SYSADMIN'",
                        Integer.class) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The last system administrator must be retained");
        }
    }

    private boolean lock() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT bootstrapped FROM account_lock WHERE id=1 FOR UPDATE", Boolean.class));
    }

    private Account find(String id) {
        List<Account> accounts = jdbc.query("SELECT * FROM local_account WHERE id=?", ACCOUNT, id);
        if (accounts.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        return accounts.getFirst();
    }

    private void uniqueUsername(String name, String id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_account WHERE username=? AND id<>?", Integer.class, name,
                id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already registered");
        }
    }

    private String username(String value) {
        String name = text(value, 254);
        if (name == null)
            throw bad("Username is required");
        return name.toLowerCase(Locale.ROOT);
    }

    private String callsign(String value) {
        String result = text(value, 20);
        return result == null ? null : result.toUpperCase(Locale.ROOT);
    }

    private String text(String value, int maximum) {
        if (value == null || value.isBlank())
            return null;
        String result = value.strip();
        if (result.length() > maximum || result.chars().anyMatch(Character::isISOControl))
            throw bad("Invalid field length or characters");
        return result;
    }

    private String hashPassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw bad("Password must contain at least 8 characters and at most 72 UTF-8 bytes");
        }
        return encoder.encode(password);
    }

    private void checkRole(String role) {
        if (!Set.of("USER", "ADMIN", "SYSADMIN").contains(role))
            throw bad("Invalid role");
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
