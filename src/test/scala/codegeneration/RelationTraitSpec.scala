package codegeneration

import helpers.CodeComparisonSpec

class RelationTraitSpec extends CodeComparisonSpec {
   

  import contextMock.universe._

  "simple trait" >> {
    generatedContainsCode(
      q"object A {@Relation trait T}",
      """trait T[+START <: Node, +END <: Node] extends AbstractRelation[START, END]  }"""
    )
  }
  "with super trait" >> {
    generatedContainsCode(
      q"object A { @Relation trait K; @Relation trait T extends K}",
      """trait T[+START <: Node, +END <: Node] extends K[START, END]  }"""
    )
  }
  "with properties" >> {
    generatedContainsCode(
      q"object A {@Relation trait T {val p:Int}}",
      q"""trait T[+START <: Node, +END <: Node] extends AbstractRelation[START, END] {
            def p: Int = rawItem.properties("p").asInstanceOf[IntPropertyValue]
          }"""
    )
  }
  "custom code" >> {
    generatedContainsCode(
      q"object A {@Node trait T {def custom = 5}}",
      q"""trait T extends Node { def custom = 5 }"""
    )
  }
}
