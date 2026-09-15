package cairn

/**
 * Identifies a node within a graph. Also the first component of a checkpoint key
 * `(runId, nodeId, attempt)` — see CLAUDE.md, "Checkpoint contract".
 */
opaque type NodeId = String

object NodeId:
  def apply(value: String): NodeId = value

  extension (id: NodeId) def value: String = id
