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

/**
  * 用于调度系统的后端接口，允许 TaskSchedulerImpl 对接不同的系统。
  * 我们假设一个类似Mesos的模型，其中应用程序在机器可用时获得资源，并可以在它们上启动任务。
 */
private[spark] trait SchedulerBackend {
  private val appId = "spark-application-" + System.currentTimeMillis

  def start(): Unit
  def stop(): Unit

  /**
    * SchedulerBackend 将自己目前可用资源交给TaskScheduler, 
    * TaskScheduler 根据调度策略分配给排队的任务, 返回一批可执行的任务描述(TaskSet),
    * SchedulerBackend 负责launchTask, 即最终把task 塞到executor模型上, executor里的线程池会执行task的run()方法
    */
  def reviveOffers(): Unit
  def defaultParallelism(): Int

  /**
   * 向executor发送请求杀掉一个正在执行的task
    *
   * @param taskId Id of the task.
   * @param executorId Id of the executor the task is running on.
   * @param interruptThread 执行程序是否应该中断任务线程。
   * @param reason 任务终止的原因。
   */
  def killTask(
      taskId: Long,
      executorId: String,
      interruptThread: Boolean,
      reason: String): Unit =
    throw new UnsupportedOperationException

  def isReady(): Boolean = true

  /**
   * 获取与job相关的application id
   *
   * @return An application ID
   */
  def applicationId(): String = appId

  /**
   *  如果集群管理器支持多次尝试，请获取此运行的attempt ID。 在客户端模式下运行的应用程序将没有attempt ID。
   *
   * @return The application attempt id, if available.
   */
  def applicationAttemptId(): Option[String] = None

  /**
    * 获取驱动程序日志的URL。
    * 这些URL用于显示Driver 的UI Executors选项卡中的链接。
   * @return Map containing the log names and their respective URLs
   */
  def getDriverLogUrls: Option[Map[String, String]] = None

  /**
   * 获取driver的属性。 当指定自定义日志URL模式时，这些属性用于替换日志URL。
   * @return Map containing attributes on driver.
   */
  def getDriverAttributes: Option[Map[String, String]] = None

  /**
    * 获取当前可以同时启动的最大任务数。
    * 请注意，请不要缓存此方法返回的值，因为该数字可能会因添加/删除执行程序而更改。
   *
   * @return The max number of tasks that can be concurrent launched currently.
   */
  def maxNumConcurrentTasks(): Int

}
