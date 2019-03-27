package com.tencent.angel.sona.common.params

import org.apache.spark.ml.param.{Param, ParamPair}
import org.apache.spark.ml.util.Identifiable
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods.{compact, parse, render}

class JObjectParam(parent: String, name: String, doc: String, isValid: JObject => Boolean)
  extends Param[JObject](parent, name, doc, isValid) {
  def this(parent: String, name: String, doc: String) =
    this(parent, name, doc, (value:JObject) => value != null)

  def this(parent: Identifiable, name: String, doc: String, isValid: JObject => Boolean) =
    this(parent.uid, name, doc, isValid)

  def this(parent: Identifiable, name: String, doc: String) = this(parent.uid, name, doc)

  override def w(value: JObject): ParamPair[JObject] = super.w(value)

  override def jsonEncode(value: JObject): String = {
    compact(render(value))
  }

  override def jsonDecode(json: String): JObject = {
    implicit val formats: DefaultFormats = DefaultFormats
    parse(json).asInstanceOf[JObject]
  }
}
