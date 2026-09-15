package cairn.testkit

import zio.test.*

object TestkitScaffoldSpec extends ZIOSpecDefault:
  def spec = suite("testkit")(
    test("module builds and wires zio-test")(assertTrue(true))
  )
