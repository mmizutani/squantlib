
//val valuedate = new org.jquantlib.time.Date(8, 5, 2012)
val paramset = "20120508-000"

try {
	"Paramset : "  + paramset
}
catch { case e => {
  println("You must define the following paramter")
  println("paramset : String")}
}



/**
 * Creates factory from given paramset.
 * Define following parameters in advance.
 *  val valuedate:Date => market value date
 *  val paramset:String => parameter id
 */

import squantlib.database._
import squantlib.database.schemadefinitions.{ Bond => dbBond, _}
import squantlib.database.objectconstructor._
import squantlib.model.discountcurve._
import org.jquantlib.time._
import org.squeryl.PrimitiveTypeMode._
import org.jquantlib.instruments.bonds.FixedRateBond
import squantlib.database.utilities._
import org.jquantlib.instruments.Bond

val t1 = System.nanoTime

var errorlist = scala.collection.mutable.Map.empty[String, String]
val factory = QuickConstructor.getDiscountCurveFactory(paramset)
val valuedate = factory.valuedate

val t2 = System.nanoTime

val dbbonds = QuickConstructor.getDBBonds
val bond_product:Map[String, String] = dbbonds.map(b => (b._1, b._2.productid)).toMap;

val t3 = System.nanoTime

val bonds:Map[String, org.jquantlib.instruments.Bond] = {
  val fixedrateproducts = Set("SB", "STEPUP", "DISC")
  val fixedrateids = bond_product.filter(b => fixedrateproducts.contains(b._2)).keySet
  val fixedratebuilder = { b:dbBond => {
    val bond = b.toFixedRateBond
    if (bond != null) bond.setPricingEngine(factory.getdiscountbondengine(bond), valuedate)
    bond
  }}
  val fixedratebonds = QuickConstructor.getBonds(fixedrateids, valuedate, fixedratebuilder)
  
  fixedratebonds
}

val pricelist:Map[String, Double] = bonds.map(b => (b._1, try {b._2.dirtyPrice} catch {case e => {errorlist += (b._1 -> e.toString); Double.NaN}})).toMap.filter(b => !b._2.isNaN)


val prices = pricelist.map { p => {
  val bond = bonds(p._1)
  new BondPrice(
		id = bond.bondid + ":" + factory.paramset + ":" + bond.currency.code,
		bondid = bond.bondid,
		currencyid = bond.currency.code,
		underlyingid = bond.bondid,
		comment = null,
		paramset = factory.paramset,
		paramdate = factory.valuedate.longDate,
		fxjpy = factory.curves(bond.currency.code).fx,
		pricedirty = p._2,
		created = Some(valuedate.longDate),
		lastmodified = Some(java.util.Calendar.getInstance.getTime),
		accrued = Some(0.0),
		currentrate = Some(0.0),
		instrument = "BONDPRICE"
      )
}}

val t4 = System.nanoTime

println("\nWriting to Database...")
transaction {DB.bondprices.deleteWhere(b => b.paramset === paramset)}
transaction {DB.bondprices.insert(prices)}

val t5 = System.nanoTime

println("\n*** Input ***")
println("valuedate :\t" + valuedate.shortDate)
println("paramset :\t" + paramset)

println("\n*** Created Variables ***")
println("dbbonds => all bonds")
println("factory => discount curve factory")
println("fixedratebonds => id ->  fixed rate bonds")
println("pricelist => bondid -> bond price")
println("errorlist => bondid -> error message")

println("\n*** Result ***")
println(dbbonds.size + " bonds")
println("%-10.10s %-8.8s %-8.8s %-8.8s".format("PRODUCT", "PRICED", "ERROR", "EXPIRED"))
val resultsummary = bond_product.groupBy(p => p._2).map{ p => {
  val vdlong = valuedate.longDate
  val validprices = pricelist.filter(c => !c._2.isNaN).filter(c => p._2.contains(c._1)).size
  val expired = bond_product.filter(b => b._2 == p._1).filter(b => dbbonds(b._1).maturity.compareTo(vdlong) <= 0).size
  val invalidprices = p._2.size - validprices - expired
  (p._1, validprices, invalidprices, expired)
}}

resultsummary.foreach { s => {
	println("%-10.10s %-8.8s %-8.8s %-8.8s".format(s._1, s._2, s._3, s._4))
}}
println("%-10.10s %-8.8s %-8.8s %-8.8s".format("TOTAL", resultsummary.map(r => r._2).sum, resultsummary.map(r => r._3).sum, resultsummary.map(r => r._4).sum))

val t6 = System.nanoTime

println("")
println("%-27.27s %.3f sec".format("Total process time:", ((t6 - t1)/1000000000.0)))
println("  %-25.25s %.3f sec".format("Factory construction:", ((t2 - t1)/1000000000.0)))
println("  %-25.25s %.3f sec".format("Bond collection:", ((t3 - t2)/1000000000.0)))
println("  %-25.25s %.3f sec".format("Bond pricing:", ((t4 - t3)/1000000000.0)))
println("  %-25.25s %.3f sec".format("db write:", ((t5 - t4)/1000000000.0)))
println("  %-25.25s %.3f sec".format("Result display:", ((t6 - t5)/1000000000.0)))
println("\n*** System Output ***")
