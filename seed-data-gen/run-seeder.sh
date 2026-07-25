#!/bin/bash
# Compile and run the seeders (CustomerSeeder, then SubscriptionSeederDueToday) against Postgres

# Job semantics: any failing step must fail the container run loudly.
set -euo pipefail

# Compile to a writable path outside /app: the image may run with a read-only
# root filesystem or read-only source dir; only SEED_OUT_DIR needs to be writable
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
