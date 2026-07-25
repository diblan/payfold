# Migrations job image: Flyway with db-migrations/*.sql baked in, runnable as a
# run-to-completion Job (exit 0 = success; re-run on a current schema is a no-op).
# A GitOps cluster can only pull artifacts from a registry — compose's host bind
# mount of this directory has no Kubernetes equivalent.
# Config via Flyway's native env vars: FLYWAY_URL, FLYWAY_USER, FLYWAY_PASSWORD.
FROM flyway/flyway:11
ENV FLYWAY_CONNECT_RETRIES=30
COPY V*.sql /flyway/sql/
CMD ["migrate"]
