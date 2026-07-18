#!/bin/bash
# Run the CustomerSeeder program with the PostgreSQL driver

# Compile to a writable path: the source dir is mounted read-only in docker compose
OUT="${SEED_OUT_DIR:-/tmp/seed-out}"
mkdir -p "$OUT"

javac -cp ".:libs/*" -d "$OUT" CustomerSeeder.java
java -cp "$OUT:libs/*" \
    -Ddb.url="$POSTGRES_URL" \
    -Ddb.user="$POSTGRES_USER" \
    -Ddb.pass="$POSTGRES_PASSWORD" \
    CustomerSeeder

javac -cp ".:libs/*" -d "$OUT" SubscriptionSeederDueToday.java
java -cp "$OUT:libs/*" \
    -Ddb.url="$POSTGRES_URL" \
    -Ddb.user="$POSTGRES_USER" \
    -Ddb.pass="$POSTGRES_PASSWORD" \
    SubscriptionSeederDueToday
