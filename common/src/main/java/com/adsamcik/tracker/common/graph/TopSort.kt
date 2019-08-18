@file:Suppress("MatchingDeclarationName")

package com.adsamcik.tracker.common.graph

import com.adsamcik.tracker.common.exception.CircularDependencyException


/**
 * Topological search using Kahn's algorithm
 */
@Suppress("NestedBlockDepth")
fun Graph.topSort(): List<Vertex> {
	val vertexList = vertexList.map { TopSortVertex(it) }.toMutableList()
	val edgeList = this.edgeList.toMutableList()

	edgeList.forEach { edge ->
		val vertexId = vertexList.binarySearch { sortVertex -> sortVertex.vertex.value - edge.to.value }
		vertexList[vertexId].incomingEdges++
	}

	val edgeLessList = mutableListOf<Vertex>()

	vertexList.removeAll {
		val noEdges = it.incomingEdges == 0
		if (noEdges) {
			edgeLessList.add(it.vertex)
		}
		noEdges
	}

	val sortedList = ArrayList<Vertex>(vertexList.size)

	while (edgeLessList.isNotEmpty()) {
		val next = edgeLessList.removeAt(edgeLessList.size - 1)
		sortedList.add(next)

		edgeList.removeAll { edge ->
			val isAffectedEdge = edge.from == next
			if (isAffectedEdge) {
				val vertexIndex = vertexList.binarySearch { sortVertex -> sortVertex.vertex.value - edge.to.value }
				val toVertex = vertexList[vertexIndex]
				if (--toVertex.incomingEdges == 0) {
					edgeLessList.add(toVertex.vertex)
					vertexList.removeAt(vertexIndex)
				}
			}
			isAffectedEdge
		}
	}

	if (vertexList.isNotEmpty()) {
		throw CircularDependencyException("Graph has circular dependency.\n" +
				"Vertexes ${this.vertexList.joinToString()}\n" +
				"Edges ${this.edgeList.joinToString()}")
	} else {
		assert(sortedList.size == this.vertexList.size)
		return sortedList
	}
}

data class TopSortVertex(val vertex: Vertex, var incomingEdges: Int = 0)
