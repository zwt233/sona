package com.tencent.angel.sona.core

import com.tencent.angel.client.{AngelContext, AngelPSClient}
import com.tencent.angel.ml.core.conf.SharedConf
import com.tencent.angel.sona.utils.ConfUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.util.ShutdownHookManager
import org.apache.spark.SparkConf
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.SPKMLUtils
import org.apache.spark.rdd.RDD

import scala.collection.mutable

class DriverContext private(hadoopConf: Configuration) extends PSAgentContext(SharedConf.get) {
  private var angelContext: AngelContext = _
  private var angelClient: AngelPSClient = _
  private var stopAngelHookTask: Runnable = _

  private val bcVariables = new mutable.HashSet[Broadcast[_]]()
  private val cachedRDDs = new mutable.HashSet[RDD[_]]()

  @transient override lazy val sparkEnvContext: SparkEnvContext = {
    if (angelClient == null) {
      throw new Exception("Pls. startAngel first!")
    }

    SparkEnvContext(angelClient)
  }

  private lazy val driverId: String = java.util.UUID.randomUUID.toString

  def getId: String = driverId

  def registerBroadcastVariables(bcVar: Broadcast[_]): this.type = synchronized {
    bcVariables.add(bcVar)
    this
  }

  def registerCachedRDD(rdd: RDD[_]): this.type = {
    cachedRDDs.add(rdd)
    this
  }

  def getAngelClient: AngelPSClient = {
    angelClient
  }

  def startAngel(): AngelPSClient = synchronized {
    if (angelClient == null) {
      angelClient = new AngelPSClient(hadoopConf)

      stopAngelHookTask = new Runnable {
        def run(): Unit = doStopAngel()
      }

      ShutdownHookManager.get().addShutdownHook(stopAngelHookTask,
        FileSystem.SHUTDOWN_HOOK_PRIORITY + 10)

      angelContext = angelClient.startPS()
      hadoopConf.set(ConfUtils.MASTER_IP, angelContext.getMasterLocation.getIp)
      hadoopConf.set(ConfUtils.MASTER_PORT, angelContext.getMasterLocation.getPort.toString)

      ConfUtils.merge(SharedConf.get, hadoopConf,
        ConfUtils.MASTER_IP, ConfUtils.MASTER_PORT, "angel", "ml", "spark.ps", "spark.hadoop")
    }

    angelClient
  }

  def isAngelAlive: Boolean = synchronized {
    if (angelClient != null) true else false
  }

  def stopAngel(): Unit = synchronized {
    if (stopAngelHookTask != null) {
      ShutdownHookManager.get().removeShutdownHook(stopAngelHookTask)
      stopAngelHookTask = null
    }

    doStopAngel()
  }

  private def doStopAngel(): Unit = {
    if (bcVariables.nonEmpty) {
      bcVariables.foreach { bcVar => SPKMLUtils.distory(bcVar) }
      bcVariables.clear()
    }

    if (angelClient != null) {
      angelClient.stopPS()
      angelClient = null
    }
  }
}

object DriverContext {
  private var driverContext: DriverContext = _

  def get(conf: SparkConf): DriverContext = synchronized {
    if (driverContext == null) {
      val hadoopConf = ConfUtils.convertToHadoop(conf)
      driverContext = new DriverContext(hadoopConf)
    }

    driverContext
  }

  def get(): DriverContext = synchronized {
    require(driverContext != null, "driverContext is null")
    driverContext
  }

  def adjustExecutorJVM(conf: SparkConf): SparkConf = {
    val extraOps = conf.getOption("spark.ps.executor.extraJavaOptions")
    val defaultOps = conf.get("spark.executor.extraJavaOptions", "")

    val extraOpsStr = if (extraOps.isDefined) {
      extraOps.get
    } else {
      var executorMemSizeInMB = conf.getSizeAsMb("spark.executor.memory", "2048M")
      if (executorMemSizeInMB < 2048) executorMemSizeInMB = 2048

      val isUseDirect: Boolean = conf.getBoolean("spark.ps.usedirectbuffer", defaultValue = true)
      val maxUse = executorMemSizeInMB - 512
      var directRegionSize: Int = 0
      if (isUseDirect) directRegionSize = (maxUse * 0.3).toInt
      else directRegionSize = (maxUse * 0.2).toInt
      val heapMax = maxUse - directRegionSize
      val youngRegionSize = (heapMax * 0.3).toInt
      val survivorRatio = 4

      conf.set("spark.executor.memory", heapMax + "M")

      val executorOps = new StringBuilder()
        .append(" -Xmn").append(youngRegionSize).append("M")
        .append(" -XX:MaxDirectMemorySize=").append(directRegionSize).append("M")
        .append(" -XX:SurvivorRatio=").append(survivorRatio)
        .append(" -XX:+AggressiveOpts")
        .append(" -XX:+UseLargePages")
        .append(" -XX:+UseConcMarkSweepGC")
        .append(" -XX:CMSInitiatingOccupancyFraction=50")
        .append(" -XX:+UseCMSInitiatingOccupancyOnly")
        .append(" -XX:+CMSScavengeBeforeRemark")
        .append(" -XX:+UseCMSCompactAtFullCollection")
        .append(" -verbose:gc")
        .append(" -XX:+PrintGCDateStamps")
        .append(" -XX:+PrintGCDetails")
        .append(" -XX:+PrintCommandLineFlags")
        .append(" -XX:+PrintTenuringDistribution")
        .append(" -XX:+PrintAdaptiveSizePolicy")
        .toString()
      executorOps
    }
    conf.set("spark.executor.extraJavaOptions", defaultOps + " " + extraOpsStr)
  }
}