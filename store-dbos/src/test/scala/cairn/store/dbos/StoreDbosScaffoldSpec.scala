package cairn.store.dbos

import zio.test.*

object StoreDbosScaffoldSpec extends ZIOSpecDefault:
  def spec = suite("store-dbos")(
    test("module builds and wires zio-test")(assertTrue(true))
  )
