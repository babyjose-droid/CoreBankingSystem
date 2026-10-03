package com.corebanking.integration.core.provider;

import java.util.Map;

/**
 * A tenant's configuration of one provider: plain settings (base URL, merchant id …) and secrets (keys, salts).
 * Secrets are decrypted only for the call; this record never prints them.
 */
public record ProviderSettings(Map<String, String> settings, Map<String, String> secrets) {

    public ProviderSettings {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }

    public static ProviderSettings empty() {
        return new ProviderSettings(Map.of(), Map.of());
    }

    public String setting(String name, String fallback) {
        String v = settings.get(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    public String requireSetting(String name) {
        String v = setting(name, null);
        if (v == null) throw new ProviderException("setting '" + name + "' is not configured", false);
        return v;
    }

    public String requireSecret(String name) {
        String v = secrets.get(name);
        if (v == null || v.isBlank()) throw new ProviderException("secret '" + name + "' is not configured", false);
        return v;
    }

    @Override
    public String toString() {
        return "ProviderSettings[settings=" + settings + ", secrets=" + secrets.keySet() + "]";
    }
}
