package com.kc1vmz.netcentral.account;

public record Account(String id, String emailAddress, String role, Callsign callsign,
        String firstName, String lastName) {
    public record Callsign(String callsign) {
    }
}
