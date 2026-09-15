package cairn.examples

import zio.test.*

object ExamplesScaffoldSpec extends ZIOSpecDefault:
  def spec = suite("examples")(
    test("module builds and wires zio-test")(assertTrue(true))
  )
