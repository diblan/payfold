import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

public class SubscriptionSeederDueToday {

    static final String url  = System.getProperty("db.url");
    static final String user = System.getProperty("db.user");
    static final String pass = System.getProperty("db.pass");

    // Default plan weights (override with -Dw.basic=0.30 etc.)
    private static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
        "basic",    0.35,
        "standard", 0.45,
        "premium",  0.20
    );

    // Brussels timezone
    private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

    public static void main(String[] args) throws Exception {
        // How many should be due today? (0.0..1.0). Remainder is split across yesterday/tomorrow.
        final double dueTodayRatio = clamp01(parseDouble(System.getProperty("dueTodayRatio"), 1.0));

        // Mode: "missing" (default) only customers without a subscription; "all" targets everyone
        final String mode = System.getProperty("mode", "missing").toLowerCase(Locale.ROOT);
        final boolean replaceExisting = Boolean.parseBoolean(System.getProperty("replace", "false"));

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setAutoCommit(false);

            // 1) Load plans (id, name, interval)
            List<Plan> plans = loadPlans(conn);
            if (plans.isEmpty()) {
                throw new IllegalStateException("No plans found. Seed the plan table first.");
            }

            // 2) Build weighted picker
            WeightedPicker<Plan> picker = buildPlanPicker(plans);

            // 3) Target customers
            List<UUID> targets = "all".equals(mode)
                    ? loadAllCustomers(conn)
                    : loadCustomersMissingSubscription(conn);

            if (targets.isEmpty()) {
                System.out.println("Nothing to do. No target customers found for mode='" + mode + "'.");
                return;
            }

            if ("all".equals(mode) && replaceExisting) {
                deleteSubscriptionsFor(conn, targets);
            }

            // 4) Insert subscriptions with due dates around today
            int inserted = insertSubscriptionsWithDueWindow(conn, targets, picker, dueTodayRatio);

            conn.commit();
            System.out.println("✅ Inserted " + inserted + " subscriptions. dueTodayRatio=" + dueTodayRatio);
        }
    }

    /* ---------------- Data types ---------------- */

    private record Plan(UUID id, String nameNorm, String nameRaw, String interval) {}

    private static String norm(String planName) {
        return planName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /* ---------------- Loaders ---------------- */

    private static List<Plan> loadPlans(Connection conn) throws SQLException {
        String sql = "SELECT id, name, interval FROM plan";
        List<Plan> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = (UUID) rs.getObject("id");
                String raw = rs.getString("name");
                String interval = rs.getString("interval"); // 'month' or 'year'
                out.add(new Plan(id, norm(raw), raw, interval == null ? "month" : interval.toLowerCase(Locale.ROOT)));
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

    /* ---------------- Insert logic (due around today) ---------------- */

    private static int insertSubscriptionsWithDueWindow(Connection conn, List<UUID> customerIds,
                                                       WeightedPicker<Plan> picker, double dueTodayRatio) throws SQLException {
        LocalDate today = LocalDate.now(ZONE);
        Random rnd = new Random();

        String sql = """
            INSERT INTO subscription (id, customer_id, plan_id, status, start_at, cancel_at, renewed_at)
            VALUES (?, ?, ?, 'active', ?, NULL, ?)
        """;

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (UUID cid : customerIds) {
                Plan plan = picker.pick();

                // Decide the due day bucket: today / yesterday / tomorrow
                LocalDate dueDate;
                double r = rnd.nextDouble();
                double rem = 1.0 - dueTodayRatio;
                double dueYesterdayCutoff = dueTodayRatio + rem / 2.0;

                if (r < dueTodayRatio) {
                    dueDate = today;
                } else if (r < dueYesterdayCutoff) {
                    dueDate = today.minusDays(1);
                } else {
                    dueDate = today.plusDays(1);
                }

                // Use 09:00 local time so you don't hit midnight edge-cases
                LocalDateTime dueAt = dueDate.atTime(9, 0);

                // Set renewed_at so that renewed_at + interval == dueAt
                LocalDateTime renewedAt;
                if ("year".equals(plan.interval())) {
                    renewedAt = dueAt.minusYears(1);
                } else {
                    renewedAt = dueAt.minusMonths(1);
                }

                // start_at some cycles before renewedAt for realism
                LocalDateTime startAt;
                if ("year".equals(plan.interval())) {
                    int yearsBack = 1 + rnd.nextInt(4); // 1..4 years before renewedAt
                    startAt = renewedAt.minusYears(yearsBack);
                } else {
                    int monthsBack = 2 + rnd.nextInt(24); // 2..25 months before renewedAt
                    startAt = renewedAt.minusMonths(monthsBack);
                }

                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, cid);
                ps.setObject(3, plan.id());
                ps.setTimestamp(4, ts(startAt));
                ps.setTimestamp(5, ts(renewedAt));

                ps.addBatch();
                if (++count % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        return count;
    }

    private static Timestamp ts(LocalDateTime ldt) {
        return Timestamp.from(ldt.atZone(ZONE).toInstant());
    }

    /* ---------------- Weights ---------------- */

    private static WeightedPicker<Plan> buildPlanPicker(List<Plan> plans) {
        Map<String, Double> overrides = new HashMap<>();
        for (Plan p : plans) {
            String key = "w." + p.nameNorm(); // e.g. w.standard
            String v = System.getProperty(key);
            if (v != null && !v.isBlank()) {
                try { overrides.put(p.nameNorm(), Double.parseDouble(v)); } catch (NumberFormatException ignored) {}
            }
        }

        Map<Plan, Double> weights = new LinkedHashMap<>();
        for (Plan p : plans) {
            double w = overrides.getOrDefault(p.nameNorm(), DEFAULT_WEIGHTS.getOrDefault(p.nameNorm(), 1.0));
            weights.put(p, w);
        }

        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
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

    /* ---------------- Helpers ---------------- */

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    /* ---------------- Weighted picker ---------------- */

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
