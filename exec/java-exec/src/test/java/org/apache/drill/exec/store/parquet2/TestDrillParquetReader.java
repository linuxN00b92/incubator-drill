/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.parquet2;

import org.apache.drill.BaseTestQuery;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.planner.physical.PlannerSettings;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.math.BigDecimal;

public class TestDrillParquetReader extends BaseTestQuery {
  // enable decimal data type
  @BeforeClass
  public static void enableDecimalDataType() throws Exception {
    setOption(PlannerSettings.ENABLE_DECIMAL_DATA_TYPE, true);
  }

  @AfterClass
  public static void disableDecimalDataType() throws Exception {
    resetOption(PlannerSettings.ENABLE_DECIMAL_DATA_TYPE);
  }

  private void testColumn(String columnName) throws Exception {
    try {
      setOption(ExecConstants.PARQUET_RECORD_READER_IMPLEMENTATION, true);

      BigDecimal result = new BigDecimal("1.20000000");

      testBuilder()
        .ordered()
        .sqlQuery("select %s from cp.`parquet2/decimal28_38.parquet`", columnName)
        .baselineColumns(columnName)
        .baselineValues(result)
        .go();
    } finally {
      resetOption(ExecConstants.PARQUET_RECORD_READER_IMPLEMENTATION);
    }
  }

  @Test
  public void testRequiredDecimal28() throws Exception {
    testColumn("d28_req");
  }

  @Test
  public void testRequiredDecimal38() throws Exception {
    testColumn("d38_req");
  }

  @Test
  public void testOptionalDecimal28() throws Exception {
    testColumn("d28_opt");
  }

  @Test
  public void testOptionalDecimal38() throws Exception {
    testColumn("d38_opt");
  }
}
