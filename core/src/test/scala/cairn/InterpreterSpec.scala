package cairn

import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * Structural behaviour of the interpreter, against `CheckpointStore.none` so that persistence is
 * out of the picture. Replay behaviour is tested in `cairn-store-memory`, which has a store that
 * actually remembers.
 *
 * No network calls - CLAUDE.md invariant #4.
 */
object InterpreterSpec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  private val noStore = ZEnvironment(CheckpointStore.none)
  private val runId = RunId("spec")

  private def effect[E](id: String)(f: Int => IO[E, Int]): Node.Effect[Any, E, Int, Int] =
    Node.Effect(NodeId(id), f, intSchema)

  def spec = suite("Interpreter")(
    test("Effect runs and returns its result") {
      val node = effect[Nothing]("double")(i => ZIO.succeed(i * 2))
      for result <- Interpreter.run(node, 21, runId).provideEnvironment(noStore)
      yield assertTrue(result == 42)
    },
    test("Effect failure is wrapped with the failing node's id") {
      val node = effect[String]("always-fails")(_ => ZIO.fail("boom"))
      for result <- Interpreter.run(node, 1, runId).provideEnvironment(noStore).either
      yield assertTrue(result == Left(GraphError.NodeFailed(NodeId("always-fails"), "boom")))
    },
    test("Seq threads the output of left into the input of right") {
      val addOne = effect[Nothing]("add-one")(i => ZIO.succeed(i + 1))
      val square = effect[Nothing]("square")(i => ZIO.succeed(i * i))
      val pipeline = Node.Seq(NodeId("add-then-square"), addOne, square)

      for result <- Interpreter.run(pipeline, 4, runId).provideEnvironment(noStore)
      yield assertTrue(result == 25) // (4 + 1) ^ 2
    },
    test("Seq short-circuits: right does not run when left fails") {
      for
        rightRuns <- Ref.make(0)
        left = effect[String]("left")(_ => ZIO.fail("boom"))
        right = effect[String]("right")(i => rightRuns.update(_ + 1).as(i))
        pipeline = Node.Seq(NodeId("pipeline"), left, right)
        result <- Interpreter.run(pipeline, 1, runId).provideEnvironment(noStore).either
        count <- rightRuns.get
      yield assertTrue(result.isLeft, count == 0)
    },
    test("FanOut runs branches in parallel and joins their results") {
      val branchA = effect[Nothing]("a")(i => ZIO.succeed(i + 1))
      val branchB = effect[Nothing]("b")(i => ZIO.succeed(i * 10))
      val fanOut = Node.FanOut[Any, Nothing, Int, Int](
        NodeId("fan"),
        NonEmptyChunk(branchA, branchB),
        results => results.map(_.asInstanceOf[Int]).sum
      )

      for result <- Interpreter.run(fanOut, 5, runId).provideEnvironment(noStore)
      yield assertTrue(result == 56) // (5 + 1) + (5 * 10)
    },
    test("an uninterpreted node dies rather than being silently skipped") {
      val inner = effect[Nothing]("inner")(i => ZIO.succeed(i))
      val gate = Node.Gate[Any, Nothing, Int, Int](NodeId("gate"), inner, _ => true)

      for exit <- Interpreter.run(gate, 1, runId).provideEnvironment(noStore).exit
      yield assertTrue(exit.isFailure && exit.causeOption.exists(_.isDie))
    }
  )
