package cairn.store.postgres

import cairn.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import zio.*

/** Shared plumbing for the specs that need a real Postgres. Requires Docker. */
object PostgresTestSupport:

  private val image = DockerImageName.parse("postgres:16-alpine")

  val container: ZLayer[Any, Throwable, PostgreSQLContainer] =
    ZLayer.scoped(
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val c = PostgreSQLContainer(dockerImageNameOverride = image)
          c.start()
          c
        }
      )(c => ZIO.attemptBlocking(c.stop()).ignore)
    )

  def configFor(c: PostgreSQLContainer): PostgresConfig =
    PostgresConfig(c.jdbcUrl, c.username, c.password, maxPoolSize = 4)

  /** A store over its own connection pool; the pool closes when `Scope` does. */
  def openStore(c: PostgreSQLContainer): ZIO[Scope, StoreError, CheckpointStore] =
    PostgresStore.layer(configFor(c)).build.map(_.get[CheckpointStore])

  val store: ZLayer[PostgreSQLContainer, StoreError, CheckpointStore] =
    ZLayer.scoped(ZIO.serviceWithZIO[PostgreSQLContainer](openStore))

  /** Container plus a store over it - the shared layer for the contract spec. */
  val containerAndStore: ZLayer[Any, Any, CheckpointStore] = container >>> store
