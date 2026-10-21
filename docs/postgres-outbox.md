# PostgreSQL outbox and inbox

`io.setu.client.jdbc.PostgresOutbox` integrates PostgreSQL transactions with a durable Setu broker. Supply an application-owned `DataSource` and PostgreSQL JDBC driver. The client module uses standard JDBC APIs and adds no production driver dependency. Apply [postgres.sql](../setu-client/src/main/resources/io/setu/client/jdbc/postgres.sql) to the application's schema through its migration process.

## Publishing

In a connection with auto-commit disabled, perform the business update and call `PostgresOutbox.enqueue(transaction, topic, partition, payload)`, then commit both together. A rollback removes both. The overload accepting a UUID supports an application-assigned immutable event identity; never reuse an identity for another event. Enqueue requires an existing transaction and does not commit it.

A relay opens its own `SetuClient` to a **FILE** broker and repeatedly calls `new PostgresOutbox(dataSource).dispatchOne(client)`. Each call locks one unpublished row using `FOR UPDATE SKIP LOCKED`, publishes an envelope containing the UUID and payload, marks it published only after a successful broker response, and commits. `false` means no currently unlocked pending row; back off before polling again. Errors roll back the database transaction; reconnect a failed client before the next attempt. Use finite socket and database timeouts. Multiple relay workers can run concurrently, but they do not preserve event order across workers or concurrent application transactions. Business ordering requires application coordination.

An accepted broker write followed by a lost reply or failed database commit can be published again. This is **at-least-once publication**. The adapter uses ordinary produce requests and does not use Setu's separately identified idempotent write API. Cross-system atomic commits are not provided. Do not use an IN_MEMORY broker for durable outbox delivery: a broker restart could lose already-marked publications. The envelope adds 20 bytes, which must fit the broker's configured record limit; the adapter's maximum application payload is 14 MiB minus 20 bytes. Published rows remain for application-managed audit and cleanup.

## Applying database effects

For each fetched record, call `outbox.process(stableConsumerId, record.value(), handler)`. It inserts `(consumerId, eventId)` into `setu_inbox` and runs the handler's business SQL on the **same connection and transaction**. A primary-key conflict skips a duplicate, including concurrent deliveries. Handler failure rolls back the inbox marker and effects so processing can be attempted again. The method returns `true` when effects commit, or `false` when the event was already processed.

Only acknowledge the broker's next offset after `process` returns successfully. A crash between database commit and broker acknowledgement can redeliver the event; its inbox key prevents the business SQL from running again. Use a stable consumer ID for the business operation, independent of ephemeral broker member IDs, and retain inbox keys for as long as events can be replayed. Do not delete inbox keys merely because the broker offset advanced.

The handler must not commit, roll back, close its connection, change auto-commit, or perform external effects such as HTTP calls. Exactly-once database effects apply only to work inside this PostgreSQL transaction, with immutable unique event IDs and retained inbox keys. This is not exactly-once network delivery, producer deduplication, or atomic consume-and-produce inside Setu. If database commit has an uncertain outcome, retry the same envelope and consumer ID. PostgreSQL availability, backups, schema privileges and inbox/outbox retention remain application responsibilities.

## Verification

Set `SETU_TEST_POSTGRES_URL` to a JDBC URL for a **disposable test database**, `SETU_TEST_POSTGRES_USER`, and optionally `SETU_TEST_POSTGRES_PASSWORD` in your local environment. Tests create and drop their own randomly named schemas; the account needs schema creation privileges. Then run:

```powershell
.\mvnw.cmd -pl setu-integration-tests -am '-Dtest=PostgresOutboxIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Without the URL these tests skip. CI supplies an ephemeral PostgreSQL 18 service and runs them explicitly, checking application rollback, relay redelivery, handler rollback and concurrent inbox deduplication. Local PostgreSQL credentials are never source fixtures. SQL coordination follows PostgreSQL's documented [row locking](https://www.postgresql.org/docs/current/sql-select.html) and [conflict handling](https://www.postgresql.org/docs/current/sql-insert.html).
