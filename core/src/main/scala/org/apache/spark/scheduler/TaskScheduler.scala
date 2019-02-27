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

package org.apache.spark.scheduler

import org.apache.spark.executor.ExecutorMetrics
import org.apache.spark.scheduler.SchedulingMode.SchedulingMode
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.util.AccumulatorV2

/**
 * Low-level task scheduler interface, currently implemented exclusively by
 * [[org.apache.spark.scheduler.TaskSchedulerImpl]].
 * This interface allows plugging in different task schedulers. Each TaskScheduler schedules tasks
 * for a single SparkContext. These schedulers get sets of tasks submitted to them from the
 * DAGScheduler for each stage, and are responsible for sending the tasks to the cluster, running
 * them, retrying if there are failures, and mitigating stragglers. They return events to the
 * DAGScheduler.
 */
private[spark] trait TaskScheduler {

  private val appId = "spark-application-" + System.currentTimeMillis

  def rootPool: Pool

  def schedulingMode: SchedulingMode

  def start(): Unit

  /**
    * 系统成功初始化后调用(通常在spark context中)
    * yarn 通过它来选择首选位置 分配资源等
    * 等待slave注册等
    */
  def postStartHook() { }

  // 断开集群连接
  def stop(): Unit

  // 提交一个tesk 队列运行
  def submitTasks(taskSet: TaskSet): Unit

  // 杀死stage中的所有task，并且使该stage以及所有依赖该stage的stage失败
  // 如果后台调度器不支持杀掉task则抛出异常UnsupportedOperationException
  def cancelTasks(stageId: Int, interruptThread: Boolean): Unit

  /**
   * Kills a task attempt.
   * Throw UnsupportedOperationException if the backend doesn't support kill a task.
   *
   * @return Whether the task was successfully killed.
   */
  def killTaskAttempt(taskId: Long, interruptThread: Boolean, reason: String): Boolean

  // Kill all the running task attempts in a stage.
  // Throw UnsupportedOperationException if the backend doesn't support kill tasks.
  def killAllTaskAttempts(stageId: Int, interruptThread: Boolean, reason: String): Unit

  // 将DAG调度器设置为upcalls , 保证在submitTasks 之前调用
  def setDAGScheduler(dagScheduler: DAGScheduler): Unit

  // 获取集群中使用的默认的并行度级别,应用到job
  def defaultParallelism(): Int

  /**
    * 更新正在执行任务状态,让master知道BlockManager is alive ,
    * 如果驱动程序知道该BlockManager 返回true,  否则返回false, 表示该BlockManager应该重新注册
    * @param execId
    * @param accumUpdates
    * @param blockManagerId
    * @param executorUpdates
    * @return
    */
  def executorHeartbeatReceived(
      execId: String,
      accumUpdates: Array[(Long, Seq[AccumulatorV2[_, _]])],
      blockManagerId: BlockManagerId,
      executorUpdates: ExecutorMetrics): Boolean

  /**
   * 获取与job相关联的应用id
   * @return An application ID
   */
  def applicationId(): String = appId

  /**
   * 进程丢失执行器
   */
  def executorLost(executorId: String, reason: ExecutorLossReason): Unit

  /**
   * 进程 removed worker
   */
  def workerRemoved(workerId: String, host: String, message: String): Unit
  /**
   * 获取与job相关的应用的attempt id
   *
   * @return An application's Attempt ID
   */
  def applicationAttemptId(): Option[String]

}
