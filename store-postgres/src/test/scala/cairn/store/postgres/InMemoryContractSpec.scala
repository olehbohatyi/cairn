package cairn.store.postgres

import cairn.store.InMemoryStore
import zio.test.*

/**
 * The contract run against `InMemoryStore`. Offline. This is what validates the contract's own
 * assertions: a failure here is a bad test, not a bad Postgres store.
 */
object InMemoryContractSpec extends ZIOSpecDefault:
  def spec = CheckpointStoreContract.tests.provideShared(InMemoryStore.layer)
