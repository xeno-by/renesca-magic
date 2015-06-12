package renesca.schema.macros

trait Code extends Context with Generators {

  import context.universe._
  import Helpers._

  def relationStart(schema: Schema, name: String): String = schema.relations.find(_.name == name).get.startNode
  def relationEnd(schema: Schema, name: String): String = schema.relations.find(_.name == name).get.endNode

  def propertyGetter(name: String, typeName: Tree) =
    q""" def ${ TermName(name) }:$typeName = node.properties(${ name }).asInstanceOf[${ TypeName(typeName.toString + "PropertyValue") }] """
  def propertyOptionGetter(name: String, typeName: Tree) =
    q""" def ${ TermName(name) }:Option[$typeName] = node.properties.get(${ name }).asInstanceOf[Option[${ TypeName(typeName.toString + "PropertyValue") }]].map(propertyValueToPrimitive) """
  def propertySetter(name: String, typeName: Tree) =
    q""" def ${ TermName(name + "_$eq") }(newValue:$typeName){ node.properties(${ name }) = newValue} """
  def propertyOptionSetter(name: String, typeName: Tree) =
    q""" def ${ TermName(name + "_$eq") }(newValue:Option[$typeName]){ if(newValue.isDefined) node.properties(${ name }) = newValue.get else node.properties -= ${ name } }"""
  def generatePropertyAccessors(statement: Tree): List[Tree] = statement match {
    case q"val $propertyName:Option[$propertyType]"      => List(propertyOptionGetter(propertyName.toString, propertyType))
    case q"var $propertyName:Option[$propertyType]"      => List(propertyOptionGetter(propertyName.toString, propertyType), propertyOptionSetter(propertyName.toString, propertyType))
    case q"val $propertyName:Option[$propertyType] = $x" => List(propertyOptionGetter(propertyName.toString, propertyType))
    case q"var $propertyName:Option[$propertyType] = $x" => List(propertyOptionGetter(propertyName.toString, propertyType), propertyOptionSetter(propertyName.toString, propertyType))
    case q"val $propertyName:$propertyType"              => List(propertyGetter(propertyName.toString, propertyType))
    case q"var $propertyName:$propertyType"              => List(propertyGetter(propertyName.toString, propertyType), propertySetter(propertyName.toString, propertyType))
    case q"val $propertyName:$propertyType = $x"         => List(propertyGetter(propertyName.toString, propertyType))
    case q"var $propertyName:$propertyType = $y"         => List(propertyGetter(propertyName.toString, propertyType), propertySetter(propertyName.toString, propertyType))
    case somethingElse                                   => List(somethingElse)
  }

  def nodeTraitFactories(schema: Schema): List[Tree] = schema.nodeTraits.flatMap { nodeTrait => import nodeTrait._
    val factoryName = TypeName(traitFactoryName(name))
    val localInterface = if(hasOwnFactory) q"def ${ TermName(traitFactoryLocal(name)) } (..${ parameterList.toParamCode }): NODE" else q""
    val superFactories = if(superTypes.isEmpty) List(tq"NodeFactory[NODE]") else superTypes.map(t => tq"${TypeName(traitFactoryName(t))}[NODE]")
    val locals = superTypes.map(t => forwardLocalMethod(parameterList, traitFactoryParameterList, t, traitFactoryLocal(name), tq"NODE"))

    List(
      q"""
           trait $factoryName [NODE <: $name_type] extends ..$superFactories {
            $localInterface

            ..$locals
           }
    """,
      q"""

           object $name_term extends RootNodeTraitFactory[$name_type]
    """
    )
  }

  def relationTraitFactories(schema: Schema): List[Tree] = schema.relationTraits.map { relationTrait => import relationTrait._
    val startEndlocalParams = List(q"val startNode:START", q"val endNode:END") ::: parameterList.toParamCode
    val factoryName = TypeName(traitFactoryName(name))
    val localInterface = if(hasOwnFactory) q" def ${ TermName(traitFactoryLocal(name)) } (..$startEndlocalParams): RELATION " else q""
    val superFactories = if(superTypes.isEmpty) List(tq"AbstractRelationFactory[START,RELATION,END]") else superTypes.map(t => tq"${TypeName(traitFactoryName(t))}[START,RELATION,END]")
    val locals = superTypes.map(t => forwardLocalMethodStartEnd(parameterList, traitFactoryParameterList, t, traitFactoryLocal(name), tq"RELATION", tq"START", tq"END"))
    q"""
           trait $factoryName [START <: Node, +RELATION <: AbstractRelation[START,END], END <: Node] extends ..$superFactories {
            $localInterface

             ..$locals
           }
           """
  }

  def forwardLocalMethod(parameterList: ParameterList, traitFactoryParameterList: Option[ParameterList], superName: String, local: String, typeName: Tree) = {
    traitFactoryParameterList.map(parentParameterList => {
      val parentCaller = parameterList.supplementMissingParametersOf(parentParameterList)
      q""" def ${ TermName(traitFactoryLocal(superName)) } (..${ parentParameterList.toParamCode }): $typeName = ${ TermName(local) } (..$parentCaller) """
    }).getOrElse(q"")
  }

  // TODO: duplicate code
  def forwardLocalMethodStartEnd(parameterList: ParameterList, traitFactoryParameterList: Option[ParameterList], superName: String, local: String, typeName: Tree, startNodeType: Tree, endNodeType: Tree) = {
    traitFactoryParameterList.map(parentParameterList => {
      val parentCaller = parameterList.supplementMissingParametersOf(parentParameterList)
      val localParameters: List[Tree] = List(q"val startNode:$startNodeType", q"val endNode:$endNodeType") ::: parentParameterList.toParamCode
      q""" def ${ TermName(traitFactoryLocal(superName)) } (..$localParameters): $typeName = ${ TermName(local) } (..${ List(q"startNode", q"endNode") ::: parentCaller }) """
    }).getOrElse(q"")
  }

  //TODO: what happens with name clashes?
  // @Node trait traitA { val name: String }; @Node trait traitB extends traitA { val name: String }
  def nodeFactories(schema: Schema): List[Tree] = schema.nodes.map { node => import node._
    val superFactories = if(superTypes.isEmpty) List(tq"NodeFactory[$name_type]") else superTypes.map(t => tq"${TypeName(traitFactoryName(t))}[$name_type]")
    val locals = superTypes.map(t => forwardLocalMethod(parameterList, traitFactoryParameterList, t, "local", tq"$name_type"))

    // Extending superFactory is enough, because NodeFactory is pulled in every case.
    // This works because NodeFactory does not get any generics.
    q"""
           object $name_term extends ..$superFactories {
             def wrap(node: raw.Node) = new $name_type(node)
             val label = raw.Label($name_label)
             def local (..${ parameterList.toParamCode }):$name_type = {
              val node = wrap(raw.Node.local(List(label)))
              ..${ parameterList.toAssignmentCode(q"node.node") }
              node
             }
             ..$locals
           }
           """
  }

  def accumulatedTraitNeighbours(r: String, neighbours: List[(String, String, String)], relationPlural: TermName, nodeTrait: String): Tree = {
    val traitName = TypeName(nodeTrait)
    val successors = neighbours.collect { case (accessorName, `r`, _) => accessorName }.foldRight[Tree](q"Set.empty") { case (name,q"$all") => q"${ TermName(name) } ++ $all " }
    q""" def $relationPlural:Set[$traitName] = $successors"""
  }

  def nodeClasses(schema: Schema): List[Tree] = schema.nodes.map { node => import node._
    val directNeighbours = node.neighbours_terms.map {
      case (accessorName, relation_term, endNode_type, endNode_term) =>
        q"""def $accessorName:Set[$endNode_type] = successorsAs($endNode_term,$relation_term)"""
    }

    val successorTraits = outRelationsToTrait.map { case (r, nodeTrait) =>
      val relationPlural = TermName(nameToPlural(r))
      accumulatedTraitNeighbours(r, node.neighbours, relationPlural, nodeTrait)
    }

    val directRevNeighbours = node.rev_neighbours_terms.map {
      case (accessorName, relation_term, startNode_type, startNode_term) =>
        q"""def $accessorName:Set[$startNode_type] = predecessorsAs($startNode_term, $relation_term)"""
    }

    val predecessorTraits = inRelationsFromTrait.map { case (r, nodeTrait) =>
      val relationPlural = TermName(rev(nameToPlural(r)))
      accumulatedTraitNeighbours(r, node.rev_neighbours, relationPlural, nodeTrait)
    }

    val nodeBody = statements.flatMap(generatePropertyAccessors(_))
    val superNodeTraitTypesWithDefault = if(superTypes.isEmpty) List(TypeName("Node")) else superTypes_type
    val externalSuperTypes_type = externalSuperTypes.map(TypeName(_))

    q"""
    case class $name_type(node: raw.Node) extends ..$superNodeTraitTypesWithDefault with ..$externalSuperTypes_type {
        ..$directNeighbours
        ..$successorTraits
        ..$directRevNeighbours
        ..$predecessorTraits
        ..$nodeBody
      }
    """
  }

  def relationFactories(schema: Schema): List[Tree] = schema.relations.map { relation => import relation._
    val superFactories = if(superTypes.isEmpty) List(tq"AbstractRelationFactory[$startNode_type, $name_type, $endNode_type]") else superTypes.map(t => tq"${TypeName(traitFactoryName(t))}[$startNode_type, $name_type, $endNode_type]")
    val locals = superTypes.map(t => forwardLocalMethodStartEnd(parameterList, traitFactoryParameterList, t, "local", tq"$name_type", tq"$startNode_type", tq"$endNode_type"))

    q"""
           object $name_term extends RelationFactory[$startNode_type, $name_type, $endNode_type]
            with ..$superFactories {
               def relationType = raw.RelationType($name_label)
               def wrap(relation: raw.Relation) = $name_term(
                 $startNode_term.wrap(relation.startNode),
                 relation,
                 $endNode_term.wrap(relation.endNode))
              def local (..${ List(q"val startNode:$startNode_type", q"val endNode:$endNode_type") ::: parameterList.toParamCode }):$name_type = {
                val relation = wrap(raw.Relation.local(startNode.node, relationType, endNode.node))
                ..${ parameterList.toAssignmentCode(q"relation.relation") }
                relation
              }
             ..$locals
           }
           """
  }

  //TODO: properties on relations
  //TODO: external supertypes on relations
  def relationClasses(schema: Schema): List[Tree] = schema.relations.map { relation => import relation._
    val superTypesWithDefault = "Relation" :: superTypes
    val superTypesWithDefaultGenerics = superTypesWithDefault.map(TypeName(_)).map(superType => tq"$superType[$startNode_type,$endNode_type]")
    q"""
           case class $name_type(startNode: $startNode_type, relation: raw.Relation, endNode: $endNode_type)
             extends ..$superTypesWithDefaultGenerics {
             ..$statements
           }
           """
  }


  def hyperRelationFactories(schema: Schema): List[Tree] = schema.hyperRelations.map { hyperRelation => import hyperRelation._
    val superFactories = if(superTypes.isEmpty) List(tq"HyperRelationFactory[$startNode_type, $startRelation_type, $name_type, $endRelation_type, $endNode_type]") else superTypes.map(t => tq"${TypeName(traitFactoryName(t))}[$startNode_type, $name_type, $endNode_type]")
    val locals = superTypes.map(t => forwardLocalMethodStartEnd(parameterList, traitFactoryParameterList, t, "local", tq"$name_type", tq"$startNode_type", tq"$endNode_type"))

    q"""
           object $name_term extends ..$superFactories {

             override def label = raw.Label($name_label)
             override def startRelationType = raw.RelationType($startRelation_label)
             override def endRelationType = raw.RelationType($endRelation_label)

             override def wrap(node: raw.Node) = new $name_type(node)

             override def wrap(startRelation: raw.Relation, middleNode: raw.Node, endRelation: raw.Relation) = {
               val hyperRelation = wrap(middleNode)
               hyperRelation._startRelation = $startRelation_term(
                  $startNode_term.wrap(startRelation.startNode),
                  startRelation,
                  hyperRelation
                )
                hyperRelation._endRelation = $endRelation_term(
                  hyperRelation,
                  endRelation,
                  $endNode_term.wrap(endRelation.endNode)
                )
                hyperRelation
             }

             def local (..${ List(q"val startNode:$startNode_type", q"val endNode:$endNode_type") ::: parameterList.toParamCode }):$name_type = {
                val middleNode = raw.Node.local(List(label))
                ..${ parameterList.toAssignmentCode(q"middleNode") }
                wrap(
                  raw.Relation.local(startNode.node, startRelationType, middleNode),
                  middleNode,
                  raw.Relation.local(middleNode, endRelationType, endNode.node)
                )
             }
             ..$locals
           }
           """
  }

  def hyperRelationClasses(schema: Schema): List[Tree] = schema.hyperRelations.flatMap { hyperRelation => import hyperRelation._
    //TODO: generate indirect neighbour-accessors based on hyperrelations
    //TODO: property accessors
    val superRelationTypesGenerics = superRelationTypes.map(TypeName(_)).map(superType => tq"$superType[$startNode_type,$endNode_type]")
    List( q"""
           case class $name_type(node: raw.Node)
              extends HyperRelation[$startNode_type, $startRelation_type, $name_type, $endRelation_type, $endNode_type]
              with ..${ superRelationTypesGenerics ::: superNodeTypes.map(t => tq"${ TypeName(t) }") } {
             ..$statements
           }
           """, q"""
           case class $startRelation_type(startNode: $startNode_type, relation: raw.Relation, endNode: $name_type)
             extends Relation[$startNode_type, $name_type]
           """, q"""
           case class $endRelation_type(startNode: $name_type, relation: raw.Relation, endNode: $endNode_type)
             extends Relation[$name_type, $endNode_type]
           """)
  }

  def nodeSuperTraits(schema: Schema): List[Tree] = schema.nodeTraits.map { nodeTrait => import nodeTrait._
    val superTypesWithDefault = (if(superTypes.isEmpty) List("Node") else superTypes).map(TypeName(_))
    val traitBody = statements.flatMap(generatePropertyAccessors(_))
    q""" trait $name_type extends ..$superTypesWithDefault { ..$traitBody } """
  }

  def relationSuperTraits(schema: Schema): List[Tree] = schema.relationTraits.map { relationTrait => import relationTrait._
    val superTypesWithDefault = if(superTypes.isEmpty) List("AbstractRelation") else superTypes
    val superTypesWithDefaultGenerics = superTypesWithDefault.map(TypeName(_)).map(superType => tq"$superType[START,END]")
    val traitBody = statements.flatMap(generatePropertyAccessors(_))
    q""" trait $name_type[+START <: Node,+END <: Node] extends ..$superTypesWithDefaultGenerics { ..$traitBody } """
  }

  def groupFactories(schema: Schema): List[Tree] = schema.groups.map { group => import group._
    q""" object $name_term {def empty = new $name_type(raw.Graph.empty) } """
  }

  def groupClasses(schema: Schema): List[Tree] = schema.groups.map { group => import group._
    // TODO: create subgroups

    def itemSets(nameAs: String, names: List[String]) = names.map { name => q""" def ${ TermName(nameToPlural(name)) }: Set[${ TypeName(name) }] = ${ TermName(nameAs) }(${ TermName(name) }) """ }
    def allOf(items: List[String]) = items.foldRight[Tree](q"Set.empty") { case (name,q"$all") => q"${ TermName(nameToPlural(name)) } ++ $all" }

    val nodeSets = itemSets("nodesAs", nodes)
    val relationSets = itemSets("relationsAs", relations)
    val hyperRelationSets = itemSets("hyperRelationsAs", hyperRelations)

    val nodeTraitSets = nodeTraits.map { nodeTrait => import nodeTrait._
      q"def $name_plural_term:Set[$name_type] = ${ allOf(subNodes) }"
    }
    val relationTraitSets = nodeTraits.map { nodeTrait => import nodeTrait._
      q"def ${ TermName(nameToPlural(name + "Relation")) }:Set[_ <: Relation[$name_type, $name_type]] = ${ allOf(subRelations.intersect(relations)) }"
    }
    val abstractRelationTraitSets = nodeTraits.map { nodeTrait => import nodeTrait._
      q"def ${ TermName(nameToPlural(name + "AbstractRelation")) }:Set[_ <: AbstractRelation[$name_type, $name_type]] = ${ allOf(subRelations.intersect(relationsWithHyperRelations)) }"
    }
    val hyperRelationTraitSets = nodeTraits.map { nodeTrait => import nodeTrait._
      val hyperNodeRelationTraits_type = commonHyperNodeRelationTraits_type.map(t => tq"$t[$name_type, $name_type]")
      q"""def ${ TermName(nameToPlural(name + "HyperRelation")) } :Set[HyperRelation[
                  $name_type,
                  _ <: Relation[$name_type, _],
                  _ <: HyperRelation[$name_type, _, _, _, $name_type] with ..$commonHyperNodeNodeTraits_type with ..$hyperNodeRelationTraits_type,
                  _ <: Relation[_, $name_type],
                  $name_type]
                  with ..$commonHyperNodeNodeTraits_type with ..$hyperNodeRelationTraits_type]
              = ${ allOf(subHyperRelations.intersect(hyperRelations)) }"""
    }

  //TODO: Common traits for nodes, relations, abstractRelations, hyperRelations
    q"""
           case class $name_type(graph: raw.Graph) extends Graph {
             ..$nodeSets
             ..$relationSets
             ..$hyperRelationSets

             ..$nodeTraitSets
             ..$relationTraitSets
             ..$abstractRelationTraitSets
             ..$hyperRelationTraitSets

             def nodes: Set[Node] = ${ allOf(nodesWithHyperNodes) }
             def relations: Set[_ <: Relation[_,_]] = ${ allOf(relations) }
             def abstractRelations: Set[_ <: AbstractRelation[_,_]] = ${ allOf(relationsWithHyperRelations) }
             def hyperRelations: Set[_ <: HyperRelation[_,_,_,_,_]] = ${ allOf(hyperRelations) }
           }
           """
  }

  def nodeLabelToFactoryMap(schema: Schema): Tree = {
    val tuples = (schema.nodes ++ schema.hyperRelations).map { node => import node._
      q""" ($name_label, $name_term) """
    }
    q""" Map[raw.Label,NodeFactory[_ <: Node]](..$tuples) """
  }

  def otherStatements(schema: Schema): List[Tree] = schema.statements.filterNot { statement =>
    NodePattern.unapply(statement).isDefined ||
      RelationPattern.unapply(statement).isDefined ||
      HyperRelationPattern.unapply(statement).isDefined ||
      NodeTraitPattern.unapply(statement).isDefined ||
      RelationTraitPattern.unapply(statement).isDefined ||
      GroupPattern.unapply(statement).isDefined
  }


  //TODO: RootNodeTraitFactory.wrap can fail with unknown labels
  def schema(schema: Schema): Tree = {
    import schema.{name_term, superTypes_type}
    q"""
           object $name_term extends ..$superTypes_type {
             import renesca.{graph => raw}
             import renesca.schema._
             import renesca.parameter._
             import renesca.parameter.implicits._

             val nodeLabelToFactory = ${ nodeLabelToFactoryMap(schema) }

             trait RootNodeTraitFactory[NODE <: Node] {
               def wrap(node: raw.Node) = {
                 val factory = nodeLabelToFactory(node.labels.head).asInstanceOf[NodeFactory[NODE]]
                 factory.wrap(node)
               }
             }

             ..${ nodeTraitFactories(schema) }
             ..${ nodeSuperTraits(schema) }

             ..${ nodeFactories(schema) }
             ..${ nodeClasses(schema) }

             ..${ relationTraitFactories(schema) }
             ..${ relationSuperTraits(schema) }

             ..${ relationFactories(schema) }
             ..${ relationClasses(schema) }

             ..${ hyperRelationFactories(schema) }
             ..${ hyperRelationClasses(schema) }

             ..${ groupFactories(schema) }
             ..${ groupClasses(schema) }

             ..${ otherStatements(schema) }
           }
           """
  }
}
