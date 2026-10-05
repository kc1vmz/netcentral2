package com.kc1vmz.netcentral.communication;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.accessor.AccountAccessor;

@Service
public class CommunicationService {
    public record View(CommunicationConfig configuration, boolean passcodeConfigured, ManagedConnection.Health health) {
    }

    private final AccountAccessor accounts;
    private final CommunicationRepository repository;
    private final CommunicationManager manager;
    private final SerialOwnership ownership;

    public CommunicationService(AccountAccessor accounts, CommunicationRepository repository,
            CommunicationManager manager, SerialOwnership ownership) {
        this.accounts = accounts;
        this.repository = repository;
        this.manager = manager;
        this.ownership = ownership;
    }

    public void requireAdmin() {
        if (!accounts.administrator())
            throw new AccessDeniedException("Administrator access required");
    }

    public List<View> list() {
        requireAdmin();
        return repository.all().stream().map(this::view).toList();
    }

    public record Choice(String id, String name, String type) {
    }

    public List<Choice> routeChoices() {
        accounts.current();
        return repository.all().stream().map(c -> new Choice(c.id(), c.name(), c.type())).toList();
    }

    public View get(String id) {
        requireAdmin();
        return view(required(id));
    }

    private View view(CommunicationConfig c) {
        // Never expose the saved passcode to a template or response object.
        return new View(c.identity(c.id(), c.version(), null), c.passcode() != null && !c.passcode().isBlank(),
                manager.health(c));
    }

    @Transactional
    public View save(String id, CommunicationConfig input) {
        requireAdmin();
        repository.lock();
        CommunicationConfig old = id == null ? null : required(id);
        if (old != null && old.version() != input.version())
            throw conflict("Configuration changed; refresh and retry");
        String secret = input.passcode();
        if (old != null && (secret == null || secret.isBlank()))
            secret = old.passcode();
        if (!"APRS_IS".equals(input.type()))
            secret = null;
        CommunicationConfig config = input.identity(id == null ? UUID.randomUUID().toString() : id,
                old == null ? 0 : old.version() + 1, secret);
        boolean serial = config.serial();
        boolean aprs = "APRS_IS".equals(config.type());
        config = new CommunicationConfig(config.id(), config.version(), config.name(), config.type(), config.enabled(),
                config.transmitEnabled(), serial ? null : config.host(), serial ? null : config.port(),
                aprs || "KENWOOD_SERIAL".equals(config.type()) ? config.username() : null, aprs ? config.passcode() : null, aprs ? config.filter() : null,
                serial ? config.serialDevice() : null, serial ? config.baudRate() : null,
                serial ? config.initCommand1() : null, serial ? config.initCommand2() : null,
                aprs ? null : config.digiPath(), config.type() != null && config.type().startsWith("KISS_") ? config.kissPort() : null);
        validate(config);
        String key = null;
        if (config.enabled() && config.serial()) {
            key = SerialOwnership.identity(config.serialDevice());
            if (!ownership.available(key, config.id()))
                throw conflict("A previous worker still owns this serial port");
            for (CommunicationConfig other : repository.all()) {
                if (!other.id().equals(config.id()) && other.enabled() && other.serial()
                        && key.equals(SerialOwnership.identity(other.serialDevice())))
                    throw conflict("Serial port is already assigned to an enabled instance");
            }
        }
        repository.save(config, key);
        return view(config);
    }

    @Transactional
    public void delete(String id, long version) {
        requireAdmin();
        repository.lock();
        if (required(id).version() != version)
            throw conflict("Configuration changed; refresh and retry");
        repository.delete(id);
    }

    private CommunicationConfig required(String id) {
        CommunicationConfig result = repository.find(id);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Communication instance not found");
        return result;
    }

    private void validate(CommunicationConfig c) {
        if (c.type() == null || !Set.of("APRS_IS", "KISS_TCP", "KISS_SERIAL", "TNC2_TCP", "TNC2_SERIAL", "KENWOOD_SERIAL").contains(c.type()))
            throw bad("Invalid transport type");
        for (String value : new String[] { c.host(), c.username(), c.passcode(), c.filter(), c.serialDevice(),
                c.initCommand1(), c.initCommand2(), c.digiPath() }) {
            if (value != null && (value.length() > 255 || value.chars().anyMatch(ch -> ch < 32 || ch > 126)))
                throw bad("Use at most 255 printable ASCII characters per setting");
        }
        if (c.serial()) {
            requiredText(c.serialDevice(), "Serial port");
            try {
                SerialOwnership.identity(c.serialDevice());
            } catch (IllegalArgumentException invalid) {
                throw bad("Invalid serial port");
            }
            if (c.baudRate() == null || c.baudRate() < 300 || c.baudRate() > 4000000)
                throw bad("Baud rate must be between 300 and 4000000");
        } else {
            requiredText(c.host(), "Host");
            if (c.host().chars().anyMatch(Character::isWhitespace))
                throw bad("Invalid host");
            if (c.port() == null || c.port() < 1 || c.port() > 65535)
                throw bad("TCP port must be between 1 and 65535");
        }
        if (c.type().equals("KENWOOD_SERIAL")) {
            requiredText(c.username(), "Kenwood MYCALL");
            try { KissCodec.validateAddress(c.username()); }
            catch (IllegalArgumentException invalid) { throw bad("Invalid Kenwood MYCALL"); }
            if (c.username().contains("*") || !c.username().equals(c.username().toUpperCase(java.util.Locale.ROOT)))
                throw bad("Use an uppercase Kenwood callsign and optional SSID");
            if ((c.initCommand1() != null && !c.initCommand1().isBlank()) || (c.initCommand2() != null && !c.initCommand2().isBlank()))
                throw bad("Kenwood startup commands are configured automatically");
        }
        if (c.type().equals("APRS_IS")) {
            if (c.username() == null || !c.username().matches("[A-Za-z0-9-]{1,10}"))
                throw bad("Invalid APRS-IS callsign");
            if (c.passcode() == null || !c.passcode().matches("-?[0-9]{1,10}"))
                throw bad("APRS-IS passcode is required");
            if (c.passcode().equals("-1") && c.transmitEnabled())
                throw bad("Receive-only passcode cannot enable transmission");
        } else {
            if (c.type().startsWith("KISS_") && (c.kissPort() == null || c.kissPort() < 0 || c.kissPort() > 15))
                throw bad("KISS port/channel must be between 0 and 15");
            if (c.digiPath() != null && !c.digiPath().isBlank()) {
                String[] path = c.digiPath().split(",", -1);
                if (path.length > 8)
                    throw bad("Use at most eight digipeaters");
                try {
                    for (String address : path)
                        KissCodec.validateAddress(address);
                } catch (IllegalArgumentException invalid) {
                    throw bad("Invalid digipeater path");
                }
            }
        }
    }

    private void requiredText(String value, String name) {
        if (value == null || value.isBlank())
            throw bad(name + " is required");
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
