package com.kc1vmz.netcentral.accessor;

import com.kc1vmz.netcentral.net.StripFormat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class StripLibraryAccessor {
    private final JdbcTemplate jdbc;
    private final AccountAccessor accounts;
    public StripLibraryAccessor(JdbcTemplate jdbc, AccountAccessor accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }
    public record Template(String id, long version, String name, String definition, OffsetDateTime updatedAt) {}
    public record Input(String name, String definition, long version) {}
    public record Library(List<Template> templates, boolean canManage) {}
    public Library list() {
        boolean admin = accounts.administrator();
        return new Library(jdbc.query("SELECT * FROM strip_template ORDER BY name_key,id", (r,n) ->
                new Template(r.getString("id"), r.getLong("version"), r.getString("name"), r.getString("definition"),
                        r.getObject("updated_at", OffsetDateTime.class))), admin);
    }
    private void administrator() {
        if (!accounts.administrator()) throw new AccessDeniedException("Administrator required to edit strip library");
    }
    @Transactional
    public Library save(String id, Input input) {
        administrator();
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty() || name.length() > 120)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Strip name is required (maximum 120 characters)");
        String key = name.toUpperCase(java.util.Locale.ROOT);
        StripFormat.definition(input.definition());
        StripFormat.wire("QS S1/" + input.definition());
        try {
            if (id == null) {
                jdbc.update("INSERT INTO strip_template(id,version,name,name_key,definition,updated_at) VALUES(?,0,?,?,?,?)",
                    UUID.randomUUID().toString(), name, key, input.definition(), OffsetDateTime.now());
            } else if (jdbc.update("UPDATE strip_template SET name=?,name_key=?,definition=?,version=version+1,updated_at=? WHERE id=? AND version=?",
                    name, key, input.definition(), OffsetDateTime.now(), id, input.version()) != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Template changed or was deleted; reload the library");
            }
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A strip with that name already exists");
        }
        return list();
    }
    @Transactional
    public Library delete(String id, long version) {
        administrator();
        if (jdbc.update("DELETE FROM strip_template WHERE id=? AND version=?", id, version) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Template changed or was deleted; reload the library");
        return list();
    }
}
