import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class SubscriptionSeeder {

    static final String url  = System.getProperty("db.url");
    static final String user = System.getProperty("db.user");
    static final String pass = System.getProperty("db.pass");

    // Default weights by (normalized) plan name; can be overridden with -Dw.basic=0.30, etc.
    private static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
        "basic",    0.35,
        "standard", 0.45,
        "premium",  0.20
    );

    public static void main(String[] args) throws Exception {
        // Mode (optional): "missing" (default) inserts only for customers with no subscription
        //                  "all"      gives everyone a subscription (skips those who already have one unless -Dreplace=true)
        final String mode = System.getProperty("mode", "missing").toLowerCase(Locale.ROOT);
        final boolean replaceExisting = Boolean.parseBoolean(System.getProperty("replace", "false"));

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setAutoCommit(false);

            // 1) Load plans (id + name)
            List<Plan> plans = loadPlans(conn);
            if (plans.isEmpty()) {
                throw new IllegalStateException("No plans found. Seed the plan table first.");
            }

            // 2) Build weights across the plans that actually exist in DB
            WeightedPicker<Plan> picker = buildPlanPicker(plans);

            // 3) Resolve target customers
            List<UUID> targets = "all".equals(mode)
                ? loadAllCustomers(conn)
                : loadCustomersMissingSubscription(conn);

            if (targets.isEmpty()) {
                System.out.println("Nothing to do. No target customers found for mode='" + mode + "'.");
                return;
            }

            // Optional: remove existing subscriptions (only if explicitly asked)
            if ("all".equals(mode) && replaceExisting) {
                deleteSubscriptionsFor(conn, targets);
            }

            // 4) Insert subscriptions
            int inserted = insertSubscriptions(conn, targets, picker);

            conn.commit();
            System.out.println("✅ Inserted " + inserted + " subscriptions (" + targets.size() + " target customers).");
        }
    }

    /* ---------- Data types ---------- */

    private record Plan(UUID id, String nameNorm, String nameRaw) {}

    private static String norm(String planName) {
        return planName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /* ---------- Loaders ---------- */

    private static List<Plan> loadPlans(Connection conn) throws SQLException {
        String sql = "SELECT id, name FROM plan";
        List<Plan> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = (UUID) rs.getObject("id");
                String raw = rs.getString("name");
                out.add(new Plan(id, norm(raw), raw));
            }
        }
        return out;
    }

    private static List<UUID> loadCustomersMissingSubscription(Connection conn) throws SQLException {
        String sql = """
            SELECT c.id
            FROM customer c
            LEFT JOIN subscription s ON s.customer_id = c.id
            WHERE s.id IS NULL
        """;
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add((UUID) rs.getObject(1));
        }
        return ids;
    }

    private static List<UUID> loadAllCustomers(Connection conn) throws SQLException {
        String sql = "SELECT id FROM customer";
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add((UUID) rs.getObject(1));
        }
        return ids;
    }

    private static void deleteSubscriptionsFor(Connection conn, List<UUID> customerIds) throws SQLException {
        String sql = "DELETE FROM subscription WHERE customer_id = ANY (?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("uuid", customerIds.toArray()));
            int n = ps.executeUpdate();
            System.out.println("Deleted " + n + " existing subscriptions due to -Dreplace=true.");
        }
    }

    /* ---------- Insert ---------- */

    private static int insertSubscriptions(Connection conn, List<UUID> customerIds, WeightedPicker<Plan> picker) throws SQLException {
        String sql = """
            INSERT INTO subscription (id, customer_id, plan_id, status)
            VALUES (?, ?, ?, 'active')
        """;
        int batchSize = 1000;
        int count = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (UUID cid : customerIds) {
                Plan plan = picker.pick();
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, cid);
                ps.setObject(3, plan.id());
                ps.addBatch();

                if (++count % batchSize == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return count;
    }

    /* ---------- Weights ---------- */

    private static WeightedPicker<Plan> buildPlanPicker(List<Plan> plans) {
        // Fetch overrides: -Dw.basic=0.30 -Dw.standard=0.50 -Dw.premium=0.20
        Map<String, Double> overrides = new HashMap<>();
        for (Plan p : plans) {
            String key = "w." + p.nameNorm();              // e.g. "w.basic", "w.standard"
            String alt = "w." + p.nameRaw().toLowerCase(Locale.ROOT); // if someone uses space: "w.standard"
            String v = System.getProperty(key, System.getProperty(alt));
            if (v != null && !v.isBlank()) {
                try { overrides.put(p.nameNorm(), Double.parseDouble(v)); } catch (NumberFormatException ignored) {}
            }
        }

        // Build final weight map (normalize later)
        Map<Plan, Double> weights = new LinkedHashMap<>();
        for (Plan p : plans) {
            double w = overrides.getOrDefault(p.nameNorm(),
                    DEFAULT_WEIGHTS.getOrDefault(p.nameNorm(), 1.0)); // unknown plans -> equal base weight 1.0
            weights.put(p, w);
        }

        // Normalize
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            // fallback to equal weights
            double eq = 1.0 / plans.size();
            return new WeightedPicker<>(plans.stream().collect(Collectors.toMap(x -> x, x -> eq)));
        }
        final double inv = 1.0 / sum;
        Map<Plan, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<Plan, Double> e : weights.entrySet()) {
            normalized.put(e.getKey(), e.getValue() * inv);
        }
        return new WeightedPicker<>(normalized);
    }

    /* ---------- Small helper: weighted random picker ---------- */

    private static final class WeightedPicker<T> {
        private final NavigableMap<Double, T> map = new TreeMap<>();
        private final Random rng = new Random();

        WeightedPicker(Map<T, Double> weights) {
            double acc = 0.0;
            for (Map.Entry<T, Double> e : weights.entrySet()) {
                if (e.getValue() <= 0) continue;
                acc += e.getValue();
                map.put(acc, e.getKey());
            }
            if (map.isEmpty()) throw new IllegalArgumentException("No positive weights.");
        }

        T pick() {
            double r = rng.nextDouble() * map.lastKey();
            return map.higherEntry(r).getValue();
        }
    }
}
