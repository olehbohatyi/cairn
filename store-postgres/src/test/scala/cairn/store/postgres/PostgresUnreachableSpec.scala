package cairn.store.postgres

import cairn.StoreError
import zio.*
import zio.test.*

/**
 * An unreachable database is a typed `StoreError.Backend`, not a thrown exception (invariant #3).
 * No Docker needed: it targets a loopback port nothing listens on, so the connection is refused at
 * once. That is the only socket this spec touches.
 */
object PostgresUnreachableSpec extends ZIOSpecDefault:

  def spec = suite("unreachable database")(
    test("opening the store fails with StoreError.Backend") {
      val config = PostgresConfig("jdbc:postgresql://127.0.0.1:1/none", "u", "p", maxPoolSize = 1)
      for result <- ZIO.scoped(PostgresStore.layer(config).build).either
      yield assertTrue(
        result.left.exists {
          case StoreError.Backend(_) => true
          case _ => false
        }
      )
    }
  ) @@ TestAspect.timeout(60.seconds)
