/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler.cluster

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import javax.annotation.concurrent.GuardedBy

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.concurrent.Future

import org.apache.hadoop.security.UserGroupInformation

import org.apache.spark.{ExecutorAllocationClient, SparkEnv, SparkException, TaskState}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.deploy.security.HadoopDelegationTokenManager
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.internal.config.Network._
import org.apache.spark.rpc._
import org.apache.spark.scheduler._
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages._
import org.apache.spark.scheduler.cluster.CoarseGrainedSchedulerBackend.ENDPOINT_NAME
import org.apache.spark.util.{RpcUtils, SerializableBuffer, ThreadUtils, Utils}

/**
  * 调度程序后端代码，等待粗粒度执行程序连接。
  * 在Spark执行期间保留每个executor，不会在程序完成时关闭executor,在新的任务进来时不会创建新的executor。
  * executor可以以多种方式运行，比如粗粒度mesos模式下或者standalone
 */
private[spark]
class CoarseGrainedSchedulerBackend(scheduler: TaskSchedulerImpl, val rpcEnv: RpcEnv)
  extends ExecutorAllocationClient with SchedulerBackend with Logging {

  // Use an atomic variable to track total number of cores in the cluster for simplicity and speed
  protected val totalCoreCount = new AtomicInteger(0)
  // Total number of executors that are currently registered
  protected val totalRegisteredExecutors = new AtomicInteger(0)
  protected val conf = scheduler.sc.conf
  private val maxRpcMessageSize = RpcUtils.maxMessageSizeBytes(conf)
  private val defaultAskTimeout = RpcUtils.askRpcTimeout(conf)
  // Submit tasks only after (registered resources / total expected resources)
  // is equal to at least this value, that is double between 0 and 1.
  private val _minRegisteredRatio =
    math.min(1, conf.get(SCHEDULER_MIN_REGISTERED_RESOURCES_RATIO).getOrElse(0.0))
  // Submit tasks after maxRegisteredWaitingTime milliseconds
  // if minRegisteredRatio has not yet been reached
  private val maxRegisteredWaitingTimeNs = TimeUnit.MILLISECONDS.toNanos(
    conf.get(SCHEDULER_MAX_REGISTERED_RESOURCE_WAITING_TIME))
  private val createTimeNs = System.nanoTime()

  // Accessing `executorDataMap` in `DriverEndpoint.receive/receiveAndReply` doesn't need any
  // protection. But accessing `executorDataMap` out of `DriverEndpoint.receive/receiveAndReply`
  // must be protected by `CoarseGrainedSchedulerBackend.this`. Besides, `executorDataMap` should
  // only be modified in `DriverEndpoint.receive/receiveAndReply` with protection by
  // `CoarseGrainedSchedulerBackend.this`.
  private val executorDataMap = new HashMap[String, ExecutorData]

  // Number of executors requested by the cluster manager, [[ExecutorAllocationManager]]
  @GuardedBy("CoarseGrainedSchedulerBackend.this")
  private var requestedTotalExecutors = 0

  // Number of executors requested from the cluster manager that have not registered yet
  @GuardedBy("CoarseGrainedSchedulerBackend.this")
  private var numPendingExecutors = 0

  private val listenerBus = scheduler.sc.listenerBus

  // Executors we have requested the cluster manager to kill that have not died yet; maps
  // the executor ID to whether it was explicitly killed by the driver (and thus shouldn't
  // be considered an app-related failure).
  @GuardedBy("CoarseGrainedSchedulerBackend.this")
  private val executorsPendingToRemove = new HashMap[String, Boolean]

  // A map to store hostname with its possible task number running on it
  @GuardedBy("CoarseGrainedSchedulerBackend.this")
  protected var hostToLocalTaskCount: Map[String, Int] = Map.empty

  // The number of pending tasks which is locality required
  @GuardedBy("CoarseGrainedSchedulerBackend.this")
  protected var localityAwareTasks = 0

  // The num of current max ExecutorId used to re-register appMaster
  @volatile protected var currentExecutorIdCounter = 0

  // Current set of delegation tokens to send to executors.
  private val delegationTokens = new AtomicReference[Array[Byte]]()

  // The token manager used to create security tokens.
  private var delegationTokenManager: Option[HadoopDelegationTokenManager] = None

  private val reviveThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("driver-revive-thread")

  class DriverEndpoint extends ThreadSafeRpcEndpoint with Logging {

    override val rpcEnv: RpcEnv = CoarseGrainedSchedulerBackend.this.rpcEnv

    // Executors that have been lost, but for which we don't yet know the real exit reason.
    protected val executorsPendingLossReason = new HashSet[String]

    protected val addressToExecutorId = new HashMap[RpcAddress, String]

    // Spark configuration sent to executors. This is a lazy val so that subclasses of the
    // scheduler can modify the SparkConf object before this view is created.
    private lazy val sparkProperties = scheduler.sc.conf.getAll
      .filter { case (k, _) => k.startsWith("spark.") }
      .toSeq

    override def onStart() {
      // Periodically revive offers to allow delay scheduling to work
      val reviveIntervalMs = conf.get(SCHEDULER_REVIVE_INTERVAL).getOrElse(1000L)

      reviveThread.scheduleAtFixedRate(new Runnable {
        override def run(): Unit = Utils.tryLogNonFatalError {
          Option(self).foreach(_.send(ReviveOffers))
        }
      }, 0, reviveIntervalMs, TimeUnit.MILLISECONDS)
    }

    override def receive: PartialFunction[Any, Unit] = {
      /**
        * task 的状态回调
        */
      case StatusUpdate(executorId, taskId, state, data) =>
        //首先调用scheduler.statusUpdate(taskId, state, data.value) 上报上去
        scheduler.statusUpdate(taskId, state, data.value)
        //然后判断这个task是否执行结束了
        if (TaskState.isFinished(state)) {
          //如果执行结束了
          executorDataMap.get(executorId) match {
              //匹配ExecutorData
            case Some(executorInfo) =>
              //把executor上的freeCore 加上去, 调用一次makeOffer()
              //这个事件就是别人直接向SchedulerBackend请求资源, 直接调用makeOffer()
              executorInfo.freeCores += scheduler.CPUS_PER_TASK
              makeOffers(executorId)
            case None =>
              // Ignoring the update since we don't know about the executor.
              logWarning(s"Ignored task status update ($taskId state $state) " +
                s"from unknown executor with ID $executorId")
          }
        }

        //直接调用了makeOffers()方法，等到一批和执行的任务描述(taskSet) , 调用launchTasks
      case ReviveOffers =>
        makeOffers()

        //killTask
      case KillTask(taskId, executorId, interruptThread, reason) =>
        //找出executorid对应的executor 然后杀掉task
        executorDataMap.get(executorId) match {
          case Some(executorInfo) =>
            executorInfo.executorEndpoint.send(
              KillTask(taskId, executorId, interruptThread, reason))
          case None =>
            // Ignoring the task kill since the executor is not registered.
            logWarning(s"Attempted to kill task $taskId for unknown executor $executorId.")
        }

      case KillExecutorsOnHost(host) =>
        scheduler.getExecutorsAliveOnHost(host).foreach { exec =>
          killExecutors(exec.toSeq, adjustTargetNumExecutors = false, countFailures = false,
            force = true)
        }

      case UpdateDelegationTokens(newDelegationTokens) =>
        updateDelegationTokens(newDelegationTokens)


      case RemoveExecutor(executorId, reason) =>

        /**
          * 我们将删除executor的状态，无法恢复它。
          * 但是，Driver和executor之间的连接可能仍然存在，
          * 因此执行程序不会自动退出，因此请尝试告诉执行程序自行停止。
          * 见SPARK-13519。
          *
          */
        executorDataMap.get(executorId).foreach(_.executorEndpoint.send(StopExecutor))
        removeExecutor(executorId, reason)
    }

    override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {

      /**
        * 用来注册executor，通常worker拉起、重启会触发executor的注册，
        * CoarseGrainedSchedulerBackend会把这些executor维护起来，更新内部的资源，比如综合书增加，最后调用一次makeOffer()
        * 即把目前空闲资源丢给taskScheduler去分配一次
        * 返回taskSet，把任务启动起来，这个makeOffer() 的调用会出现在任何资源变化的事件中，下面会看到
        */
      case RegisterExecutor(executorId, executorRef, hostname, cores, logUrls, attributes) =>
        if (executorDataMap.contains(executorId)) {
          executorRef.send(RegisterExecutorFailed("Duplicate executor ID: " + executorId))
          context.reply(true)
        } else if (scheduler.nodeBlacklist.contains(hostname)) {
          /**
            * 如果cluster manager 在黑名单节点上给我们一个executor
            * （因为它在我们通知我们的黑名单之前已经开始分配这些资源，或者它忽略了我们的黑名单），
            * 那么我们立即拒绝该执行者。
            */
          logInfo(s"Rejecting $executorId as it has been blacklisted.")
          executorRef.send(RegisterExecutorFailed(s"Executor is blacklisted: $executorId"))
          context.reply(true)
        } else {
          /**
            * 如果executor的rpc env 没有监听到传入连接，则 hostPort 将设置为null，并且应该使用client 连接executor
            */
          val executorAddress = if (executorRef.address != null) {
              executorRef.address
            } else {
              context.senderAddress
            }
          logInfo(s"Registered executor $executorRef ($executorAddress) with ID $executorId")
          addressToExecutorId(executorAddress) = executorId
          totalCoreCount.addAndGet(cores)
          totalRegisteredExecutors.addAndGet(1)
          val data = new ExecutorData(executorRef, executorAddress, hostname,
            cores, cores, logUrls, attributes)
          // 这必须同步，因为在请求executor时会读取此块中变异的变量
          CoarseGrainedSchedulerBackend.this.synchronized {
            executorDataMap.put(executorId, data)
            if (currentExecutorIdCounter < executorId.toInt) {
              currentExecutorIdCounter = executorId.toInt
            }
            if (numPendingExecutors > 0) {
              numPendingExecutors -= 1
              logDebug(s"Decremented number of pending executors ($numPendingExecutors left)")
            }
          }
          executorRef.send(RegisteredExecutor)
          // 注意：一些测试期望在我们将执行程序放入映射之后得到答复
          context.reply(true)
          listenerBus.post(
            SparkListenerExecutorAdded(System.currentTimeMillis(), executorId, data))
          makeOffers()
        }

      case StopDriver =>
        context.reply(true)
        stop()

      /**
        * 通知每个executor 处理stopExecutor事件
        */
      case StopExecutors =>
        logInfo("Asking each executor to shut down")
        for ((_, executorData) <- executorDataMap) {
          executorData.executorEndpoint.send(StopExecutor)
        }
        context.reply(true)

      case RemoveWorker(workerId, host, message) =>
        removeWorker(workerId, host, message)
        context.reply(true)

      case RetrieveSparkAppConfig =>
        val reply = SparkAppConfig(
          sparkProperties,
          SparkEnv.get.securityManager.getIOEncryptionKey(),
          Option(delegationTokens.get()))
        context.reply(reply)
    }

    // 为所有executor提供虚拟资源
    private def makeOffers() {
      // 确保在某个任务启动时没有executor被杀死
      val taskDescs = CoarseGrainedSchedulerBackend.this.synchronized {
        // 过滤出活跃的executor
        val activeExecutors = executorDataMap.filterKeys(executorIsAlive)
        val workOffers = activeExecutors.map {
          case (id, executorData) =>
            new WorkerOffer(id, executorData.executorHost, executorData.freeCores,
              Some(executorData.executorAddress.hostPort))
        }.toIndexedSeq
        scheduler.resourceOffers(workOffers)
      }
      if (!taskDescs.isEmpty) {
        launchTasks(taskDescs)
      }
    }

    override def onDisconnected(remoteAddress: RpcAddress): Unit = {
      addressToExecutorId
        .get(remoteAddress)
        .foreach(removeExecutor(_, SlaveLost("Remote RPC client disassociated. Likely due to " +
          "containers exceeding thresholds, or network issues. Check driver logs for WARN " +
          "messages.")))
    }

    // Make fake resource offers on just one executor
    private def makeOffers(executorId: String) {
      // Make sure no executor is killed while some task is launching on it
      val taskDescs = CoarseGrainedSchedulerBackend.this.synchronized {
        // Filter out executors under killing
        if (executorIsAlive(executorId)) {
          val executorData = executorDataMap(executorId)
          val workOffers = IndexedSeq(
            new WorkerOffer(executorId, executorData.executorHost, executorData.freeCores,
              Some(executorData.executorAddress.hostPort)))
          scheduler.resourceOffers(workOffers)
        } else {
          Seq.empty
        }
      }
      if (!taskDescs.isEmpty) {
        launchTasks(taskDescs)
      }
    }

    private def executorIsAlive(executorId: String): Boolean = synchronized {
      !executorsPendingToRemove.contains(executorId) &&
        !executorsPendingLossReason.contains(executorId)
    }

    // 启动一组提供资源的 task
    private def launchTasks(tasks: Seq[Seq[TaskDescription]]) {
      for (task <- tasks.flatten) {
        //将task转换为二进制
        val serializedTask = TaskDescription.encode(task)
        //判断二进制数据大小，如果二进制数据超过最大范围则报错
        if (serializedTask.limit() >= maxRpcMessageSize) {
          Option(scheduler.taskIdToTaskSetManager.get(task.taskId)).foreach { taskSetMgr =>
            try {
              var msg = "Serialized task %s:%d was %d bytes, which exceeds max allowed: " +
                s"${RPC_MESSAGE_MAX_SIZE.key} (%d bytes). Consider increasing " +
                s"${RPC_MESSAGE_MAX_SIZE.key} or using broadcast variables for large values."
              msg = msg.format(task.taskId, task.index, serializedTask.limit(), maxRpcMessageSize)
              taskSetMgr.abort(msg)
            } catch {
              case e: Exception => logError("Exception in error callback", e)
            }
          }
        }
        else {
          val executorData = executorDataMap(task.executorId)
          executorData.freeCores -= scheduler.CPUS_PER_TASK

          logDebug(s"Launching task ${task.taskId} on executor id: ${task.executorId} hostname: " +
            s"${executorData.executorHost}.")
          //将task 发送给executor执行
          executorData.executorEndpoint.send(LaunchTask(new SerializableBuffer(serializedTask)))
        }
      }
    }

    /**
      * 从集群中移除一个断开连接的slave
      */
    private def removeExecutor(executorId: String, reason: ExecutorLossReason): Unit = {
      logDebug(s"Asked to remove executor $executorId with reason $reason")
      executorDataMap.get(executorId) match {
          //通过executorId匹配executor
        case Some(executorInfo) =>
          //这必须同步，因为在请求executor时会读取此块中变异的变量
          val killed = CoarseGrainedSchedulerBackend.this.synchronized {
            addressToExecutorId -= executorInfo.executorAddress
            executorDataMap -= executorId
            executorsPendingLossReason -= executorId
            executorsPendingToRemove.remove(executorId).getOrElse(false)
          }
          totalCoreCount.addAndGet(-executorInfo.totalCores)
          totalRegisteredExecutors.addAndGet(-1)
          // TaskScheduler.executorLost() 方法, 通知上层我这边有一批资源不能用了,你处理下吧，
          // TaskScheduler会继续把 executorLost事件上报给DAGScheduler,
          // 原因是DAGScheduler关心shuffle任务的outputlocation,
          // DAGScheduler会告诉BlockManager这个executor不能用了,然后移走它,
          // 然后把所有的stage的shuffleOutput信息都遍历一遍, 移走这个executor,
          // 并且更新后的shuffleoutput信息注册到MapOutputTracker 上, 最后清理下本地的CacheLocationsMap
          scheduler.executorLost(executorId, if (killed) ExecutorKilled else reason)
          listenerBus.post(
            SparkListenerExecutorRemoved(System.currentTimeMillis(), executorId, reason.toString))
        case None =>
          // SPARK-15262: If an executor is still alive even after the scheduler has removed
          // its metadata, we may receive a heartbeat from that executor and tell its block
          // manager to reregister itself. If that happens, the block manager master will know
          // about the executor, but the scheduler will not. Therefore, we should remove the
          // executor from the block manager when we hit this case.
          scheduler.sc.env.blockManager.master.removeExecutorAsync(executorId)
          logInfo(s"Asked to remove non-existent executor $executorId")
      }
    }

    // Remove a lost worker from the cluster
    private def removeWorker(workerId: String, host: String, message: String): Unit = {
      logDebug(s"Asked to remove worker $workerId with reason $message")
      scheduler.workerRemoved(workerId, host, message)
    }

    /**
     * Stop making resource offers for the given executor. The executor is marked as lost with
     * the loss reason still pending.
     *
     * @return Whether executor should be disabled
     */
    protected def disableExecutor(executorId: String): Boolean = {
      val shouldDisable = CoarseGrainedSchedulerBackend.this.synchronized {
        if (executorIsAlive(executorId)) {
          executorsPendingLossReason += executorId
          true
        } else {
          // Returns true for explicitly killed executors, we also need to get pending loss reasons;
          // For others return false.
          executorsPendingToRemove.contains(executorId)
        }
      }

      if (shouldDisable) {
        logInfo(s"Disabling executor $executorId.")
        scheduler.executorLost(executorId, LossReasonPending)
      }

      shouldDisable
    }
  }

  val driverEndpoint = rpcEnv.setupEndpoint(ENDPOINT_NAME, createDriverEndpoint())

  protected def minRegisteredRatio: Double = _minRegisteredRatio

  override def start() {
    if (UserGroupInformation.isSecurityEnabled()) {
      delegationTokenManager = createTokenManager()
      delegationTokenManager.foreach { dtm =>
        val ugi = UserGroupInformation.getCurrentUser()
        val tokens = if (dtm.renewalEnabled) {
          dtm.start()
        } else if (ugi.hasKerberosCredentials()) {
          val creds = ugi.getCredentials()
          dtm.obtainDelegationTokens(creds)
          SparkHadoopUtil.get.serialize(creds)
        } else {
          null
        }
        if (tokens != null) {
          updateDelegationTokens(tokens)
        }
      }
    }
  }

  protected def createDriverEndpoint(): DriverEndpoint = new DriverEndpoint()

  def stopExecutors() {
    try {
      if (driverEndpoint != null) {
        logInfo("Shutting down all executors")
        driverEndpoint.askSync[Boolean](StopExecutors)
      }
    } catch {
      case e: Exception =>
        throw new SparkException("Error asking standalone scheduler to shut down executors", e)
    }
  }

  override def stop() {
    reviveThread.shutdownNow()
    stopExecutors()
    delegationTokenManager.foreach(_.stop())
    try {
      if (driverEndpoint != null) {
        driverEndpoint.askSync[Boolean](StopDriver)
      }
    } catch {
      case e: Exception =>
        throw new SparkException("Error stopping standalone scheduler's driver endpoint", e)
    }
  }

  /**
   * Reset the state of CoarseGrainedSchedulerBackend to the initial state. Currently it will only
   * be called in the yarn-client mode when AM re-registers after a failure.
   * */
  protected def reset(): Unit = {
    val executors: Set[String] = synchronized {
      requestedTotalExecutors = 0
      numPendingExecutors = 0
      executorsPendingToRemove.clear()
      executorDataMap.keys.toSet
    }

    // Remove all the lingering executors that should be removed but not yet. The reason might be
    // because (1) disconnected event is not yet received; (2) executors die silently.
    executors.foreach { eid =>
      removeExecutor(eid, SlaveLost("Stale executor after cluster manager re-registered."))
    }
  }

  override def reviveOffers() {
    driverEndpoint.send(ReviveOffers)
  }

  override def killTask(
      taskId: Long, executorId: String, interruptThread: Boolean, reason: String) {
    driverEndpoint.send(KillTask(taskId, executorId, interruptThread, reason))
  }

  override def defaultParallelism(): Int = {
    conf.getInt("spark.default.parallelism", math.max(totalCoreCount.get(), 2))
  }

  /**
   * Called by subclasses when notified of a lost worker. It just fires the message and returns
   * at once.
   */
  protected def removeExecutor(executorId: String, reason: ExecutorLossReason): Unit = {
    driverEndpoint.send(RemoveExecutor(executorId, reason))
  }

  protected def removeWorker(workerId: String, host: String, message: String): Unit = {
    driverEndpoint.ask[Boolean](RemoveWorker(workerId, host, message)).failed.foreach(t =>
      logError(t.getMessage, t))(ThreadUtils.sameThread)
  }

  def sufficientResourcesRegistered(): Boolean = true

  override def isReady(): Boolean = {
    if (sufficientResourcesRegistered) {
      logInfo("SchedulerBackend is ready for scheduling beginning after " +
        s"reached minRegisteredResourcesRatio: $minRegisteredRatio")
      return true
    }
    if ((System.nanoTime() - createTimeNs) >= maxRegisteredWaitingTimeNs) {
      logInfo("SchedulerBackend is ready for scheduling beginning after waiting " +
        s"maxRegisteredResourcesWaitingTime: $maxRegisteredWaitingTimeNs(ns)")
      return true
    }
    false
  }

  /**
   * Return the number of executors currently registered with this backend.
   */
  private def numExistingExecutors: Int = executorDataMap.size

  override def getExecutorIds(): Seq[String] = {
    executorDataMap.keySet.toSeq
  }

  override def maxNumConcurrentTasks(): Int = {
    executorDataMap.values.map { executor =>
      executor.totalCores / scheduler.CPUS_PER_TASK
    }.sum
  }

  /**
   * Request an additional number of executors from the cluster manager.
   * @return whether the request is acknowledged.
   */
  final override def requestExecutors(numAdditionalExecutors: Int): Boolean = {
    if (numAdditionalExecutors < 0) {
      throw new IllegalArgumentException(
        "Attempted to request a negative number of additional executor(s) " +
        s"$numAdditionalExecutors from the cluster manager. Please specify a positive number!")
    }
    logInfo(s"Requesting $numAdditionalExecutors additional executor(s) from the cluster manager")

    val response = synchronized {
      requestedTotalExecutors += numAdditionalExecutors
      numPendingExecutors += numAdditionalExecutors
      logDebug(s"Number of pending executors is now $numPendingExecutors")
      if (requestedTotalExecutors !=
          (numExistingExecutors + numPendingExecutors - executorsPendingToRemove.size)) {
        logDebug(
          s"""requestExecutors($numAdditionalExecutors): Executor request doesn't match:
             |requestedTotalExecutors  = $requestedTotalExecutors
             |numExistingExecutors     = $numExistingExecutors
             |numPendingExecutors      = $numPendingExecutors
             |executorsPendingToRemove = ${executorsPendingToRemove.size}""".stripMargin)
      }

      // Account for executors pending to be added or removed
      doRequestTotalExecutors(requestedTotalExecutors)
    }

    defaultAskTimeout.awaitResult(response)
  }

  /**
   * Update the cluster manager on our scheduling needs. Three bits of information are included
   * to help it make decisions.
   * @param numExecutors The total number of executors we'd like to have. The cluster manager
   *                     shouldn't kill any running executor to reach this number, but,
   *                     if all existing executors were to die, this is the number of executors
   *                     we'd want to be allocated.
   * @param localityAwareTasks The number of tasks in all active stages that have a locality
   *                           preferences. This includes running, pending, and completed tasks.
   * @param hostToLocalTaskCount A map of hosts to the number of tasks from all active stages
   *                             that would like to like to run on that host.
   *                             This includes running, pending, and completed tasks.
   * @return whether the request is acknowledged by the cluster manager.
   */
  final override def requestTotalExecutors(
      numExecutors: Int,
      localityAwareTasks: Int,
      hostToLocalTaskCount: Map[String, Int]
    ): Boolean = {
    if (numExecutors < 0) {
      throw new IllegalArgumentException(
        "Attempted to request a negative number of executor(s) " +
          s"$numExecutors from the cluster manager. Please specify a positive number!")
    }

    val response = synchronized {
      this.requestedTotalExecutors = numExecutors
      this.localityAwareTasks = localityAwareTasks
      this.hostToLocalTaskCount = hostToLocalTaskCount

      numPendingExecutors =
        math.max(numExecutors - numExistingExecutors + executorsPendingToRemove.size, 0)

      doRequestTotalExecutors(numExecutors)
    }

    defaultAskTimeout.awaitResult(response)
  }

  /**
   * Request executors from the cluster manager by specifying the total number desired,
   * including existing pending and running executors.
   *
   * The semantics here guarantee that we do not over-allocate executors for this application,
   * since a later request overrides the value of any prior request. The alternative interface
   * of requesting a delta of executors risks double counting new executors when there are
   * insufficient resources to satisfy the first request. We make the assumption here that the
   * cluster manager will eventually fulfill all requests when resources free up.
   *
   * @return a future whose evaluation indicates whether the request is acknowledged.
   */
  protected def doRequestTotalExecutors(requestedTotal: Int): Future[Boolean] =
    Future.successful(false)

  /**
   * Request that the cluster manager kill the specified executors.
   *
   * @param executorIds identifiers of executors to kill
   * @param adjustTargetNumExecutors whether the target number of executors be adjusted down
   *                                 after these executors have been killed
   * @param countFailures if there are tasks running on the executors when they are killed, whether
   *                      those failures be counted to task failure limits?
   * @param force whether to force kill busy executors, default false
   * @return the ids of the executors acknowledged by the cluster manager to be removed.
   */
  final override def killExecutors(
      executorIds: Seq[String],
      adjustTargetNumExecutors: Boolean,
      countFailures: Boolean,
      force: Boolean): Seq[String] = {
    logInfo(s"Requesting to kill executor(s) ${executorIds.mkString(", ")}")

    val response = synchronized {
      val (knownExecutors, unknownExecutors) = executorIds.partition(executorDataMap.contains)
      unknownExecutors.foreach { id =>
        logWarning(s"Executor to kill $id does not exist!")
      }

      // If an executor is already pending to be removed, do not kill it again (SPARK-9795)
      // If this executor is busy, do not kill it unless we are told to force kill it (SPARK-9552)
      val executorsToKill = knownExecutors
        .filter { id => !executorsPendingToRemove.contains(id) }
        .filter { id => force || !scheduler.isExecutorBusy(id) }
      executorsToKill.foreach { id => executorsPendingToRemove(id) = !countFailures }

      logInfo(s"Actual list of executor(s) to be killed is ${executorsToKill.mkString(", ")}")

      // If we do not wish to replace the executors we kill, sync the target number of executors
      // with the cluster manager to avoid allocating new ones. When computing the new target,
      // take into account executors that are pending to be added or removed.
      val adjustTotalExecutors =
        if (adjustTargetNumExecutors) {
          requestedTotalExecutors = math.max(requestedTotalExecutors - executorsToKill.size, 0)
          if (requestedTotalExecutors !=
              (numExistingExecutors + numPendingExecutors - executorsPendingToRemove.size)) {
            logDebug(
              s"""killExecutors($executorIds, $adjustTargetNumExecutors, $countFailures, $force):
                 |Executor counts do not match:
                 |requestedTotalExecutors  = $requestedTotalExecutors
                 |numExistingExecutors     = $numExistingExecutors
                 |numPendingExecutors      = $numPendingExecutors
                 |executorsPendingToRemove = ${executorsPendingToRemove.size}""".stripMargin)
          }
          doRequestTotalExecutors(requestedTotalExecutors)
        } else {
          numPendingExecutors += executorsToKill.size
          Future.successful(true)
        }

      val killExecutors: Boolean => Future[Boolean] =
        if (!executorsToKill.isEmpty) {
          _ => doKillExecutors(executorsToKill)
        } else {
          _ => Future.successful(false)
        }

      val killResponse = adjustTotalExecutors.flatMap(killExecutors)(ThreadUtils.sameThread)

      killResponse.flatMap(killSuccessful =>
        Future.successful (if (killSuccessful) executorsToKill else Seq.empty[String])
      )(ThreadUtils.sameThread)
    }

    defaultAskTimeout.awaitResult(response)
  }

  /**
   * Kill the given list of executors through the cluster manager.
   * @return whether the kill request is acknowledged.
   */
  protected def doKillExecutors(executorIds: Seq[String]): Future[Boolean] =
    Future.successful(false)

  /**
   * Request that the cluster manager kill all executors on a given host.
   * @return whether the kill request is acknowledged.
   */
  final override def killExecutorsOnHost(host: String): Boolean = {
    logInfo(s"Requesting to kill any and all executors on host ${host}")
    // A potential race exists if a new executor attempts to register on a host
    // that is on the blacklist and is no no longer valid. To avoid this race,
    // all executor registration and killing happens in the event loop. This way, either
    // an executor will fail to register, or will be killed when all executors on a host
    // are killed.
    // Kill all the executors on this host in an event loop to ensure serialization.
    driverEndpoint.send(KillExecutorsOnHost(host))
    true
  }

  /**
   * Create the delegation token manager to be used for the application. This method is called
   * once during the start of the scheduler backend (so after the object has already been
   * fully constructed), only if security is enabled in the Hadoop configuration.
   */
  protected def createTokenManager(): Option[HadoopDelegationTokenManager] = None

  /**
   * Called when a new set of delegation tokens is sent to the driver. Child classes can override
   * this method but should always call this implementation, which handles token distribution to
   * executors.
   */
  protected def updateDelegationTokens(tokens: Array[Byte]): Unit = {
    SparkHadoopUtil.get.addDelegationTokens(tokens, conf)
    delegationTokens.set(tokens)
    executorDataMap.values.foreach { ed =>
      ed.executorEndpoint.send(UpdateDelegationTokens(tokens))
    }
  }

  protected def currentDelegationTokens: Array[Byte] = delegationTokens.get()

}

private[spark] object CoarseGrainedSchedulerBackend {
  val ENDPOINT_NAME = "CoarseGrainedScheduler"
}
