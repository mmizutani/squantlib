package squantlib.pricing.model

import squantlib.model.Market
import squantlib.schedule.payoff.{Payoffs, Payoff}
import squantlib.schedule.{ScheduledPayoffs, Schedule, CalculationPeriod}
import squantlib.pricing.mcengine._
import squantlib.model.fx.FX
import squantlib.model.bond.Bond
import squantlib.util.JsonUtils._
import squantlib.model.rates.DiscountCurve
import squantlib.util.Date
import org.codehaus.jackson.JsonNode
import scala.collection.mutable.{SynchronizedMap, WeakHashMap}

case class FxMc1f(valuedate:Date, 
					  mcengine:Montecarlo1f, 
					  scheduledPayoffs:ScheduledPayoffs, 
					  fx:FX,
					  defaultPaths:Int,
					  trigger:List[Option[Double]],
					  frontierFunction:() => List[Option[Double]],
					  parameterRepository:Any => Unit) extends PricingModel {
  
	mcPaths = defaultPaths
	val redemamt = scheduledPayoffs.bonusAmount.takeRight(trigger.size)

	override def modelPaths(paths:Int):List[List[Double]] = {
	  val mcYears = scheduledPayoffs.eventDateYears(valuedate)
	  val (mcdates, mcpaths) = mcengine.generatePaths(mcYears, paths, p => scheduledPayoffs.price(p, trigger, redemamt))
	  if (mcdates.sameElements(mcYears)) mcpaths
	  else { println("invalid mc dates"); List.empty}
	}
	 
	def mcPrice(paths:Int):List[Double] = {
	  try { 
	    val mpaths = modelPaths(paths)
	    if (mpaths.isEmpty) scheduledPayoffs.price
	    else mpaths.transpose.map(_.sum).map(_ / paths.toDouble) 
	  }
	  catch {case e:Throwable => println("MC calculation error : " + e.getStackTrace.mkString(sys.props("line.separator"))); List.empty}
	}
	
	override def modelForward(paths:Int):List[Double] = modelPaths(paths).transpose.map(_.sum).map(_ / paths)
	  
	override def calculatePrice:List[Double] = calculatePrice(mcPaths)
	
	def calculatePrice(paths:Int):List[Double] = getOrUpdateCache("PRICE", mcPrice(paths))
	
	override def calibrate:FxMc1f = {
	  val frontier = frontierFunction()
	  parameterRepository(frontier)  
	  FxMc1f(valuedate, mcengine, scheduledPayoffs, fx, mcPaths, frontier, frontierFunction, parameterRepository)
	}
	
	override val priceType = "MODEL"
	  
	override val mcEngine = Some(mcengine)
	  
}


object FxMc1f {
	
	var defaultPaths = 300000
	var frontierPaths = 15000
	
	def apply(market:Market, bond:Bond, mcengine:FX => Option[Montecarlo1f]):Option[FxMc1f] = apply(market, bond, mcengine, defaultPaths, frontierPaths)
  
	def apply(market:Market, bond:Bond, mcengine:FX => Option[Montecarlo1f], triggers:List[Option[Double]]):Option[FxMc1f] = apply(market, bond, mcengine, defaultPaths, frontierPaths, triggers)
	
	def apply(market:Market, bond:Bond, mcengine:FX => Option[Montecarlo1f], paths:Int, frontierPths:Int):Option[FxMc1f] = {
	  val trig = bond.getCalibrationCache("FXMontecarlo1f") match {
	    case Some(t:List[Any]) => t.map{
	      case Some(v:Double) => Some(v)
	      case _ => None
	    }.toList
	    case _ => bond.liveTriggers(market.valuedate).map(t => if (t.isEmpty) None else t.head)
	  } 
	  apply(market, bond, mcengine, paths, frontierPths, trig)
	}
	
	def apply(
	    market:Market, 
	    bond:Bond, 
	    mcengine:FX => Option[Montecarlo1f], 
	    paths:Int, 
	    frontierPths:Int,
	    triggers:List[Option[Double]]):Option[FxMc1f] = {
	  
	  val valuedate = market.valuedate
	  
	  val scheduledPayoffs = bond.livePayoffs(valuedate)
	  
	  if (scheduledPayoffs.underlyings.size != 1) { 
	    println(bond.id + " : unsupported variable size for FXMC1 model " + scheduledPayoffs.underlyings.size)
	    return None}
	  
	  val variable = scheduledPayoffs.underlyings.head
	  
	  val fx = market.getFX(variable).orNull
	  
	  if (fx == null) {
	    println(bond.id + " : invalid fx underlying for FXMC1 model - " + variable + " in market " + market.paramset)
	    return None}
	  
	  if (fx.currencyDom != bond.currency) {
	    println(bond.id + " : quanto model not supported by FXMC1 model - " + variable)
	    return None}
	  
	  val mcmodel = mcengine(fx).orNull
	  
	  if (mcmodel == null) {
	    println(bond.id + " : model name not found or model calibration error")
	    return None}
	  
	  val frontierFunction = () => bond.fxFrontiers(1.00, 0.003, 20, frontierPths).map(t => if (t.isEmpty) None else t.head)
	  
	  val paramRepository = (obj:Any) => bond.calibrationCache.update("FXMontecarlo1f", obj)
	  
	  Some(FxMc1f(valuedate, mcmodel, scheduledPayoffs, fx, paths, triggers, frontierFunction, paramRepository))
	}
}


object FxQtoMc1f {
	
	var defaultPaths = 300000
	var frontierPaths = 15000
	
	def apply(market:Market, bond:Bond, mcengine:(FX, FX) => Option[Montecarlo1f]):Option[FxMc1f] = apply(market, bond, mcengine, defaultPaths, frontierPaths)
  
	def apply(market:Market, bond:Bond, mcengine:(FX, FX) => Option[Montecarlo1f], triggers:List[Option[Double]]):Option[FxMc1f] = apply(market, bond, mcengine, defaultPaths, frontierPaths, triggers)
	
	def apply(market:Market, bond:Bond, mcengine:(FX, FX) => Option[Montecarlo1f], paths:Int, frontierPths:Int):Option[FxMc1f] = {
	  val trig = bond.getCalibrationCache("FXMontecarlo1f") match {
	    case Some(t:List[Any]) => t.map{
	      case Some(v:Double) => Some(v)
	      case _ => None
	    }.toList
	    case _ => bond.liveTriggers(market.valuedate).map(t => if (t.isEmpty) None else t.head)
	  } 
	  apply(market, bond, mcengine, paths, frontierPths, trig)
	}
	
	def apply(
	    market:Market, 
	    bond:Bond, 
	    mcengine:(FX, FX) => Option[Montecarlo1f], 
	    paths:Int, 
	    frontierPths:Int,
	    triggers:List[Option[Double]]):Option[FxMc1f] = {
	  
	  val valuedate = market.valuedate
	  
	  val scheduledPayoffs = bond.livePayoffs(valuedate)
	  
	  if (scheduledPayoffs.underlyings.size != 1) { 
	    println(bond.id + " : unsupported variable size for FXMC1 model " + scheduledPayoffs.underlyings.size)
	    return None}
	  
	  val variable = scheduledPayoffs.underlyings.head
	  
	  val fx = market.getFX(variable).orNull
	  
	  if (fx == null) {
	    println(bond.id + " : invalid fx underlying for FXMC1 model - " + variable + " in market " + market.paramset)
	    return None}
	  
	  if (fx.currencyDom == bond.currency) {
	    println(bond.id + " : non-quanto model not supported by FXQtoMC1 model - " + variable)
	    return None}
	  
	  val qtofx = market.getFX(bond.currency.code, fx.currencyDom.code).orNull

	  if (qtofx == null) {
	    println(bond.id + " : invalid fx underlying for quanto model - " + qtofx.id + " in market " + market.paramset)
	    return None}
	  
	  val mcmodel = mcengine(fx, qtofx).orNull
	  
	  if (mcmodel == null) {
	    println(bond.id + " : model name not found or model calibration error")
	    return None}
	  
	  val frontierFunction = () => bond.fxFrontiers(1.00, 0.003, 20, frontierPths).map(t => if (t.isEmpty) None else t.head)
	  
	  val paramRepository = (obj:Any) => bond.calibrationCache.update("FXMontecarlo1f", obj)
	  
	  Some(FxMc1f(valuedate, mcmodel, scheduledPayoffs, fx, paths, triggers, frontierFunction, paramRepository))
	}
}
