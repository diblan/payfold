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
    private final BankProperties.BankEntry cardEntry;

    public BankRegistry(BankProperties properties) {
        if (properties.registry() == null || properties.registry().isEmpty()) {
            throw new IllegalStateException("bank registry must not be empty");
        }

        Map<String, BankProperties.BankEntry> idEntries = new LinkedHashMap<>();
        Map<String, BankProperties.BankEntry> countryEntries = new LinkedHashMap<>();
        Map<String, BankProperties.BankEntry> countryClaims = new LinkedHashMap<>();
        BankProperties.BankEntry configuredCard = null;
        for (BankProperties.BankEntry configured : properties.registry()) {
            BankProperties.BankEntry entry = normalize(configured);
            validate(entry);
            if (idEntries.putIfAbsent(entry.id(), entry) != null) {
                throw new IllegalStateException("duplicate bank id " + entry.id());
            }
            if ("card".equals(entry.scheme())) {
                if (configuredCard != null) {
                    throw new IllegalStateException("bank registry must contain exactly one card entry");
                }
                configuredCard = entry;
            }
            for (String country : entry.countries() == null ? java.util.List.<String>of() : entry.countries()) {
                if (country == null) {
                    throw new IllegalStateException(
                            "bank registry entry " + entry.id() + " has a missing country");
                }
                String normalized = country.trim().toUpperCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    throw new IllegalStateException(
                            "bank registry entry " + entry.id() + " has a blank country");
                }
                if (countryClaims.putIfAbsent(normalized, entry) != null) {
                    throw new IllegalStateException("duplicate bank country claim " + normalized);
                }
                if ("sepa_core".equals(entry.scheme())) {
                    countryEntries.put(normalized, entry);
                }
            }
        }
        if (configuredCard == null) {
            throw new IllegalStateException("bank registry must contain exactly one card entry");
        }
        this.byId = Map.copyOf(idEntries);
        this.byCountry = Map.copyOf(countryEntries);
        this.cardEntry = configuredCard;
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

    public BankProperties.BankEntry cardEntry() {
        return cardEntry;
    }

    private BankProperties.BankEntry normalize(BankProperties.BankEntry entry) {
        if (entry == null) {
            return null;
        }
        String scheme = entry.scheme() == null ? "sepa_core" : entry.scheme();
        return new BankProperties.BankEntry(
                entry.id(), scheme, entry.baseUrl(), entry.webhookSecret(), entry.countries());
    }

    private void validate(BankProperties.BankEntry entry) {
        if (entry == null
                || blank(entry.id())
                || blank(entry.scheme())
                || blank(entry.baseUrl())
                || blank(entry.webhookSecret())) {
            throw new IllegalStateException(
                    "bank registry entry must define id, scheme, baseUrl, and webhookSecret");
        }
        if (!"sepa_core".equals(entry.scheme()) && !"card".equals(entry.scheme())) {
            throw new IllegalStateException("unsupported bank scheme " + entry.scheme());
        }
        if ("sepa_core".equals(entry.scheme())
                && (entry.countries() == null || entry.countries().isEmpty())) {
            throw new IllegalStateException(
                    "sepa_core bank registry entry must define countries");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
