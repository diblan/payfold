# Seed job image: bakes the seeder source, JDBC driver, and name data that compose
# previously bind-mounted from the host (a Kubernetes cluster cannot mount a
# dev-machine path). Runnable as a run-to-completion Job: exit 0 = success.
# run-seeder.sh compiles at container start, so the image needs a JDK and a
# writable SEED_OUT_DIR (default /tmp/seed-out).
# Config env: POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD, SEED_CUSTOMERS.
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY libs ./libs
COPY data ./data
COPY CustomerSeeder.java SubscriptionSeederDueToday.java run-seeder.sh ./
ENTRYPOINT ["bash", "./run-seeder.sh"]
