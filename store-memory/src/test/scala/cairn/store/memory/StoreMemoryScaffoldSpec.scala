package cairn.store.memory

import zio.test.*

object StoreMemoryScaffoldSpec extends ZIOSpecDefault:
  def spec = suite("store-memory")(
    test("module builds and wires zio-test")(assertTrue(true))
  )
