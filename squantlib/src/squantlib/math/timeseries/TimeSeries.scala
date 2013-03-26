package squantlib.math.timeseries

import org.jquantlib.time.{Date => qlDate, Period => qlPeriod }
import scala.collection.SortedMap

case class TimeSeries(ts:SortedMap[qlDate, Double]) extends SortedMap[qlDate, Double] {
  
//  val sorted = SortedMap(ts.toSeq:_*)
  
  implicit def sortedMapToTS(smap:SortedMap[qlDate, Double]) = TimeSeries(ts)
	  
  def correlation(series:TimeSeries, nbdays:Int = -1):TimeSeries = 
    Correlation.calculate(ts, series, nbdays)

  def volatility(nbdays:Int = -1, annualdays:Double = 260.0):TimeSeries = 
    Volatility.calculate(ts, if (nbdays > 0) nbdays else ts.size, annualdays)
	  
  def variance(nbDays:Int):TimeSeries = Variance.calculate(ts, nbDays)
	
  def movingaverage(nbDays:Int):TimeSeries = MovingAverage.calculate(ts, nbDays)
		
  def show = ts.foreach(t => println(t._1.shortDate.toString + "\t" + t._2))
  
  override def +[T >: Double](ts2:(qlDate, T)):SortedMap[qlDate, T] = ts + ts2
  
  override def -(key:qlDate):SortedMap[qlDate, Double] = ts.-(key)
  
  override def iterator:Iterator[(qlDate, Double)] = ts.iterator
  
  override def get(key:qlDate):Option[Double] = ts.get(key)
  
  override def rangeImpl(from: Option[qlDate], until: Option[qlDate]) = TimeSeries(ts.rangeImpl(from, until)) 
  
  override def ordering = ts.ordering
	  
}

object TimeSeries {
  
  def apply(ts:Map[qlDate, Double]):TimeSeries = TimeSeries(SortedMap(ts.toSeq : _*))
}