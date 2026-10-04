package com.kc1vmz.netcentral.accessor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ApplicationSettingsAccessor {
    private final JdbcTemplate jdbc;

    public ApplicationSettingsAccessor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String applicationName() {
        return jdbc.queryForObject(
                "SELECT setting_value FROM application_settings WHERE setting_key = ?",
                String.class, "application.name");
    }
}
