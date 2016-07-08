package ai.x.diff
import shapeless._, record._, shapeless.syntax._, labelled._, ops.record._, ops.hlist._
import org.cvogt.scala.StringExtensions
import org.cvogt.scala.debug.ThrowableExtensions

object `package` {
  def red( s: String ) = Console.RED + s + Console.RESET
  def green( s: String ) = Console.GREEN + s + Console.RESET
  def blue( s: String ) = Console.BLUE + s + Console.RESET
  def pad( s: Any, i: Int = 5 ) = ( " " * ( i - s.toString.size ) ) + s
  def arrow( l: String, r: String ) = l + " -> " + r
  def showChange( l: String, r: String ) = red( l ) + " -> " + green( r )
}

object conversions{
  implicit def seqAsSet[E:DiffShow]: DiffShow[Seq[E]] = new DiffShow[Seq[E]]{
    def show(t: Seq[E]) = DiffShow[Set[E]].show(t.toSet)
    def diff(left: Seq[E], right: Seq[E]) = DiffShow[Set[E]].diff(left.toSet, right.toSet)
  }
  implicit def listAsSet[E:DiffShow]: DiffShow[List[E]] = new DiffShow[List[E]]{
    def show(t: List[E]) = DiffShow[Set[E]].show(t.toSet)
    def diff(left: List[E], right: List[E]) = DiffShow[Set[E]].diff(left.toSet, right.toSet)
  }
  implicit def vectorAsSet[E:DiffShow]: DiffShow[Vector[E]] = new DiffShow[Vector[E]]{
    def show(t: Vector[E]) = DiffShow[Set[E]].show(t.toSet)
    def diff(left: Vector[E], right: Vector[E]) = DiffShow[Set[E]].diff(left.toSet, right.toSet)
  }
}

abstract class Comparison {
  def string: String
  def create( s: String ): Comparison
  def map( f: String => String ): Comparison = create( f( this.string ) )
  def flatMap( f: String => Comparison ): Comparison = f( this.string ) match {
    case Identical( s ) => create( s )
    case c: Different   => c
    case c: Error   => c
  }
  def isIdentical: Boolean
}
case class Identical( string: String ) extends Comparison {
  def create( s: String ) = Identical( s )
  override def isIdentical = true
}
object Identical {
  def apply[T: DiffShow]( value: T ): Identical = Identical( DiffShow.show( value ) )
}
case class Different( string: String ) extends Comparison {
  def create( s: String ) = Different( s )
  override def isIdentical = false
}
case class Error( string: String ) extends Comparison {
  def create( s: String ) = Error( s )
  override def isIdentical = throw new Exception( string )
}
object Different {
  def apply[T: DiffShow]( left: T, right: T ): Different = Different( DiffShow.show( left ), DiffShow.show( right ) )
  def apply( left: String, right: String ): Different = Different( showChange( left, right ) )
}

abstract class DiffShow[T] {
  def show( t: T ): String
  def diff( left: T, right: T ): Comparison
  def diffable( left: T, right: T ) = diff(left, right).isIdentical
}
object DiffShow extends DiffShowInstances {
  def apply[T]( implicit diffShow: DiffShow[T] ) = diffShow
  def show[T]( t: T )( implicit diffShow: DiffShow[T] ): String = diffShow.show( t )
  def diff[T]( left: T, right: T )( implicit diffShow: DiffShow[T] ): Comparison = diffShow.diff( left, right )
  def diffable[T]( left: T, right: T )( implicit diffShow: DiffShow[T] ) = diffShow.diffable( left, right )
}
abstract class DiffShowFields[-T] { // contra-variant to allow Seq type class for List
  def show( t: T ): Map[String, String]
  def diff( left: T, right: T ): Map[String, Comparison]
}

abstract class DiffShowFieldsLowPriority {
  def create[T]( _show: T => Map[String, String], _diff: ( T, T ) => Map[String, Comparison] ) = new DiffShowFields[T] {
    def show( t: T ) = _show( t )
    def diff( left: T, right: T ) = _diff( left, right )
  }

  implicit def other[T: scala.reflect.ClassTag]: DiffShowFields[T] = fallbackException[T]
  def fallbackException[T: scala.reflect.ClassTag] = {
    val T = scala.reflect.classTag[T].toString
    // throw new Exception( s"Cannot find DiffShowFields[$T]" )
    create[T](
      v => throw new Exception( s"Cannot find DiffShowFields[$T] to show value " + v ),
      ( l, r ) => throw new Exception( s"Cannot find DiffShowFields[$T] to diff values ($l, $r)" )
    )
  }
}

object DiffShowFields {
  def apply[T]( implicit show: DiffShowFields[T] ): DiffShowFields[T] = show

  implicit object hNil extends DiffShowFields[HNil] {
    def show( t: HNil ) = Map()
    def diff( left: HNil, right: HNil ) = Map()
  }

  implicit def hCons[Key <: Symbol, Value, Tail <: HList](
    implicit
    key:      Witness.Aux[Key],
    showHead: DiffShow[Value],
    showTail: Lazy[DiffShowFields[Tail]]
  ): DiffShowFields[FieldType[Key, Value] :: Tail] =
    new DiffShowFields[FieldType[Key, Value] :: Tail] {
      def show( hlist: FieldType[Key, Value] :: Tail ) =
        showTail.value.show( hlist.tail ) + ( key.value.name -> showHead.show( hlist.head ) )
      def diff( left: FieldType[Key, Value] :: Tail, right: FieldType[Key, Value] :: Tail ) = {
        showTail.value.diff( left.tail, right.tail ) + ( key.value.name -> DiffShow.diff[Value]( left.head, right.head ) )
      }
    }
}

abstract class DiffShowInstancesLowPriority {
  def create[T]( _show: T => String, _diff: ( T, T ) => Comparison ) = new DiffShow[T] {
    def show( t: T ) = _show( t )
    def diff( left: T, right: T ) = _diff( left, right )
  }

  // enable for debugging if your type class can't be found
  implicit def otherDiffShow[T: scala.reflect.ClassTag]: DiffShow[T] = fallbackException[T]
  def fallbackException[T: scala.reflect.ClassTag] = {
    val T = scala.reflect.classTag[T].toString
    // throw new Exception( s"Cannot find DiffShow[$T]" )
    create[T](
      v => red(new Exception(s"ERROR: Cannot find DiffShow[$T] to show value " + v).showStackTrace),
      ( l, r ) => Error( new Exception(s"ERROR: Cannot find DiffShow[$T] to show values ($l, $r)").showStackTrace )
    )
  }
}

abstract class DiffShowInstances extends DiffShowInstancesLowPriority {
  // helper methods
  def constructor( name: String, keyValues: List[( String, String )] ): String = constructorOption( name, keyValues.map( Option( _ ) ) )
  def constructorOption( name: String, keyValues: List[Option[( String, String )]] ): String = {
    val suppressed = keyValues.contains( None )
    val args = keyValues.collect {
      case Some( ( "", r ) ) => r
      case Some( ( l, r ) )  => s"$l = $r"
    }

    val inlined = args.mkString( ", " )
    name.stripSuffix( "$" ) + (
      if ( keyValues.isEmpty ) (
        if ( name.endsWith( "$" ) ) "" // case object
        else if ( suppressed ) "(...)" // unchanged collection
        else "()" // EmptyCaseClass() or List()
      )
      else if ( keyValues.size == 1 && keyValues.flatten.size == 1 ) (
        if ( inlined.contains( "\n" ) ) s"(\n${keyValues.flatten.head._2.indent( 1 )}\n)" // avoid x in Some( x = ... )
        else s"( ${keyValues.flatten.head._2} )" // avoid x in Some(\n x = ... \n)
      )
      else "(" + ( if ( suppressed ) " ...," else "" ) + (
        if ( inlined.size < 120 ) s""" ${inlined} """ // short enough content to inline
        else ( "\n" + args.mkString( ",\n" ).indent( 1 ) + "\n" ) // long content, break lines
      ) + ")"
    )
  }

  def primitive[T]( show: T => String ) = create[T](
    show,
    ( left: T, right: T ) => if ( left == right ) Identical( show( left ) ) else Different( show( left ), show( right ) )
  )

  // instances for primitive types

  implicit def booleanDiffShow = primitive[Boolean]( _.toString )
  implicit def floatDiffShow = primitive[Float]( _.toString )
  implicit def doubleDiffShow = primitive[Double]( _.toString )
  implicit def intDiffShow = primitive[Int]( _.toString )
  implicit def stringDiffShow = primitive[String]( s => "\"" + s.replace( "(\n|\r)+", " " ).replace( " +", " " ) + "\"" )

  // instances for Scala collection types

  // TODO: this should probably Set[T] and Seq[T] in our case be a converter instance on top of it
  implicit def setDiffShow[T: DiffShow]: DiffShow[Set[T]] = new DiffShow[Set[T]] {
    // this is hacky and requires an asInstanceOf. Mayber there is a cleaner implementation.
    override def show( l: Set[T] ): String = {
      val fields = l.map( v => DiffShow.show( v ) ).toList
      constructor( "Set", fields.map( ( "", _ ) ) )
    }

    override def diff( left: Set[T], right: Set[T] ): ai.x.diff.Comparison = {
      var _right = right diff left
      val results = (left diff right).map{ l =>
        _right.find( DiffShow.diffable( l, _ ) ).map{
          r =>
            _right = _right - r
            Right(
              DiffShow.diff(l,r)
            )
        }.getOrElse( Left(l) )
      }
      val removed = results.flatMap( _.left.toOption.map( r => Some("" ->   red(DiffShow.show[T](r))) ) ).toList
      val added   = _right     .map(                      r => Some("" -> green(DiffShow.show[T](r)))   ).toList
      val (identical,changed) = results.flatMap(_.right.toOption.map{
        case Identical(s) => None
        case Different(s) => Some("" -> s)
      }).toList.partition(_.isEmpty)

      val string = constructorOption(
        "Set",
        changed ++ removed ++ added
        ++ ( if( (identical ++ (left intersect right)).nonEmpty ) Some(None) else None )
        )

      if ( removed.isEmpty && added.isEmpty && changed.isEmpty )
        Identical( string )
      else
        Different( string )
    }
  }

  implicit def mapDiffShow[K: DiffShow, V: DiffShow]: DiffShow[Map[K, V]] = new DiffShow[Map[K, V]] {
    def show( l: Map[K, V] ) = (
      constructor(
        "Map",
        l.map { case ( k, v ) => DiffShow.show( k ) -> DiffShow.show( v ) }.map( ( arrow _ ).tupled ).map( ( "", _ ) ).toList
      )
    )
    def diff( left: Map[K, V], right: Map[K, V] ) = {
      val identical = left.keys.toList intersect right.keys.toList
      val removed = left.keys.toList diff right.keys.toList
      val added = right.keys.toList diff left.keys.toList
      def show( keys: List[K] ) = keys.map( k => DiffShow.show( k ) -> DiffShow.show( right( k ) ) )
      val changed = for {
        key <- left.keys.toList diff removed
        Different( s ) <- DiffShow.diff( left( key ), right( key ) ) :: Nil
      } yield s

      val string =
        constructorOption(
          "Map",
          identical.map( _ => None ) ++ Seq(
            changed,
            show( removed ).map( ( arrow _ ).tupled ).map( red ),
            show( added ).map( ( arrow _ ).tupled ).map( green )
          ).flatten.map( s => Option( ( "", s ) ) )
        )
      if ( removed.isEmpty && added.isEmpty && changed.isEmpty )
        Identical( string )
      else
        Different( string )
    }
  }

  // instances for Shapeless types

  implicit object CNilDiffShow extends DiffShow[CNil] {
    def show( t: CNil ) = throw new Exception("Methods in CNil type class instance should never be called. Right shapeless?")
    def diff( left: CNil, right: CNil ) = throw new Exception("Methods in CNil type class instance should never be called. Right shapeless?")
  }

  implicit def coproductDiffShow[Name <: Symbol, Head, Tail <: Coproduct](
    implicit
    key:      Witness.Aux[Name],
    headShow: DiffShow[Head],
    tailShow: DiffShow[Tail]
  ): DiffShow[FieldType[Name, Head] :+: Tail] = new DiffShow[FieldType[Name, Head] :+: Tail] {
    def show( co: FieldType[Name, Head] :+: Tail ) = {
      co match {
        case Inl( found ) => headShow.show( found )
        case Inr( tail )  => tailShow.show( tail )
      }
    }
    def diff( left: FieldType[Name, Head] :+: Tail, right: FieldType[Name, Head] :+: Tail ) = {
      ( left, right ) match {
        case ( Inl( l ), Inl( r ) ) => headShow.diff( l, r )
        case ( Inr( l ), Inr( r ) ) => tailShow.diff( l, r )
        case ( Inl( l ), Inr( r ) ) => Different( headShow.show( l ), tailShow.show( r ) )
        case ( Inr( l ), Inl( r ) ) => Different( tailShow.show( l ), headShow.show( r ) )
      }
    }
  }

  implicit def sealedDiffShow[T, L <: Coproduct](
    implicit
    coproduct:     LabelledGeneric.Aux[T, L],
    coproductShow: Lazy[DiffShow[L]]
  ): DiffShow[T] = new DiffShow[T] {
    def show( t: T ) = coproductShow.value.show( coproduct.to( t ) )
    def diff( l: T, r: T ) = coproductShow.value.diff( coproduct.to( l ), coproduct.to( r ) )
  }

  implicit def caseClassDiffShow[T, L <: HList](
    implicit
    labelled:  LabelledGeneric.Aux[T, L],
    hlistShow: Lazy[DiffShowFields[L]]
  ): DiffShow[T] = new CaseClassDiffShow[T, L]

  /** reusable class for case class instances, can be used to customize "diffable" for specific case classes */
  class CaseClassDiffShow[T, L <: HList](
    implicit
    labelled:  LabelledGeneric.Aux[T, L],
    hlistShow: Lazy[DiffShowFields[L]]
  ) extends DiffShow[T] {
    def show( t: T ) = {
      val fields = hlistShow.value.show( labelled to t ).toList.sortBy( _._1 )
      constructor( t.getClass.getSimpleName, fields )
    }
    def diff( left: T, right: T ) = {
      val fields = hlistShow.value.diff( labelled to left, labelled to right ).toList.sortBy( _._1 ).map {
        case ( name, Different( value ) ) => Some( name -> value )
        case ( name, Error( value ) ) => Some( name -> value )
        case ( name, Identical( _ ) )     => None
      }
      if ( fields.flatten.nonEmpty ) Different(
        constructorOption( left.getClass.getSimpleName, fields )
      )
      else Identical( show( left ) )
    }
  }

}
