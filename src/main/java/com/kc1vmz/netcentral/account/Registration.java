package com.kc1vmz.netcentral.account;

public record Registration(String username, String password, String password2,
        String callsign, String firstName, String lastName) {
    @Override
    public String toString() {
        return "Registration[redacted]";
    }
}
