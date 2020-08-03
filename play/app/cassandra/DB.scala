/*
 * Copyright (c) 2015-2017 EpiData, Inc.
*/

package cassandra

import com.chrisomeara.pillar.Migration
import com.chrisomeara.pillar.Migrator
import com.chrisomeara.pillar.Registry
import com.chrisomeara.pillar.Reporter
import com.chrisomeara.pillar.ReplicationOptions
import com.datastax.driver.core._
import java.io.File
import java.io.PrintStream

import java.sql.{Connection, CallableStatement, DriverManager, PreparedStatement, ResultSet, SQLException, Statement}
import java.util.Date

import org.joda.time.Instant

/**
 * Singleton object for managing the server's connection to a Cassandra or SQLite
 * database and executing queries.
 */
object DB {
  private var connection: Option[ConnectionDB] = None

  /**
   * Connect to cassandra. On connect, the keyspace is created and migrated if
   * necessary.
   */
  def connect(nodeNames: String, keyspace: String, username: String, password: String) = {
    connection = Some(new ConnectionCas(nodeNames, keyspace, username, password))
  }
  /**
   * Connect to SQlite. 
   */
  def connect(url: String) = {
    connection = Some(new ConnectionSql(url))
  }


  /** Generate a prepared statement. */
  def prepare(statementSpec: String) = connection.get.prepare(statementSpec)

  /** Execute a previously prepared statement. */
  def execute(statement: Statement): ResultSet = connection.get.execute(statement)

  /** Execute a previously prepared statement which returns and empty ResultSet */
  def executeUpdate(statement: Statement): ResultSet = connection.get.executeUpdate(statement)

  /** ResultSet to Json Object. */
//  def resultToJson(rs : ResultSet) = {
//    // Get the next page info
//    val nextPage = rs.getExecutionInfo().getPagingState()
//    val nextBatch = if (nextPage == null) "" else nextPage.toString
//
//    // only return the available ones by not fetching.
//
//    val rows = 1.to(rs.getFetchSize()).map(_ => rs.one())
//    val records = new JLinkedList[JLinkedHashMap[String, Object]]()
//
//    rows
//      .map(Model.rowToJLinkedHashMap(_, tableName, modelName))
//      .foreach(m => records.add(m))
//
//    // Return the json object
//    JsonHelpers.toJson(records, nextBatch)
//  }

  /** ResultSet to User. */

   /** Executes a batch of statements individually but reverts back to original state if error */
  def batchExecute(statements: List[Statement]): ResultSet = connection.get.batchExecute(statements)

    /** Binds the values in args to the statement. */
  def binds(statement: Statement, args: Any*): Statement = connection.get.binds(statement, args)
    
  /** Execute a SQL statement by binding ordered attributes. */
  def cql(statement: String, args: Any*): ResultSet = connection.get.cql(statement, args)

  /** Execute a CQL statement by binding named attributes. */
  def cql(statement: String, args: Map[String, Any]): ResultSet = connection.get.cql(statement, args)
    
  /** Closes a Cassandra connection. */
  def close = connection.get.close 

  /** Returns the ongoing session. */
  def session = connection.get.session

}


private abstract class ConnectionDB {

  val session: Connection
  def prepare(stm: String) : Statement
  def execute(stm: Statement) : ResultSet
  def executeUpdate(stm: Statement) : ResultSet
  def batchExecute(statements: List[Statement]): ResultSet
  def binds(statement: Statement, args: Seq[Any]): Statement
  def cql(statement: String, args: Seq[Any]): ResultSet
  def cql(statement: String, args: Map[String, Any]): ResultSet
  def close

}

private class ConnectionLite(url: String) extends ConnectionDB {

  override val session = DriverManager.getConnection(url)
  val filename = "play/app/conf/pillar/migration/epidatatr.txt"
  val sql1 = Source.fromFile(filename).getLines.mkString
  session.createStatement().executeUpdate(sql1)


  def prepare(statement: String) : PreparedStatement = session.prepareStatement(statement)

  def execute(statement: Statement) = statement.asInstanceOf[PreparedStatement].executeQuery()

  def executeUpdate(statement: Statement): ResultSet = {
    statement.asInstanceOf[PreparedStatement].executeUpdate()
    val l:ResultSet = null
    l
  }

  def batchExecute(statements: List[String]) : ResultSet = {
    val t0 = EpidataMetrics.getCurrentTime
    var statement = session.createStatement()
    statements.foreach(s => statement.addBatch(s))
    statement.executeBatch()
    EpidataMetrics.increment("DB.batchExecute", t0)
    val l:ResultSet = null
    l
  }

  def binds(statement: Statement, args: Seq[Any]): Statement = {
    val stm = statement.asInstanceOf[PreparedStatement]
    var i = 1
    args.foreach{ e =>
      stm.setObject(i, e)
      i+=1
    }
    stm
  }

  def cql(statement: String, args: Seq[Any]): ResultSet = {
    val boundStatement = prepare(statement)
    var i = 1
    for( e <- args){
      boundStatement.setObject(i, e)
      i += 1
    }
    boundStatement.executeQuery()
  }

  def cql(statement: String, args: Map[String, Any]): ResultSet = {
    val boundStatement = session.prepareCall(statement)
    args.foreach {
      case (key, value: String) => boundStatement.setString(key, value)
      case (key, value: Int) => boundStatement.setInt(key, value)
      case (key, value: Double) => boundStatement.setDouble(key, value)
      case _ => throw new IllegalArgumentException("Unexpected args.")
    }
    connection.get.execute(boundStatement)
  }

  def close = {
    session.close()
  }

}



private class TerseMigrationReporter(stream: PrintStream) extends Reporter {
  override def initializing(
    session: Session,
    keyspace: String,
    replicationOptions: ReplicationOptions
  ) {
  }

  override def migrating(session: Session, dateRestriction: Option[Date]) {
  }

  override def applying(migration: Migration) {
    stream.println( // scalastyle:ignore
      s"Applying migration ${migration.authoredAt.getTime}: ${migration.description}"
    )
  }

  override def reversing(migration: Migration) {
    stream.println( // scalastyle:ignore
      s"Reversing migration ${migration.authoredAt.getTime}: ${migration.description}"
    )
  }

  override def destroying(session: Session, keyspace: String) {
  }
}


private class ConnectionCas(nodeNames: String, keyspace: String, username: String, password: String) extends ConnectionDB {

  val cluster = nodeNames.split(',').foldLeft(Cluster.builder())({ (builder, nodeName) =>
    try {
      builder.addContactPoint(nodeName).withCredentials(username, password)
    } catch {
      case e: IllegalArgumentException => Logger.warn(e.getMessage); builder
    }
  }).build()

  val session = cluster.connect()

  val reporter = new TerseMigrationReporter(System.out)
  val registry = {
    import play.api.Play.current
    Registry.fromDirectory(
      Play.application.getFile("conf/pillar/migrations/epidata"), reporter
    )
  }

  // Create keyspace if necessary.
  Migrator(registry, reporter).initialize(session, keyspace)

  // Use the specified keyspace.
  session.execute(s"USE ${keyspace}")

  // Perform migrations if necessary.
  Migrator(registry, reporter).migrate(session)

  def prepare(statementSpec: String) = session.prepare(statementSpec)

  def execute(statement: Statement) = session.execute(statement)

  def executeUpdate(statement: Statement) = session.execute(statement)

  def batchExecute(statements: List[Statement]): ResultSet = {
    val t0 = EpidataMetrics.getCurrentTime
    val batch = new BatchStatement()
    statements.foreach(s => batch.add(s))
    // execute the batch
    val rs = this.execute(batch)
    EpidataMetrics.increment("DB.batchExecute", t0)
    rs
  }

  def binds(statement: Statement, args: AnyRef*): Statement = statement.binds(args)

  def cql(statement: String, args: AnyRef*): ResultSet = {
    this.execute(new SimpleStatement(statement, args: _*))
  }

  def cql(statement: String, args: Map[String, Any]): ResultSet = {
    val boundStatement = new BoundStatement(this.prepare(statement))
    args.foreach {
      case (key, value: String) => boundStatement.setString(key, value)
      case (key, value: Int) => boundStatement.setInt(key, value)
      case (key, value: Double) => boundStatement.setDouble(key, value)
      case (key, value: Date) => boundStatement.setDate(key, LocalDate.fromMillisSinceEpoch(value.getTime))
      case _ => throw new IllegalArgumentException("Unexpected args.")
    }
    this.execute(boundStatement)
  }

  def close = {
    session.close()
    cluster.close()
  }
}