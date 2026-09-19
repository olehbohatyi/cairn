package cairn.store.postgres

import zio.test.*

/**
 * The shared `CheckpointStore` contract against a real Postgres under testcontainers.
 *
 * WRITTEN, NOT YET RUN: needs a Docker daemon, which was not available when this was written. The
 * same assertions pass against `InMemoryStore` (`InMemoryContractSpec`), so a failure here points
 * at the SQL or the codec-through-Postgres path, not at the test logic. Run before merging.
 */
object PostgresContractSpec extends ZIOSpecDefault:
  def spec = CheckpointStoreContract.tests.provideShared(PostgresTestSupport.containerAndStore)
