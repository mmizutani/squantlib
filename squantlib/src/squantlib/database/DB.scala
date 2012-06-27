package squantlib.database

import java.sql.DriverManager
import org.squeryl.adapters._
import org.squeryl.{Session, SessionFactory}
import org.squeryl.Schema

import squantlib.database.schemadefinitions._

object DB extends Schema {
  
  /**
   * Sets up the DB connection for current thread.
   * 
   * @param uri A connection string such as mysql://your.mysql.server:3128/database_name
   * @param username A username to MySQL
   * @param password Password for the user above
   *
   */
  def setup(uri:String, username:String, password:String):Unit = {
    Class.forName("com.mysql.jdbc.Driver")
    SessionFactory.concreteFactory = Some(() => Session.create(DriverManager.getConnection("jdbc:" + uri, username, password), new MySQLInnoDBAdapter))
  }

  /**
   * Attach schema definitions to the tables.
   *
   * Example: 
   *
   *   val countries = table[Country]["countries"]
   *                         ^^^^^^^   ^^^^^^^^^
   *    A class in SchemaDefinitions   Actual table name in the database.
   */
  val countries = table[Country]("countries")

}