package renesca.schema.macros

trait Generators extends Context with Patterns with Parameters {

  //TODO: abort when wrong superType inheritance. example: Relation extends NodeTrait

  import Helpers._
  import context.universe._

  trait Named {
    def pattern: NamePattern
    def name = pattern.name

    def name_type = TypeName(name)
    def name_term = TermName(name)
    def name_label = nameToLabel(name)
    def name_plural = nameToPlural(name)
    def name_plural_term = TermName(name_plural)
  }

  trait SuperTypes {
    def pattern: SuperTypesPattern
    def superTypes = pattern.superTypes

    def superTypes_type = superTypes.map(TypeName(_))
  }

  trait StartEndNode {
    def pattern: StartEndNodePattern
    def startNode = pattern.startNode
    def endNode = pattern.endNode

    def startNode_type = TypeName(startNode)
    def startNode_term = TermName(startNode)
    def endNode_type = TypeName(endNode)
    def endNode_term = TermName(endNode)
  }


  trait StartEndRelation extends StartEndNode with Named {
    def pattern: StartEndNodePattern with NamePattern

    def startRelation = relationName(startNode, name)
    def startRelation_type = TypeName(startRelation)
    def startRelation_term = TermName(startRelation)
    def startRelation_label = nameToLabel(startRelation)
    def endRelation = relationName(name, endNode)
    def endRelation_type = TypeName(endRelation)
    def endRelation_term = TermName(endRelation)
    def endRelation_label = nameToLabel(endRelation)
  }

  trait HasOwnFactory {
    val hasOwnFactory: Option[Boolean]
    val parameterList: ParameterList
  }

  trait Statements {
    def pattern: StatementsPattern
    def statements = pattern.statements
  }

  trait HasParameterList {
    val parameterList: ParameterList
  }

  trait HasTraitFactoryParameterList {
    def traitFactoryParameterList: List[ParameterList]
  }

  object Schema {
    def apply(schemaPattern: SchemaPattern): Schema = {
      import schemaPattern._
      //TODO: dry collect
      val nodePatterns: List[NodePattern] = schemaPattern.statements.collect { case NodePattern(node) => node }
      val relationPatterns: List[RelationPattern] = schemaPattern.statements.collect { case RelationPattern(relationPattern) => relationPattern }
      val hyperRelationPatterns: List[HyperRelationPattern] = schemaPattern.statements.collect { case HyperRelationPattern(hyperRelationPattern) => hyperRelationPattern }
      val nodeTraitPatterns: List[NodeTraitPattern] = schemaPattern.statements.collect { case NodeTraitPattern(nodeTraitpattern) => nodeTraitpattern }
      val relationTraitPatterns: List[RelationTraitPattern] = schemaPattern.statements.collect { case RelationTraitPattern(nodeTraitpattern) => nodeTraitpattern }
      val graphPatterns: List[GraphPattern] = schemaPattern.statements.collect { case GraphPattern(graphPattern) => graphPattern }
      val allRelationPatterns = relationPatterns ::: hyperRelationPatterns

      def abortIfInheritsFrom(childItem: String, childType: String, child: NamePattern with SuperTypesPattern, parentItem: String, parentType: String, parents: List[NamePattern]): Unit = {
        for(childSuperTypeName <- child.superTypes; parentName <- parents.map(_.name) if childSuperTypeName == parentName)
          abort(s"$childItem $childType `${ child.name }` cannot inherit from $parentItem $parentType `$parentName`.")
      }

      val nodeTraits = nodeTraitPatterns.map { nodeTraitPattern =>
        abortIfInheritsFrom("Node", "trait", nodeTraitPattern, "Node", "class", nodePatterns)
        abortIfInheritsFrom("Node", "trait", nodeTraitPattern, "Relation", "class", relationPatterns)
        abortIfInheritsFrom("Node", "trait", nodeTraitPattern, "Relation", "trait", relationTraitPatterns)
        abortIfInheritsFrom("Node", "trait", nodeTraitPattern, "Graph", "trait", graphPatterns)
        NodeTrait(nodeTraitPattern, nodeTraitPatterns, relationTraitPatterns, nodePatterns, hyperRelationPatterns, relationPatterns, hyperRelationPatterns)
      }

      nodeTraits.foreach { nodeTrait =>
        nodeTrait.traitFactoryParameterList = findSuperFactoryParameterList(nodeTraitPatterns, nodeTrait.pattern, nodeTraits)
      }

      val relationTraits = relationTraitPatterns.map { relationTraitPattern =>
        abortIfInheritsFrom("Relation", "trait", relationTraitPattern, "Relation", "class", relationPatterns)
        abortIfInheritsFrom("Relation", "trait", relationTraitPattern, "Node", "class", nodePatterns)
        abortIfInheritsFrom("Relation", "trait", relationTraitPattern, "Node", "trait", nodeTraitPatterns)
        abortIfInheritsFrom("Relation", "trait", relationTraitPattern, "Graph", "trait", graphPatterns)
        RelationTrait(relationTraitPattern,
          flatSuperStatements(relationTraitPatterns, relationTraitPattern),
          traitCanHaveOwnFactory(allRelationPatterns ::: nodeTraitPatterns ::: relationTraitPatterns, relationTraitPattern)) //TODO: why nodeTraitPatterns
      }

      // create special nodepatterns for creating matches class for nodetraits
      val (traitImplementationPattern, traitImplementationMap) = {
        val (pattern, mapping) = nodeTraits.map(nodeTrait => { import nodeTrait._
          val implName = traitMatchesClassName(name)
          (NodePattern(implName, List(name), List.empty), implName -> nodeTrait)
        }).unzip

        (pattern, mapping.toMap)
      }

      val nodes = (traitImplementationPattern ++ nodePatterns).map { rawNodePattern => {
        abortIfInheritsFrom("Node", "class", rawNodePattern, "Node", "class", nodePatterns)
        abortIfInheritsFrom("Node", "class", rawNodePattern, "Relation", "class", relationPatterns)
        abortIfInheritsFrom("Node", "class", rawNodePattern, "Relation", "trait", relationTraitPatterns)
        abortIfInheritsFrom("Node", "class", rawNodePattern, "Graph", "trait", graphPatterns)

        val nodePattern = rawNodePattern.copy(_superTypes = rawNodePattern.superTypes intersect nodeTraits.map(_.name))
        Node(nodePattern,
          superTypes = nodePattern.superTypes,
          flatSuperTypesWithSelf = patternToFlatSuperTypesWithSelf(nodeTraitPatterns, nodePattern).map(_.name) intersect (nodePattern.name :: nodeTraits.map(_.name)),
          externalSuperTypes = rawNodePattern.superTypes.filterNot(nodeTraits.map(_.name) contains _),
          neighbours = neighbours(nodePattern, allRelationPatterns, nodePatterns, nodeTraitPatterns),
          rev_neighbours = rev_neighbours(nodePattern, allRelationPatterns, nodePatterns, nodeTraitPatterns),
          outRelationsToTrait = allRelationPatterns.filter { r =>
            (patternToFlatSuperTypesWithSelf(nodeTraitPatterns, nodePattern).map(_.name) contains r.startNode) &&
              (nodeTraitPatterns.map(_.name) contains r.endNode)
          }.map(r => (r.name, r.endNode)),
          inRelationsFromTrait = allRelationPatterns.filter { r =>
            // endNode is this node or one of its supertypes
            // and startNode is a Node Trait
            (patternToFlatSuperTypesWithSelf(nodeTraitPatterns, nodePattern).map(_.name) contains r.endNode) &&
              (nodeTraitPatterns.map(_.name) contains r.startNode)
          }.map(r => (r.name, r.startNode)),
          flatStatements = flatSuperStatements(nodeTraitPatterns, nodePattern),
          traitFactoryParameterList = findSuperFactoryParameterList(nodeTraitPatterns, nodePattern, nodeTraits),
          implementedTrait = traitImplementationMap.get(nodePattern.name))
      }}

      val graphs = graphPatterns.map { graphPattern =>
        abortIfInheritsFrom("Graph", "trait", graphPattern, "Node", "class", nodePatterns)
        abortIfInheritsFrom("Graph", "trait", graphPattern, "Node", "trait", nodeTraitPatterns)
        abortIfInheritsFrom("Graph", "trait", graphPattern, "Relation", "class", relationPatterns)
        abortIfInheritsFrom("Graph", "trait", graphPattern, "Relation", "trait", relationTraitPatterns)
        //TODO: maybe also specify hypernodes?
        val notAllowed = graphPattern.nodes.distinct diff (nodePatterns ++ nodeTraitPatterns).map(_.name)
        if(notAllowed.nonEmpty) abort(s"Graph `${ graphPattern.name }` cannot contain ${ notAllowed.mkString("`", "`, `", "`") }. Only Node classes and traits are allowed.")

        //TODO: test/audit!
        val traits = graphToNodes(graphPatterns, graphPattern).distinct intersect nodeTraits.map(_.name)
        val expandedTraits = traits.flatMap(t => patternToFlatSubTypesWithoutSelf(nodeTraitPatterns ::: nodePatterns, nameToPattern(nodeTraitPatterns, t))).map(_.name)
        val nodes = (graphToNodes(graphPatterns, graphPattern) ++ expandedTraits).distinct diff nodeTraits.map(_.name)
        val connectedHyperNodes = inducedRelations(nodes, nodePatterns, nodeTraitPatterns, hyperRelationPatterns, hyperRelationPatterns)
        val nodesWithHyperNodes = nodes ++ connectedHyperNodes
        val graphedNodePatterns = nodes.map(nameToPattern(nodePatterns ::: hyperRelationPatterns, _))
        val graphedTraits = graphedNodePatterns.flatMap(patternToFlatSuperTypesWithoutSelf(nodeTraitPatterns, _)).distinct

        Graph(graphPattern,
          nodes = nodes.map(nameToPattern(nodePatterns ::: hyperRelationPatterns, _)).collect { case n: NodePattern => n.name },
          nodesWithHyperNodes = nodesWithHyperNodes,
          relations = inducedRelations(nodes, nodePatterns, nodeTraitPatterns, hyperRelationPatterns, relationPatterns),
          relationsWithHyperRelations = inducedRelations(nodes, nodePatterns, nodeTraitPatterns, hyperRelationPatterns, allRelationPatterns),
          hyperRelations = connectedHyperNodes,
          nodeTraits = graphedTraits.map(nodeTraitPattern => {
            NodeTrait(nodeTraitPattern, nodeTraitPatterns, relationTraitPatterns,
              nodes.filter(nodePatterns.map(_.name).toSet).distinct.map(nameToPattern(nodePatterns, _)),
              connectedHyperNodes.filter(hyperRelationPatterns.map(_.name).toSet).distinct.map(nameToPattern(hyperRelationPatterns, _)),
              relationPatterns, hyperRelationPatterns)
          })
        )
      }

      val hyperRelations = hyperRelationPatterns.map { hyperRelationPattern =>
        abortIfInheritsFrom("HyperRelation", "class", hyperRelationPattern, "Relation", "class", relationPatterns)
        abortIfInheritsFrom("HyperRelation", "class", hyperRelationPattern, "Node", "class", nodePatterns)
        abortIfInheritsFrom("HyperRelation", "class", hyperRelationPattern, "Graph", "trait", graphPatterns)
        if(graphPatterns.map(_.name) contains hyperRelationPattern.startNode) abort(s"HyperRelation class `${ hyperRelationPattern.name }` needs startNode `${ hyperRelationPattern.startNode }` to be a Node, Node trait, or HyperRelation. Not a Graph.")
        if(relationPatterns.map(_.name) contains hyperRelationPattern.startNode) abort(s"HyperRelation class `${ hyperRelationPattern.name }` needs startNode `${ hyperRelationPattern.startNode }` to be a Node, Node trait, or HyperRelation. Not a Relation.")
        if(relationTraitPatterns.map(_.name) contains hyperRelationPattern.startNode) abort(s"HyperRelation class `${ hyperRelationPattern.name }` needs startNode `${ hyperRelationPattern.startNode }` to be a Node, Node trait, or HyperRelation. Not a Relation trait.")
        if(graphPatterns.map(_.name) contains hyperRelationPattern.endNode) abort(s"HyperRelation class `${ hyperRelationPattern.name }` needs endNode `${ hyperRelationPattern.endNode }` to be a Node, Node trait, or HyperRelation. Not a Graph.")
        if(relationPatterns.map(_.name) contains hyperRelationPattern.endNode) abort(s"HyperRelation class `${ hyperRelationPattern.name }` needs endNode `${ hyperRelationPattern.endNode }` to be a Node, Node trait, or HyperRelation. Not a Relation.")
        if(relationTraitPatterns.map(_.name) contains hyperRelationPattern.endNode) abort(s"HyperRelation class `${ hyperRelationPattern.name }` needs endNode `${ hyperRelationPattern.endNode }` to be a Node, Node trait, or HyperRelation. Not a Relation trait.")

        HyperRelation(
          pattern = hyperRelationPattern,
          superNodeTypes = filterSuperTypes(nodeTraitPatterns, hyperRelationPattern),
          flatSuperNodeTypesWithSelf = patternToFlatSuperTypesWithSelf(nodeTraitPatterns, hyperRelationPattern).map(_.name) intersect (hyperRelationPattern.name :: nodeTraits.map(_.name)),
          superRelationTypes = filterSuperTypes(relationTraitPatterns, hyperRelationPattern),
          flatSuperStatements = flatSuperStatements(nodeTraitPatterns ::: relationTraitPatterns, hyperRelationPattern),
          traitFactoryParameterList = findSuperFactoryParameterList(nodeTraitPatterns ::: relationTraitPatterns, hyperRelationPattern, nodeTraits ::: relationTraits))
      }

      val relations = relationPatterns.map { relationPattern =>
        abortIfInheritsFrom("Relation", "class", relationPattern, "Relation", "class", relationPatterns)
        abortIfInheritsFrom("Relation", "class", relationPattern, "Node", "class", nodePatterns)
        abortIfInheritsFrom("Relation", "class", relationPattern, "Node", "trait", nodeTraitPatterns)
        abortIfInheritsFrom("Relation", "class", relationPattern, "Graph", "trait", graphPatterns)
        if(graphPatterns.map(_.name) contains relationPattern.startNode) abort(s"Relation class `${ relationPattern.name }` needs startNode `${ relationPattern.startNode }` to be a Node, Node trait, or HyperRelation. Not a Graph.")
        if(relationPatterns.map(_.name) contains relationPattern.startNode) abort(s"Relation class `${ relationPattern.name }` needs startNode `${ relationPattern.startNode }` to be a Node, Node trait, or HyperRelation. Not a Relation.")
        if(relationTraitPatterns.map(_.name) contains relationPattern.startNode) abort(s"Relation class `${ relationPattern.name }` needs startNode `${ relationPattern.startNode }` to be a Node, Node trait, or HyperRelation. Not a Relation trait.")
        if(graphPatterns.map(_.name) contains relationPattern.endNode) abort(s"Relation class `${ relationPattern.name }` needs endNode `${ relationPattern.endNode }` to be a Node, Node trait, or HyperRelation. Not a Graph.")
        if(relationPatterns.map(_.name) contains relationPattern.endNode) abort(s"Relation class `${ relationPattern.name }` needs endNode `${ relationPattern.endNode }` to be a Node, Node trait, or HyperRelation. Not a Relation.")
        if(relationTraitPatterns.map(_.name) contains relationPattern.endNode) abort(s"Relation class `${ relationPattern.name }` needs endNode `${ relationPattern.endNode }` to be a Node, Node trait, or HyperRelation. Not a Relation trait.")

        Relation(
          pattern = relationPattern,
          flatStatements = flatSuperStatements(relationTraitPatterns, relationPattern),
          traitFactoryParameterList = findSuperFactoryParameterList(relationTraitPatterns, relationPattern, relationTraits))
      }

      Schema(schemaPattern, nodes, relations, hyperRelations, nodeTraits, relationTraits, graphs, statements)
    }

    def findSuperFactoryParameterList[P <: NamePattern with SuperTypesPattern, Q <: Named with HasOwnFactory](patterns: List[_ <: P], pattern: P, nameClasses: List[Q]): List[ParameterList] = {
      patternToNameClasses(patternToFlatSuperTypesWithoutSelf(patterns, pattern), nameClasses).map(_.parameterList)
    }

    def patternToNameClasses[P <: Named with HasOwnFactory](patterns: List[_ <: NamePattern], nameClasses: List[P]): List[P] = nameClasses.filter(nameClass => patterns.map(_.name).contains(nameClass.name))

    def filterSuperTypes(patterns: List[_ <: NamePattern], pattern: SuperTypesPattern): List[String] = {
      pattern.superTypes intersect patterns.map(_.name)
    }

    def flatSuperStatements[P <: NamePattern with SuperTypesPattern with StatementsPattern](superTypePatterns: List[NamePattern with SuperTypesPattern with StatementsPattern], pattern: P): List[Tree] = {
      val superTypes: List[StatementsPattern with NamePattern with SuperTypesPattern] = pattern.superTypes.map(superType => nameToPattern(superTypePatterns, superType))
      val flatSuperTypes: List[StatementsPattern] = patternToFlatSuperTypesWithSelf(superTypePatterns, pattern)
      flatSuperTypes.flatMap(_.statements)
    }

    def nameToPattern[P <: NamePattern](patterns: List[P], name: String): P = {
      val found = patterns.find(_.name == name)
      if(found.isEmpty)
        abort(s"Cannot find `$name` in `${ patterns.map(_.name).mkString(", ") }`.")
      else
        found.get
    }

    def neighbours(nodePattern: NodePattern, relations: List[NamePattern with StartEndNodePattern], nodePatterns: List[NodePattern], nodeTraitPatterns: List[NodeTraitPattern]): List[(String, String, String)] = {
      val sources = patternToFlatSuperTypesWithSelf(nodeTraitPatterns, nodePattern).map(_.name)
      relations.filter(sources contains _.startNode).flatMap { r =>
        if(nodeTraitPatterns.map(_.name) contains r.endNode) {
          // if r.endNode is a trait
          // generate accessors for all childNodes
          val childNodes = childNodesOfNodeTrait(nodeTraitPatterns, nodePatterns, nameToPattern(nodeTraitPatterns, r.endNode))
          childNodes.map { childNode =>
            val accessorName = nameToPlural(r.name + childNode)
            (accessorName, r.name, childNode)
          }
        }
        else {
          val accessorName = nameToPlural(r.name)
          List((accessorName, r.name, r.endNode))
        }
      }
    }

    def rev_neighbours(nodePattern: NodePattern, relations: List[NamePattern with StartEndNodePattern], nodePatterns: List[NodePattern], nodeTraitPatterns: List[NodeTraitPattern]): List[(String, String, String)] = {
      val targets = patternToFlatSuperTypesWithSelf(nodeTraitPatterns, nodePattern).map(_.name)
      relations.filter(targets contains _.endNode).flatMap { r =>
        if(nodeTraitPatterns.map(_.name) contains r.startNode) {
          // if r.startNode is a trait
          val childNodes = childNodesOfNodeTrait(nodeTraitPatterns, nodePatterns, nameToPattern(nodeTraitPatterns, r.startNode))
          childNodes.map { childNode =>
            val accessorName = rev(nameToPlural(r.name + childNode))
            (accessorName, r.name, childNode)
          }
        }
        else {
          val accessorName = rev(nameToPlural(r.name))
          List((accessorName, r.name, r.startNode))
        }
      }
    }

    def isDeepSuperType[P <: NamePattern with SuperTypesPattern](patterns: List[P], subPattern: P, superPattern: P): Boolean = {
      subPattern.superTypes match {
        case Nil        => false
        case superTypes => superTypes.exists { name =>
          superPattern.name == name || (patterns.exists(_.name == name) && isDeepSuperType(patterns, nameToPattern(patterns, name), superPattern))
        }
      }
    }

    def patternToSuperTypes[P <: NamePattern with SuperTypesPattern](patterns: List[P], pattern: P): List[P] = pattern.superTypes.map(nameToPattern(patterns, _))

    def patternToFlatSuperTypesWithoutSelf[P <: NamePattern with SuperTypesPattern, SUPER <: P](patterns: List[SUPER], pattern: P): List[SUPER] = {
      patterns.filter { superPattern => isDeepSuperType(patterns, pattern, superPattern) }
    }

    def patternToFlatSuperTypesWithSelf[P <: NamePattern with SuperTypesPattern, SUPER <: P](patterns: List[SUPER], pattern: P): List[P] = {
      pattern :: patternToFlatSuperTypesWithoutSelf(patterns, pattern)
    }

    def patternToSubTypes[P <: NamePattern with SuperTypesPattern](patterns: List[P], pattern: P): List[P] = patterns.filter(_.superTypes.contains(pattern.name))

    def patternToFlatSubTypesWithoutSelf[P <: NamePattern with SuperTypesPattern, SUB <: P](patterns: List[SUB], pattern: P): List[SUB] = {
      patterns.filter { subPattern => isDeepSuperType(patterns, subPattern, pattern) }
    }

    def patternToFlatSubTypesWithSelf[P <: NamePattern with SuperTypesPattern, SUB <: P](patterns: List[SUB], pattern: P): List[P] = {
      pattern :: patternToFlatSubTypesWithoutSelf(patterns, pattern)
    }

    // TODO: check usages if other callers also need intermediate traits
    def childNodesOfNodeTrait(nodeTraits: List[NodeTraitPattern], nodePatterns: List[NamePattern with SuperTypesPattern], nodeTrait: NodeTraitPattern): List[String] = {
      patternToFlatSubTypesWithSelf(nodeTraits, nodeTrait).flatMap { subTrait =>
        nodePatterns.filter(_.superTypes contains subTrait.name)
      }.distinct.map(_.name)
    }

    def childNodesOfNodeTraitsWithTraits(nodeTraits: List[NodeTraitPattern], nodePatterns: List[NamePattern with SuperTypesPattern], nodeTrait: NodeTraitPattern): List[String] = {
      (nodeTrait :: patternToFlatSubTypesWithoutSelf(nodeTraits, nodeTrait)).flatMap { subTrait =>
        subTrait :: nodePatterns.filter(_.superTypes contains subTrait.name)
      }.distinct.map(_.name)
    }

    def nodeNamesToRelations[R <: StartEndNodePattern](nodeNames: List[String], relations: List[R]): List[R] = {
      relations.filter(relation => nodeNames.contains(relation.startNode) && nodeNames.contains(relation.endNode))
    }

    def nodeTraitToCommonHyperNodeTraits[P <: NamePattern with SuperTypesPattern](nodeTraitPatterns: List[NodeTraitPattern], middleNodeTraitPatterns: List[P], nodePatterns: List[NodePattern], hyperRelationPatterns: List[HyperRelationPattern], nodeTrait: NodeTraitPattern): List[String] = {
      val nodes = childNodesOfNodeTraitsWithTraits(nodeTraitPatterns, nodePatterns ::: hyperRelationPatterns, nodeTrait)
      val subHyperRelations = nodeNamesToRelations(nodes, hyperRelationPatterns)
      val flatSuperTypes: List[List[String]] = subHyperRelations.map(hyperRelation => patternToFlatSuperTypesWithoutSelf(middleNodeTraitPatterns, hyperRelation).map(_.name))
      if(flatSuperTypes.isEmpty) Nil
      else if(flatSuperTypes.size == 1) flatSuperTypes.head
      else flatSuperTypes.reduce(_ intersect _)
    }

    def graphToNodes(graphPatterns: List[GraphPattern], graphPattern: GraphPattern): List[String] = {
      patternToFlatSuperTypesWithSelf(graphPatterns, graphPattern).flatMap(_.nodes).distinct
    }

    def inducedRelations(nodes: List[String], nodePatterns: List[NodePattern], nodeTraitPatterns: List[NodeTraitPattern], hyperRelationPatterns: List[HyperRelationPattern], relations: List[NamePattern with StartEndNodePattern]): List[String] = {
      val traits = nodes.map(nameToPattern(nodePatterns, _)).flatMap(patternToFlatSuperTypesWithSelf(nodeTraitPatterns, _)).distinct.map(_.name)
      nodeNamesToRelations(nodes ::: traits ::: hyperRelationPatterns.map(_.name), relations).map(_.name)
    }

    def traitCanHaveOwnFactory(hierarchyPatterns: List[NamePattern with SuperTypesPattern with StatementsPattern], currentTrait: NamePattern with SuperTypesPattern): Option[Boolean] = {
      val children = patternToFlatSubTypesWithoutSelf(hierarchyPatterns, currentTrait)
      val childrenWithFlatParents = children.flatMap(patternToFlatSuperTypesWithSelf(hierarchyPatterns, _)).distinct
      val subWithoutSuper = childrenWithFlatParents diff patternToFlatSuperTypesWithSelf(hierarchyPatterns, currentTrait)
      // if we currently are at NodeTrait, we need to check whether one of its
      // children is a HyperRelation. If that is the case, a factory cannot be
      // generated, as the HyperRelation additionally needs Start-/EndNode in
      // its create-method.
      val isNodeTrait = currentTrait.isInstanceOf[NodeTraitPattern]
      val hasHyperRelationChild = children.exists(_.isInstanceOf[HyperRelationPattern])
      //TODO: should return something else for matches without start/end
      if(isNodeTrait && hasHyperRelationChild)
        return None

      //TODO: anything we can do to avoid repeating outselves here all over again?
      val statements = subWithoutSuper.flatMap(_.statements)
      Some(statements.forall {
        case q"val $x:Option[$propertyType]"         => true
        case q"var $x:Option[$propertyType]"         => true
        case q"val $x:$propertyType = $y"            => true
        case q"var $x:$propertyType = $y"            => true
        case q"val $x:$propertyType"                 => false
        case q"var $x:$propertyType"                 => false
        case q"@unique val $x:Option[$propertyType]" => true
        case q"@unique var $x:Option[$propertyType]" => true
        case q"@unique val $x:$propertyType = $y"    => true
        case q"@unique var $x:$propertyType = $y"    => true
        case q"@unique val $x:$propertyType"         => false
        case q"@unique var $x:$propertyType"         => false
        case _                                       => true // custom statements
      })
    }
  }

  case class Schema(
                     pattern: SchemaPattern,
                     nodes: List[Node],
                     relations: List[Relation],
                     hyperRelations: List[HyperRelation],
                     nodeTraits: List[NodeTrait],
                     relationTraits: List[RelationTrait],
                     graphs: List[Graph],
                     statements: List[Tree]
                     ) extends Named with SuperTypes {
  }

  case class Graph(
                    pattern: GraphPattern,
                    nodesWithHyperNodes: List[String],
                    nodes: List[String],
                    relationsWithHyperRelations: List[String],
                    relations: List[String],
                    hyperRelations: List[String],
                    nodeTraits: List[NodeTrait]
                    ) extends Named with SuperTypes


  case class NodeTrait(
                        pattern: NodeTraitPattern,
                        flatSuperTypesWithSelf: List[String], // only self and nodeTraits without external traits
                        subNodes: List[String],
                        subRelations: List[String],
                        subHyperRelations: List[String],
                        commonHyperNodeNodeTraits: List[String],
                        commonHyperNodeRelationTraits: List[String],
                        flatStatements: List[Tree],
                        hasOwnFactory: Option[Boolean]
                        ) extends Named with SuperTypes with Statements with HasOwnFactory with HasParameterList with HasTraitFactoryParameterList {

    def commonHyperNodeNodeTraits_type = commonHyperNodeNodeTraits.map(TypeName(_))
    def commonHyperNodeRelationTraits_type = commonHyperNodeRelationTraits.map(TypeName(_))

    val parameterList = ParameterList.create(flatStatements, name, representsNode = true, hasOwnFactory)

    var traitFactoryParameterList: List[ParameterList] = null
  }

  object NodeTrait {
    def apply(
               nodeTraitPattern: NodeTraitPattern,
               nodeTraitPatterns: List[NodeTraitPattern],
               relationTraitPatterns: List[RelationTraitPattern],
               selectedNodePatterns: List[NodePattern],
               selectedHyperRelationPatterns: List[HyperRelationPattern],
               relationPatterns: List[RelationPattern],
               hyperRelationPatterns: List[HyperRelationPattern]
               ) = {

      import Schema.{childNodesOfNodeTrait, flatSuperStatements, nodeNamesToRelations, nodeTraitToCommonHyperNodeTraits, patternToFlatSuperTypesWithSelf, traitCanHaveOwnFactory}
      val childNodes = childNodesOfNodeTrait(nodeTraitPatterns, selectedNodePatterns ::: selectedHyperRelationPatterns, nodeTraitPattern)
      val childTraits = childNodesOfNodeTrait(nodeTraitPatterns, nodeTraitPatterns, nodeTraitPattern)
      new NodeTrait(
        nodeTraitPattern,
        flatSuperTypesWithSelf = patternToFlatSuperTypesWithSelf(nodeTraitPatterns, nodeTraitPattern).map(_.name) intersect nodeTraitPatterns.map(_.name),
        subNodes = childNodes,
        subRelations = nodeNamesToRelations(nodeTraitPattern.name :: childNodes ::: childTraits, hyperRelationPatterns ::: relationPatterns).map(_.name),
        subHyperRelations = nodeNamesToRelations(nodeTraitPattern.name :: childNodes ::: childTraits, hyperRelationPatterns).map(_.name),
        commonHyperNodeNodeTraits = nodeTraitToCommonHyperNodeTraits(nodeTraitPatterns, nodeTraitPatterns, selectedNodePatterns, hyperRelationPatterns, nodeTraitPattern),
        commonHyperNodeRelationTraits = nodeTraitToCommonHyperNodeTraits(nodeTraitPatterns, relationTraitPatterns, selectedNodePatterns, hyperRelationPatterns, nodeTraitPattern),
        flatStatements = flatSuperStatements(nodeTraitPatterns, nodeTraitPattern),
        hasOwnFactory = traitCanHaveOwnFactory(selectedNodePatterns ::: hyperRelationPatterns ::: relationTraitPatterns ::: nodeTraitPatterns, nodeTraitPattern)
      )
    }
  }

  case class RelationTrait(
                            pattern: RelationTraitPattern,
                            flatStatements: List[Tree],
                            hasOwnFactory: Option[Boolean]
                            ) extends Named with SuperTypes with Statements with HasOwnFactory with HasParameterList {

    val parameterList = ParameterList.create(flatStatements, name, representsNode = false, hasOwnFactory)
  }

  case class Node(
                   pattern: NodePattern,
                   override val superTypes: List[String], // only nodeTraits
                   flatSuperTypesWithSelf: List[String], // only self and nodeTraits without external traits
                   externalSuperTypes: List[String],
                   neighbours: List[(String, String, String)], // accessorName, relation, endNode
                   rev_neighbours: List[(String, String, String)], // accessorName, relation, startNode
                   outRelationsToTrait: List[(String, String)],
                   inRelationsFromTrait: List[(String, String)],
                   flatStatements: List[Tree],
                   traitFactoryParameterList: List[ParameterList],
                   implementedTrait: Option[NodeTrait]
                   ) extends Named with SuperTypes with Statements with HasParameterList with HasTraitFactoryParameterList {

    val parameterList = ParameterList.create(flatStatements, name, representsNode = true)

    def isTraitImplementation = implementedTrait.isDefined

    def neighbours_terms = neighbours.map { case (accessorName, relation, endNode) =>
      (TermName(accessorName), TermName(relation), TypeName(endNode), TermName(endNode))
    }

    def rev_neighbours_terms = rev_neighbours.map { case (rev_accessorName, relation, startNode) =>
      (TermName(rev_accessorName), TermName(relation), TypeName(startNode), TermName(startNode))
    }
  }

  case class Relation(
                       pattern: RelationPattern,
                       flatStatements: List[Tree], // TODO: rename to flatSuperStatements (same for node etc)
                       traitFactoryParameterList: List[ParameterList]
                       ) extends Named with StartEndNode with SuperTypes with Statements with HasParameterList with HasTraitFactoryParameterList {

    val parameterList = ParameterList.create(flatStatements, name, representsNode = false)
  }

  case class HyperRelation(
                            pattern: HyperRelationPattern,
                            superNodeTypes: List[String],
                            flatSuperNodeTypesWithSelf: List[String], // only self and nodeTraits without external traits
                            superRelationTypes: List[String],
                            flatSuperStatements: List[Tree],
                            traitFactoryParameterList: List[ParameterList]
                            ) extends Named with SuperTypes with StartEndNode with Statements with StartEndRelation with HasParameterList with HasTraitFactoryParameterList {

    val parameterList = ParameterList.create(flatSuperStatements, name, representsNode = true)
  }

}
