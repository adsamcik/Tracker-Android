package com.adsamcik.tracker.shared.base.graph

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Graph - data structure with edge validation")
class GraphTest {

	@Nested
	@DisplayName("Valid construction")
	inner class ValidConstruction {
		@Test
		fun `graph with no edges is valid`() {
			val graph = Graph(
				listOf(Vertex(0), Vertex(1)),
				emptyList()
			)
			graph.vertexList shouldHaveSize 2
			graph.edgeList shouldHaveSize 0
		}

		@Test
		fun `graph with valid edges is valid`() {
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val v2 = Vertex(2)
			val graph = Graph(
				listOf(v0, v1, v2),
				listOf(Edge(v0, v1), Edge(v1, v2))
			)
			graph.edgeList shouldHaveSize 2
		}

		@Test
		fun `self-loop edge is valid if vertex exists`() {
			val v0 = Vertex(0)
			val graph = Graph(
				listOf(v0),
				listOf(Edge(v0, v0))
			)
			graph.edgeList shouldHaveSize 1
		}

		@Test
		fun `empty graph is valid`() {
			val graph = Graph(emptyList(), emptyList())
			graph.vertexList shouldHaveSize 0
		}

		@Test
		fun `single vertex no edges`() {
			val graph = Graph(listOf(Vertex(42)), emptyList())
			graph.vertexList shouldHaveSize 1
			graph.vertexList[0].value shouldBe 42
		}
	}

	@Nested
	@DisplayName("Invalid construction")
	inner class InvalidConstruction {
		@Test
		fun `edge with unknown from-vertex throws`() {
			assertThrows<IllegalArgumentException> {
				Graph(
					listOf(Vertex(0)),
					listOf(Edge(Vertex(99), Vertex(0)))
				)
			}
		}

		@Test
		fun `edge with unknown to-vertex throws`() {
			assertThrows<IllegalArgumentException> {
				Graph(
					listOf(Vertex(0)),
					listOf(Edge(Vertex(0), Vertex(99)))
				)
			}
		}

		@Test
		fun `edge referencing no known vertices throws`() {
			assertThrows<IllegalArgumentException> {
				Graph(
					listOf(Vertex(0)),
					listOf(Edge(Vertex(10), Vertex(20)))
				)
			}
		}

		@Test
		fun `error message contains vertex and edge info`() {
			val ex = assertThrows<IllegalArgumentException> {
				Graph(
					listOf(Vertex(0)),
					listOf(Edge(Vertex(0), Vertex(5)))
				)
			}
			ex.message!!.contains("invalid edges") shouldBe true
		}
	}

	@Nested
	@DisplayName("Value classes")
	inner class ValueClasses {
		@Test
		fun `Vertex wraps int value`() {
			Vertex(42).value shouldBe 42
		}

		@Test
		fun `Edge stores from and to`() {
			val edge = Edge(Vertex(1), Vertex(2))
			edge.from shouldBe Vertex(1)
			edge.to shouldBe Vertex(2)
		}

		@Test
		fun `equal edges are equal`() {
			Edge(Vertex(1), Vertex(2)) shouldBe Edge(Vertex(1), Vertex(2))
		}
	}
}
