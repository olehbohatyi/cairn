package cairn.store.postgres

import zio.test.*

object StorePostgresScaffoldSpec extends ZIOSpecDefault:
  def spec = suite("store-postgres")(
    test("module builds and wires zio-test")(assertTrue(true))
  )
