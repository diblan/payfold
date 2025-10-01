#!/bin/bash
# Run the CustomerSeeder program with the PostgreSQL driver

# Compile the seeder (only needed once, you can remove if already compiled)
# Compile to out\
javac -cp ".:libs/*" -d out CustomerSeeder.java
java -cp "out:libs/*" \
    -Ddb.url="$POSTGRES_URL" \
    -Ddb.user="$POSTGRES_USER" \
    -Ddb.pass="$POSTGRES_PASSWORD" \
    CustomerSeeder

# Compile the seeder (only needed once, you can remove if already compiled)
# Compile to out\
javac -cp ".:libs/*" -d out SubscriptionSeederDueToday.java
java -cp "out:libs/*" \
    -Ddb.url="$POSTGRES_URL" \
    -Ddb.user="$POSTGRES_USER" \
    -Ddb.pass="$POSTGRES_PASSWORD" \
    SubscriptionSeederDueToday
