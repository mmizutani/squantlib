package squantlib.database.schemadefinitions

import java.util.Date
import org.squeryl.annotations.Column
import org.squeryl.KeyedEntity

class Correlation(@Column("ID")				override var id:String,
              @Column("Underlying1Asset")	var underlying1asset:String,
              @Column("Underlying1ID")		var underlying1id:String,
              @Column("Underlying2Asset")	var underlying2asset:String,
              @Column("Underlying2ID")		var underlying2id:String,
              @Column("ValueDate")			var valuedate:Date,
              @Column("Periodicity")		var periodicity:Int,
              @Column("NbDays")				var nbdays:Int,
              @Column("Value")				var value:Double,
              @Column("LastModified")		var lastmodified:Date
              ) extends StringEntity {
  
  def this() = this(
      id = null, 
      underlying1asset = null,
      underlying1id = null,
      underlying2asset = null,
      underlying2id = null,
      valuedate = null,
      periodicity = -999999,
      nbdays = -99999,
      value = -99999999,
      lastmodified = null)

  override def toString():String = format("%-10s %-15s %-15s", underlying1id, underlying2id, value) + "%tY/%<tm/%<td".format(valuedate)
}
