package cairn

import zio.*
import zio.test.*

/**
 * No network calls in any test — CLAUDE.md invariant #4. Everything here is pure Scala; there is
 * nothing yet to fake an LLM call with (that arrives with `llm` + `testkit`, roadmap Week 2–3).
 */
object InterpreterSpec extends ZIOSpecDefault:

  def spec = suite("Interpreter")(
    test("Effect runs and maps its error into GraphError.NodeFailed") {
      val node = Node.Effect[Any, String, Int, Int](
        NodeId("double"),
        i => ZIO.succeed(i * 2)
      )
      for result <- Interpreter.run(node, 21)
      yield assertTrue(result == 42)
    },
    test("Effect failure is wrapped with the failing node's id") {
      val node = Node.Effect[Any, String, Int, Int](
        NodeId("always-fails"),
        _ => ZIO.fail("boom")
      )
      for result <- Interpreter.run(node, 1).exit
      yield assertTrue(
        result == Exit.fail(GraphError.NodeFailed(NodeId("always-fails"), "boom"))
      )
    },
    test("Seq threads output of left into input of right") {
      val addOne = Node.Effect[Any, Nothing, Int, Int](NodeId("add-one"), i => ZIO.succeed(i + 1))
      val square = Node.Effect[Any, Nothing, Int, Int](NodeId("square"), i => ZIO.succeed(i * i))
      // Explicit type args: Node[-R, ...] is contravariant in R, and with no
      // expected type here Scala infers an unconstrained contravariant param
      // as its lower bound (Nothing), not Any — silently producing a
      // Node[Nothing, ...] that zio-test then (correctly) refuses to run.
      val pipeline =
        Node.Seq[Any, Nothing, Int, Int, Int](NodeId("add-then-square"), addOne, square)

      for result <- Interpreter.run(pipeline, 4)
      yield assertTrue(result == 25) // (4 + 1) ^ 2
    },
    test("FanOut runs branches in parallel and joins their results") {
      val branchA = Node.Effect[Any, Nothing, Int, Int](NodeId("a"), i => ZIO.succeed(i + 1))
      val branchB = Node.Effect[Any, Nothing, Int, Int](NodeId("b"), i => ZIO.succeed(i * 10))
      val fanOut = Node.FanOut[Any, Nothing, Int, Int](
        NodeId("fan"),
        NonEmptyChunk(branchA, branchB),
        results => results.asInstanceOf[Chunk[Int]].sum
      )

      for result <- Interpreter.run(fanOut, 5)
      yield assertTrue(result == 56) // (5 + 1) + (5 * 10)
    }
  )
