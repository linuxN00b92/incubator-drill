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
package org.apache.drill.exec.client;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.exec.client.QuerySubmitter.Format;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.memory.TopLevelAllocator;
import org.apache.drill.exec.proto.UserBitShared.QueryId;
import org.apache.drill.exec.record.RecordBatchLoader;
import org.apache.drill.exec.rpc.RpcException;
import org.apache.drill.exec.rpc.user.ConnectionThrottle;
import org.apache.drill.exec.rpc.user.QueryResultBatch;
import org.apache.drill.exec.rpc.user.UserResultsListener;
import org.apache.drill.exec.util.VectorUtil;

public class PrintingResultsListener implements UserResultsListener {
  AtomicInteger count = new AtomicInteger();
  private CountDownLatch latch = new CountDownLatch(1);
  RecordBatchLoader loader;
  Format format;
  int    columnWidth;
  BufferAllocator allocator;
  volatile Exception exception;
  QueryId queryId;
  PrintWriter pw;
  String outFile;

  public PrintingResultsListener(DrillConfig config, Format format, int columnWidth) {
    this(config, format, new PrintWriter(System.out), columnWidth);
  }

  public PrintingResultsListener(DrillConfig config, Format format, int columnWidth, String outFile) {

    ByteArrayOutputStream os = new ByteArrayOutputStream();
    PrintWriter writer;
    try {
      writer = new PrintWriter(outFile, "UTF-8");
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }

    this.allocator = new TopLevelAllocator(config);
    loader = new RecordBatchLoader(allocator);
    this.format = format;
    this.columnWidth = columnWidth;
    this.pw = writer;
    this.outFile = outFile;
  }

  private PrintingResultsListener(DrillConfig config, Format format, PrintWriter pw, int columnWidth) {
    this.allocator = new TopLevelAllocator(config);
    loader = new RecordBatchLoader(allocator);
    this.format = format;
    this.columnWidth = columnWidth;
    this.pw = pw;
  }

  @Override
  public void submissionFailed(RpcException ex) {
    exception = ex;
    latch.countDown();
  }

  @Override
  public void resultArrived(QueryResultBatch result, ConnectionThrottle throttle) {
    int rows = result.getHeader().getRowCount();
    if (result.getData() != null) {
      count.addAndGet(rows);
      try {
        loader.load(result.getHeader().getDef(), result.getData());
      } catch (SchemaChangeException e) {
        submissionFailed(new RpcException(e));
      }

      switch(format) {
        case TABLE:
          VectorUtil.showVectorAccessibleContent(loader, columnWidth);
          break;
        case TSV:
          VectorUtil.storeVectorAccessibleContent(loader, "\t", pw);
          break;
        case CSV:
          VectorUtil.storeVectorAccessibleContent(loader, ",", pw);
          break;
      }
      loader.clear();
    }

    boolean isLastChunk = result.getHeader().getIsLastChunk();
    result.release();

    if (isLastChunk) {
      // outfile is not null, write out data
      if (outFile != null) {
        pw.close();
      }
      allocator.close();
      latch.countDown();
      System.out.println("Total rows returned : " + count.get());
    }

  }

  public int await() throws Exception {
    latch.await();
    if (exception != null) {
      throw exception;
    }
    return count.get();
  }

  public QueryId getQueryId() {
    return queryId;
  }

  @Override
  public void queryIdArrived(QueryId queryId) {
    this.queryId = queryId;
  }

}
