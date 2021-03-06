package squantlib.pricing.model

import squantlib.model.Market
import squantlib.schedule.{CalculationPeriod, Schedule, ScheduledPayoffs}
import squantlib.schedule.payoff.{Payoff, Payoffs}
import squantlib.pricing.mcengine._
import squantlib.model.index.Index
import squantlib.model.bond.Bond
import squantlib.model.fx.FX
import squantlib.util.Date
import squantlib.util.JsonUtils._
import org.codehaus.jackson.JsonNode
import squantlib.model.rates.DiscountCurve
import scala.collection.mutable.{SynchronizedMap, WeakHashMap}

case class IndexMc1f(valuedate:Date, 
					  mcengine:Montecarlo1f, 
					  scheduledPayoffs:ScheduledPayoffs, 
					  index:Index,
					  defaultPaths:Int) extends PricingModel {
  
	mcPaths = defaultPaths

	override def modelPaths(paths:Int):List[List[Double]] = {
	  val mcYears = scheduledPayoffs.eventDateYears(valuedate)
	  if (mcYears.exists(_ < 0.0)) {println("MC paths : cannot compute past dates"); List.empty}
	  val (mcdates, mcpaths) = mcengine.generatePaths(mcYears, paths, p => scheduledPayoffs.price(p))
	  if (mcdates.sameElements(mcYears)) mcpaths
	  else { println("invalid mc dates"); List.empty}
	}
	 
	def mcPrice(paths:Int):List[Double] = {
	  try { 
	    val mpaths = modelPaths(paths)
	    if (mpaths.isEmpty) scheduledPayoffs.price
	    else mpaths.transpose.map(_.sum / paths.toDouble) 
	  }
	  catch {case e:Throwable => println("MC calculation error : " + e.getStackTrace.mkString(sys.props("line.separator"))); List.empty}
	}
	
	override def calculatePrice:List[Double] = calculatePrice(mcPaths)
	
	def calculatePrice(paths:Int):List[Double] = getOrUpdateCache("PRICE", mcPrice(paths))
	
	override def modelForward(paths:Int):List[Double] = modelPaths(paths).transpose.map(_.sum).map(_ / paths)
	
	override val priceType = "MODEL"
	  
	override val mcEngine = Some(mcengine)
}


object IndexMc1f {
	
	var defaultPaths = 200000
	
	def apply(market:Market, bond:Bond, mcengine:Index => Option[Montecarlo1f]):Option[IndexMc1f] = apply(market, bond, mcengine, defaultPaths)
	
	def apply(
	    market:Market, 
	    bond:Bond, 
	    mcengine:Index => Option[Montecarlo1f], 
	    paths:Int):Option[IndexMc1f] = {
	  
	  val valuedate = market.valuedate
	  
	  val scheduledPayoffs = bond.livePayoffs(valuedate)
	  
	  if (scheduledPayoffs.underlyings.size != 1) { 
	    println(bond.id + " : payoff not compatible with Index1d model")
	    return None}
	  
	  if (scheduledPayoffs.calls.isBermuda) { 
	    println(bond.id + " : callability not supported on Index1d model")
	    return None}
	  
	  val variable = scheduledPayoffs.underlyings.head
	  
	  val index = market.getIndex(variable).orNull
	  
	  if (index == null) {
	    println(bond.id + " : invalid index underlying - " + variable + " in market " + market.paramset)
	    return None}
	  
	  if (index.currency != bond.currency) {
	    println(bond.id + " : quanto product not supported by this model IndexMc1f - " + variable)
	    return None}
	  
	  val mcmodel = mcengine(index).orNull
	  
	  if (mcmodel == null) {
	    println(bond.id + " : model name not found or model calibration error")
	    return None}
	  
	  Some(IndexMc1f(valuedate, mcmodel, scheduledPayoffs, index, paths))
	}
}

object IndexQtoMc1f {
	
	var defaultPaths = 200000
	
	def apply(market:Market, bond:Bond, mcengine:(Index, FX) => Option[Montecarlo1f]):Option[IndexMc1f] = apply(market, bond, mcengine, defaultPaths)
	
	def apply(
	    market:Market, 
	    bond:Bond, 
	    mcengine:(Index, FX) => Option[Montecarlo1f], 
	    paths:Int):Option[IndexMc1f] = {
	  
	  val valuedate = market.valuedate
	  
	  val scheduledPayoffs = bond.livePayoffs(valuedate)
	  
	  if (scheduledPayoffs.underlyings.size != 1) { 
	    println(bond.id + " : payoff not compatible with Index1d model")
	    return None}
	  
	  if (scheduledPayoffs.calls.isBermuda) { 
	    println(bond.id + " : callability not supported on Index1d model")
	    return None}
	  
	  val variable = scheduledPayoffs.underlyings.head
	  
	  val index = market.getIndex(variable).orNull
	  
	  if (index == null) {
	    println(bond.id + " : invalid index underlying - " + variable + " in market " + market.paramset)
	    return None}
	  
	  if (index.currency == bond.currency) {
	    println(bond.id + " : non-quanto products not supported by this model IndexQtoMc1f - " + variable)
	    return None}
	  
	  val fx = market.getFX(bond.currency.code, index.currency.code).orNull

	  if (fx == null) {
	    println(bond.id + " : invalid fx underlying for quanto model - " + fx.id + " in market " + market.paramset)
	    return None}
	  
	  val mcmodel = mcengine(index, fx).orNull
	  
	  if (mcmodel == null) {
	    println(bond.id + " : model name not found or model calibration error")
	    return None}
	  
	  Some(IndexMc1f(valuedate, mcmodel, scheduledPayoffs, index, paths))
	}
}







