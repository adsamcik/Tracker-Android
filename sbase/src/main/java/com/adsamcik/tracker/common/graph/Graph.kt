package com.adsamcik.tracker.common.graph

/*data class Graph(s: String, edges: List<Pair<Int,Int>>) {
	val vertices = s.split(", ")
	val numVertices = vertices.size
	val adjacency = List(numVertices) { BooleanArray(numVertices) }

	init {
		for (edge in edges) adjacency[edge.first][edge.second] = true
	}
}*/

inline class Vertex(val value: Int)

data class Edge(val from: Vertex, val to: Vertex)

data class Graph(val vertexList: List<Vertex>, val edgeList: List<Edge>) {
	init {
		val areEdgesValid = edgeList.all {
			vertexList.contains(it.from) &&
					vertexList.contains(it.to)
		}

		if (!areEdgesValid) {
			throw IllegalArgumentException(
					"Graph received invalid edges.\n" +
							"Vertexes ${vertexList.joinToString()}\n" +
							"Edges ${edgeList.joinToString()}"
			)
		}
	}
}
