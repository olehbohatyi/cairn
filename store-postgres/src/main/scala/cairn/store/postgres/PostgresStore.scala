package cairn.store.postgres

import cairn.*
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import zio.*

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Types}
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import javax.sql.DataSource
import scala.util.Using

/** Connection settings for [[PostgresStore.layer]]. Ours, not Hikari's - no pool type leaks out. */
final case class PostgresConfig(
    jdbcUrl: String,
    user: String,
    password: String,
    maxPoolSize: Int = 10
)

/**
 * [[CheckpointStore]] over plain JDBC and a HikariCP pool (CLAUDE.md, Stack: no `zio-jdbc`, no
 * `zio-sql`).
 *
 * Write-once is the primary key, not a read-then-write: `commit` is a plain `INSERT`, and a unique
 * violation (SQLState `23505`) is what becomes [[StoreError.AlreadyCommitted]]. Never
 * `ON CONFLICT DO NOTHING` - that would let a losing worker believe it had committed.
 *
 * `Money` is stored as `(cost_cents BIGINT, currency VARCHAR(3))`, not a fixed-currency column, so
 * a second currency later is not a migration. `VARCHAR`, not `CHAR`: `CHAR(3)` would hand back a
 * shorter code space-padded. The bound is three characters (ISO 4217); a longer code is rejected by
 * the database and surfaces as [[StoreError.Backend]].
 *
 * `value` is `JSON`, not `JSONB`: replay must get back exactly what was committed, and `JSONB`
 * normalises text and rejects the NUL character in strings. Nothing here queries into the value.
 *
 * `committed_at` is `TIMESTAMPTZ`, which keeps microseconds; `InMemoryStore` keeps nanoseconds.
 * Nothing reads `committedAt` for anything but ordering.
 *
 * Failures never carry a `Throwable` (invariant #3): SQL errors are logged with their cause at the
 * boundary and surface as a structured [[StoreError]].
 */
final class PostgresStore private (ds: DataSource) extends CheckpointStore:
  import PostgresStore.*

  def get(runId: RunId, nodeId: NodeId, attempt: Attempt): IO[StoreError, Option[Checkpoint]] =
    io { conn =>
      Using.resource(conn.prepareStatement(SelectOne)) { ps =>
        ps.setString(1, runId.value)
        ps.setString(2, nodeId.value)
        ps.setInt(3, attempt.value)
        Using.resource(ps.executeQuery())(rs => if rs.next() then Some(readRow(rs)) else None)
      }
    }().flatMap {
      case None => ZIO.none
      case Some(row) => toCheckpoint(row).map(Some(_))
    }

  def commit(checkpoint: Checkpoint): IO[StoreError, Unit] =
    io { conn =>
      Using.resource(conn.prepareStatement(InsertCheckpoint)) { ps =>
        ps.setString(1, checkpoint.runId.value)
        ps.setString(2, checkpoint.nodeId.value)
        ps.setInt(3, checkpoint.attempt.value)
        ps.setString(4, DynamicValueJson.encode(checkpoint.value))
        setOptLong(ps, 5, checkpoint.cost.map(_.cents))
        setOptString(ps, 6, checkpoint.cost.map(_.currency))
        setOptLong(ps, 7, checkpoint.tokens.map(_.input))
        setOptLong(ps, 8, checkpoint.tokens.map(_.output))
        ps.setObject(9, toOffset(checkpoint.committedAt))
        ps.executeUpdate()
        ()
      }
    } {
      case e: SQLException if e.getSQLState == UniqueViolation =>
        StoreError.AlreadyCommitted(checkpoint.runId, checkpoint.nodeId, checkpoint.attempt)
    }

  def list(runId: RunId): IO[StoreError, Chunk[Checkpoint]] =
    io { conn =>
      Using.resource(conn.prepareStatement(SelectRun)) { ps =>
        ps.setString(1, runId.value)
        Using.resource(ps.executeQuery()) { rs =>
          val rows = Chunk.newBuilder[Row]
          while rs.next() do rows += readRow(rs)
          rows.result()
        }
      }
    }().flatMap(ZIO.foreach(_)(toCheckpoint))

  def delete(runId: RunId): IO[StoreError, Unit] =
    transaction { conn =>
      update(conn, "DELETE FROM cairn_checkpoint WHERE run_id = ?", runId.value)
      update(conn, "DELETE FROM cairn_attempt WHERE run_id = ?", runId.value)
    }

  def recordAttempt(record: AttemptRecord): IO[StoreError, Unit] =
    io { conn =>
      Using.resource(conn.prepareStatement(InsertAttempt)) { ps =>
        ps.setString(1, record.runId.value)
        ps.setString(2, record.nodeId.value)
        ps.setInt(3, record.attempt.value)
        ps.setString(4, record.outcome.toString)
        setOptLong(ps, 5, record.cost.map(_.cents))
        setOptString(ps, 6, record.cost.map(_.currency))
        setOptLong(ps, 7, record.tokens.map(_.input))
        setOptLong(ps, 8, record.tokens.map(_.output))
        ps.setObject(9, toOffset(record.recordedAt))
        ps.executeUpdate()
        ()
      }
    }()

  def listAttempts(runId: RunId): IO[StoreError, Chunk[AttemptRecord]] =
    io { conn =>
      Using.resource(conn.prepareStatement(SelectAttempts)) { ps =>
        ps.setString(1, runId.value)
        Using.resource(ps.executeQuery()) { rs =>
          val records = Chunk.newBuilder[AttemptRecord]
          while rs.next() do
            records += AttemptRecord(
              RunId(rs.getString("run_id")),
              NodeId(rs.getString("node_id")),
              Attempt(rs.getInt("attempt")),
              AttemptRecord.Outcome.valueOf(rs.getString("outcome")),
              readMoney(rs),
              readTokens(rs),
              rs.getObject("recorded_at", classOf[OffsetDateTime]).toInstant
            )
          records.result()
        }
      }
    }()

  /**
   * Runs `f` on a pooled connection on the blocking pool. `recover` claims the errors this call
   * knows how to type; anything else is logged with its cause and becomes [[StoreError.Backend]].
   */
  private def io[A](f: Connection => A)(
      recover: PartialFunction[Throwable, StoreError] = PartialFunction.empty
  ): IO[StoreError, A] =
    typed(ZIO.attemptBlocking(Using.resource(ds.getConnection)(f)))(recover)

  /**
   * Like [[io]], but `f` runs in one transaction: committed if it returns, rolled back if it fails
   * or is interrupted. Nothing is rethrown by hand - the failure stays in the ZIO error channel.
   */
  private def transaction[A](f: Connection => A): IO[StoreError, A] =
    typed(
      ZIO.acquireReleaseWith(ZIO.attemptBlocking(ds.getConnection))(conn =>
        ZIO.attemptBlocking(conn.close()).ignore
      ) { conn =>
        ZIO
          .attemptBlocking {
            conn.setAutoCommit(false)
            val result = f(conn)
            conn.commit()
            result
          }
          .onError(_ => ZIO.attemptBlocking(conn.rollback()).ignore)
      }
    )(PartialFunction.empty)

  private def typed[A](effect: Task[A])(
      recover: PartialFunction[Throwable, StoreError]
  ): IO[StoreError, A] =
    effect.catchAll { t =>
      recover.lift(t) match
        case Some(known) => ZIO.fail(known)
        case None =>
          ZIO.logWarningCause("checkpoint store backend failure", Cause.fail(t)) *>
            ZIO.fail(StoreError.Backend(s"${t.getClass.getSimpleName}: ${t.getMessage}"))
    }

  private def toCheckpoint(row: Row): IO[StoreError, Checkpoint] =
    ZIO
      .fromEither(DynamicValueJson.decode(row.json))
      .mapError(reason => StoreError.DecodeFailed(RunId(row.runId), NodeId(row.nodeId), reason))
      .map(value =>
        Checkpoint(
          RunId(row.runId),
          NodeId(row.nodeId),
          Attempt(row.attempt),
          value,
          row.cost,
          row.tokens,
          row.committedAt
        )
      )

  private def migrate: IO[StoreError, Unit] =
    transaction { conn =>
      Using.resource(conn.createStatement()) { st =>
        // Serialises concurrent pods starting together: CREATE TABLE IF NOT EXISTS is not
        // race-free in Postgres on its own. Advisory locks are local to each database
        // (PostgreSQL docs, pg_locks), so this contends only with another user of the same
        // key in the same database, not with other databases on the server.
        st.execute(s"SELECT pg_advisory_xact_lock($MigrationLockKey)")
        Ddl.foreach(st.execute)
      }
    }

object PostgresStore:

  /** A pooled store; the pool is closed when the layer's scope ends. Runs the migration first. */
  def layer(config: PostgresConfig): ZLayer[Any, StoreError, CheckpointStore] =
    ZLayer.scoped(pool(config).flatMap(make))

  /** Over a caller-owned `DataSource`, which the caller closes. Runs the migration first. */
  def make(ds: DataSource): IO[StoreError, CheckpointStore] =
    val store = new PostgresStore(ds)
    store.migrate.as(store)

  private def pool(config: PostgresConfig): ZIO[Scope, StoreError, HikariDataSource] =
    ZIO.acquireRelease(
      ZIO
        .attemptBlocking {
          val hc = new HikariConfig()
          hc.setJdbcUrl(config.jdbcUrl)
          hc.setUsername(config.user)
          hc.setPassword(config.password)
          hc.setMaximumPoolSize(config.maxPoolSize)
          new HikariDataSource(hc)
        }
        .mapError(t => StoreError.Backend(s"could not open connection pool: ${t.getMessage}"))
    )(ds => ZIO.attemptBlocking(ds.close()).ignore)

  private val UniqueViolation = "23505"
  private val MigrationLockKey = 727274L

  private val Ddl = List(
    """CREATE TABLE IF NOT EXISTS cairn_checkpoint (
      |  run_id       TEXT        NOT NULL,
      |  node_id      TEXT        NOT NULL,
      |  attempt      INTEGER     NOT NULL,
      |  value        JSON        NOT NULL,
      |  cost_cents   BIGINT,
      |  currency     VARCHAR(3),
      |  input_tokens  BIGINT,
      |  output_tokens BIGINT,
      |  committed_at TIMESTAMPTZ NOT NULL,
      |  PRIMARY KEY (run_id, node_id, attempt)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS cairn_attempt (
      |  id            BIGSERIAL   PRIMARY KEY,
      |  run_id        TEXT        NOT NULL,
      |  node_id       TEXT        NOT NULL,
      |  attempt       INTEGER     NOT NULL,
      |  outcome       TEXT        NOT NULL,
      |  cost_cents    BIGINT,
      |  currency      VARCHAR(3),
      |  input_tokens  BIGINT,
      |  output_tokens BIGINT,
      |  recorded_at   TIMESTAMPTZ NOT NULL
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS cairn_attempt_run ON cairn_attempt (run_id)"
  )

  private val Columns =
    "run_id, node_id, attempt, value::text AS value, cost_cents, currency, " +
      "input_tokens, output_tokens, committed_at"

  private val SelectOne =
    s"SELECT $Columns FROM cairn_checkpoint WHERE run_id = ? AND node_id = ? AND attempt = ?"

  private val SelectRun =
    s"SELECT $Columns FROM cairn_checkpoint WHERE run_id = ? " +
      "ORDER BY committed_at, node_id, attempt"

  private val InsertCheckpoint =
    "INSERT INTO cairn_checkpoint (run_id, node_id, attempt, value, cost_cents, currency, " +
      "input_tokens, output_tokens, committed_at) VALUES (?, ?, ?, ?::json, ?, ?, ?, ?, ?)"

  private val InsertAttempt =
    "INSERT INTO cairn_attempt (run_id, node_id, attempt, outcome, cost_cents, currency, " +
      "input_tokens, output_tokens, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

  private val SelectAttempts =
    "SELECT run_id, node_id, attempt, outcome, cost_cents, currency, input_tokens, " +
      "output_tokens, recorded_at FROM cairn_attempt WHERE run_id = ? ORDER BY id"

  final private case class Row(
      runId: String,
      nodeId: String,
      attempt: Int,
      json: String,
      cost: Option[Money],
      tokens: Option[TokenCount],
      committedAt: Instant
  )

  private def readRow(rs: ResultSet): Row =
    Row(
      rs.getString("run_id"),
      rs.getString("node_id"),
      rs.getInt("attempt"),
      rs.getString("value"),
      readMoney(rs),
      readTokens(rs),
      rs.getObject("committed_at", classOf[OffsetDateTime]).toInstant
    )

  private def readMoney(rs: ResultSet): Option[Money] =
    for
      cents <- optLong(rs, "cost_cents")
      currency <- Option(rs.getString("currency"))
    yield Money(cents, currency)

  private def readTokens(rs: ResultSet): Option[TokenCount] =
    for
      in <- optLong(rs, "input_tokens")
      out <- optLong(rs, "output_tokens")
    yield TokenCount(in, out)

  private def optLong(rs: ResultSet, column: String): Option[Long] =
    val v = rs.getLong(column)
    if rs.wasNull() then None else Some(v)

  private def setOptLong(ps: PreparedStatement, index: Int, value: Option[Long]): Unit =
    value match
      case Some(v) => ps.setLong(index, v)
      case None => ps.setNull(index, Types.BIGINT)

  private def setOptString(ps: PreparedStatement, index: Int, value: Option[String]): Unit =
    value match
      case Some(v) => ps.setString(index, v)
      case None => ps.setNull(index, Types.VARCHAR)

  private def toOffset(instant: Instant): OffsetDateTime =
    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

  private def update(conn: Connection, sql: String, param: String): Unit =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      ps.setString(1, param)
      ps.executeUpdate()
      ()
    }
