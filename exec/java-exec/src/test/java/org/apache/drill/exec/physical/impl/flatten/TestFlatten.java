/*******************************************************************************
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
 ******************************************************************************/
package org.apache.drill.exec.physical.impl.flatten;

import org.apache.drill.BaseTestQuery;
import org.junit.Ignore;
import org.junit.Test;

public class TestFlatten extends BaseTestQuery {

  @Test
  public void testFlattenFailure() throws Exception {
    test("select flatten(complex), rownum from cp.`/store/json/test_flatten_mappify2.json`");
//    test("select complex, rownum from cp.`/store/json/test_flatten_mappify2.json`");
  }

  @Test
  public void testKVGenFlatten1() throws Exception {
    // works - TODO and verify results
    test("select flatten(kvgen(f1)) as monkey, x " +
        "from cp.`/store/json/test_flatten_mapify.json`");
  }

  @Test
  public void testTwoFlattens() throws Exception {
    // second re-write rule has been added to test the fixes together, this now runs
    test("select `integer`, `float`, x, flatten(z), flatten(l) from cp.`/jsoninput/input2_modified.json`");
  }

  @Test
  public void testFlattenRepeatedMap() throws Exception {
    test("select `integer`, `float`, x, flatten(z) from cp.`/jsoninput/input2.json`");
  }

  @Test
  public void testFlattenKVGenFlatten() throws Exception {
    // currently does not fail, but produces incorrect results, requires second re-write rule to split up expressions
    // with complex outputs
    test("select `integer`, `float`, x, flatten(kvgen(flatten(z))) from cp.`/jsoninput/input2.json`");
  }

  @Test
  public void testKVGenFlatten2() throws Exception {
    // currently runs
    // TODO - re-verify results by hand
    test("select flatten(kvgen(visited_cellid_counts)) as mytb from dfs.`/tmp/ericsson.json`") ;
  }

  @Test
  public void testFilterFlattenedRecords() throws Exception {
    // WORKS!!
    // TODO - hand verify results
    test("select t2.key from (select t.monkey.`value` as val, t.monkey.key as key from (select flatten(kvgen(f1)) as monkey, x " +
        "from cp.`/store/json/test_flatten_mapify.json`) as t) as t2 where t2.val > 1");
  }

  @Test
  public void testFilterFlattenedRecords2() throws Exception {
    // previously failed in generated code
    //  "value" is neither a method, a field, nor a member class of "org.apache.drill.exec.expr.holders.RepeatedVarCharHolder" [ 42eb1fa1-0742-4e4f-8723-609215c18900 on 10.250.0.86:31010 ]
    // appears to be resolving the data coming out of flatten as repeated, check fast schema stuff

    // FIXED BY RETURNING PROPER SCHEMA DURING FAST SCHEMA STEP
    // these types of problems are being solved more generally as we develp better support for chaning schema
    test("select celltbl.catl from (\n" +
        "        select flatten(categories) catl from dfs.`/tmp/yelp_academic_dataset_business.json` b limit 100\n" +
        "    )  celltbl where celltbl.catl = 'Doctors'");
  }

  @Test
  public void testCountAggFlattened() throws Exception {
    // WORKS!! - requires new fix for fast schema
    test("select celltbl.catl, count(celltbl.catl) from ( " +
        "select business_id, flatten(categories) catl from dfs.`/tmp/yelp_academic_dataset_business.json` b limit 100 " +
        ")  celltbl group by celltbl.catl limit 10 ");
  }


  @Ignore
  @Test
  public void tstFlattenAndAdditionalColumn() throws Exception {
    // currently hangs, allocating indefinitely
    test("select business_id, flatten(categories) from dfs.`/tmp/yelp_academic_dataset_business.json` b");
  }

  @Ignore
  @Test
  public void testFailingFlattenAlone() throws Exception {
    // no records, also allocating indefintely, allocations are all tiny
    test("select flatten(categories) from dfs.`/tmp/yelp_academic_dataset_business.json` b  ");
  }

  @Ignore
  @Test
  public void testDistinctAggrFlattened() throws Exception {
    // weird tiny memory allocation until timeout
    test(" select distinct(celltbl.catl) from (\n" +
        "        select flatten(categories) catl from dfs.`/tmp/yelp_academic_dataset_business.json` b\n" +
        "    )  celltbl");
  }

}
