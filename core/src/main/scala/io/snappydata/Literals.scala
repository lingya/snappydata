/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package io.snappydata

import scala.reflect.ClassTag

import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.internal.{AltName, SQLAltName, SQLConfigEntry}

/**
 * Constant names suggested per naming convention
 * http://docs.scala-lang.org/style/naming-conventions.html
 *
 * we decided to use upper case with underscore word separator.
 */
object Constant {

  val DEFAULT_EMBEDDED_URL = "jdbc:snappydata:"

  val SNAPPY_URL_PREFIX = "snappydata://"

  val JDBC_URL_PREFIX = "snappydata://"

  val JDBC_EMBEDDED_DRIVER = "io.snappydata.jdbc.EmbeddedDriver"

  val JDBC_CLIENT_DRIVER = "io.snappydata.jdbc.ClientDriver"

  val PROPERTY_PREFIX = "snappydata."

  val STORE_PROPERTY_PREFIX = s"${PROPERTY_PREFIX}store."

  val SPARK_PREFIX = "spark."

  val SPARK_SNAPPY_PREFIX: String = SPARK_PREFIX + PROPERTY_PREFIX

  val SPARK_STORE_PREFIX: String = SPARK_PREFIX + STORE_PROPERTY_PREFIX

  private[snappydata] val JOBSERVER_PROPERTY_PREFIX = "jobserver."

  val DEFAULT_SCHEMA = "APP"

  val DEFAULT_CONFIDENCE: Double = 0.95d

  val DEFAULT_ERROR: Double = 0.2d

  val DEFAULT_BEHAVIOR: String = "DEFAULT_BEHAVIOR"
  val BEHAVIOR_RUN_ON_FULL_TABLE = "RUN_ON_FULL_TABLE"
  val BEHAVIOR_DO_NOTHING = "DO_NOTHING"
  val BEHAVIOR_LOCAL_OMIT = "LOCAL_OMIT"
  val BEHAVIOR_STRICT = "STRICT"
  val BEHAVIOR_PARTIAL_RUN_ON_BASE_TABLE = "PARTIAL_RUN_ON_BASE_TABLE"

  val keyBypassSampleOperator = "aqp.debug.byPassSampleOperator"
  val defaultBehaviorAsDO_NOTHING = "spark.sql.aqp.defaultBehaviorAsDO_NOTHING"

  val DEFAULT_USE_HIKARICP = false

  // Interval in ms  to run the SnappyAnalyticsService
  val DEFAULT_CALC_TABLE_SIZE_SERVICE_INTERVAL: Long = 10000

  // Internal Column table store schema
  final val INTERNAL_SCHEMA_NAME = "SNAPPYSYS_INTERNAL"

  // Internal Column table store suffix
  final val SHADOW_TABLE_SUFFIX = "_COLUMN_STORE_"

  // Property to Specify whether zeppelin interpreter should be started
  // with leadnode
  val ENABLE_ZEPPELIN_INTERPRETER = "zeppelin.interpreter.enable"

  // Property to specify the port on which zeppelin interpreter
  // should be started
  val ZEPPELIN_INTERPRETER_PORT = "zeppelin.interpreter.port"

  val DEFAULT_CACHE_TIMEOUT_SECS = 10

  val CHAR_TYPE_BASE_PROP = "base"

  val CHAR_TYPE_SIZE_PROP = "size"

  val MAX_VARCHAR_SIZE = 32672

  val MAX_CHAR_SIZE = 254

  val DEFAULT_SERIALIZER = "org.apache.spark.serializer.PooledKryoSerializer"

  // LZ4 JNI version is the fastest one but LZF gives best balance between
  // speed and compression ratio having higher compression ration than LZ4.
  // But the JNI version means no warmup time which helps for short jobs.
  val DEFAULT_CODEC = "lz4"

  // System property to tell the system whether the String type columns
  // should be considered as clob or not
  val STRING_AS_CLOB_PROP = "spark-string-as-clob"

  val CHANGEABLE_JAR_NAME = "SNAPPY_CHANGEABLE_JAR_NAME"

  val RESERVOIR_AS_REGION = "spark.sql.aqp.reservoirAsRegion"
}

/**
 * Property names should be as per naming convention
 * http://docs.scala-lang.org/style/naming-conventions.html
 * i.e. upper camel case.
 */
object Property extends Enumeration {

  case class SparkValue[T](name: String, altName: String,
      configEntry: SQLConfigEntry) extends Property.Val(name) with AltName[T] {

    override def toString(): String =
      if (altName == null) name else name + '/' + altName
  }

  case class SQLValue[T](name: String, altName: String,
      configEntry: SQLConfigEntry) extends Property.Val(name) with SQLAltName[T] {

    override def toString(): String =
      if (altName == null) name else name + '/' + altName
  }

  protected final def Val[T: ClassTag](name: String, doc: String,
      defaultValue: Option[T], prefix: String = null,
      isPublic: Boolean = true): SparkValue[T] = {
    SparkValue(name, if (prefix == null) null else prefix + name,
      SQLConfigEntry.sparkConf(name, doc, defaultValue, isPublic))
  }

  protected final def SQLVal[T: ClassTag](name: String, doc: String,
      defaultValue: Option[T], prefix: String = null,
      isPublic: Boolean = true): SQLValue[T] = {
    SQLValue(name, if (prefix == null) null else prefix + name,
      SQLConfigEntry(name, doc, defaultValue, isPublic))
  }

  def getPropertyValue(propertyName: String): Option[String] = {
    if (propertyName.startsWith(Constant.PROPERTY_PREFIX) &&
        !propertyName.startsWith(Constant.STORE_PROPERTY_PREFIX)) {
      Some(propertyName.substring(Constant.PROPERTY_PREFIX.length))
    } else None
  }

  def getSnappyPropertyValue(propertyName: String): Option[String] = {
    if (propertyName.startsWith(Constant.SPARK_SNAPPY_PREFIX) &&
        !propertyName.startsWith(Constant.SPARK_STORE_PREFIX)) {
      Some(propertyName.substring(Constant.SPARK_SNAPPY_PREFIX.length))
    } else None
  }

  val Locators = Val[String](s"${Constant.STORE_PROPERTY_PREFIX}locators",
    "The list of locators as comma-separated host:port values that have been " +
        "configured in the SnappyData cluster.", None, Constant.SPARK_PREFIX)

  val McastPort = Val[Int](s"${Constant.STORE_PROPERTY_PREFIX}mcastPort",
    "[Deprecated] The multicast port configured in the SnappyData cluster " +
        "when locators are not being used. This mode is no longer supported.",
    None, Constant.SPARK_PREFIX)

  val JobServerEnabled = Val(s"${Constant.JOBSERVER_PROPERTY_PREFIX}enabled",
    "If true then REST API access via Spark jobserver will be available in " +
        "the SnappyData cluster", Some(true), prefix = null, isPublic = false)

  val Embedded = Val(s"${Constant.PROPERTY_PREFIX}embedded",
    "Enabled in SnappyData embedded cluster and disabled for other " +
        "deployments.", Some(true), Constant.SPARK_PREFIX, isPublic = false)

  val MetaStoreDBURL = Val[String](s"${Constant.PROPERTY_PREFIX}metastore.dbUrl",
    "An explicit JDBC URL to use for external meta-data storage. " +
        "Normally this is set to use the SnappyData store by default and " +
        "should not be touched unless there are special requirements. " +
        "Use with caution since an incorrect configuration can result in " +
        "loss of entire meta-data (and thus data).", None, Constant.SPARK_PREFIX)

  val MetaStoreDriver = Val[String](s"${Constant.PROPERTY_PREFIX}metastore.dbDriver",
    s"Explicit JDBC driver class for ${MetaStoreDBURL.name} setting.",
    None, Constant.SPARK_PREFIX)

  val ColumnBatchSize = SQLVal[Int](s"${Constant.PROPERTY_PREFIX}column.batchSize",
    "The default size of blocks to use for storage in SnappyData column " +
        "store. When inserting data into the column storage this is " +
        "the unit (in bytes) that will be used to split the data into chunks " +
        "for efficient storage and retrieval. It can also be set for each table " +
        s"using the ${ExternalStoreUtils.COLUMN_BATCH_SIZE} option in " +
        "create table DDL.", Some(24 * 1024 * 1024))

  val ColumnMaxDeltaRows = SQLVal[Int](s"${Constant.PROPERTY_PREFIX}column.maxDeltaRows",
    "The maximum number of rows that can be in the delta buffer of a column table. " +
        s"The size of delta buffer is already limited by $ColumnBatchSize but " +
        "this allows a lower limit on number of rows for better scan performance. " +
        "So the delta buffer will be rolled into the column store whichever of " +
        s"$ColumnBatchSize and this property is hit first. It can also be set for " +
        s"each table using the ${ExternalStoreUtils.COLUMN_MAX_DELTA_ROWS} option in " +
        s"create table DDL else this setting is used for the create table.", Some(10000))

  val CompressionCodec = SQLVal[String](s"${Constant.PROPERTY_PREFIX}compression.codec",
    "The compression codec to use when creating column batches for binary and " +
        "complex type columns. Possible values: none, snappy, gzip, lzo. It can " +
        s"also be set as ${ExternalStoreUtils.COMPRESSION_CODEC} option in " +
        s"create table DDL. Default is no compression.", Some("none"))

  val HashJoinSize = SQLVal[Long](s"${Constant.PROPERTY_PREFIX}hashJoinSize",
    "The join would be converted into a hash join if the table is of size less" +
        "than hashJoinSize. Default value is 100 MB.", Some(100L * 1024 * 1024))

  val EnableExperimentalFeatures = SQLVal[Boolean](
    s"${Constant.PROPERTY_PREFIX}enable-experimental-features",
    "SQLConf property that enables snappydata experimental features like distributed index " +
        "optimizer choice during query planning. Default is turned off.",
    Some(false), Constant.SPARK_PREFIX)

  val FlushReservoirThreshold = SQLVal[Int](s"${Constant.PROPERTY_PREFIX}flushReservoirThreshold",
    "Reservoirs of sample table will be flushed and stored in columnar format if sampling is done" +
        " on baset table of size more than flushReservoirThreshold." +
        " Default value is 10,000.", Some(10000))

  val NumBootStrapTrials = SQLVal[Int](s"${Constant.SPARK_PREFIX}sql.aqp.numBootStrapTrials",
    "Number of bootstrap trials to do for calculating error bounds. Default value is 100.",
    Some(100))

  // TODO: check with suyog  Why are we having two different error defaults one as 1 & other as .2?

  val MaxErrorAllowed = SQLVal[Double](s"${Constant.SPARK_PREFIX}sql.aqp.maxErrorAllowed",
    "Maximum relative error tolerable in the approximate value calculation. It should be a " +
      "fractional value not exceeding 1. Default value is 1.0", Some(1.0d), null, false)

  val Error = SQLVal[Double](s"${Constant.SPARK_PREFIX}sql.aqp.error",
    "Maximum relative error tolerable in the approximate value calculation. It should be a " +
      s"fractional value not exceeding 1. Default value is ${Constant.DEFAULT_ERROR}",
    Some(Constant.DEFAULT_ERROR))

  val Confidence = SQLVal[Double](s"${Constant.SPARK_PREFIX}sql.aqp.confidence",
    "Confidence with which the error bounds are calculated for the approximate value. It should " +
    s"be a fractional value not exceeding 1. Default value is ${Constant.DEFAULT_CONFIDENCE}",
    None)

  val Behavior = SQLVal[String](s"${Constant.SPARK_PREFIX}sql.aqp.behavior",
    s" The action to be taken if the error computed goes oustide the error tolerance limit. " +
      s"Default value is ${Constant.BEHAVIOR_DO_NOTHING}", None)

  val AqpDebug = SQLVal[Boolean](s"${Constant.SPARK_PREFIX}sql.aqp.debug",
    s" Boolean if true tells engine to do  bootstrap analysis in debug mode returning an array of" +
      s" values for the iterations. Default is false", Some(false), null, false)

  val AqpDebugFixedSeed = SQLVal[Boolean](s"${Constant.SPARK_PREFIX}sql.aqp.debug.fixedSeed",
    s" Boolean if true tells engine to initialize the seed for poisson value calculation with a " +
      s"fixed  number 123456789L. Default is false.", Some(false), null, false)

  val AQPDebugPoissonType = SQLVal[String](s"${Constant.SPARK_PREFIX}sql.aqp.debug.poissonType",
    s" If aqp debugging is enbaled, this property can be used to set different types of algorithm" +
      s" to generate bootstrap multiplicity numbers. Default is Real", None, null, false)

  val ClosedFormEstimates = SQLVal[Boolean](s"${Constant.SPARK_PREFIX}sql.aqp.closedFormEstimates",
    s"Boolean if false tells engine to use bootstrap analysis for error calculation for all cases" +
      s". Default is true.", Some(true), null, false)


}

// extractors for properties

object SparkProperty {
  def unapply(property: Property.SparkValue[_]): Option[String] =
    Property.getPropertyValue(property.name)
}

object SparkSQLProperty {
  def unapply(property: Property.SQLValue[_]): Option[String] =
    Property.getPropertyValue(property.name)
}

object SnappySparkProperty {
  def unapply(property: Property.SparkValue[_]): Option[String] =
    Property.getSnappyPropertyValue(property.name)
}

object SnappySparkSQLProperty {
  def unapply(property: Property.SQLValue[_]): Option[String] =
    Property.getSnappyPropertyValue(property.name)
}

/**
 * SQL query hints as interpreted by the SnappyData SQL parser. The format
 * mirrors closely the format used by Hive,Oracle query hints with a comment
 * followed immediately by a '+' and then "key(value)" for the hint. Example:
 * <p>
 * SELECT * /`*`+ hint(value) *`/` FROM t1
 */
object QueryHint extends Enumeration {

  type Type = Value

  import scala.language.implicitConversions

  implicit def toStr(h: Type): String = h.toString

  /**
   * Query hint for SQL queries to serialize complex types (ARRAY, MAP, STRUCT)
   * as CLOBs in JSON format for routed JDBC/ODBC queries rather
   * than as serialized blobs to display better in external tools.
   *
   * Possible values are 'true/1' or 'false/0'
   *
   * Example:<br>
   * SELECT * FROM t1 --+ complexTypeAsJson(1)
   */
  val ComplexTypeAsJson = Value("complexTypeAsJson")

  /**
   * Query hint followed by table to override optimizer choice of index per table.
   *
   * Possible values are valid indexes defined on the table.
   *
   * Example:<br>
   * SELECT * FROM t1 /`*`+ index(xxx) *`/`, t2 --+ withIndex(yyy)
   */
  val Index = Value("index")

  /**
   * Query hint after FROM clause to indicate following tables have join order fixed and
   * optimizer shouldn't try to re-order joined tables.
   *
   * Possible comma separated values are [[io.snappydata.JOS]].
   *
   * Example:<br>
   * SELECT * FROM /`*`+ joinOrder(fixed) *`/` t1, t2
   */
  val JoinOrder = Value("joinOrder")

  /**
   * Query hint for SQL queries to serialize STRING type as CLOB rather than
   * as VARCHAR.
   *
   * Possible values are valid column names in the tables/schema. Multiple
   * column names to be comma separated.
   * One can also provide '*' for serializing all the STRING columns as CLOB.
   *
   * Example:<br>
   * SELECT id, name, addr, medical_history FROM t1 --+ columnsAsClob(addr)
   * SELECT id, name, addr, medical_history FROM t1 --+ columnsAsClob(*)
   */
  val ColumnsAsClob = Value("columnsAsClob")
}

/**
 * List of possible values for Join Order QueryHint.
 *
 * `Note:` Ordering is applicable only when index choice is left to the optimizer. By default,
 * if user specifies explicit index hint like "select * from t1 --+ index()", optimizer will just
 * honor the hint and skip everything mentioned in joinOrder. In other words, a blank index()
 * hint for any table disables choice of index and its associated following rules.
 */
object JOS extends Enumeration {
  type Type = Value

  import scala.language.implicitConversions

  implicit def toStr(h: Type): String = h.toString

  /**
   * Continue to attempt optimization choices of index for colocated joins even if user have
   * specified explicit index hints for some tables.
   *
   * `Note:` user specified index hint will be honored and optimizer will only attempt for
   * other tables in the query.
   */
  val ContinueOptimizations = Value("continueOpts")

  /**
   * By default if query have atleast one colocated join conditions mentioned between a pair of
   * partitiioned tables, optimizer won't try to derive colocation possibilities with replicated
   * tables in between. This switch tells the optimizer to include partition -> replicated ->
   * partition like indirect colocation possibilities even if partition -> partition join
   * conditions are mentioned.
   */
  val IncludeGeneratedPaths = Value("includeGeneratedPaths")

  /**
   * Don't alter the join order provided by the user.
   */
  val Fixed = Value("fixed")
}
