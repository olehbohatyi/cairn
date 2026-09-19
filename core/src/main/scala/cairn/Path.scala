package cairn

import zio.Chunk

/**
 * A chain of ancestor node ids leading to (but not including) the current node. Used ONLY to
 * qualify checkpoint *store keys* - it never appears in a `GraphError`, which always carries the
 * plain id the graph author wrote. Error attribution and storage-key qualification are deliberately
 * separate concerns; conflating them would mean a nested node's failure gets reported to the user
 * as a compound path string instead of the id they actually wrote in their graph.
 *
 * Fixes the node id collision gap (CLAUDE.md, Open decisions): two `FanOut` branches sharing a
 * stated id, or a sub-graph reused under two differently named wrapper nodes, now land at distinct
 * store keys instead of silently replaying each other's output.
 *
 * Deliberately partial, not a general solution: `Seq`'s `left`/`right` children share the same
 * descended path (neither adds a segment of its own), so two children of one `Seq` sharing a
 * literal id are still NOT disambiguated. Only `FanOut` gets positional (index-based)
 * qualification, since "two branches with the same name" was the explicitly named collision case; a
 * `Seq` with two identically-named children is a different, narrower mistake than either case
 * CLAUDE.md records, and nested disambiguation via ordinary path segments is not the whole answer
 * to it.
 *
 * `qualify` is the identity when the path is root - every checkpoint key for a top-level,
 * non-nested node is completely unchanged by this type's introduction, which is what keeps every
 * existing test's assertions valid.
 */
opaque type Path = Chunk[String]

object Path:
  val root: Path = Chunk.empty

  extension (p: Path)
    /**
     * Descend into a child position, tagging it with `segment` - the parent's own id for
     * `Loop`/`Verify`, or `"<id>#<index>"` for a `FanOut` branch.
     */
    def descend(segment: String): Path = p :+ segment

    def qualify(id: NodeId): NodeId =
      if p.isEmpty then id else NodeId((p :+ id.value).mkString("/"))
