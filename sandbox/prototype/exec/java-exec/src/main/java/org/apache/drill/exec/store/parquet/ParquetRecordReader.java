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
package org.apache.drill.exec.store.parquet;

import com.beust.jcommander.internal.Maps;
import io.netty.buffer.ByteBuf;
import org.apache.drill.common.exceptions.DrillRuntimeException;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.ExpressionPosition;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.physical.impl.OutputMutator;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.SchemaBuilder;
import org.apache.drill.exec.store.RecordReader;
import org.apache.drill.exec.store.VectorHolder;
import org.apache.drill.exec.vector.BaseDataValueVector;
import org.apache.drill.exec.vector.TypeHelper;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.VarBinaryVector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import parquet.bytes.BytesInput;
import parquet.bytes.BytesUtils;
import parquet.bytes.LittleEndianDataInputStream;
import parquet.column.ColumnDescriptor;
import parquet.column.ColumnReader;
import parquet.column.ColumnWriter;
import parquet.column.impl.ColumnReadStoreImpl;
import parquet.column.impl.ColumnReaderImpl;
import parquet.column.impl.ColumnWriteStoreImpl;
import parquet.column.page.Page;
import parquet.column.page.PageReadStore;
import parquet.column.page.PageReader;
import parquet.column.values.plain.PlainValuesReader;
import parquet.example.DummyRecordConverter;
import parquet.format.Util;
import parquet.hadoop.ColumnChunkPageReadStore;
import parquet.hadoop.ParquetFileReader;
import parquet.hadoop.metadata.BlockMetaData;
import parquet.hadoop.metadata.ColumnChunkMetaData;
import parquet.hadoop.metadata.ParquetMetadata;
import parquet.schema.MessageType;
import parquet.schema.PrimitiveType;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class ParquetRecordReader implements RecordReader {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ParquetRecordReader.class);

  // this value has been inflated to read in multiple value vectors at once, and then break them up into smaller vectors
  private static final int NUMBER_OF_VECTORS = 1;
  private static final long DEFAULT_BATCH_LENGTH = 256 * 1024 * NUMBER_OF_VECTORS; // 256kb
  private static final long DEFAULT_BATCH_LENGTH_IN_BITS = DEFAULT_BATCH_LENGTH * 8; // 256kb

  // TODO - should probably find a smarter way to set this, currently 2 megabytes
  private static final int VAR_LEN_FIELD_LENGTH = 1024 * 1024 * 2;

  private static final String SEPERATOR = System.getProperty("file.separator");

  private ParquetFileReader parquetReader;
  private BatchSchema currentSchema;
  private int bitWidthAllFixedFields;
  private boolean allFieldsFixedLength;
  private int recordsPerBatch;

  private ByteBuf bufferWithAllData;

  long totalRecords;

  //TODO - this will go away when the changes with field ID removed are mreged
  int fieldID;

  // used for clearing the last n bits of a byte
  private byte[] endBitMasks = {-2, -4, -8, -16, -32, -64, -128};

  // used for clearing the first n bits of a byte
  private byte[] startBitMasks = {127, 63, 31, 15, 7, 3, 1};

  private static final class ColumnReadStatus {
    // Value Vector for this column
    VectorHolder valueVecHolder;
    // column description from the parquet library
    ColumnDescriptor columnDescriptor;
    // metadata of the column, from the parquet library
    ColumnChunkMetaData columnChunkMetaData;
    // status information on the current page
    PageReadStatus pageReadStatus;

    //THIS IS HOW WE NEE DOT BE READING EVERYTHING
    ColumnReaderImpl columnReader;
    // quick reference to see if the field is fixed length (as this requires an instanceof)
    boolean isFixedLength;
    // counter for the total number of values read from one or more pages
    // when a batch is filled all of these values should be the same for each column
    int totalValuesRead;
    // counter for the values that have been read in this pass (a single call to the next() method)
    int valuesReadInCurrentPass;
    // length of single data value in bits, if the length is fixed
    int dataTypeLengthInBits;
    // data structure for maintaining the positions in the value vector of variable length values
    // allows for faster determination of a good cutoff point for a batch
    //ValueIndex valueIndex = new ValueIndex(50);

    int bytesReadInCurrentPass;

    // used to keep track of a running average of the lengths of the data values (for variable length fields)
    float averageLength;
    // used to update the averageLength as new values are read
    int valuesInAverage;
    private LittleEndianDataInputStream inputStream;
  }

  // TODO - not sure that this is needed, we have random access in the value vectors
  // data structure for maintaining the positions in the value vector of variable length values
  // allows for faster determination of a good cutoff point for a batch
  private static final class ValueIndex {
    // how frequently and index is stored
    final int valuesPerIndex;
    // an estimation of the average number of bytes a value takes, used to allocate space for the index
    int dataValueLengthEstimate;
    // the estimated percentage of a record this field takes up, for records with a single variable length column
    // this is just dataValueLengthEstimate / (lengthAllFixedFeilds + dataValueLengthEstimate)
    float percentageEstimate;
    int[] index;

    public ValueIndex(int valuesPerIndex, int dataValueLengthEstimate, float percentageEstimate) {
      this.valuesPerIndex = valuesPerIndex;
      this.dataValueLengthEstimate = dataValueLengthEstimate;
      // based upon the total size of a vector, and the estimation of the size of this field
      // create an array large enough to hold the expected number of indexes
      index = new int[(int) (DEFAULT_BATCH_LENGTH_IN_BITS / 8 * percentageEstimate / valuesPerIndex)];
    }

    public void refactorIndex() {

    }

  }

  // class to keep track of the read position of variable length columns
  private static final class PageReadStatus {
    // store references to the pages that have been uncompressed, but not copied to ValueVectors yet
    Page currentPage;
    // the bytes read from the current page
    byte[] bytes;
    PageReader pageReader;
    // read position in the current page, stored in bytes
    long readPosInBytes;
    // bit shift needed for the next page if the last one did not line up with a byte boundary
    int bitShift;
    // storage space for extra bits at the end of a page if they did not line up with a byte boundary
    // prevents the need to keep the entire last page, as these bytes need to be added to the next batch
    //byte extraBits;
    // the number of values read out of the last page
    int valuesRead;

    public boolean next() throws IOException {
      currentPage = pageReader.readPage();
      if (currentPage == null) {
        return false;
      }
      bytes = currentPage.getBytes().toByteArray();
      readPosInBytes = 0;
      valuesRead = 0;
      return true;
    }
  }

  // this class represents a row group, it is named poorly in the parquet library
  private PageReadStore currentRowGroup;
  private Map<MaterializedField, ColumnReadStatus> columnStatuses;


  // would only need this to compare schemas of different row groups
  //List<Footer> footers;
  //Iterator<Footer> footerIter;
  ParquetMetadata footer;
  BytesInput currBytes;

  private OutputMutator outputMutator;
  private BufferAllocator allocator;
  private int currentRowGroupIndex;
  private long batchSize;
  private MessageType schema;

  Path hadoopPath;
  Configuration configuration;
  int rowGroupIndex;

  public ParquetRecordReader(FragmentContext fragmentContext,
                             String path, int rowGroupIndex) throws ExecutionSetupException {
    this(fragmentContext, DEFAULT_BATCH_LENGTH_IN_BITS, path, rowGroupIndex);
  }


  public ParquetRecordReader(FragmentContext fragmentContext, long batchSize,
                             String path, int rowGroupIndex) throws ExecutionSetupException {
    this.allocator = fragmentContext.getAllocator();

    Path hadoopPath = new Path(path);
    Configuration configuration = new Configuration();
    configuration.set("fs.default.name", "maprfs://10.10.30.52/");
    this.rowGroupIndex = rowGroupIndex;
  }

  /**
   * @param type a fixed length type from the parquet library enum
   * @return the length in bytes of the type
   */
  public static int getTypeLengthInBytes(PrimitiveType.PrimitiveTypeName type) {
    switch (type) {
      case INT64:   return 64;
      case INT32:   return 32;
      case BOOLEAN: return 1;
      case FLOAT:   return 32;
      case DOUBLE:  return 64;
      case INT96:   return 96;
      // binary and fixed length byte array
      default:
        throw new IllegalStateException("Length cannot be determined for type " + type);
    }
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    outputMutator = output;
    schema = footer.getFileMetaData().getSchema();
    currentRowGroupIndex = -1;
    columnStatuses = Maps.newHashMap();
    currentRowGroup = null;
    fieldID = 0;

    try {
      footer = ParquetFileReader.readFooter(configuration, hadoopPath);
    } catch (IOException e) {
      e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
    }
    totalRecords = footer.getBlocks().get(rowGroupIndex).getRowCount();

    List<ColumnDescriptor> columns = schema.getColumns();
    allFieldsFixedLength = true;
    ColumnDescriptor column = null;
    ColumnChunkMetaData columnChunkMetaData = null;
    SchemaBuilder builder = BatchSchema.newBuilder();

    // loop to add up the length of the fixed width columns and build the schema
    for (int i = 0; i < columns.size(); ++i) {
      column = columns.get(i);
      MaterializedField field = MaterializedField.create(new SchemaPath(toFieldName(column.getPath()), ExpressionPosition.UNKNOWN),
          toMajorType(column.getType(), getDataMode(column)));

      // sum the lengths of all of the fixed length fields
      if (column.getType() != PrimitiveType.PrimitiveTypeName.BINARY) {
        // There is not support for the fixed binary type yet in parquet, leaving a task here as a reminder
        // TODO - implement this when the feature is added upstream
//          if (column.getType() == PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY){
//              byteWidthAllFixedFields += column.getType().getWidth()
//          }
//          else { } // the code below for the rest of the fixed length fields

        bitWidthAllFixedFields += getTypeLengthInBytes(column.getType());
      } else {
        allFieldsFixedLength = false;
      }

      builder.addField(field);
    }
    currentSchema = builder.build();

    ParquetFileReader parReader = null;
    ParquetMetadata readFooter = null;
    this.batchSize = batchSize;
    this.footer = readFooter;
    this.parquetReader = parReader;

    if (allFieldsFixedLength) {
      recordsPerBatch = (int) Math.min(batchSize / bitWidthAllFixedFields, footer.getBlocks().get(0).getColumns().get(0).getValueCount());
    }
    try {
      // initialize all of the column read status objects, if their lengths are known value vectors are allocated
      int i = 0;
      boolean fieldFixedLength = false;
      for (MaterializedField field : currentSchema) {
        column = columns.get(i);
        columnChunkMetaData = footer.getBlocks().get(0).getColumns().get(i);
        field = MaterializedField.create(new SchemaPath(toFieldName(column.getPath()), ExpressionPosition.UNKNOWN),
            toMajorType(column.getType(), getDataMode(column)));
        fieldFixedLength = column.getType() != PrimitiveType.PrimitiveTypeName.BINARY;
        if (allFieldsFixedLength) {
          createColumnStatus(fieldFixedLength, field, column, columnChunkMetaData, recordsPerBatch);
        } else {
          createColumnStatus(fieldFixedLength, field, column, columnChunkMetaData, -1);
        }
        i++;
      }
      outputMutator.setNewSchema();
    } catch (SchemaChangeException e) {
      e.printStackTrace();
    }

    for (ColumnReadStatus crs : columnStatuses.values()){
      try {

        FileSystem fs = FileSystem.get(configuration);
        FSDataInputStream inputStream = fs.open(hadoopPath);

        int totalWidthInBytes = (int)((bitWidthAllFixedFields / 8) * totalRecords);
        bufferWithAllData = allocator.buffer(totalWidthInBytes);

        parReader = new ParquetFileReader(configuration, hadoopPath,
            Arrays.asList(readFooter.getBlocks().get(rowGroupIndex)), readFooter.getFileMetaData().getSchema().getColumns());

        int recordsRead = 0;
        while (recordsRead < columnChunkMetaData.getValueCount()){
          bufferWithAllData.writeBytes(inputStream, Util.readPageHeader(inputStream).getData_page_header().crs.columnChunkMetaData.getFirstDataPageOffset());
        }
      } catch (IOException e) {
        throw new ExecutionSetupException("Error opening or reading metatdata for parquet file at location: " + hadoopPath.getName());
      }
    }
  }

  private static String toFieldName(String[] paths) {
    return join(SEPERATOR, paths);
  }

  private TypeProtos.DataMode getDataMode(ColumnDescriptor column) {
    if (schema.getColumnDescription(column.getPath()).getMaxDefinitionLevel() == 0) {
      return TypeProtos.DataMode.REQUIRED;
    } else {
      return TypeProtos.DataMode.OPTIONAL;
    }
  }

  private void resetBatch() {
    for (ColumnReadStatus column : columnStatuses.values()) {
      column.valueVecHolder.reset();
      column.valuesReadInCurrentPass = 0;
    }
  }

  /**
   * @param fixedLength
   * @param field
   * @param descriptor
   * @param columnChunkMetaData
   * @param allocateSize        - the size of the vector to create, if the value is less than 1 the vector is left null for variable length
   * @return
   * @throws SchemaChangeException
   */
  private boolean createColumnStatus(boolean fixedLength, MaterializedField field, ColumnDescriptor descriptor,
                                     ColumnChunkMetaData columnChunkMetaData, int allocateSize) throws SchemaChangeException {
    TypeProtos.MajorType type = field.getType();
    ValueVector v = TypeHelper.getNewVector(field, allocator);
    ColumnReadStatus newCol = new ColumnReadStatus();
    if (allocateSize > 1) {
      newCol.valueVecHolder = new VectorHolder(allocateSize, (BaseDataValueVector) v);
      newCol.valueVecHolder.reset();
    }
    else{
      newCol.valueVecHolder = new VectorHolder(5000, (BaseDataValueVector) v);
    }
    newCol.columnDescriptor = descriptor;
    newCol.columnChunkMetaData = columnChunkMetaData;
    newCol.isFixedLength = fixedLength;
    newCol.pageReadStatus = new PageReadStatus();

//    ColumnReaderImpl columnReader = (ColumnReaderImpl) new ColumnReadStoreImpl(
//        new ColumnChunkPageReadStore(totalRecords),
//        new DummyRecordConverter(schema).getRootConverter(),
//        schema
//    ).getColumnReader(descriptor);
//    newCol.inputStream = ((PlainValuesReader)columnReader.getDataColumnValuesReader()).getInputStream();
//    columnReader.getPageReader().readPage();

    if (newCol.columnDescriptor.getType() != PrimitiveType.PrimitiveTypeName.BINARY) {
      newCol.dataTypeLengthInBits = getTypeLengthInBytes(newCol.columnDescriptor.getType());
    }
    columnStatuses.put(field, newCol);
    outputMutator.addField(v);
    fieldID++;
    return true;
  }

  public void readAllFixedFields(long recordsToRead, ColumnReadStatus firstColumnStatus) throws IOException {
    long readStartInBytes = 0, readLength = 0, readLengthInBits = 0, currRecordsRead = 0;
    byte[] bytes;
    byte firstByte;
    byte currentByte;
    byte nextByte;
    ByteBuf buffer;
    for (ColumnReadStatus columnReadStatus : columnStatuses.values()) {
      if (!columnReadStatus.isFixedLength) {
        continue;
      }
      if (columnReadStatus.pageReadStatus.pageReader == null) {
        columnReadStatus.pageReadStatus.pageReader = currentRowGroup.getPageReader(columnReadStatus.columnDescriptor);
      }

      do {
        // if no page has been read, or all of the records have been read out of a page, read the next one
        if (columnReadStatus.pageReadStatus.currentPage == null
            || columnReadStatus.pageReadStatus.valuesRead == columnReadStatus.pageReadStatus.currentPage.getValueCount()) {
          columnReadStatus.totalValuesRead += columnReadStatus.pageReadStatus.valuesRead;
          if (!columnReadStatus.pageReadStatus.next()) {
            break;
          }
        }

        currRecordsRead = Math.min(columnReadStatus.pageReadStatus.currentPage.getValueCount()
            - columnReadStatus.pageReadStatus.valuesRead, recordsToRead - columnReadStatus.valuesReadInCurrentPass);

        readStartInBytes = columnReadStatus.pageReadStatus.readPosInBytes;
        readLengthInBits = currRecordsRead * columnReadStatus.dataTypeLengthInBits;
        readLength = (int) Math.ceil(readLengthInBits / 8.0);

        bytes = columnReadStatus.pageReadStatus.bytes;
        // standard read, using memory mapping
        if (columnReadStatus.pageReadStatus.bitShift == 0) {
          ((BaseDataValueVector)columnReadStatus.valueVecHolder.getValueVector()).getData().writeBytes(columnReadStatus.inputStream, (int) readLength);
        }
        else{ // read in individual values, because a bitshift is necessary with where the last page or batch ended

          buffer = ((BaseDataValueVector)columnReadStatus.valueVecHolder.getValueVector()).getData();
          nextByte = bytes[(int) Math.max(0, Math.ceil(columnReadStatus.pageReadStatus.valuesRead / 8.0) - 1)];
          readLengthInBits = currRecordsRead + columnReadStatus.pageReadStatus.bitShift;
          //currRecordsRead -= (8 - columnReadStatus.pageReadStatus.bitShift);

          int i = 0;
          for (; i <= (int) readLength; i++) {
            currentByte = nextByte;
            currentByte = (byte) (currentByte >>> columnReadStatus.pageReadStatus.bitShift);
            // mask the bits about to be added from the next byte
            currentByte = (byte) (currentByte & startBitMasks[columnReadStatus.pageReadStatus.bitShift - 1]);
            // if we are not on the last byte
            if ((int) Math.ceil(columnReadStatus.pageReadStatus.valuesRead / 8.0) + i < bytes.length) {
              // grab the next byte from the buffer, shift and mask it, and OR it with the leftover bits
              nextByte = bytes[(int) Math.ceil(columnReadStatus.pageReadStatus.valuesRead / 8.0) + i];
              currentByte = (byte) (currentByte | nextByte
                  << (8 - columnReadStatus.pageReadStatus.bitShift)
                  & endBitMasks[8 - columnReadStatus.pageReadStatus.bitShift - 1]);
            }
            buffer.setByte(columnReadStatus.valuesReadInCurrentPass / 8 + i, currentByte);
          }
          buffer.setIndex(0, (columnReadStatus.valuesReadInCurrentPass / 8)
              + (int) readLength - 1);
          buffer.capacity(buffer.writerIndex() + 1);
        }

        // check if the values in this page did not end on a byte boundary, store a number of bits the next page must be
        // shifted by to read all of the values into the vector without leaving space
        if (readLengthInBits % 8 != 0) {
          columnReadStatus.pageReadStatus.bitShift = (int) readLengthInBits % 8;
        } else {
          columnReadStatus.pageReadStatus.bitShift = 0;
        }

        columnReadStatus.valuesReadInCurrentPass += currRecordsRead;
        columnReadStatus.totalValuesRead += currRecordsRead;
        columnReadStatus.pageReadStatus.valuesRead += currRecordsRead;
        if (readStartInBytes + readLength >= bytes.length) {
          columnReadStatus.pageReadStatus.next();
        } else {
          columnReadStatus.pageReadStatus.readPosInBytes = readStartInBytes + readLength;
        }
      }
      while (columnReadStatus.valuesReadInCurrentPass < recordsToRead && columnReadStatus.pageReadStatus.currentPage != null);
      ((BaseDataValueVector)columnReadStatus.valueVecHolder.getValueVector()).getMutator().setValueCount(
          columnReadStatus.valuesReadInCurrentPass);
    }
  }

  public void readFields(long recordsToRead, ColumnReadStatus firstColumnStatus) throws IOException {

    long readStartInBytes = 0, readLength = 0, readLengthInBits = 0, currRecordsRead = 0;
    int lengthVarFieldsInCurrentRecord;
    boolean rowGroupFinished = false;
    byte[] bytes;
    VarBinaryVector currVec;
    // ensure all of the columns have a page reader associated with them for the current row group
    // write the first 0 offset
    for (ColumnReadStatus columnReadStatus : columnStatuses.values()) {
      if (columnReadStatus.isFixedLength) {
        continue;
      }
      if (columnReadStatus.pageReadStatus.pageReader == null) {
        columnReadStatus.pageReadStatus.pageReader = currentRowGroup.getPageReader(columnReadStatus.columnDescriptor);
        if (columnReadStatus.isFixedLength) {
          continue;
        }
      }
      currVec = (VarBinaryVector) columnReadStatus.valueVecHolder.getValueVector();
      currVec.getAccessor().getOffsetVector().getData().writeInt(0);
      columnReadStatus.bytesReadInCurrentPass = 0;
      columnReadStatus.valuesReadInCurrentPass = 0;
    }
    do {
      lengthVarFieldsInCurrentRecord = 0;
      for (ColumnReadStatus columnReadStatus : columnStatuses.values()) {
        if (columnReadStatus.isFixedLength) {
          continue;
        }
        if (columnReadStatus.pageReadStatus.currentPage == null
            || columnReadStatus.pageReadStatus.valuesRead == columnReadStatus.pageReadStatus.currentPage.getValueCount()) {
          columnReadStatus.totalValuesRead += columnReadStatus.pageReadStatus.valuesRead;
          if (!columnReadStatus.pageReadStatus.next()) {
            rowGroupFinished = true;
            break;
          }
        }
        bytes = columnReadStatus.pageReadStatus.bytes;

        // re-purposing  this field here for length in BYTES to prevent repetitive multiplication/division
        columnReadStatus.dataTypeLengthInBits = BytesUtils.readIntLittleEndian(bytes,
            (int) columnReadStatus.pageReadStatus.readPosInBytes);
        lengthVarFieldsInCurrentRecord += columnReadStatus.dataTypeLengthInBits;

      }
      // check that the next record will fit in the batch
      if (rowGroupFinished || (currRecordsRead + 1) * bitWidthAllFixedFields + lengthVarFieldsInCurrentRecord * 8
          > batchSize){
        break;
      }
      else{
        currRecordsRead++;
      }
      for (ColumnReadStatus columnReadStatus : columnStatuses.values()) {
        if (columnReadStatus.isFixedLength) {
          continue;
        }
        bytes = columnReadStatus.pageReadStatus.bytes;
        currVec = (VarBinaryVector) columnReadStatus.valueVecHolder.getValueVector();
        // again, I am re-purposing the unused field here, it is a length n BYTES, not bits
        currVec.getAccessor().getOffsetVector().getData().writeInt((int) columnReadStatus.bytesReadInCurrentPass  +
            columnReadStatus.dataTypeLengthInBits - 4 * (int) columnReadStatus.valuesReadInCurrentPass);
        currVec.getData().writeBytes(bytes, (int) columnReadStatus.pageReadStatus.readPosInBytes + 4,
            columnReadStatus.dataTypeLengthInBits);
        columnReadStatus.pageReadStatus.readPosInBytes += columnReadStatus.dataTypeLengthInBits + 4;
        columnReadStatus.bytesReadInCurrentPass += columnReadStatus.dataTypeLengthInBits + 4;
        columnReadStatus.pageReadStatus.valuesRead++;
        columnReadStatus.valuesReadInCurrentPass++;
        currVec.getMutator().setValueCount((int)currRecordsRead);
        // reached the end of a page
        if ( columnReadStatus.pageReadStatus.valuesRead == columnReadStatus.pageReadStatus.currentPage.getValueCount()) {
          columnReadStatus.pageReadStatus.next();
        }
      }
    } while (currRecordsRead < recordsToRead);
    readAllFixedFields(currRecordsRead, firstColumnStatus);

  }

  @Override
  public int next() {
    resetBatch();
    long recordsToRead = 0;
    try {
      ColumnReadStatus firstColumnStatus = columnStatuses.values().iterator().next();
      if (allFieldsFixedLength) {
        recordsToRead = Math.min(recordsPerBatch, firstColumnStatus.columnChunkMetaData.getValueCount() - firstColumnStatus.totalValuesRead);
      } else {
        // arbitrary
        recordsToRead = 8000;

        // going to incorporate looking at length of values and copying the data into a single loop, hopefully it won't
        // get too complicated

        //loop through variable length data to find the maximum records that will fit in this batch
        // this will be a bit annoying if we want to loop though row groups, columns, pages and then individual variable
        // length values...
        // jacques believes that variable length fields will be encoded as |length|value|length|value|...
        // cannot find more information on this right now, will keep looking
      }

      if (currentRowGroup == null ) {
        //currentRowGroup = parquetReader.readNextRowGroup();
        if (currentRowGroup == null) {
          return 0;
        }
        currentRowGroupIndex++;
      }

        if (allFieldsFixedLength) {
          readAllFixedFields(recordsToRead, firstColumnStatus);
        } else { // variable length columns
          readFields(recordsToRead, firstColumnStatus);
        }

      return firstColumnStatus.valuesReadInCurrentPass;
    } catch (IOException e) {
      throw new DrillRuntimeException(e);
    }
  }

  static TypeProtos.MajorType toMajorType(PrimitiveType.PrimitiveTypeName primitiveTypeName,
                                               TypeProtos.DataMode mode) {
    return toMajorType(primitiveTypeName, 0, mode);
  }

  static TypeProtos.MajorType toMajorType(PrimitiveType.PrimitiveTypeName primitiveTypeName, int length,
                                               TypeProtos.DataMode mode) {
    switch (primitiveTypeName) {
      case BINARY:
        return TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.VARBINARY).setMode(mode).build();
      case INT64:
        return TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.BIGINT).setMode(mode).build();
      case INT32:
        return TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.INT).setMode(mode).build();
      case BOOLEAN:
        return TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.BIT).setMode(mode).build();
      case FLOAT:
        return TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.FLOAT4).setMode(mode).build();
      case DOUBLE:
        return TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.FLOAT8).setMode(mode).build();
      // Both of these are not supported by the parquet library yet (7/3/13),
      // but they are declared here for when they are implemented
      case INT96:
        return TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.FIXEDBINARY).setWidth(12)
            .setMode(mode).build();
      case FIXED_LEN_BYTE_ARRAY:
        checkArgument(length > 0, "A length greater than zero must be provided for a FixedBinary type.");
        return TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.FIXEDBINARY)
            .setWidth(length).setMode(mode).build();
      default:
        throw new UnsupportedOperationException("Type not supported: " + primitiveTypeName);
    }
  }

  static String join(String delimiter, String... str) {
    StringBuilder builder = new StringBuilder();
    int i = 0;
    for (String s : str) {
      builder.append(s);
      if (i < str.length) {
        builder.append(delimiter);
      }
      i++;
    }
    return builder.toString();
  }

  @Override
  public void cleanup() {
  }
}
