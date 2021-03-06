/*
 Copyright (c) 2011, 2012, 2013 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

package Chisel

import Node._
import ChiselError._

abstract trait Num[T <: Data] {
  // def << (b: T): T;
  // def >> (b: T): T;
  def unary_-(): T;
  def +  (b: T): T;
  def *  (b: T): T;
  def /  (b: T): T;
  def %  (b: T): T;
  def -  (b: T): T;
  def <  (b: T): Bool;
  def <= (b: T): Bool;
  def >  (b: T): Bool;
  def >= (b: T): Bool;
}

/** *Data* is part of the *Node* Composite Pattern class hierarchy.
  It is the root of the type system which includes composites (Bundle, Vec)
  and atomic types (UInt, SInt, etc.).

  Instances of Data are meant to help with construction and correctness
  of a logic graph. They will trimmed out of the graph before a *Backend*
  generates target code.
  */
abstract class Data extends Node {
  var comp: proc = null;

  // Interface required by Vec:
  def ===[T <: Data](right: T): Bool = {
    throw new Exception("=== not defined on " + this.getClass
      + " and " + right.getClass)
  }

  def toBits(): UInt = chiselCast(this){UInt()};

  def toBool(): Bool = {
    if(this.getWidth > 1) {
      throw new Exception("multi bit signal " + this + " converted to Bool");
    }
    if(this.getWidth == -1) {
      throw new Exception("unable to automatically convert " + this + " to Bool, convert manually instead")
    }
    chiselCast(this){Bool()};
  }

  // Interface required by Cat:
  def ##[T <: Data](right: T): this.type = {
    throw new Exception("## not defined on " + this.getClass + " and " + right.getClass)
  }


  def setIsTypeNode {
    assert(inputs.length > 0, ChiselError.error("Type Node must have an input"))
    isTypeNode = true
    inferWidth = widthOf(0)
  }

  def apply(name: String): Data = null
  def flatten: Array[(String, Bits)] = Array[(String, Bits)]();
  def terminate(): Unit = { }
  def flip(): this.type = this;
  def asInput(): this.type = this;

  /** Sets the direction (*dir*) of instances derived from Bits to OUTPUT
    or recursively sets members of Bundle/Vec to OUTPUT.
    Returns this instance with its exact type.
    */
  def asOutput(): this.type
  def asDirectionless(): this.type
  def isDirectionless: Boolean = true;

  def toNode: Node = this;

  /** Factory method to create and assign a leaf-type instance out of a subclass
    of *Node* instance which we have lost the concrete type. */
  def fromNode(n: Node): this.type;
  def fromBits(b: Bits): this.type = {
    val n = fromNode(b)
    n.setIsTypeNode
    n
  }

  def :=(that: Data): Unit = that match {
    case b: Bits => this colonEquals b
    case b: Bundle => this colonEquals b
    case b: Vec[_] => this colonEquals b
    case _ => illegalAssignment(that)
  }

  protected def colonEquals(that: Bits): Unit = illegalAssignment(that)
  protected def colonEquals(that: Bundle): Unit = illegalAssignment(that)
  protected def colonEquals[T <: Data](that: Iterable[T]): Unit = illegalAssignment(that)

  protected def illegalAssignment(that: Any): Unit =
    ChiselError.error(":= not defined on " + this.getClass + " and " + that.getClass)

  override def clone(): this.type = {
    try {
      val constructor = this.getClass.getConstructors.head
      val res = constructor.newInstance(Array.fill(constructor.getParameterTypes.size)(null):_*)
      res.asInstanceOf[this.type]
    } catch {
      case npe: java.lang.reflect.InvocationTargetException if npe.getCause.isInstanceOf[java.lang.NullPointerException] =>
        throwException("Parameterized Bundle " + this.getClass + " needs clone method. You are probably using an anonymous Bundle object that captures external state and hence is un-cloneable", npe)
      case e: java.lang.Exception =>
        throwException("Parameterized Bundle " + this.getClass + " needs clone method", e)
    }
  }

  override def nameIt(path: String, isNamingIo: Boolean) {
    if (isTypeNode && comp != null) {
      comp.nameIt(path, isNamingIo)
    } else {
      super.nameIt(path, isNamingIo)
    }
  }

  def setWidth(w: Int) {
    this.width = w;
  }
}

abstract class Aggregate extends Data {
  override def setPseudoName(path: String, isNamingIo: Boolean) {
    if (isTypeNode && comp != null) {
      comp setPseudoName (path, isNamingIo)
    } else {
      super.setPseudoName(path, isNamingIo)
    }
  }
}
