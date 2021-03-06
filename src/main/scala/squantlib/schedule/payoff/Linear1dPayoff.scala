package squantlib.schedule.payoff

import scala.collection.JavaConversions._
import squantlib.util.DisplayUtils._
import squantlib.util.JsonUtils._
import org.codehaus.jackson.map.ObjectMapper

/**
 * Interprets JSON forimport squantlib.schedule.payoff.Payoff
mula specification for a linear formula with cap & floor.
 * JSON format:
 * - {type:"linear1d", variable:string, payoff:formula}, where
 *   formula = {min:double, max:double, mult:double, add:double, description:XXX}
 *   payment for array(i) is min <= mult * variable + add <= max
 */
case class Linear1dPayoff(
    variable:String, 
    payoff:Linear1dFormula, 
    description:String) extends Payoff {
  
	override val variables:Set[String] = if (variable == null) Set.empty else Set(variable)
	
	override val isPriceable = payoff.coeff.collect{case c => !c.isNaN}.getOrElse(true) && payoff.constant.collect{case c => !c.isNaN}.getOrElse(true)
	
	override def priceImpl(fixings:Map[String, Double]) = fixings.get(variable) match {
	  case Some(v) if !v.isNaN && !v.isInfinity => payoff.price(v)
	  case _ => Double.NaN
	}
	
	override def priceImpl(fixing:Double) = if (fixing.isNaN || fixing.isInfinity) Double.NaN else payoff.price(fixing)
	
	override def priceImpl = Double.NaN
	
	override def toString:String = payoff.toString(variable)
	
	override def jsonString = {
	    
	  val jsonPayoff:java.util.Map[String, Any] = Map(
	      "min" -> payoff.minValue.getOrElse("None"),
	      "max" -> payoff.maxValue.getOrElse("None"),
	      "coeff" -> payoff.coeff.getOrElse("None"),
	      "description" -> payoff.description)
	      
	  val infoMap:java.util.Map[String, Any] = Map(
	      "type" -> "linear1d", 
	      "variable" -> variable, 
	      "payoff" -> jsonPayoff)
	  
	  (new ObjectMapper).writeValueAsString(infoMap)	  
	}
}

object Linear1dPayoff {
  
	def apply(formula:String):Linear1dPayoff = {
	  val variable:String = formula.parseJsonString("variable").orNull
	  val payoff:Linear1dFormula = formula.parseJsonObject("payoff", Linear1dFormula(_)).orNull
	  Linear1dPayoff(variable, payoff, null)
	}
	
	def apply(variable:String, payoff:Map[String, Any]):Linear1dPayoff = Linear1dPayoff(variable, Linear1dFormula(payoff), null)
	
	
	def apply(variable:String, coeff:Option[Double], constant:Option[Double], minValue:Option[Double], maxValue:Option[Double], description:String = null):Linear1dPayoff = 
	  Linear1dPayoff(variable, Linear1dFormula(coeff, constant, minValue, maxValue, description), description)
}

case class Linear1dFormula (val coeff:Option[Double], val constant:Option[Double], val minValue:Option[Double], val maxValue:Option[Double], val description:String) {

	def price(fixing:Double):Double = {
	  var p = (coeff, constant) match {
	    case (None, None) => 0.0
		case (None, Some(c)) => c
		case (Some(x), None) => x * fixing
		case (Some(x), Some(c)) => x * fixing + c
	  }
	  
	  if (minValue.isDefined) p = p.max(minValue.get)
	  if (maxValue.isDefined) p = p.min(maxValue.get)
	  p
	} 
	
	def toString(varname:String) = 
	  linearFormula(coeff, varname, constant) + minValue.asPercentOr("", " >", "") + maxValue.asPercentOr("", " <", "")
									
}

object Linear1dFormula {
	def apply(parameters:Map[String, Any]):Linear1dFormula = {
		val minValue:Option[Double] = parameters.getDouble("min")
		val maxValue:Option[Double] = parameters.getDouble("max")
		val coeff:Option[Double] = Some(parameters.getDouble("mult").getOrElse(1.0))
		val constant:Option[Double] = parameters.getDouble("add")
		val description:String = parameters.getString("description").orNull
		Linear1dFormula(coeff, constant, minValue, maxValue, description)
	}
	
}
