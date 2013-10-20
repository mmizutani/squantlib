package squantlib.model.rates

import squantlib.util.Date
import squantlib.model.yieldparameter.YieldParameter
import org.jquantlib.daycounters.DayCounter
import org.jquantlib.currencies.Currency

/**
 * Encapsulates a curve to be used for cash flow discounting.
 */

trait DiscountableCurve {
  
  val currency : Currency
  val fx : Double
  val valuedate : Date
  
  def shiftRate(shift:(Double, Double) => Double):DiscountableCurve
  def multFX(shift:Double):DiscountableCurve
  
  /** 
   * Builds zero coupon curve using the curve itself as discount currency.
   * @param refinance spread on float rate
   */
  def getZC(spread:YieldParameter) : DiscountCurve
  
  /** 
   * Builds zero coupon curve using external curve as discount currency.
   * Either external curve or this curve must be basis swap pivot curve.
   * @param refinance spread on float rate
   */
  def getZC(discountrate:RateCurve, discountcurve:DiscountCurve) : DiscountCurve
  
  override def toString():String 
}
