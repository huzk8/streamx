/*
 * Copyright (c) 2019 The StreamX Project
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.streamxhub.streamx.flink.connector.elasticsearch7.sink

import com.streamxhub.streamx.common.conf.ConfigConst._
import com.streamxhub.streamx.common.util.{ConfigUtils, Logger, Utils}
import com.streamxhub.streamx.flink.connector.elasticsearch7.bean.RestClientFactoryImpl
import com.streamxhub.streamx.flink.connector.elasticsearch7.internal.ESSinkFunction
import com.streamxhub.streamx.flink.connector.function.TransformFunction
import com.streamxhub.streamx.flink.connector.sink.Sink
import com.streamxhub.streamx.flink.core.scala.StreamingContext
import org.apache.flink.streaming.api.datastream.{DataStreamSink, DataStream => JavaDataStream}
import org.apache.flink.streaming.api.scala.DataStream
import org.apache.flink.streaming.connectors.elasticsearch.ActionRequestFailureHandler
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchSinkBase.{CONFIG_KEY_BULK_FLUSH_BACKOFF_DELAY, CONFIG_KEY_BULK_FLUSH_BACKOFF_ENABLE, CONFIG_KEY_BULK_FLUSH_BACKOFF_RETRIES, CONFIG_KEY_BULK_FLUSH_BACKOFF_TYPE, CONFIG_KEY_BULK_FLUSH_INTERVAL_MS, CONFIG_KEY_BULK_FLUSH_MAX_ACTIONS, CONFIG_KEY_BULK_FLUSH_MAX_SIZE_MB, FlushBackoffType}
import org.apache.flink.streaming.connectors.elasticsearch.util.RetryRejectedExecutionFailureHandler
import org.apache.flink.streaming.connectors.elasticsearch7.{ElasticsearchSink, RestClientFactory}
import org.apache.http.HttpHost
import org.elasticsearch.action.ActionRequest

import java.util.Properties
import scala.annotation.meta.param
import scala.collection.JavaConversions._

object ES7Sink {
  def apply(@(transient@param)
            property: Properties = new Properties(),
            parallelism: Int = 0,
            name: String = null,
            uid: String = null)(implicit ctx: StreamingContext): ES7Sink = new ES7Sink(ctx, property, parallelism, name, uid)
}

class ES7Sink(@(transient@param)ctx: StreamingContext,
              property: Properties = new Properties(),
              parallelism: Int = 0,
              name: String = null,
              uid: String = null,
              alias: String = "") extends Sink with Logger {

  def this(ctx: StreamingContext) {
    this(ctx, new Properties(), 0, null, null, "")
  }

  val prop = ConfigUtils.getConf(ctx.parameter.toMap, ES_PREFIX)(alias)

  Utils.copyProperties(property, prop)

  private val httpHosts: Array[HttpHost] = {
    val httpHosts = prop.getOrElse(KEY_HOST, SIGN_EMPTY)
      .split(SIGN_COMMA)
      .map(x => {
        x.split(SIGN_COLON) match {
          case Array(host, port) => new HttpHost(host, port.toInt)
        }
      })
    require(httpHosts.nonEmpty, "elasticsearch config error, please check, e.g: sink.es.host=$host1:$port1,$host2:$port2")
    httpHosts
  }

  private def process[T](stream: DataStream[T],
                 restClientFactory: Option[RestClientFactory],
                 failureHandler: ActionRequestFailureHandler,
                 f: T => ActionRequest): DataStreamSink[T] = {
    require(stream != null, "sink Stream must not null")
    require(f != null, "es process element func must not null")
    val sinkFunc: ESSinkFunction[T] = new ESSinkFunction(f)
    val esSink: _root_.org.apache.flink.streaming.connectors.elasticsearch7.ElasticsearchSink[T] =
      buildESSink(restClientFactory, failureHandler, httpHosts, sinkFunc)
    if (prop.getOrElse(KEY_ES_DISABLE_FLUSH_ONCHECKPOINT, "false").toBoolean) {
      esSink.disableFlushOnCheckpoint()
    }
    val sink = stream.addSink(esSink)
    afterSink(sink, parallelism, name, uid)
  }

  private def process[T](stream: JavaDataStream[T],
                         restClientFactory: Option[RestClientFactory],
                         failureHandler: ActionRequestFailureHandler,
                         f: TransformFunction[T, ActionRequest]): DataStreamSink[T] = {
    require(stream != null, "sink Stream must not null")
    require(f != null, "es process element func must not null")
    val sinkFunc: ESSinkFunction[T] = new ESSinkFunction(f)
    val esSink: _root_.org.apache.flink.streaming.connectors.elasticsearch7.ElasticsearchSink[T] =
      buildESSink(restClientFactory, failureHandler, httpHosts, sinkFunc)
    if (prop.getOrElse(KEY_ES_DISABLE_FLUSH_ONCHECKPOINT, "false").toBoolean) {
      esSink.disableFlushOnCheckpoint()
    }
    val sink = stream.addSink(esSink)
    afterSink(sink, parallelism, name, uid)
  }

  private def buildESSink[T](restClientFactory: Option[RestClientFactory],
                             failureHandler: ActionRequestFailureHandler,
                             httpHosts: Array[HttpHost],
                             sinkFunc: ESSinkFunction[T]): ElasticsearchSink[T] = {
    val sinkBuilder = new ElasticsearchSink.Builder[T](httpHosts.toList, sinkFunc)
    sinkBuilder.setFailureHandler(failureHandler)
    restClientFactory match {
      case Some(factory) =>
        sinkBuilder.setRestClientFactory(factory)
      case None =>
        sinkBuilder.setRestClientFactory(new RestClientFactoryImpl(prop))
    }
    def doConfig(param: (String, String)): Unit = param match {
      // parameter of sink.es.bulk.flush.max.actions
      case (CONFIG_KEY_BULK_FLUSH_MAX_ACTIONS, v) => sinkBuilder.setBulkFlushMaxActions(v.toInt)
      // parameter of sink.es.bulk.flush.max.size.mb
      case (CONFIG_KEY_BULK_FLUSH_MAX_SIZE_MB, v) => sinkBuilder.setBulkFlushMaxSizeMb(v.toInt)
      // parameter of sink.es.bulk.flush.interval.ms
      case (CONFIG_KEY_BULK_FLUSH_INTERVAL_MS, v) => sinkBuilder.setBulkFlushInterval(v.toInt)
      // parameter of sink.es.bulk.flush.backoff.enable
      case (CONFIG_KEY_BULK_FLUSH_BACKOFF_ENABLE, v) => sinkBuilder.setBulkFlushBackoff(v.toBoolean)
      // parameter of sink.es.bulk.flush.backoff.type value of [ CONSTANT or EXPONENTIAL ]
      case (CONFIG_KEY_BULK_FLUSH_BACKOFF_TYPE, v) => sinkBuilder.setBulkFlushBackoffType(FlushBackoffType.valueOf(v))
      // parameter of sink.es.bulk.flush.backoff.retries
      case (CONFIG_KEY_BULK_FLUSH_BACKOFF_RETRIES, v) => sinkBuilder.setBulkFlushBackoffRetries(v.toInt)
      // parameter of sink.es.bulk.flush.backoff.delay
      case (CONFIG_KEY_BULK_FLUSH_BACKOFF_DELAY, v) => sinkBuilder.setBulkFlushBackoffDelay(v.toLong)
      // other...
      case _ =>
    }
    // set value from properties
    prop.filter(_._1.startsWith(KEY_ES_BULK_PREFIX)).foreach(doConfig)
    // set value from method parameter...
    property.forEach((k: Object, v: Object) => doConfig(k.toString, v.toString))
    sinkBuilder.build()
  }

  def sink[T](stream: DataStream[T],
              restClientFactory: Option[RestClientFactory] = None,
              failureHandler: ActionRequestFailureHandler = new RetryRejectedExecutionFailureHandler)
             (implicit f: T => ActionRequest): DataStreamSink[T] = {
    process(stream, restClientFactory, failureHandler, f)
  }

  def sink[T](stream: JavaDataStream[T],
              restClientFactory: RestClientFactory,
              failureHandler: ActionRequestFailureHandler,
              f: TransformFunction[T, ActionRequest]): DataStreamSink[T] = {
    process(stream, Some(restClientFactory), failureHandler, f)
  }

  def sink[T](stream: JavaDataStream[T],
              restClientFactory: RestClientFactory,
              f: TransformFunction[T, ActionRequest]): DataStreamSink[T] = {
    process(stream, Some(restClientFactory), new RetryRejectedExecutionFailureHandler, f)
  }

  def sink[T](stream: JavaDataStream[T],
              f: TransformFunction[T, ActionRequest]): DataStreamSink[T] = {
    process(stream, None, new RetryRejectedExecutionFailureHandler, f)
  }
}
