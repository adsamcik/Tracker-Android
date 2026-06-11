package com.adsamcik.tracker.shared.base.graph

import com.adsamcik.tracker.shared.base.exception.CircularDependencyException
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("TopSort - Kahn's algorithm topological sort")
class TopSortTest {

	@Nested
	@DisplayName("Valid DAGs")
	inner class ValidDags {
		@Test
		fun `single vertex returns single element`() {
			val v = Vertex(0)
			val graph = Graph(listOf(v), emptyList())
			val result = graph.topSort()
			result shouldHaveSize 1
			result[0] shouldBe v
		}

		@Test
		fun `linear chain produces correct order`() {
			// 0 -> 1 -> 2
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val v2 = Vertex(2)
			val graph = Graph(
				listOf(v0, v1, v2),
				listOf(Edge(v0, v1), Edge(v1, v2))
			)
			val result = graph.topSort()
			result shouldHaveSize 3
			// v0 must come before v1, v1 must come before v2
			val idx0 = result.indexOf(v0)
			val idx1 = result.indexOf(v1)
			val idx2 = result.indexOf(v2)
			(idx0 < idx1) shouldBe true
			(idx1 < idx2) shouldBe true
		}

		@Test
		fun `diamond DAG respects all dependencies`() {
			// 0 -> 1, 0 -> 2, 1 -> 3, 2 -> 3
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val v2 = Vertex(2)
			val v3 = Vertex(3)
			val graph = Graph(
				listOf(v0, v1, v2, v3),
				listOf(Edge(v0, v1), Edge(v0, v2), Edge(v1, v3), Edge(v2, v3))
			)
			val result = graph.topSort()
			result shouldHaveSize 4

			val idx0 = result.indexOf(v0)
			val idx1 = result.indexOf(v1)
			val idx2 = result.indexOf(v2)
			val idx3 = result.indexOf(v3)
			(idx0 < idx1) shouldBe true
			(idx0 < idx2) shouldBe true
			(idx1 < idx3) shouldBe true
			(idx2 < idx3) shouldBe true
		}

		@Test
		fun `disconnected vertices are all included`() {
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val v2 = Vertex(2)
			val graph = Graph(listOf(v0, v1, v2), emptyList())
			val result = graph.topSort()
			result shouldHaveSize 3
			result shouldContainAll listOf(v0, v1, v2)
		}

		@Test
		fun `two-vertex DAG produces correct order`() {
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val graph = Graph(
				listOf(v0, v1),
				listOf(Edge(v0, v1))
			)
			val result = graph.topSort()
			result shouldHaveSize 2
			result[0] shouldBe v0
			result[1] shouldBe v1
		}

		@Test
		fun `wider fan-out DAG`() {
			// 0 -> 1, 0 -> 2, 0 -> 3
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val v2 = Vertex(2)
			val v3 = Vertex(3)
			val graph = Graph(
				listOf(v0, v1, v2, v3),
				listOf(Edge(v0, v1), Edge(v0, v2), Edge(v0, v3))
			)
			val result = graph.topSort()
			result shouldHaveSize 4
			result.indexOf(v0) shouldBe 0
		}
	}

	@Nested
	@DisplayName("Circular dependency detection")
	inner class CircularDependency {
		@Test
		fun `two-vertex cycle throws CircularDependencyException`() {
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val graph = Graph(
				listOf(v0, v1),
				listOf(Edge(v0, v1), Edge(v1, v0))
			)
			assertThrows<CircularDependencyException> {
				graph.topSort()
			}
		}

		@Test
		fun `three-vertex cycle throws CircularDependencyException`() {
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val v2 = Vertex(2)
			val graph = Graph(
				listOf(v0, v1, v2),
				listOf(Edge(v0, v1), Edge(v1, v2), Edge(v2, v0))
			)
			assertThrows<CircularDependencyException> {
				graph.topSort()
			}
		}

		@Test
		fun `exception message contains graph info`() {
			val v0 = Vertex(0)
			val v1 = Vertex(1)
			val graph = Graph(
				listOf(v0, v1),
				listOf(Edge(v0, v1), Edge(v1, v0))
			)
			val ex = assertThrows<CircularDependencyException> {
				graph.topSort()
			}
			ex.message!!.contains("circular dependency") shouldBe true
		}
	}
}
