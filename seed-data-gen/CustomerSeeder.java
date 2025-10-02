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
            INSERT INTO customer (id, email, name, locale, status, created_at)
            VALUES (?, ?, ?, ?, ?, now())
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
        int howMany = args.length > 1 ? Integer.parseInt(args[1]) : 15000;   // number of customers to insert

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
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

                for (int i = 0; i < howMany; i++) {
                    Loc loc = pickLocale(weights, rnd);

                    String first = pickRandom(firstNames.get(loc), rnd);
                    String last = pickRandom(lastNames.get(loc), rnd);
                    String fullName = first + " " + last;

                    // email: first.last + short unique suffix @example.<tld>
                    String localPart = slugify(first) + "." + slugify(last) + randomSuffix(rnd);
                    String domain = "example." + loc.code; // be/nl/fr/en
                    String email = (localPart + "@" + domain).toLowerCase(Locale.ROOT);

                    ps.setObject(1, java.util.UUID.randomUUID());
                    ps.setString(2, email);
                    ps.setString(3, fullName);
                    ps.setString(4, loc.code);
                    ps.setString(5, STATUSES[rnd.nextInt(STATUSES.length)]);
                    ps.addBatch();

                    // Optional: uncomment for verbose progress
                    // System.out.println("[" + loc.code + "] " + fullName + " <" + email + ">");
                }

                ps.executeBatch();
            }
            conn.commit();
        }

        System.out.println("✅ Inserted " + howMany + " customers with locale-aware names & emails.");
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

    // Small unique suffix to avoid email collisions while remaining readable
    private static String randomSuffix(Random rnd) {
        // 3 base36 chars => ~46k combos
        int n = rnd.nextInt(36 * 36 * 36);
        String base36 = Integer.toString(n, 36);
        return "-" + "000".substring(base36.length()) + base36;
    }
}
