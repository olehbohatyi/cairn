package cairn

import zio.test.*

object CoreScaffoldSpec extends ZIOSpecDefault:
  def spec = suite("core")(
    test("module builds and wires zio-test")(assertTrue(true))
  )
