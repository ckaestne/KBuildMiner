package gsd.cdl.ase10

/*
 * Copyright (c) 2010 Thorsten Berger <berger@informatik.uni-leipzig.de>
 *
 * This file is part of CDLTools.
 *
 * CDLTools is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * CDLTools is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with CDLTools.  If not, see <http://www.gnu.org/licenses/>.
 */
import util.parsing.input.PagedSeqReader
import collection.immutable.PagedSeq
import org.kiama.rewriting.Rewriter._
import scala.collection.mutable
import java.io._
import javax.xml.bind.{Marshaller, JAXBContext}
import collection.JavaConversions._
import gsd.cdl.statistics._
import gsd.cdl.parser.combinator.IMLParser
import gsd.cdl.model._
import gsd.cdl.parser.EcosIml
import gsd.cdl.AnalysisHelpers

object EcosI386StatisticsMain extends IMLParser{

//  var nodesById = Map[String,Node]()
//  var childParentMap = Map[String,String]()

  val model = EcosIml.CupParser.parseFile( "input/iml/pc_vmWare.iml" )

  def main( args: Array[String] ){

    doCollectStatistics( model, "pc_vmWare" )
  }

  def doCollectStatistics( model: IML, target : String ) : CDLModel = {
    val ret = new CDLModel()
    ret.target = target

    // leave-depth histogram
    val leaves = model.allNodes.filter( x => !model.childParentMap.values.contains( x.id ) )
    println( leaves.size )
    leaves.foreach( println )
    outputHistogram( ret,
      leaves.map( x => depth( x.id, model.childParentMap ) ),
      "output/histograms/leaf_depth_" + ret.target + ".csv" )

    // branching factor histogram
    outputHistogram( ret,
      model.allNodes.map( _.nchildren.size ).filter( _ != 0 ),
      "output/histograms/branching_" + ret.target + ".csv" )


    ret.nodes = model.allNodes.size
    ret.userChangeable = model.allNodes.
            filter( x => x.cdlType == ComponentType || x.cdlType == OptionType || x.cdlType == PackageType ).
            filter( x => x.flavor == BoolFlavor || x.flavor == BoolDataFlavor || x.flavor == DataFlavor ).
            filter( x => x.calculated == None ).
            size

    val derived = model.allNodes.
            filter( x =>  x.cdlType == InterfaceType || x.calculated != None || x.flavor == NoneFlavor )
    ret.derived = derived.size

    ret.withChangeableDataValue = model.allNodes.
            filter( x => x.cdlType == ComponentType || x.cdlType == OptionType ).
            filter( x => x.flavor == BoolDataFlavor || x.flavor == DataFlavor ).
            filter( x => x.calculated == None ).
            size

//    model.allNodes.foreach( x => println( x.reqs.size + ";  " + x.activeIfs.size + ";  " + x.calculated ) )

    ret.mandatory = model.allNodes.
            filter( x => x.cdlType != InterfaceType ).
            filter( x => ( x.reqs.isEmpty && x.activeIfs.isEmpty && x.calculated == None ) ).
            filter( x => ( x.flavor == NoneFlavor || x.flavor == DataFlavor || x.cdlType == PackageType ) ).
            size

    ret.nodesWithAIconstraints = model.allNodes.filter( x => !x.activeIfs.isEmpty ).size
    ret.nodesWithReqsConstraints = model.allNodes.filter( x => !x.reqs.isEmpty ).size

    ret.dataNodesWhichAreTests = model.allNodes.
            filter( x => x.flavor == BoolDataFlavor || x.flavor == DataFlavor ).
            filter( x => x.id.indexOf( "TESTS" ) > 0 ).
            size

    println( "\n===========\n groups:")
    val orGroups = AnalysisHelpers.findGroups( model, "or" )
    println( orGroups.size + " or groups" )
    val xorGroups = AnalysisHelpers.findGroups( model, "xor" )
    println( xorGroups.size + " xor groups" )
//    printGroups( xorGroups, "xor" )

    val mutexGroups = AnalysisHelpers.findGroups( model, "mutex" )
//    val mutexWithMoreThanOneChild = for( g <- mutexGroups; gelem <- g; if(gelem._2.size > 1) ) yield (gelem._1.id, gelem._2)
    val mutexWithMoreThanOneChild = mutexGroups.filter( _._2.size > 1 )

    println( mutexGroups.size + " mutex groups, but only " + mutexWithMoreThanOneChild.size + " mutex groups with more than one child:" )
    printGroups2( mutexGroups, "mutex" )


    println( "# of all nodes: " + ret.nodes )
    println( "# of user-changeable: " + ret.userChangeable )
    println( "# of derived: " + ret.derived + " (interfaces: " + derived.filter( _.cdlType == InterfaceType ).size + ", " +
            "calculated: " + derived.filter( _.calculated != None ).size + ", " +
            "none: " + derived.filter( _.flavor == NoneFlavor ).size )
    println( "# of features that have changeable data value: " + ret.withChangeableDataValue )
    println( "# of mandatory features: " + ret.mandatory )
    println( "# of nodes with active_if constraints: " + ret.nodesWithAIconstraints )
    println( "# of nodes with requires constraints: " + ret.nodesWithReqsConstraints )
    println( "# of data nodes which are test specifications: " + ret.dataNodesWhichAreTests )

    rafaelOutput
    stevenCSVOutput

    enumerationAnalysis( model.allNodes, ret )

    expressionAnalysis( model.topLevelNodes, ret )
    
    // interface cross-cutting analysis
    interfaceCrossCuttingAnalysis( model.allNodes, ret )

    println("Packages: ")
    println("==============================")
    model.allNodes.filter( _.cdlType == PackageType ).foreach( x => println( x.id ) )

    ret
  }

  def printGroups( groups : List[mutable.Map[Node,List[Node]]], gtype : String ){
      for( g <- groups; gelem <- g ){
        println( gtype + " group " + gelem._1.id + ":")
        gelem._2.foreach( c => println( "     " + c.id ) )
      }
  }

  def printGroups2( groups: List[Tuple2[Node,List[Node]]], gtype: String ){
    for( g <- groups ){
      println( gtype + " group " + g._1.id + ":")
      g._2.foreach( c => println( "     " + c.id ) )
    }

  }


  def interfaceCrossCuttingAnalysis( allNodes : List[Node], cdlModel : CDLModel ){

    val allInterfaces = allNodes.filter( _.cdlType == InterfaceType )
    outputHistogram( cdlModel, allInterfaces.map( x => impls( x.id ).size ), "output/histograms/numOfImplementations_" + cdlModel.target + ".csv" )

    // how many of the implementations are siblings?
    var interfacesWithSiblingImplementations = List[Node]()
    for( i <- allInterfaces ){
      val parents = impls( i.id ).map( x => model.childParentMap.get( x.id ) ).map( _.get)
      if( !parents.isEmpty && parents.forall( x => x == parents( 0 )) )
        interfacesWithSiblingImplementations = i :: interfacesWithSiblingImplementations
    }
    println( "\nfor " + interfacesWithSiblingImplementations.size + " interfaces, all implementations share the same parent, i.e. they're not cross-cutting" )

    // now, what's the relationship between interfaces and their implementations?
    val interfAndImplAreSiblings = interfacesWithSiblingImplementations.
            filter( x => model.childParentMap.get( x.id ).get == model.childParentMap.get( impls( x.id )(0).id ).get )

    val interfAreParentToImpl = interfacesWithSiblingImplementations.
            filter( x => x.id == model.childParentMap.get( impls( x.id )(0).id ).get )

    val interfAreAChildOfOneImpl = interfacesWithSiblingImplementations.
            filter( x => impls( x.id ).map( _.id ).contains( model.childParentMap.get( x.id ) ) )

    println( interfAndImplAreSiblings.size + " interfaces are siblings to their implementations")
    println( interfAreParentToImpl.size + " interfaces are parents of their implementations")
    println( interfAreAChildOfOneImpl.size + " interfaces are a child of one of their implementations")

    // spit out what's left
    println( "left are:" )
    val left = interfacesWithSiblingImplementations -- interfAndImplAreSiblings -- interfAreParentToImpl -- interfAreAChildOfOneImpl
    for( i <- left ){
      print( i.id )
      val impl = impls( i.id ).map( _.id )
      if( !impl.isEmpty )
        print( ": " + impl.reduceLeft[String]( (a,b) => a + ", " + b ) )
      println
    }


    val constrainedInterfaces = allInterfaces.filter( !_.reqs.isEmpty )
//    println( "\n==== all constrained interfaces: ")
//    constrainedInterfaces.foreach( x => println( x.reqs ) )
    val cardinalityConstrainedInterfaces = constrainedInterfaces.filter( x => collectl{
             case Eq( LongIntLiteral( 1 ), Identifier( x.id ) )             => x.id
             case Eq( Identifier( x.id ), LongIntLiteral( 1 ) )             => x.id
             case GreaterThanOrEq( LongIntLiteral( 1 ), Identifier( x.id) ) => x.id
             case GreaterThanOrEq( Identifier( x.id), LongIntLiteral( 1 ) ) => x.id
             case LessThanOrEq( Identifier( x.id ), LongIntLiteral( 1 ) )   => x.id
          }(x.reqs).size > 0 )

    println( "\n====cardinality-constrained interfaces: ")
//    cardinalityConstrainedInterfaces.foreach( x => println( x.reqs ) )
    println( " -> " + cardinalityConstrainedInterfaces.size )
    println( "\n=== we don't recognize the following as cardinality-constrained: ")
    constrainedInterfaces.filter( x => !( cardinalityConstrainedInterfaces contains x ) ).foreach( x => println( x ) )

    val numOfImplementors = cardinalityConstrainedInterfaces.map( x => impls( x.id ).size ).toList
    numOfImplementors.foreach( x => print( x + " " ) )


    // do package analysis
    allInterfaces.foreach( x => println( "int: " + x.id + "; package: " + (findPackage( x ) match {
      case Some(n) => n.id
      case None => "none found!"
    })) )

    println( "=======================")
    println( "searching for package relationship between interfaces and implementations" )
    for( i <- allInterfaces ){
      val interfPkg = findPackage( i ).get
      val implementations = impls( i.id )
      if( !implementations.isEmpty ){
        val implPkg = findPackage( implementations.first ).get
        if( implPkg.id != interfPkg.id )
          println( "Interface: " + i.id + " declared in package " + interfPkg.id + " has " + implementations.size + " implementations in package " + implPkg.id )
      }
    }

    print( "The implementations of CYGPKG_IO_FILEIO are:")
    impls( "CYGINT_IO_FILEIO_FS" ).foreach( x => print( " " + x.id ))
    println

    println

  }

  def findPackage( node : Node ): Option[Node] = {
    node match {
      case Node(_,PackageType,_,_,_,_,_,_,_,_,_,_) => Some( node )
      case _            => model.childParentMap.get( node.id ) match{
                              case Some("root") => None
                              case Some( n )  => findPackage( model.nodesById.get(n).get )
                              case _ => sys.error( "No parent node found?!" )
                           }
    }
  }


  def enumerationAnalysis( allNodes: List[Node], model : CDLModel ){
    val lvRestrictedNodes = allNodes.filter( x => x.legalValues != None )

    println( "=== enumeration analysis" )
    val stringEnumRestricted = lvRestrictedNodes.
            filter( x => x.legalValues.get.ranges.forall( e =>
              e match {
                  case SingleValueRange( StringLiteral( _ ) ) => true
                  case _ => false
              } )
            )
    val intEnumRestricted = lvRestrictedNodes.
            filter( x => x.legalValues.get.ranges.forall( e =>
              e match {
                  case SingleValueRange( LongIntLiteral( _ ) ) => true
                  case _ => false
              } )
            )
    val stringOrIntEnumRestricted = ( lvRestrictedNodes -- stringEnumRestricted -- intEnumRestricted ).
            filter( x => x.legalValues.get.ranges.forall( e =>
              e match {
                  case SingleValueRange( StringLiteral( _ ) ) => true
                  case SingleValueRange( LongIntLiteral( _ ) ) => true
                  case _ => false
              } )
            )

    val rangeRestricted = lvRestrictedNodes.
            filter( x => x.legalValues.get.ranges.forall( e =>
              e match {
                  case MinMaxRange( LongIntLiteral( _ ), LongIntLiteral( _ ) ) => true
                  // the following match on hex bounds; just a workaround for now, since hex is parsed as string
                  case MinMaxRange( StringLiteral( _ ), LongIntLiteral( _ ) ) => true
                  case MinMaxRange( LongIntLiteral( _ ), StringLiteral( _ ) ) => true
                  case MinMaxRange( StringLiteral( _ ), StringLiteral( _ ) ) => true
                  case _ => false
              } )
            )

    println( "# nodes with legal_values restriction: " + lvRestrictedNodes.size )
    println( "# nodes with just an enumeration of string values: " + stringEnumRestricted.size )
    println( "# nodes with just an enumeration of int values: " + intEnumRestricted.size )
    println( "# nodes with just an enumeration of string or int values: " + stringOrIntEnumRestricted.size )
    println( "# nodes with (possibly multiple) ranges with int and hex as bounds: " + rangeRestricted.size )

    val enums = stringEnumRestricted ++ intEnumRestricted ++ stringOrIntEnumRestricted
    val enumSizes = enums.map( x => x.legalValues.get.ranges.size )
    println( "Ok, enum sizes: min=" + enumSizes.reduceLeft[Int]( (a,b) => Math.min(a,b) ) +
                            ", max=" + enumSizes.reduceLeft[Int]( (a,b) => Math.max(a,b) ) +
                            ", sum=" + enumSizes.foldLeft(0)( _ + _ ))

    val left = lvRestrictedNodes -- stringEnumRestricted -- intEnumRestricted --stringOrIntEnumRestricted -- rangeRestricted
//    left.foreach( n => println( n.id + ": " + n.legalValues ) )

    println
  }


  def depth( id : String, childParentMap : Map[String,String] ): Int =
    childParentMap.get( id ) match {
      case Some(n) => depth( n, childParentMap ) + 1
      case None => 0
    }


  def outputHistogram( model : CDLModel, values : List[Int], file : String ){
		val pw = new PrintWriter( new File( file ) )
		aggregateValuesForGnuplot( values, 1 ).
            foreach( x => pw.println( x._1 + "," + x._2 ) )
		pw.close();
	}

  def aggregateValuesForGnuplot( values : List[Int], raster : Int ) = {
    val ret = mutable.Map[Int, Int]()
    for( val f <- values ){
      val newValue:Int = ( ( f / raster ) * raster ) + raster/2;
      ret.get( newValue ) match{
        case None => ret + (newValue -> 1)
        case Some(v) => ret + ( newValue -> ( v + 1 ) )
      }
    }
		Map[Int,Int]() ++ ret
	}

    def impls( interfaceID : String ) =
      model.nodesById.values.filter( _.implements contains Identifier( interfaceID ) ).toList



  def expressionAnalysis( topLevelNodes : List[Node], cdlModel : CDLModel ){
    println( "\n==================================")
    println( "expression analysis" )
    // go and get all expressions
    val allExpressions = List.flatten( collectl{
      case Node( id,_,_,_,_,dv,ca,lv,re,ai,_,_) => {
        var el = List[CDLExpression]()
        if( ca != None ) el = ca.get :: el
        if( dv != None ) el = dv.get :: el
//        if( lv != None ) el += lv.get
        re.foreach( x => el = x :: el )
        ai.foreach( x => el = x :: el )
//        List.flatten( re.map( _.splitConjunctions ) ).foreach( el += _ )
//        List.flatten( ai.map( _.splitConjunctions ) ).foreach( el += _ )
        el
      }
    }( topLevelNodes ) ).filter( _ != True() )


    val positiveBinaryImplication = allExpressions.filter( _ match {
      case Identifier( _ ) => true
      case _ => false
    })
    val negativeBinaryImplication = allExpressions.filter( _ match {
      case Not( Identifier( _ ) ) => true
      case _ => false
    })
    println( "before kiama...")
    val allLoadedIdentifiers = collects{
      case Identifier( x ) => x
    }( allExpressions ).filter( model.nodesById.keySet contains _ )

    println( "Ok, so the total number of (non-conjoined) expressions is: " + allExpressions.size )
    println( "# of positive binary implications: " + positiveBinaryImplication.size )
    println( "# of negative binary implications: " + negativeBinaryImplication.size )
    println( "# of features participating in cross-tree constraints: " +
            allLoadedIdentifiers.size + "/" + model.nodesById.keySet.size +
            " (" + ( ( allLoadedIdentifiers.size.toFloat / model.nodesById.keySet.size.toFloat ) * 100 ).toInt + "%)" )
    println
    
    val numberOfIdentifiers = allExpressions.map( x => collectl{
      case Identifier( v ) => v
    }(x).size )

    outputHistogram( cdlModel, numberOfIdentifiers, "output/histograms/referenced_features_in_expressions_" + cdlModel.target + ".csv" )

    val nonBoolean = allExpressions.filter( e => ( rewrite( IsItCompletelyBoolean )(e) match {
        case NonBoolean( _ ) => true
        case _ => false
    }))
//    nonBoolean.foreach( println )

    println( "# non-boolean: " + nonBoolean.size )

    val containComparisonOperator = nonBoolean.filter( x => collectl{
      case o:Eq => o
      case o:NEq => o
      case o:LessThan => o
      case o:LessThanOrEq => o
      case o:GreaterThan => o
      case o:GreaterThanOrEq => o
    }(x).size > 0 )

    val containEquals = containComparisonOperator.filter( x => collectl{
        case o:Eq => o
    }(x).size > 0 )


    val containArithmeticOperator = nonBoolean.filter( x => collectl{
      case o:Plus => o
      case o:Minus => o
      case o:Times => o
      case o:Div => o
      case o:Mod => o
    }(x).size > 0 )

    val containFunctionCall = nonBoolean.filter( x => collectl{
      case o:FunctionCall => o
    }(x).size > 0 )

    val containConditional = nonBoolean.filter( x => collectl{
      case o:Conditional => o
    }(x).size > 0 )

    // Dot

    println( "From the non-boolean ones contain a comparison operator (==,!=,<,>,<=,>=): " + containComparisonOperator.size )
    println( " ....if we ask ask for an equals (==): " + containEquals.size )
    println( "From the non-boolean ones contain an arithmetic operator (+,-,*,/,%): " + containArithmeticOperator.size )
    containArithmeticOperator.foreach( println )
    println( "From the non-boolean ones contain a function call: " + containFunctionCall.size )
    println( "From the non-boolean ones contain a conditional (a?b:c): " + containConditional.size )

  }

  def makeConjunct( l : List[CDLExpression] ) =
    l.foldLeft( True(): CDLExpression )( (a,b) => a & b )

  val isItBoolean1stPass = rule {
    case t@LongIntLiteral( 1 ) => t
    case t@LongIntLiteral( 0 ) => t
    // TODO A == 0 or A == 1
  }

  val isItBoolean2ndPass = topdownS({
    rule{
      case t:And => t
      case t:Or => t
      case t:Implies => t
      case t:True => t
      case t:False => t
      case t:Identifier => t
      case t:Not => t
    }
  }, { s => all(all(fail)) }
  )

  val returnNonBoolean = rule{
    case _ => NonBoolean( Identifier( "" ) )
  }
  
  val IsItCompletelyBoolean = isItBoolean1stPass <+ isItBoolean2ndPass <+ returnNonBoolean

  def stevenCSVOutput{
    val out = new PrintWriter( new FileWriter( "output/ecos_hierarchy.csv") )
    for( cp <- model.childParentMap ){
      val c = cp._1
      val p = if( cp._2 == "root" ) "^" else cp._2
      out.println( c + "," + p )  
    }
    out.close
  }

  def rafaelOutput(){
    val jc = JAXBContext.newInstance( classOf[Feature], classOf[Features] );
    val unmarshaller = jc.createUnmarshaller();
    val features = unmarshaller.unmarshal( new File( "input/descriptions/pc_vmWare_descriptions.xml" ) ).asInstanceOf[Features]

    val available = features.feature.map( _.id )
    val missing = model.nodesById.keySet.filter( x => !( available contains x ) )

    for( m <- missing )
      features.feature.add( new Feature( m ) )

    for( x <- features.feature ){
      val fullFeature = model.nodesById.get( x.id ).get
      if( fullFeature.display != "" )
        x.prompt = fullFeature.display.substring( 1, fullFeature.display.length - 1 )
      x.parent = model.childParentMap.get( x.id ) match{
        case Some(p) => p
        case None => null
      }
      x.isParentCandidate = fullFeature.cdlType != OptionType
    }

		val marshaller = jc.createMarshaller();
		marshaller.setProperty( Marshaller.JAXB_FORMATTED_OUTPUT, new java.lang.Boolean(true) );
		marshaller.marshal( features, new File( "output/descriptions_pc_vmWare.xml" ) );
  }

}