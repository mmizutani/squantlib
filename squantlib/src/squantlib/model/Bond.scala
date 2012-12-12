package squantlib.model

import org.jquantlib.currencies.Currency
import org.jquantlib.time.{Date => qlDate, Period => qlPeriod, TimeUnit, _}
import org.jquantlib.termstructures.Compounding
import org.jquantlib.daycounters.{Absolute, Actual365Fixed, Thirty360, DayCounter}
import squantlib.database.schemadefinitions.{Bond => dbBond, BondPrice}
import squantlib.payoff.{Payoffs, Schedule, Payoff, CalcPeriod}
import squantlib.model.rates.DiscountCurve
import squantlib.setting.initializer.{DayAdjustments, Currencies, Daycounters}
import squantlib.util.JsonUtils._
import squantlib.database.fixings.Fixings
import squantlib.pricing.model.{PricingModel, NoModel}
import squantlib.math.solver.NewtonRaphson
import org.codehaus.jackson.JsonNode
import scala.collection.mutable.{Set => mutableSet}

/**
 * Bond class with enclosed risk analysis functions.
 */
class Bond(
		val db:dbBond, 
	    val id:String,	
		val issueDate:qlDate,
		val maturity:qlDate,	
		val currency:Currency,
		val nominal:Double,	
		val denomination:Option[Double],	
		val period:qlPeriod,	
		val daycount:DayCounter,	
		val calendarAdjust:BusinessDayConvention,	
		val paymentAdjust:BusinessDayConvention,	
		val maturityAdjust:BusinessDayConvention,
		val calendar:Calendar,	
		val fixingInArrears:Boolean,	
		val couponNotice:Int,	
		val issuePrice:Option[Double],	
		val call:String,	
		val bondType:String,	
		val initialFX:Double,	
		val issuer:String,	
		val inputSchedule:Schedule,	      
		val coupon:Payoffs,	
		val redemption:Payoff,	
		val settings:Option[JsonNode]) {
  

	/* 
	 * Specify default pricing model
	 */
	protected var _model:Option[PricingModel] = None
	def model:Option[PricingModel] = _model
		
	def initializeModel = {
	  _model = if (modelSetter == null || market.isEmpty) None else modelSetter(market.get, this)
	  cpncache.clear
	}
	
	protected var _modelSetter:(Market, Bond) => Option[PricingModel] = null
	
	def modelSetter = _modelSetter
	def modelSetter_= (newModel:(Market, Bond) => Option[PricingModel]) = {
		_modelSetter = newModel
		initializeModel
	}
	
	protected var _market:Option[Market] = None
	
	/* 
	 * Specify default market parameters
	 */
	def market:Option[Market] = _market
	def market_= (newMarket:Market) = {
	  _market = Some(newMarket)
		initializeModel
	}
	
	def reset(newMarket:Market, setter:(Market, Bond) => Option[PricingModel]) = {
	  _market = Some(newMarket)
	  _modelSetter = setter
	  initializeModel
	}
	
	def valueDate:Option[qlDate] = market.collect{case mkt => mkt.valuedate}
	
	/*
	 * Returns full bond schedule
	 * @returns full bond payment schedule (date only)
	 * @returns full bond payoff (no dates)
	 */
	val (schedule, payoffs):(Schedule, Payoffs) = inputSchedule.sortWith(coupon.toList :+ redemption) match { case (s, p) => (s, Payoffs(p.toList))}

	
	/*	
	 * Returns full bond schedule
	 * @returns list of calculation period & payoff
	 */
	val payoffLegs:List[(CalcPeriod, Payoff)] = (schedule.toList zip payoffs.toList).toList
	
	
	/*	
	 * Returns "live" schedules
	 * 	@returns Schedule containing legs with payment date after market value date or specified value date.
	 */
	def liveSchedule:Schedule = valueDate.collect{case d => liveSchedule(d)}.orNull
	
	def liveSchedule(vd:qlDate):Schedule = Schedule(schedule.toList.filter(_.paymentDate gt vd))
	
	
	/*	
	 * Returns "live" payment schedules
	 * 	@returns element 1: Schedule containing legs with payment date after market value date or specified value date.
	 * 			element 2: Payoffs containing legs with payment dates after market value date or specified value date.
	 */
	def livePayoffs:(Schedule, Payoffs) = valueDate.collect {case d => livePayoffs(d)}.getOrElse((Schedule.empty, Payoffs.empty))
	
	def livePayoffs(vd:qlDate):(Schedule, Payoffs) = {
		val payoffSchedule = payoffLegs.filter{case (cp, p) => (cp.paymentDate gt vd)}
	  
    	val po = payoffSchedule.map{
    	  case (_, payoff) if payoff.variables.size == 0 => payoff
    	  case (period, payoff) if period.eventDate gt vd => payoff
    	  case (period, payoff) => {
    	    val fixings = payoff.variables.map(v => Fixings(v, period.eventDate).collect{
    	      case (d, f) => (v, f)}).flatMap(x => x).toMap
    	    payoff.applyFixing(fixings)
    	    }
    	}
    	(Schedule(payoffSchedule.unzip._1), Payoffs(po))
	}
	
	/*	
	 * Returns "live" payment schedules broken down into pairs of a Calculation Period and a Payoff
	 *  @param value date
	 * 	@returns element 1: Schedule containing legs with payment date after market or specified value date
	 * 			element 2: Payoffs containing legs with payment dates after market or specified value date
	 */
	def livePayoffLegs:List[(CalcPeriod, Payoff)] = livePayoffs match { case (s, p) => (s.toList zip p.toList)}
	
	def livePayoffLegs(vd:qlDate):List[(CalcPeriod, Payoff)] = livePayoffs(vd) match { case (s, p) => (s.toList zip p.toList)}
	
	/*	
	 * Returns discount curve.
	 * 	@returns discount curve created from either pre-set or specified market
	 */
	def discountCurve:Option[DiscountCurve] = market.flatMap(m => m.getDiscountCurve(currency, issuer))
	
	/*	
	 * Returns discount curve.
	 * 	@returns discount curve created from either pre-set or specified market
	 */
	def discountFactors:Option[List[(qlDate, Double)]] = (discountCurve, valueDate) match {
	  case (Some(curve), Some(vd)) => Some(schedule.paymentDates.withFilter(_ gt vd).map(d => (d, curve(d))))
	  case _ => None
	}

	/*
	 * Temporal cache to store spot and forward coupons.
	 */
	private val cpncache = new scala.collection.mutable.WeakHashMap[String, List[(CalcPeriod, Double)]]
	
	
	/*	
	 * Returns coupons fixed with current spot market (not forward!). 
	 */
	def spotFixedRates:List[(CalcPeriod, Double)] = cpncache.getOrElseUpdate("SpotFixedRates", 
	    livePayoffLegs.map{case (d, p) => 
	  (d, market match { case Some(mkt) => p.spotCoupon(mkt) case None => Double.NaN})})
	
	/*	
	 * Returns forward value of each coupon (not discounted)
	 */
	def forwardLegs:Option[List[(CalcPeriod, Double)]] = 
	  if (cpncache contains "ForwardLegs") Some(cpncache("ForwardLegs"))
	  else {
	    val result = valueDate.flatMap { case d => 
	      val (dates, payoff) = livePayoffs(d)
	      val pricemodel = if (payoff.variables.size == 0) Some(NoModel(payoff, dates)) else model
	      pricemodel match {
	      	case None => println(id + " : model calibration error"); None
	      	case Some(mdl) => Some((dates zip mdl.priceLegs).toList)
	    }}
	    if (result.isEmpty || result.get.isEmpty) None
	    else {
	      cpncache("ForwardLegs") = result.get
	      result
	    }
	  }
	    
	
	/*	
	 * Returns price legs of the bond. (including accrued interest)
	 */
	def priceLegs:Option[List[Double]] = (discountCurve, forwardLegs) match {
	  case (Some(curve), Some(fwd)) if !fwd.isEmpty => Some(fwd.map{ case (d, p) => d.coefficient(curve) * p})
	  case _ => None
	}
	
	/*	
	 * Returns dirty price of the bond. (ie. including accrued interest)
	 */
	def dirtyPrice:Option[Double] = priceLegs.collect{case legs => legs.sum}
	
	/*	
	 * Returns clean price of the bond (ie. Dirty price - accrued coupon)
	 */
	def cleanPrice:Option[Double] = (dirtyPrice, accruedAmount) match { case (Some(d), Some(a)) => Some(d - a) case _ => None}
	
	/*	
	 * Returns accrued coupon.
	 */
	def accruedAmount:Option[Double] = market.flatMap(mkt => 
	  payoffLegs.filter{case (d, p) => (d.isCurrentPeriod(mkt.valuedate) && d.daycounter != new Absolute)} match {
	    case pos if pos.isEmpty => None
	    case pos => Some(pos.map{case (d, p) => (d.accrued(mkt.valuedate)) * p.spotCoupon(mkt) }.sum)
	  })

	/*	
	 * Returns current coupon rate.
	 */
	def currentRate:Option[Double] = market collect { case mkt => payoffLegs.withFilter{case (d, p) => (d.isCurrentPeriod(mkt.valuedate) && d.daycounter != new Absolute)}
	  								.map{case (d, p) => p.spotCoupon(mkt) }.sum}

	/*	
	 * Returns accrued coupon.
	 */
	def nextPayment:Option[(qlDate, Double)] = market.collect{case mkt => 
	  payoffLegs.filter{case (d, p) => (d.isCurrentPeriod(mkt.valuedate) && d.daycounter != new Absolute)}
	  .minBy{case (d, p) => d.paymentDate} match {case (d, p) => (d.paymentDate, d.dayCount * p.spotCoupon(mkt))}}
	
	/*	
	 * Returns spot FX rate against JPY
	 */
	def fxjpy:Option[Double] = market.flatMap (mkt => mkt.fx(currency.code, "JPY"))
	
	/*	
	 * Returns JPY dirty price defined as price x FX/FX0, where FX0 = FX as of issue date.
	 */
	def dirtyPriceJpy:Option[Double] = (dirtyPrice, fxjpy, db.initialfx) match { 
	  case (Some(p), Some(fx), init) if init > 0 => Some(p * fx / init)
	  case _ => None
	}
	
	/*	
	 * Returns JPY clean price defined as price x FX/FX0, where FX0 = FX as of issue date.
	 */
	def cleanPriceJpy:Option[Double] = (cleanPrice, fxjpy, db.initialfx) match { 
	  case (Some(p), Some(fx), init) if init > 0 => Some(p * fx / init)
	  case _ => None
	}
	
	/*	
	 * Returns JPY accrued amount defined as accrued x FX/FX0, where FX0 = FX as of issue date.
	 */
	def accruedAmountJpy:Option[Double] = (accruedAmount, fxjpy, db.initialfx) match { 
	  case (Some(p), Some(fx), init) if init > 0 => Some(p * fx / init)
	  case _ => None
	}
	
	/* 
	 * Returns rate at which the MtM of the bond is target price.
	 */
    def getYield(target:Double, dc:DayCounter, comp:Compounding, freq:Frequency, accuracy:Double, maxIteration:Int):Option[Double] = valueDate.flatMap{ case vd =>
      if (comp == Compounding.None) return None
	  
	  val paylegs:List[(Double, Double)] = spotFixedRates.map{case (d, r) => (dc.yearFraction(vd, d.paymentDate), r * d.dayCount)}
	  if (paylegs.exists(_._2.isNaN)) return None
	    
	  def priceFromYield(y:Double):Double = {
	    def zc(d:Double) = comp match {
	      case Compounding.Simple => 1.0 / (1.0 + y * d)
	      case Compounding.Compounded | Compounding.SimpleThenCompounded => {val fr = freq.toInteger.toDouble; 1.0 / math.pow(1.0 + y / fr, fr * d)}
	      case Compounding.Continuous => math.exp(-y * d)
	      } 
	      paylegs.map{case (d, v) => v * zc(d)}.sum
	    }
	    
	    val priceformula = (y:Double) => (priceFromYield(y) - target)
	    NewtonRaphson.solve(priceformula, 0.03, accuracy, 0.01, maxIteration)
	  }
	
	/*	
	 * Returns bond yield.
	 * @param comp Compounding rate, as one of the following
	 * 		"None" => Discounting is not taken into account : ZC = 1.0
	 * 		"Simple" => No compounding : ZC = 1 / rt
	 * 		"Compounded" => Standard compounding: ZC = 1 / (1+r/f)^tf 
	 * 		"Continuous" => 
	 */
	def bondYield(comp:Compounding, freq:Frequency = Frequency.Annual):Option[Double] = bondYield(new Actual365Fixed, comp, freq, 0.00001, 20)
	
    def bondYield(dc:DayCounter, comp:Compounding, freq:Frequency, accuracy:Double, maxIteration:Int):Option[Double] = {
      if (comp == Compounding.None) 
      	market.flatMap {case mkt => 
      	  val fullcashflow:Double = livePayoffLegs.map{case (d, p) => p.spotCoupon(mkt) * d.dayCount case _=> 0.0}.sum
      	  (cleanPrice, accruedAmount) match { 
      	    case (Some(p), Some(a)) => Some((fullcashflow - p - a) / (dc.yearFraction(mkt.valuedate, maturity) * p))
      	    case _ => None
      	  }
      }
      else dirtyPrice.flatMap{case p => getYield(p, dc, comp, freq, accuracy, maxIteration)}
	}
	
	/*	Returns each bond yield.
	 */
	def yieldContinuous:Option[Double] = bondYield(Compounding.Continuous)
	def yieldSemiannual:Option[Double] = bondYield(Compounding.Compounded, Frequency.Semiannual)
	def yieldAnnual:Option[Double] = bondYield(Compounding.Compounded, Frequency.Annual)
	def yieldSimple:Option[Double] = bondYield(Compounding.None, Frequency.Annual)
	
	/*	Returns yield at which bond price becomes 100% (if any)
	 * @param comp Compounding rate, as one of the following
	 * 		"None" => Not applicable
	 * 		"Simple" => No compounding : ZC = 1 / rt
	 * 		"Compounded" => Standard compounding: ZC = 1 / (1+r/f)^tf 	
	 * 		"Continuous" => 
	 */
	def parMtMYield:Option[Double] = getYield(1.0, daycount, Compounding.Continuous, null, 0.00001, 20)
	
	/*	Returns FX at which JPY dirty bond price becomes 100% (if any)
	 */
	def parMtMfx:Option[Double] = dirtyPrice.collect{case p => db.initialfx / p }
	
	/*	
	 * Returns present value of adding 1 basis point of coupon for the remainder of the bond.
	 */
	def bpvalue:Option[Double] = (valueDate, discountCurve) match {
	  case (Some(vd), Some(curve)) => Some(livePayoffs._1.map{
	    case d if d.daycounter == new Absolute => 0.0
	    case d => d.dayCountAfter(vd) * curve(d.paymentDate)
	  }.sum * 0.0001) 
	  case _ => None
	}
	
	/*	
	 * Internal Rate of Return, defined to be the same as annually compounded yield.
	 */
    def irr:Option[Double] = irr(daycount, 0.00001, 20)
	
    def irr(dc:DayCounter, accuracy:Double, maxIteration:Int):Option[Double] = yieldAnnual
    
    /*
     * Yield value of a basis point The yield value of a one basis point change
     * in price is the derivative of the yield with respect to the price
     */
    def yieldValueBasisPoint:Option[Double] = (dirtyPrice, modifiedDuration) match {
      case (Some(p), Some(dur)) => Some(1.0 / (-p * dur))
      case _ => None
    }
    
	/*	
	 * Returns Macauley duration defined as Sum {tV} / Sum{V}
	 */
	def macaulayDuration:Option[Double] = (valueDate, discountCurve) match {
	  case (Some(vd), Some(curve)) => {
	  	val (yearfrac, price):(List[Double], List[Double]) = spotFixedRates.map{case (d, r) => 
	  	  (daycount.yearFraction(vd, d.paymentDate), r * d.coefficient(curve))}.unzip
	  	Some((yearfrac, price).zipped.map(_ * _).sum / price.sum)
	  }
	  case _ => None
	}
	
	/*	
	 * Returns modified duration defined as Macauley duration / (1 + yield / freq)
	 */
	def modifiedDuration:Option[Double] = modifiedDuration(Compounding.Compounded, Frequency.Annual)
	def modifiedDuration(comp:Compounding, freq:Frequency):Option[Double] = macaulayDuration.flatMap { case dur =>
	    comp match {
	      case Compounding.Continuous => Some(dur)
	      case Compounding.Compounded | Compounding.SimpleThenCompounded => bondYield(comp, freq).collect{case y => dur / (1.0 + y / freq.toInteger.toDouble)}
	      case Compounding.Simple => None
	  }
	}
	
	/*	
	 * Returns modified duration defined as rate delta
	 */
	def effectiveDuration:Option[Double] = rateDelta(-0.0001).collect{case d => d * 10000} // TO BE COMPUTED AS RATE DELTA
	
	/*	
	 * List of underlying currencies
	 */
	def currencyList:Set[String] = {
	  var result = scala.collection.mutable.Set.empty[String]
	  livePayoffs._2.variables.foreach {
	    case c if c.size == 6 => if (Currencies contains (c take 3)) result += (c take 3)
			  				  if (Currencies contains (c takeRight 3)) result += (c takeRight 3)
	    case c if c.size >= 3 => if (Currencies contains (c take 3)) result += (c take 3)
	    case _ => {}
	  }
	  (result + currency.code).toSet
	}
	
	/*	
	 * Returns rate delta
	 */
	def rateDelta(shift:Double):Option[Double] = rateDelta(currency.code, shift)
	
	def rateDelta(ccy:String, shift:Double):Option[Double] = rateDelta((b:Bond) => b.dirtyPrice, Map(ccy -> shift))
	  
	def rateDelta(target:Bond => Option[Double], shift:Map[String, Double]):Option[Double] = market.flatMap { case mkt =>
	  val initmkt = mkt
	  val shiftedmkt:Market = mkt.rateShifted(shift)
	  this.market = shiftedmkt
	  val newprice = target(this)
	  this.market = initmkt
	  val initprice = target(this)
	  (initprice, newprice) match { case (Some(i), Some(n)) => Some(n - i) case _ => None }
	}
	
	/*	
	 * Returns rate delta for all involved currencies.
	 */
	def rateDeltas(shift:Double):Map[String, Double] = currencyList.map(f => (f, rateDelta(f, shift))).collect{case (a, Some(b)) => (a, b)}.toMap
	
	/*	
	 * Returns FX delta on JPY bond price.
	 */
	def fxDeltaJpy(mult:Double):Map[String, Double] = (currencyList - "JPY").map(f => 
	  (f + "JPY", fxDelta((b:Bond) => b.dirtyPriceJpy, Map(f -> 1/mult)))).collect{case (a, Some(b)) => (a, b)}.toMap
	
	/*	
	 * Returns delta of 1 yen change in FX on JPY price.
	 */
	def fxDeltaOneJpy:Map[String, Double] = market match {
	  case None => Map.empty
	  case Some(mkt) => (currencyList - "JPY").map(f => mkt.fx(f, "JPY") match {
	      case Some(fx) => (f + "JPY", fxDelta((b:Bond) => b.dirtyPriceJpy, Map(f -> fx/(fx+1))))
	      case None => (f + "JPY", None)
	    }).collect{case (a, Some(b)) => (a, b)}.toMap}
	    
	/*	
	 * Returns FX delta for all involved currencies.
	 */
	def fxDeltas(mult:Double):Map[String, Double] = (currencyList - currency.code).map(ccy => ("USD" + ccy, fxDelta((b:Bond) => b.dirtyPrice, Map(ccy -> mult)))).collect{case (a, Some(b)) => (a, b)}.toMap
	
	def fxDelta(ccy:String, mult:Double):Option[Double] = fxDelta((b:Bond) => b.dirtyPrice, Map(ccy -> mult))
	  
	def fxDelta(target:Bond => Option[Double], mult:Map[String, Double]):Option[Double] = market.flatMap { case mkt =>
	  val initmkt = mkt
	  val shiftedmkt:Market = mkt.fxShifted(mult)
	  this.market = shiftedmkt
	  val newprice = target(this)
	  this.market = initmkt
	  val initprice = target(this)
	  (initprice, newprice) match { case (Some(i), Some(n)) => Some(n - i) case _ => None }
	}
	
	/*	
	 * List of FX underlyings
	 */
	def fxList:Set[String] = livePayoffs._2.variables.filter(
	  c => ((c.size == 6) && (Currencies contains (c take 3)) && (Currencies contains (c takeRight 3))))

	/*	
	 * Returns rate vega
	 */
	def fxVegas(addvol:Double):Map[String, Double] = fxList.map(fx => (fx, fxVega(fx, addvol))).collect{case (a, Some(b)) => (a, b)}.toMap
	
	def fxVega(ccypair:String, addvol:Double):Option[Double] = fxVega((b:Bond) => b.dirtyPrice, Map(ccypair -> addvol))
	  
	def fxVega(target:Bond => Option[Double], addvol:Map[String, Double]):Option[Double] = market.flatMap { case mkt =>
	  val initmkt = mkt
	  val shiftedmkt:Market = mkt.fxVolShifted(addvol)
	  this.market = shiftedmkt
	  val newprice = target(this)
	  this.market = initmkt
	  val initprice = target(this)
	  (initprice, newprice) match { case (Some(i), Some(n)) => Some(n - i) case _ => None }
	}
	
	/*
     * Cash-flow convexity
     * The convexity of a string of cash flows is defined as {@latex[ C = \frac{1}{P} \frac{\partial^2 P}{\partial y^2} } where
     * {@latex$ P } is the present value of the cash flows according to the given IRR {@latex$ y }.
     */
    def convexity(comp:Compounding, freq:Frequency = Frequency.Annual):Option[Double] = (valueDate, discountCurve) match {
	  case (Some(vd), Some(discount)) => 
	    val currentRate:Double = if (comp == Compounding.Compounded) bondYield(comp, freq).getOrElse(Double.NaN) else 0.0
	    if (currentRate.isNaN) return None
	    val f:Double = freq.toInteger.toDouble
	    val dc = new Actual365Fixed
	    val (p, d2Pdy2) = spotFixedRates.map{case (d, r) => 
	      val t = dc.yearFraction(vd, d.paymentDate)
	      val c = r * d.dayCount
	      val B = discount(d.paymentDate)
	      val d2:Double = comp match {
	        case Compounding.Simple => c * 2.0 * B * B * B * t * t
	        case Compounding.Compounded | Compounding.SimpleThenCompounded => c * B * t * (f * t + 1) / (f * (1 + currentRate / f) * (1 + currentRate / f))
	        case Compounding.Continuous => c * B * t * t
	      }
	      (c * B, d2)
	      }.unzip
	    if (p.sum == 0) None else Some(d2Pdy2.sum / p.sum)
	    
	  case _ => None
	}
	
	def convexity:Option[Double] = convexity(Compounding.Continuous, Frequency.Annual)
	
    /*
     * Remaining life in number of years
     */
	def remainingLife:Option[Double] = valueDate.collect{ case d => (new Actual365Fixed).yearFraction(d, maturity)}
	
	
    /*
     * Output to BondPrice object
     */
	import org.codehaus.jackson.node.{JsonNodeFactory, ObjectNode, ArrayNode}
	import org.codehaus.jackson.map.ObjectMapper
	
	def mapToJsonString(params:Map[String, Double]):String = (new ObjectMapper).writeValueAsString(params)
	
	def toBondPrice:Option[BondPrice] = (market, cleanPrice) match {
	  case (Some(mkt), Some(p)) => Some(new BondPrice(
			  		id = id + ":" + mkt.paramset + ":" + currency.code,
					bondid = id,
					currencyid = currency.code,
					comment = null,
					paramset = mkt.paramset,
					paramdate = mkt.valuedate.longDate,
					fxjpy = fxjpy.getOrElse(0),
					pricedirty = dirtyPrice.collect{case p => p * 100}.getOrElse(Double.NaN),
					priceclean = cleanPrice.collect{case p => p * 100},
					accrued = accruedAmount.collect{case p => p * 100},
					pricedirty_jpy = dirtyPriceJpy.collect{case p => p * 100},
					priceclean_jpy = cleanPriceJpy.collect{case p => p * 100},
					accrued_jpy = accruedAmountJpy.collect{case p => p * 100},
					yield_continuous = yieldContinuous,
					yield_annual = yieldAnnual,
					yield_semiannual = yieldSemiannual,
					yield_simple = yieldSimple,
					bpvalue = bpvalue.collect{case p => p * 100},
					irr = irr,
					currentrate = currentRate,
					nextamount = nextPayment.collect{case (d, p) => p * 100},
					nextdate = nextPayment.collect{case (d, p) => d.longDate},
					dur_simple = effectiveDuration,
					dur_modified = modifiedDuration,
					dur_macauley = macaulayDuration,
					yieldvaluebp = yieldValueBasisPoint,
					convexity = convexity,
					remaininglife = remainingLife,
					parMtMYield = parMtMYield,
					parMtMfx = parMtMfx,
					rateDelta = mapToJsonString(rateDeltas(0.001)),
					rateVega = null,
					fxDelta = mapToJsonString(fxDeltas(1.01)),
					fxDeltaJpy = mapToJsonString(fxDeltaOneJpy),
					fxVega = mapToJsonString(fxVegas(0.01)),
					created = Some(new java.sql.Timestamp(java.util.Calendar.getInstance.getTime.getTime)),
					lastmodified = Some(new java.sql.Timestamp(java.util.Calendar.getInstance.getTime.getTime))))
	  
	  case _ => None
	}
	
	override def toString:String = "ID: " + id + "\n"
	
	def show:Unit = {
	    println("Id:" + id)
	    println("Currency: " + currency.code)
	    println("Default Model: " + (model match { case None => "Not defined" case Some(m) => m.getClass.getName}))
	    println("Default Market: " + (market match { case None => "Not defined" case Some(m) => m.paramset}))
	    
	    if (market isDefined) {
	      println("Remaining payoffs") 
	      livePayoffLegs.foreach{case (s, po) => println(s + " " + po)}
	    }
	    else {
	      println("Full Schedule:")
		  payoffLegs.foreach{case (s, po) => println(s + " " + po)}
	    }
	  }
	
	def showAll:Unit = {
	  def disp(name:String, f: => Any) = println(name + "\t" + (f match {
	    case s:Option[Any] => s.getOrElse("None")
	    case s => s.toString}))
	  disp("currency", currency.code)
	  disp("paramset", market.collect{case m => m.paramset})
	  disp("paramdate", market.collect{case m => m.valuedate.longDate})
	  disp("fx jpy spot", fxjpy)
	  disp("dirty price", dirtyPrice)
	  disp("clean price", cleanPrice)
	  disp("accrued cpn", accruedAmount)
	  disp("dirty JPY", dirtyPriceJpy)
	  disp("clean JPY", cleanPriceJpy)
	  disp("accruedJpy", accruedAmountJpy)
	  disp("yieldCont", yieldContinuous)
	  disp("yieldAnnual", yieldAnnual)
	  disp("yieldSemi", yieldSemiannual)
	  disp("yieldSimple", yieldSimple)
	  disp("bpValue  ", bpvalue)
	  disp("irr\t", irr)
	  disp("currentRate", currentRate)
	  disp("next amount", nextPayment)
	  disp("effectiveDuration", effectiveDuration)
	  disp("modified duration", modifiedDuration)
	  disp("macaulay duration", macaulayDuration)
	  disp("yieldValuebp", yieldValueBasisPoint)
	  disp("convexity", convexity)
	  disp("remainLife", remainingLife)
	  disp("parMtMYield", parMtMYield)
	  disp("parMtMfx", parMtMfx)
	  disp("rate delta 1%", mapToJsonString(rateDeltas(0.001)))
	  disp("fx delta 1%", mapToJsonString(fxDeltas(1.01)))
	  disp("fx delta 1yen", mapToJsonString(fxDeltaOneJpy))
	  disp("fx vega 1%", mapToJsonString(fxVegas(0.01)))
	  disp("initialfx", initialFX)
	  
	}
	
	/*	Returns message returned by pricing model.
	 */
	def modelmsg:Unit = model match { case None => {} case Some(m) => m.message.foreach(println) }
	
} 


object Bond {
  
	def apply(db:dbBond):Option[Bond] = {
	  
		val defaultDayCounter:DayCounter = new Thirty360
		val defaultAdjustment:BusinessDayConvention = BusinessDayConvention.ModifiedFollowing
		
		val id = db.id
		
		val issueDate:qlDate = new qlDate(db.issuedate)
		
		val maturity:qlDate = new qlDate(db.maturity)
		
		val nominal:Double = db.nominal
		
		val currency:Currency = Currencies(db.currencyid).orNull
		if (currency == null) { println(db.id  + " : currency not found"); return None}
		
		val denomination:Option[Double] = db.denomination
		
		val period:qlPeriod = (db.coupon_freq collect { case f => new qlPeriod(f, TimeUnit.Months)}).orNull
		if (period == null) { println(db.id  + " : period not defined"); return None}
		
		val daycount:DayCounter = Daycounters(db.daycount).getOrElse(defaultDayCounter)
		
		val calendarAdjust:BusinessDayConvention = DayAdjustments.getOrElse(db.daycount_adj, defaultAdjustment)
		
		val paymentAdjust:BusinessDayConvention = DayAdjustments.getOrElse(db.payment_adj, defaultAdjustment)
		
		val maturityAdjust:BusinessDayConvention = DayAdjustments.getOrElse(db.daycount_adj, defaultAdjustment)
	
		val calendar:Calendar = db.calendar
		
		val fixingInArrears:Boolean = db.inarrears.isDefined && db.inarrears == 0
		
		val couponNotice:Int = db.cpnnotice.getOrElse(5)
		
		val issuePrice:Option[Double] = db.issueprice
		
		val call:String = db.call
		
		val bondType:String = db.bondtype
		
		val initialFX:Double = db.initialfx
		
		val rule:DateGeneration.Rule = DateGeneration.Rule.Backward
		
		val firstDate:Option[qlDate] = None
		 
		val nextToLastdate:Option[qlDate] = None
		
		val issuer:String = db.issuerid
		
		val redemnotice = db.redemnotice.getOrElse(10)
		
		val schedule:Schedule = try {
			Schedule(issueDate, maturity, period, calendar, calendarAdjust, paymentAdjust, 
			    maturityAdjust, rule, fixingInArrears, couponNotice, daycount, firstDate, 
			    nextToLastdate, true, redemnotice)}
			  catch { case _ => null}
		      
		if (schedule == null) { println(db.id  + " : schedule cannot be initialized"); return None}

		val coupon:Payoffs = if (db.coupon == null || db.coupon.isEmpty) null
			else Payoffs(db.coupon, schedule.size - 1)
			
		if (db.redemprice.isEmpty) {return None}
		val redemption:Payoff = Payoff(db.redemprice)
		
		if (coupon == null) {println(id + " : coupon not defined"); return None}
		if (coupon.size + 1 != schedule.size) {println(id + " : coupon (" + (coupon.size+1) + ") and schedule (" + schedule.size + ")  not compatible"); return None}
		
		val settings:Option[JsonNode] = db.settings.jsonNode
		
		Some(new Bond(db, id, issueDate, maturity, currency, nominal, denomination, period, daycount,	
			calendarAdjust,	paymentAdjust,	maturityAdjust, calendar, fixingInArrears, couponNotice,	
			issuePrice,	call, bondType,	initialFX,	issuer,	schedule, coupon, redemption, settings))
	  
	}
  
}
	
