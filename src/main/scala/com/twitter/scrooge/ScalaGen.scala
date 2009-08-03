package com.twitter.scrooge

object ScalaGen {
  def apply(tree: Tree): String = tree match {
    case Document(headers, defs) =>
      filterHeaders(headers).map(apply).mkString("", "\n", "\n") +
        defs.map(apply).mkString("", "\n", "\n")
    case Namespace(namespace, name) =>
      "package " + name
    case Include(filename, document) =>
      "\n" + apply(document) + "\n"
    case Const(name, tpe, value) =>
      "val " + name + ": " + apply(tpe) + " = " + apply(value)
    case Typedef(name, tpe) =>
      "type " + name + " = " + apply(tpe)
    case s @ Struct(name, fields) =>
      genStruct(s)
    case Exception(name, fields) =>
      "case class " + name + "Exception" + genFields(fields) + " extends Exception"
    case Service(name, Some(parent), fns) =>
      "trait " + name + " extends " + parent + genFunctions(fns)
    case Service(name, None, fns) =>
      "trait " + name + genFunctions(fns)
    case Function(name, tpe, args, async, throws) =>
      (if (throws.isEmpty) "" else throws.map { field => apply(field.ftype) }.mkString("@throws(", ",", ") ")) +
        "def " + name + genFields(args) + ": " + apply(tpe)
    case Field(id, name, tpe, default, required, optional) =>
      name + ": " + apply(tpe)
    case Void => "Unit"
    case TBool => "Boolean"
    case TByte => "Byte"
    case TI16 => "Int"
    case TI32 => "Int"
    case TI64 => "Long"
    case TDouble => "Double"
    case TString => "String"
    case TBinary => "Array[Byte]"
    case MapType(ktpe, vtpe, _) => "Map[" + apply(ktpe) + ", " + apply(vtpe) + "]"
    case SetType(tpe, _) => "Set[" + apply(tpe) + "]"
    case ListType(tpe, _) => "Seq[" + apply(tpe) + "]"
    case ReferenceType(name) => name
    case IntConstant(i) => i
    case DoubleConstant(d) => d
    case ConstList(elems) => elems.map(apply).mkString("List(", ", ", ")")
    case ConstMap(elems) => elems.map { case (k, v) => apply(k) + " -> " + apply(v) }.mkString("Map(", ", ", ")")
    case StringLiteral(str) => "\"" + str + "\""
    case Identifier(name) => name
    case Enum(name, values) =>
      "object " + name + " {\n" + values.toString + "\n}\n"
    case catchall => catchall.toString
  }

  def filterHeaders(headers: List[Header]): List[Header] = {
    headers filter {
      case Namespace("scala", _) => true
      case Namespace("java", _) => true
      case Namespace(_, _) => false
      case _ => true
    }
  }

  def genFunctions(functions: List[Function]): String =
    functions.map(apply).mkString(" {\n  ", "\n  ", "\n}")

  def genFields(fields: List[Field]): String =
    fields.map(apply).mkString("(", ", ", ")")

  def genStruct(struct: Struct): String = {
    "case class " + struct.name + struct.fields.map(f => "var " + apply(f)).mkString("(", ", ", ")") + " {\n" +
    "  def this() = this" + struct.fields.map(f => defaultForType(f.ftype)).mkString("(", ", ", ")") + "\n" +
    "\n" +
    struct.fields.map { f =>
      "  val F_" + f.name.toUpperCase + " = " + f.id
    }.mkString("\n") + "\n\n" +
    "  def decode(f: " + struct.name + " => Step): Step = Codec.readStruct(this, f) {\n" +
    struct.fields.map { f =>
      "    case (F_" + f.name.toUpperCase + ", " + constForType(f.ftype) + ") => " +
      decoderForType(f.ftype) + " { v => this." + f.name + " = v; decode(f) }"
    }.mkString("\n") + "\n" +
    "    case (_, ftype) => Codec.skip(ftype) { decode(f) }\n" +
    "  }\n" +
    "\n" +
    "  def encode(buffer: Buffer) {\n" +
    struct.fields.map { f =>
      "    buffer.writeFieldHeader(" + constForType(f.ftype) + ", F_" + f.name.toUpperCase + ")\n" +
      "    " + encoderForType(f.ftype, "this." + f.name)
    }.mkString("\n") + "\n" +
    "  }\n" +
    "}\n"
  }

  def defaultForType(ftype: FieldType): String = ftype match {
    case TBool => "false"
    case TByte => "0.toByte"
    case TI16 => "0"
    case TI32 => "0"
    case TI64 => "0L"
    case TDouble => "0.0"
    case TString => "null"
    case TBinary => "null"
    case MapType(ktpe, vtpe, _) => "mutable.Map.empty[" + apply(ktpe) + ", " + apply(vtpe) + "]"
    case SetType(tpe, _) => "mutable.Set.empty[" + apply(tpe) + "]"
    case ListType(tpe, _) => "List[" + apply(tpe) + "]()"
    case ReferenceType(name) => name + "()"
  }

  def decoderForType(ftype: FieldType): String = ftype match {
    case TBool => "Codec.readBoolean"
    case TByte => "Codec.readByte"
    case TI16 => "Codec.readI16"
    case TI32 => "Codec.readI32"
    case TI64 => "Codec.readI64"
    case TDouble => "Codec.readDouble"
    case TString => "Codec.readString"
    case TBinary => "Codec.readBinary"
    case MapType(ktype, vtype, _) =>
      "Codec.readMap[" + apply(ktype) + ", " + apply(vtype) + "](" + constForType(ktype) + ", " + constForType(vtype) + ") " +
      "{ f => " + decoderForType(ktype) + " { item => f(item) } } " +
      "{ f => " + decoderForType(vtype) + " { item => f(item) } }"
    case ListType(itype, _) =>
      "Codec.readList[" + apply(itype) + "](" + constForType(itype) + ") { f => " + decoderForType(itype) + " { item => f(item) } }"
    case SetType(itype, _) =>
      "Codec.readSet[" + apply(itype) + "](" + constForType(itype) + ") { f => " + decoderForType(itype) + " { item => f(item) } }"
    case ReferenceType(name) =>
      "(new " + name + ").decode"
  }

  def encoderForType(ftype: FieldType, name: String): String = {
    ftype match {
      case TBool => "buffer.writeBoolean(" + name + ")"
      case TByte => "buffer.writeByte(" + name + ")"
      case TI16 => "buffer.writeI16(" + name + ")"
      case TI32 => "buffer.writeI32(" + name + ")"
      case TI64 => "buffer.writeI64(" + name + ")"
      case TDouble => "buffer.writeDouble(" + name + ")"
      case TString => "buffer.writeString(" + name + ")"
      case TBinary => "buffer.writeBinary(" + name + ")"
      case MapType(ktype, vtype, _) =>
        "buffer.writeMapHeader(" + constForType(ktype) + ", "+ constForType(vtype) + ", " + name + ".size); " +
        "for ((k, v) <- " + name + ") { " + encoderForType(ktype, "k") + "; " + encoderForType(vtype, "v") + " }"
      case ListType(itype, _) =>
        "buffer.writeListHeader(" + constForType(itype) + ", " + name + ".size); " +
        "for (item <- " + name + ") { " + encoderForType(itype, "item") + " }"
      case SetType(itype, _) =>
        "buffer.writeSetHeader(" + constForType(itype) + ", " + name + ".size); " +
        "for (item <- " + name + ") { " + encoderForType(itype, "item") + " }"
      case ReferenceType(_) =>
        name + ".encode(buffer)"
    }
  }

  def constForType(ftype: FieldType): String = {
    "Type." + (ftype match {
      case TBool => "BOOL"
      case TByte => "BYTE"
      case TI16 => "I16"
      case TI32 => "I32"
      case TI64 => "I64"
      case TDouble => "DOUBLE"
      case TString => "STRING"
      case TBinary => "STRING"
      case MapType(_, _, _) => "MAP"
      case SetType(_, _) => "SET"
      case ListType(_, _) => "LIST"
      case ReferenceType(_) => "STRUCT"
    })
  }
}