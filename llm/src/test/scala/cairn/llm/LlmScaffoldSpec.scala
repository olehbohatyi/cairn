package cairn.llm

import zio.test.*

object LlmScaffoldSpec extends ZIOSpecDefault:
  def spec = suite("llm")(
    test("module builds and wires zio-test")(assertTrue(true))
  )
