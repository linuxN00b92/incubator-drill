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
package org.apache.drill.exec.physical.impl.writer;

import static org.junit.Assert.assertEquals;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.drill.BaseTestQuery;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.proto.UserBitShared;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.HyperVectorWrapper;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.RecordBatchLoader;
import org.apache.drill.exec.record.VectorWrapper;
import org.apache.drill.exec.rpc.user.QueryResultBatch;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class TestParquetWriter extends BaseTestQuery {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TestParquetWriter.class);

  static FileSystem fs;

  @BeforeClass
  public static void initFs() throws Exception {
    Configuration conf = new Configuration();
    conf.set("fs.name.default", "local");

    fs = FileSystem.get(conf);
  }

  @Test
  public void testSimple() throws Exception {
    String selection = "*";
    String inputTable = "cp.`employee.json`";
    runTestAndValidate(selection, selection, inputTable, "employee_parquet");
  }

  @Test
  public void testComplex() throws Exception {
    String selection = "*";
    String inputTable = "cp.`donuts.json`";
    runTestAndValidate(selection, selection, inputTable, "donuts_json");
  }

  @Test
  public void testComplexRepeated() throws Exception {
    String selection = "*";
    String inputTable = "cp.`testRepeatedWrite.json`";
    runTestAndValidate(selection, selection, inputTable, "repeated_json");
  }

  @Test
  public void testCastProjectBug_Drill_929() throws Exception {
    String selection = "L_ORDERKEY, L_PARTKEY, L_SUPPKEY, L_LINENUMBER, L_QUANTITY, L_EXTENDEDPRICE, L_DISCOUNT, L_TAX, " +
        "L_RETURNFLAG, L_LINESTATUS, L_SHIPDATE, cast(L_COMMITDATE as DATE) as COMMITDATE, cast(L_RECEIPTDATE as DATE) AS RECEIPTDATE, L_SHIPINSTRUCT, L_SHIPMODE, L_COMMENT";
    String validationSelection = "L_ORDERKEY, L_PARTKEY, L_SUPPKEY, L_LINENUMBER, L_QUANTITY, L_EXTENDEDPRICE, L_DISCOUNT, L_TAX, " +
        "L_RETURNFLAG, L_LINESTATUS, L_SHIPDATE,COMMITDATE ,RECEIPTDATE, L_SHIPINSTRUCT, L_SHIPMODE, L_COMMENT";
    String inputTable = "cp.`tpch/lineitem.parquet`";
    String query = String.format("SELECT %s FROM %s", selection, inputTable);
    List<QueryResultBatch> expected = testSqlWithResults(query);
    BatchSchema schema = null;
    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    List<Map> expectedRecords = new ArrayList<>();
    // read the data out of the results, the error manifested itself upon call of getObject on the vectors as they had contained deadbufs
    addToMaterializedResults(expectedRecords, expected, loader, schema);
    for (QueryResultBatch result : expected) {
      result.release();
    }
}

  @Test
  public void testTPCHReadWrite1() throws Exception {
    String inputTable = "cp.`tpch/lineitem.parquet`";
    runTestAndValidate("*", "*", inputTable, "lineitem_parquet_all");
  }

  @Test
  public void testTPCHReadWrite1_date_convertedType() throws Exception {
    String selection = "L_ORDERKEY, L_PARTKEY, L_SUPPKEY, L_LINENUMBER, L_QUANTITY, L_EXTENDEDPRICE, L_DISCOUNT, L_TAX, " +
        "L_RETURNFLAG, L_LINESTATUS, L_SHIPDATE, cast(L_COMMITDATE as DATE) as L_COMMITDATE, cast(L_RECEIPTDATE as DATE) AS L_RECEIPTDATE, L_SHIPINSTRUCT, L_SHIPMODE, L_COMMENT";
    String validationSelection = "L_ORDERKEY, L_PARTKEY, L_SUPPKEY, L_LINENUMBER, L_QUANTITY, L_EXTENDEDPRICE, L_DISCOUNT, L_TAX, " +
        "L_RETURNFLAG, L_LINESTATUS, L_SHIPDATE,L_COMMITDATE ,L_RECEIPTDATE, L_SHIPINSTRUCT, L_SHIPMODE, L_COMMENT";
    String inputTable = "cp.`tpch/lineitem.parquet`";
    runTestAndValidate(selection, validationSelection, inputTable, "lineitem_parquet_converted");
  }

  @Test
  public void testTPCHReadWrite2() throws Exception {
    String inputTable = "cp.`tpch/customer.parquet`";
    runTestAndValidate("*", "*", inputTable, "customer_parquet");
  }

  @Test
  public void testTPCHReadWrite3() throws Exception {
    String inputTable = "cp.`tpch/nation.parquet`";
    runTestAndValidate("*", "*", inputTable, "nation_parquet");
  }

  @Test
  public void testTPCHReadWrite4() throws Exception {
    String inputTable = "cp.`tpch/orders.parquet`";
    runTestAndValidate("*", "*", inputTable, "orders_parquet");
  }

  @Test
  public void testTPCHReadWrite5() throws Exception {
    String inputTable = "cp.`tpch/part.parquet`";
    runTestAndValidate("*", "*", inputTable, "part_parquet");
  }

  @Test
  public void testTPCHReadWrite6() throws Exception {
    String inputTable = "cp.`tpch/partsupp.parquet`";
    runTestAndValidate("*", "*", inputTable, "partsupp_parquet");
  }

  @Test
  public void testTPCHReadWrite7() throws Exception {
    String inputTable = "cp.`tpch/region.parquet`";
    runTestAndValidate("*", "*", inputTable, "region_parquet");
  }

  @Test
  public void testTPCHReadWrite8() throws Exception {
    String inputTable = "cp.`tpch/supplier.parquet`";
    runTestAndValidate("*", "*", inputTable, "supplier_parquet");
  }

  // working to create an exhaustive test of the format for this one. including all convertedTypes
  // will not be supporting interval for Beta as of current schedule
  // Types left out:
  // "TIMESTAMPTZ_col"
  @Test
  public void testRepeated() throws Exception {
    String inputTable = "cp.`parquet/basic_repeated.json`";
    runTestAndValidate("*", "*", inputTable, "basic_repeated");
  }

  @Test
  public void testRepeatedDouble() throws Exception {
    String inputTable = "cp.`parquet/repeated_double_data.json`";
    runTestAndValidate("*", "*", inputTable, "repeated_double_parquet");
  }

  @Test
  public void testRepeatedLong() throws Exception {
    String inputTable = "cp.`parquet/repeated_integer_data.json`";
    runTestAndValidate("*", "*", inputTable, "repeated_int_parquet");
  }

  @Test
  public void testRepeatedBool() throws Exception {
    String inputTable = "cp.`parquet/repeated_bool_data.json`";
    runTestAndValidate("*", "*", inputTable, "repeated_bool_parquet");
  }

  @Test
  public void testNullReadWrite() throws Exception {
    String inputTable = "cp.`parquet/null_test_data.json`";
    runTestAndValidate("*", "*", inputTable, "nullable_test");
  }

  @Test
  public void testDecimal() throws Exception {
    String selection = "cast(salary as decimal(8,2)) as decimal8, cast(salary as decimal(15,2)) as decimal15, " +
        "cast(salary as decimal(24,2)) as decimal24, cast(salary as decimal(38,2)) as decimal38";
    String validateSelection = "decimal8, decimal15, decimal24, decimal38";
    String inputTable = "cp.`employee.json`";
    runTestAndValidate(selection, validateSelection, inputTable, "parquet_decimal");
  }

  @Test
  public void testMulipleRowGroups() throws Exception {
    try {
      //test(String.format("ALTER SESSION SET `%s` = %d", ExecConstants.PARQUET_BLOCK_SIZE, 1*1024*1024));
      String selection = "mi";
      String inputTable = "cp.`customer.json`";
      int count = testRunAndPrint(UserBitShared.QueryType.SQL, "select mi from cp.`customer.json`");
      System.out.println(count);
      runTestAndValidate(selection, selection, inputTable, "foodmart_customer_parquet");
    } finally {
      test(String.format("ALTER SESSION SET `%s` = %d", ExecConstants.PARQUET_BLOCK_SIZE, 512*1024*1024));
    }
  }


  @Test
  public void testDate() throws Exception {
    String selection = "cast(hire_date as DATE) as hire_date";
    String validateSelection = "hire_date";
    String inputTable = "cp.`employee.json`";
    runTestAndValidate(selection, validateSelection, inputTable, "foodmart_employee_parquet");
  }

  public void compareParquetReaders(String selection, String table) throws Exception {
    test("alter system set `store.parquet.use_new_reader` = true");
    List<QueryResultBatch> expected = testSqlWithResults("select " + selection + " from " + table);

    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    BatchSchema schema = null;

    List<Map> expectedRecords = new ArrayList<>();
    addToMaterializedResults(expectedRecords, expected, loader, schema);

    test("alter system set `store.parquet.use_new_reader` = false");
    List<QueryResultBatch> results = testSqlWithResults("select " + selection + " from " + table);

    List<Map> actualRecords = new ArrayList<>();
    addToMaterializedResults(actualRecords, results, loader, schema);
    compareResults(expectedRecords, actualRecords);
    for (QueryResultBatch result : results) {
      result.release();
    }
    for (QueryResultBatch result : expected) {
      result.release();
    }
  }

  public void compareParquetReadersColumnar(String selection, String table) throws Exception {
    test("alter system set `store.parquet.use_new_reader` = true");
    List<QueryResultBatch> expected = testSqlWithResults("select " + selection + " from " + table);

    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    BatchSchema schema = null;

    Map<String, List> expectedSuperVectors = addToCombinedVectorResults(expected, loader, schema);

    test("alter system set `store.parquet.use_new_reader` = false");
    List<QueryResultBatch> results = testSqlWithResults("select " + selection + " from " + table);

    Map<String, List> actualSuperVectors = addToCombinedVectorResults(results, loader, schema);
    compareMergedVectors(expectedSuperVectors, actualSuperVectors);
    for (QueryResultBatch result : results) {
      result.release();
    }
    for (QueryResultBatch result : expected) {
      result.release();
    }
  }

  public void compareParquetReadersHyperVector(String selection, String table) throws Exception {
    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    BatchSchema schema = null;

    // TODO - It didn't seem to respect the max width per node setting, so I went in and modified the SimpleParalellizer directly.
    // I backed out the changes after the test passed.
//    test("alter system set `planner.width.max_per_node` = 1");
    test("alter system set `store.parquet.use_new_reader` = false");
    String query = "select " + selection + " from " + table;
    List<QueryResultBatch> results = testSqlWithResults(query);

    Map<String, HyperVectorValueIterator> actualSuperVectors = addToHyperVectorMap(results, loader, schema);

    test("alter system set `store.parquet.use_new_reader` = true");
    List<QueryResultBatch> expected = testSqlWithResults(query);

    Map<String, HyperVectorValueIterator> expectedSuperVectors = addToHyperVectorMap(expected, loader, schema);

    compareHyperVectors(expectedSuperVectors, actualSuperVectors);
    for (QueryResultBatch result : results) {
      result.release();
    }
    for (QueryResultBatch result : expected) {
      result.release();
    }
  }

  @Ignore
  @Test
  public void testReadVoter() throws Exception {
    compareParquetReadersHyperVector("*", "dfs.`/tmp/voter.parquet`");
  }

  @Ignore
  @Test
  public void testReadSf_100_supplier() throws Exception {
    compareParquetReadersHyperVector("*", "dfs.`/tmp/sf100_supplier.parquet`");
  }

  @Ignore
  @Test
  public void testParquetRead_checkNulls_NullsFirst() throws Exception {
    compareParquetReadersColumnar("*", "dfs.`/tmp/parquet_with_nulls_should_sum_100000_nulls_first.parquet`");
  }

  @Ignore
  @Test
  public void testParquetRead_checkNulls() throws Exception {
    compareParquetReadersColumnar("*", "dfs.`/tmp/parquet_with_nulls_should_sum_100000.parquet`");
  }

  @Ignore
  @Test
  public void test958_sql() throws Exception {
    compareParquetReadersHyperVector("ss_ext_sales_price", "dfs.`/tmp/store_sales`");
  }

  @Ignore
  @Test
  public void testReadSf_1_supplier() throws Exception {
    compareParquetReadersHyperVector("*", "dfs.`/tmp/orders_part-m-00001.parquet`");
  }

  @Ignore
  @Test
  public void test958_sql_all_columns() throws Exception {
    compareParquetReadersHyperVector("*",  "dfs.`/tmp/store_sales`");
    compareParquetReadersHyperVector("ss_addr_sk, ss_hdemo_sk", "dfs.`/tmp/store_sales`");
    // TODO - Drill 1388 - this currently fails, but it is an issue with project, not the reader, pulled out the physical plan
    // removed the unneeded project in the plan and ran it against both readers, they outputs matched
//    compareParquetReadersHyperVector("pig_schema,ss_sold_date_sk,ss_item_sk,ss_cdemo_sk,ss_addr_sk, ss_hdemo_sk",
//        "dfs.`/tmp/store_sales`");
  }

  @Ignore
  @Test
  public void testDrill_1314() throws Exception {
    compareParquetReadersColumnar("l_partkey ", "dfs.`/tmp/drill_1314.parquet`");
  }

  @Ignore
  @Test
  public void testDrill_1314_all_columns() throws Exception {
    compareParquetReadersHyperVector("*", "dfs.`/tmp/drill_1314.parquet`");
    compareParquetReadersColumnar("l_orderkey,l_partkey,l_suppkey,l_linenumber, l_quantity, l_extendedprice,l_discount,l_tax",
        "dfs.`/tmp/drill_1314.parquet`");
  }

  @Ignore
  @Test
  public void testParquetRead_checkShortNullLists() throws Exception {
    compareParquetReadersColumnar("*", "dfs.`/tmp/short_null_lists.parquet`");
  }

  @Ignore
  @Test
  public void testParquetRead_checkStartWithNull() throws Exception {
    compareParquetReadersColumnar("*", "dfs.`/tmp/start_with_null.parquet`");
  }

  @Ignore
  @Test
  public void testParquetReadWebReturns() throws Exception {
    compareParquetReadersColumnar("wr_returning_customer_sk", "dfs.`/tmp/web_returns`");
  }

  public void runTestAndValidate(String selection, String validationSelection, String inputTable, String outputFile) throws Exception {

    Path path = new Path("/tmp/" + outputFile);
    if (fs.exists(path)) {
      fs.delete(path, true);
    }

    test("use dfs.tmp");
//    test("ALTER SESSION SET `planner.add_producer_consumer` = false");
    String query = String.format("SELECT %s FROM %s", selection, inputTable);
    String create = "CREATE TABLE " + outputFile + " AS " + query;
    String validateQuery = String.format("SELECT %s FROM " + outputFile, validationSelection);
    test(create);

    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    BatchSchema schema = null;

    List<QueryResultBatch> expected = testSqlWithResults(query);
    List<Map> expectedRecords = new ArrayList<>();
    addToMaterializedResults(expectedRecords, expected, loader, schema);

    List<QueryResultBatch> results = testSqlWithResults(validateQuery);
    List<Map> actualRecords = new ArrayList<>();
    addToMaterializedResults(actualRecords, results, loader, schema);

    compareResults(expectedRecords, actualRecords);
    for (QueryResultBatch result : results) {
      result.release();
    }
    for (QueryResultBatch result : expected) {
      result.release();
    }
  }

}
