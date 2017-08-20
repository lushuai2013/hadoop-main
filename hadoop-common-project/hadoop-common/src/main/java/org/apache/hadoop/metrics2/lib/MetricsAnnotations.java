/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.metrics2.lib;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.metrics2.MetricsSource;

/**
 * Metrics注解工工具类
 * Metrics annotation helpers.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class MetricsAnnotations {
  /**
   * 创建了一个MetricsSourceBuilder，并将需要转换的源对象作为构造函数的第一个参数传入。
   * 当MetricsSourceBuilder对象创建完成之后，调用该对象的build方法，将之前构造函数传入的source对象转换成MetricsSource对象。
   * 该build方法最终返回的就是一个新建匿名类对象，实现了MetricsSource接口的getMetrics
   * Make an metrics source from an annotated object.
   * @param source  the annotated object.
   * @return a metrics source
   */
  public static MetricsSource makeSource(Object source) {
    return new MetricsSourceBuilder(source,
        DefaultMetricsFactory.getAnnotatedMetricsFactory()).build();
  }

  /**
   * 创建了一个MetricsSourceBuilder，并将需要转换的源对象作为构造函数的第一个参数传入。
   * @param source
   * @return
     */
  public static MetricsSourceBuilder newSourceBuilder(Object source) {
    return new MetricsSourceBuilder(source,
        DefaultMetricsFactory.getAnnotatedMetricsFactory());
  }
}
