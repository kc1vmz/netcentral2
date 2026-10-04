package com.kc1vmz.netcentral.communication;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SerialOwnership {
    private final Map<String, String> owners = new HashMap<>();

    public static String identity(String device) {
        return identity(device, System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    public static String identity(String device, boolean windows) {
        if (device == null || device.isBlank())
            throw new IllegalArgumentException("Serial device is required");
        if (windows) {
            String normalized = device.strip().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("\\\\.\\"))
                normalized = normalized.substring(4);
            if (!normalized.matches("COM[0-9]+"))
                throw new IllegalArgumentException("Use a Windows COM port");
            int number = Integer.parseInt(normalized.substring(3));
            if (number < 1)
                throw new IllegalArgumentException("Invalid COM port");
            return "COM" + number;
        }
        Path path = Path.of(device).toAbsolutePath().normalize();
        try {
            return path.toRealPath().toString();
        } catch (IOException missing) {
            return path.toString();
        }
    }

    public synchronized boolean available(String key, String id) {
        return !owners.containsKey(key) || owners.get(key).equals(id);
    }

    public synchronized boolean claim(String key, String id) {
        if (!available(key, id))
            return false;
        owners.put(key, id);
        return true;
    }

    public synchronized void release(String key, String id) {
        owners.remove(key, id);
    }
}
