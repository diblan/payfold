import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

public class CustomerSeeder {

    static final String url = System.getProperty("db.url");
    static final String user = System.getProperty("db.user");
    static final String pass = System.getProperty("db.pass");

    enum Loc {
        BE("be"), NL("nl"), FR("fr"), EN("en");
        public final String code;

        Loc(String code) {
            this.code = code;
        }
    }

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM customer";

    private static final String INSERT_SQL = """
            INSERT INTO customer (id, email, name, locale, status, payment_method,
            debtor_iban, mandate_reference, country, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            """;

    // Adjust to your taste (must sum ~1.0; code normalizes anyway)
    private static final Map<Loc, Double> DEFAULT_WEIGHTS = Map.of(
            Loc.BE, 0.50,
            Loc.NL, 0.25,
            Loc.FR, 0.15,
            Loc.EN, 0.10
    );

    private static final String[] STATUSES = {"active", "inactive", "pending"};

    public static void main(String[] args) throws Exception {
        // ---- Config ----
        String dataDir = args.length > 0 ? args[0] : "data";
        int howMany = args.length > 1 ? Integer.parseInt(args[1]) : seedTarget();   // number of customers to insert
        int sddPercent = seedPercent("SEED_SDD_PERCENT", 20);
        int sddRulePercent = seedPercent("SEED_SDD_RULE_PERCENT", 4);
        if (sddRulePercent < 0 || sddPercent < sddRulePercent || sddPercent > 100) {
            throw new IllegalStateException(
                    "Seed payment-method percentages must satisfy 0 <= "
                            + "SEED_SDD_RULE_PERCENT <= SEED_SDD_PERCENT <= 100");
        }

        // Email numbering starts at the current row count: every run draws from a
        // fresh, disjoint number range, so customer_email_key (UNIQUE) cannot trip
        // at any seed size or across top-up runs.
        int offset;
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            // count
            int have = 0;
            try (PreparedStatement ps = conn.prepareStatement(COUNT_SQL); ResultSet rs = ps.executeQuery()) {
                rs.next();
                have = rs.getInt(1);
            }

            if (have >= howMany) {
                System.out.println("Seed skip: customers=" + have);
                return;
            } else {
                offset = have;
                howMany -= have;
            }
        }


        // Optional: override weights via system properties, e.g.
        // -Dw.be=0.60 -Dw.nl=0.20 -Dw.fr=0.15 -Dw.en=0.05
        Map<Loc, Double> weights = readWeightsFromSystemOrDefault();

        // ---- Load names per locale ----
        Map<Loc, List<String>> firstNames = new EnumMap<>(Loc.class);
        Map<Loc, List<String>> lastNames = new EnumMap<>(Loc.class);

        for (Loc loc : Loc.values()) {
            firstNames.put(loc, loadNames(Paths.get(dataDir, "first-" + loc.code + ".txt")));
            lastNames.put(loc, loadNames(Paths.get(dataDir, "last-" + loc.code + ".txt")));
        }

        validateNonEmpty(firstNames, "first names");
        validateNonEmpty(lastNames, "last names");

        // ---- Generate + insert ----
        Random rnd = new Random();
        int sddCount = 0;
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

                for (int i = 0; i < howMany; i++) {
                    int n = offset + i;
                    Loc loc = pickLocale(weights, rnd);

                    String first = pickRandom(firstNames.get(loc), rnd);
                    String last = pickRandom(lastNames.get(loc), rnd);
                    String fullName = first + " " + last;

                    // email: first.last-<seq> @example.<tld>; <seq> is globally unique
                    String localPart = slugify(first) + "." + slugify(last) + "-" + n;
                    String domain = "example." + loc.code; // be/nl/fr/en
                    String email = (localPart + "@" + domain).toLowerCase(Locale.ROOT);
                    boolean sdd = n % 100 < sddPercent;
                    String debtorIban = null;
                    String mandateReference = null;
                    String country = null;
                    if (sdd) {
                        String suffix = n % 100 < sddRulePercent
                                ? new String[]{"99", "98", "97", "96"}[n % 4]
                                : "01";
                        debtorIban = "BE68" + String.format("%010d", n) + suffix;
                        mandateReference = "MNDT-" + n;
                        country = countryFor(loc);
                        sddCount++;
                    }

                    ps.setObject(1, java.util.UUID.randomUUID());
                    ps.setString(2, email);
                    ps.setString(3, fullName);
                    ps.setString(4, loc.code);
                    ps.setString(5, STATUSES[rnd.nextInt(STATUSES.length)]);
                    ps.setString(6, sdd ? "sdd" : "card");
                    ps.setString(7, debtorIban);
                    ps.setString(8, mandateReference);
                    ps.setString(9, country);
                    ps.addBatch();
                    if ((i + 1) % 1000 == 0) ps.executeBatch();   // bounded batch, same cadence as SubscriptionSeederDueToday

                    // Optional: uncomment for verbose progress
                    // System.out.println("[" + loc.code + "] " + fullName + " <" + email + ">");
                }

                ps.executeBatch();
            }
            conn.commit();
        }

        System.out.println("✅ Inserted " + howMany
                + " customers with locale-aware names & emails (" + sddCount + " SDD).");
    }

    // Seed target: CLI arg wins, then the SEED_CUSTOMERS env var (passed through by
    // docker-compose), then the 15000 demo default. A malformed value fails the seed
    // container loudly (NumberFormatException) instead of silently shrinking the run.
    private static int seedTarget() {
        String v = System.getenv("SEED_CUSTOMERS");
        if (v == null || v.isBlank()) return 15000;
        return Integer.parseInt(v.trim());
    }

    private static int seedPercent(String name, int defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return defaultValue;
        return Integer.parseInt(v.trim());
    }

    private static String countryFor(Loc loc) {
        return switch (loc) {
            case BE -> "BE";
            case NL -> "NL";
            case FR -> "FR";
            case EN -> "IE";
        };
    }

    // Read weight overrides from -Dw.be=.. etc.
    private static Map<Loc, Double> readWeightsFromSystemOrDefault() {
        Map<Loc, Double> w = new EnumMap<>(Loc.class);
        w.put(Loc.BE, parseDoubleOr(DEFAULT_WEIGHTS.get(Loc.BE), System.getProperty("w.be")));
        w.put(Loc.NL, parseDoubleOr(DEFAULT_WEIGHTS.get(Loc.NL), System.getProperty("w.nl")));
        w.put(Loc.FR, parseDoubleOr(DEFAULT_WEIGHTS.get(Loc.FR), System.getProperty("w.fr")));
        w.put(Loc.EN, parseDoubleOr(DEFAULT_WEIGHTS.get(Loc.EN), System.getProperty("w.en")));
        // normalize to sum 1.0
        double sum = w.values().stream().mapToDouble(d -> d).sum();
        if (sum <= 0) return DEFAULT_WEIGHTS;
        for (Loc k : w.keySet()) w.put(k, w.get(k) / sum);
        return w;
    }

    private static double parseDoubleOr(double def, String s) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static List<String> loadNames(Path path) throws IOException {
        if (!Files.exists(path)) return List.of();
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .distinct()
                .collect(Collectors.toList());
    }

    private static void validateNonEmpty(Map<Loc, List<String>> m, String label) {
        for (Loc loc : Loc.values()) {
            List<String> list = m.get(loc);
            if (list == null || list.isEmpty()) {
                throw new IllegalStateException("Missing/empty " + label + " for locale: " + loc.code);
            }
        }
    }

    private static Loc pickLocale(Map<Loc, Double> weights, Random rnd) {
        double r = rnd.nextDouble();
        double acc = 0.0;
        for (Loc loc : Loc.values()) {
            acc += weights.getOrDefault(loc, 0.0);
            if (r <= acc) return loc;
        }
        return Loc.EN; // fallback
    }

    private static String pickRandom(List<String> list, Random rnd) {
        return list.get(rnd.nextInt(list.size()));
    }

    // Remove accents/diacritics, keep letters/digits/dots/dashes/underscores only
    private static String slugify(String s) {
        String norm = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", ""); // strip diacritics
        norm = norm.replace("'", "").replace("’", "");
        return norm.replaceAll("[^A-Za-z0-9._-]", "");
    }

}
