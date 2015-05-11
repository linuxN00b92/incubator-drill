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

package org.apache.drill.exec.fn.impl;

import org.apache.drill.BaseTestQuery;
import org.apache.drill.common.util.FileUtils;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.planner.physical.PlannerSettings;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestCastEmptyStrings extends BaseTestQuery {
    // enable decimal data type
    @BeforeClass
    public static void enableDecimalDataType() throws Exception {
      setOption(PlannerSettings.ENABLE_DECIMAL_DATA_TYPE, true);
      // Enable the new cast functions (cast empty string "" to null)
      test(String.format("alter system set `%s` = true", ExecConstants.CAST_TO_NULLABLE_NUMERIC_OPTION.name()));
    }

    @AfterClass
    public static void disableDecimalDataType() throws Exception {
      resetOption(PlannerSettings.ENABLE_DECIMAL_DATA_TYPE);
      // Enable the new cast functions (cast empty string "" to null)
      test(String.format("alter system set `%s` = false", ExecConstants.CAST_TO_NULLABLE_NUMERIC_OPTION.name()));
    }

    @Test // see DRILL-1874
    public void testCastInputTypeNullableVarCharToNumeric() throws Exception {
      String root = FileUtils.getResourceAsFile("/emptyStrings.csv").toURI().toString();

      // Test Optional VarChar
      test(String.format("select cast(columns[0] as int) from dfs_test.`%s`;", root));
      test(String.format("select cast(columns[0] as bigint) from dfs_test.`%s`;", root));
      test(String.format("select cast(columns[0] as float) from dfs_test.`%s`;", root));
      test(String.format("select cast(columns[0] as double) from dfs_test.`%s`;", root));
    }

    @Test // see DRILL-1874
    public void testCastInputTypeNonNullableVarCharToNumeric() throws Exception {
      String root = FileUtils.getResourceAsFile("/emptyStrings.csv").toURI().toString();

      // Test Required VarChar
      test(String.format("select cast('' as int) from dfs_test.`%s`;", root));
      test(String.format("select cast('' as bigint) from dfs_test.`%s`;", root));
      test(String.format("select cast('' as float) from dfs_test.`%s`;", root));
      test(String.format("select cast('' as double) from dfs_test.`%s`;", root));
    }

    @Test // see DRILL-1874
    public void testCastInputTypeNullableVarCharToDecimal() throws Exception {
      String root = FileUtils.getResourceAsFile("/emptyStrings.csv").toURI().toString();

      // Test Optional VarChar
      test(String.format("select cast(columns[0] as decimal) from dfs_test.`%s` where cast(columns[0] as decimal) is null;", root));
      test(String.format("select cast(columns[0] as decimal(9)) from dfs_test.`%s`;", root));
      test(String.format("select cast(columns[0] as decimal(18)) from dfs_test.`%s`;", root));
      test(String.format("select cast(columns[0] as decimal(28)) from dfs_test.`%s`;", root));
      test(String.format("select cast(columns[0] as decimal(38)) from dfs_test.`%s`;", root));
    }

    @Test // see DRILL-1874
    public void testCastInputTypeNonNullableVarCharToDecimal() throws Exception {
      String root = FileUtils.getResourceAsFile("/emptyStrings.csv").toURI().toString();

      // Test Required VarChar
      test(String.format("select cast('' as decimal) from dfs_test.`%s` where cast('' as decimal) is null;", root));
      test(String.format("select cast('' as decimal(18)) from dfs_test.`%s`;", root));
      test(String.format("select cast('' as decimal(28)) from dfs_test.`%s`;", root));
      test(String.format("select cast('' as decimal(38)) from dfs_test.`%s`;", root));
    }
}
