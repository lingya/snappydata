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
package io.snappydata.hydra

import java.io.{File, FileOutputStream, PrintWriter}

import io.snappydata.hydra.installJar.TestUtils
import org.apache.spark.sql.SnappyContext
import org.apache.spark.{SparkContext, SparkConf}

import scala.util.{Failure, Success, Try}

object InstallJarTestSparkApp {
  val conf = new SparkConf().
    setAppName("InstallJarTest Application")
  val sc = new SparkContext(conf)
  val snc = SnappyContext(sc)

  def main(args: Array[String]): Unit = {
    val threadID = Thread.currentThread().getId
    val outputFile = "ValidateInstallJarTestApp_thread_" + threadID + "_" + System.currentTimeMillis + ".out"
    val pw = new PrintWriter(new FileOutputStream(new File(outputFile), true));
    Try {
      pw.println("****** DynamicJarLoadingJob started ******")
      pw.flush()
      val numServers: Int = args(1).toInt
      val expectedException: Boolean = args(2).toBoolean
      TestUtils.verify(snc, args(0), pw, numServers, expectedException)
      pw.println("****** DynamicJarLoadingJob finished ******")
    } match {
      case Success(v) => pw.close()
      case Failure(e) =>
        pw.println("Exception occurred while executing the job " + "\nError Message:" + e.getMessage)
        pw.close()
    }
  }
}
