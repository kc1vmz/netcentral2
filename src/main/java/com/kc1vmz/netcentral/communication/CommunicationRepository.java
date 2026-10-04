package com.kc1vmz.netcentral.communication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CommunicationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CommunicationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<CommunicationConfig> all() {
        return jdbc.query("SELECT * FROM communication_instance ORDER BY id", (row, number) -> {
            try {
                return mapper.readValue(row.getString("configuration"), CommunicationConfig.class)
                        .identity(row.getString("id"), row.getLong("version"), row.getString("passcode"));
            } catch (JsonProcessingException invalid) {
                throw new IllegalStateException("Invalid saved communication configuration");
            }
        });
    }

    public CommunicationConfig find(String id) {
        return all().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    public void lock() {
        jdbc.queryForObject("SELECT id FROM communication_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    public void save(CommunicationConfig config, String reservation) {
        try {
            jdbc.update(
                    "MERGE INTO communication_instance (id,version,configuration,passcode,serial_reservation) KEY(id) VALUES(?,?,?,?,?)",
                    config.id(), config.version(), mapper.writeValueAsString(config), config.passcode(), reservation);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to store communication configuration");
        }
    }

    public void delete(String id) {
        jdbc.update("UPDATE communication_route SET version=version+1 WHERE owner_key IN "
                + "(SELECT owner_key FROM communication_route_instance WHERE instance_id=?)", id);
        jdbc.update("DELETE FROM communication_instance WHERE id=?", id);
    }
}
