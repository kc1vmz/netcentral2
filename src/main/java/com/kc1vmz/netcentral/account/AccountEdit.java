package com.kc1vmz.netcentral.account;

public record AccountEdit(String emailAddress, String firstName, String lastName,
        Account.Callsign callsign, String role, String password, String currentPassword) {
    @Override
    public String toString() {
        return "AccountEdit[redacted]";
    }
}
