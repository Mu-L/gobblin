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

package org.apache.gobblin.temporal.ddm.activity;

import java.util.Properties;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import org.apache.gobblin.source.workunit.WorkUnit;
import org.apache.gobblin.temporal.ddm.work.GenerateWorkUnitsResult;
import org.apache.gobblin.temporal.workflows.metrics.EventSubmitterContext;


/** Activity for generating {@link WorkUnit}s and persisting them to the {@link org.apache.hadoop.fs.FileSystem}, per "job properties" */
@ActivityInterface
public interface GenerateWorkUnits {

  public static final String NUM_WORK_UNITS_SIZE_SUMMARY_QUANTILES = GenerateWorkUnits.class.getName() + ".numWorkUnitsSizeInfoQuantiles";
  public static final int DEFAULT_NUM_WORK_UNITS_SIZE_SUMMARY_QUANTILES = 10;


  /** @return the number of {@link WorkUnit}s generated and persisted */
  @ActivityMethod
  GenerateWorkUnitsResult generateWorkUnits(Properties jobProps, EventSubmitterContext eventSubmitterContext);
}
