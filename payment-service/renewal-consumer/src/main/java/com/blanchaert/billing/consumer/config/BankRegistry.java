package com.blanchaert.billing.consumer.config;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class BankRegistry {
    private final Map<String, BankProperties.BankEntry> byId;
    private final Map<String, BankProperties.BankEntry> byCountry;

    public BankRegistry(BankProperties properties) {
        if (properties.registry() == null || properties.registry().isEmpty()) {
            throw new IllegalStateException("bank registry must not be empty");
        }

        Map<String, BankProperties.BankEntry> idEntries = new LinkedHashMap<>();
        Map<String, BankProperties.BankEntry> countryEntries = new LinkedHashMap<>();
        for (BankProperties.BankEntry entry : properties.registry()) {
            validate(entry);
            if (idEntries.putIfAbsent(entry.id(), entry) != null) {
                throw new IllegalStateException("duplicate bank id " + entry.id());
            }
            for (String country : entry.countries()) {
                if (country == null) {
                    throw new IllegalStateException(
                            "bank registry entry " + entry.id() + " has a missing country");
                }
                String normalized = country.trim().toUpperCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    throw new IllegalStateException(
                            "bank registry entry " + entry.id() + " has a blank country");
                }
                if (countryEntries.putIfAbsent(normalized, entry) != null) {
                    throw new IllegalStateException("duplicate bank country claim " + normalized);
                }
            }
        }
        this.byId = Map.copyOf(idEntries);
        this.byCountry = Map.copyOf(countryEntries);
    }

    public BankProperties.BankEntry byId(String id) {
        return id == null ? null : byId.get(id);
    }

    public BankProperties.BankEntry byCountry(String country) {
        return country == null ? null : byCountry.get(country.trim().toUpperCase(Locale.ROOT));
    }

    public Collection<BankProperties.BankEntry> entries() {
        return byId.values();
    }

    private void validate(BankProperties.BankEntry entry) {
        if (entry == null
                || blank(entry.id())
                || blank(entry.baseUrl())
                || blank(entry.webhookSecret())
                || entry.countries() == null
                || entry.countries().isEmpty()) {
            throw new IllegalStateException(
                    "bank registry entry must define id, baseUrl, webhookSecret, and countries");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
