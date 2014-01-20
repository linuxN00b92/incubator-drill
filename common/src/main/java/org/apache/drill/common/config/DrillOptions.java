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
package org.apache.drill.common.config;

import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Stores a list of all available options and their expected types/values.
 *
 * For all strings, including names and option values, case is not considered meaningful.
 */
public class DrillOptions implements Iterable<DrillOptions.DrillOptionValue>, Cloneable{

  public static final String  QUOTED_IDENTIFIERS = "quoted_identifiers",
                              QUERY_TIMEOUT = "query_timeout",
                              EXPLAIN_PLAN_LEVEL = "explain_plan_level",
                              EXPLAIN_PLAN_FORMAT = "explain_plan_format";

  private static CaseInsensitiveMap<OptionValidator> optionValidators;
  private HashMap<String, DrillOptionValue> optionValues;

  static {
    optionValidators = new CaseInsensitiveMap();
    // TODO - these are just example options that can be represented with the structures in this static class, they are not
    // implemented in the execution engine yet

    optionValidators.put(QUOTED_IDENTIFIERS, allowAllValuesValidator(QUOTED_IDENTIFIERS));
    optionValidators.put(QUERY_TIMEOUT, new MinMaxValidator(QUERY_TIMEOUT, 10, Integer.MAX_VALUE));
    optionValidators.put(EXPLAIN_PLAN_LEVEL, new EnumeratedStringValidator(EXPLAIN_PLAN_LEVEL, "PHYSICAL", "LOGICAL"));
    optionValidators.put(EXPLAIN_PLAN_FORMAT, new EnumeratedValueValidator(EXPLAIN_PLAN_FORMAT, "TEXT", "XML", "JSON"));

    // example to show the enumerated values can be used for anything that implements compareTo including integers
    optionValidators.put("numeric_enumeration_option",
        new EnumeratedValueValidator<Integer>("numeric_enumeration_option", 100, 200, 300, 400, 500));
    // just and example of how a custom validator can be created using an anonymous static class
    // creates an integer option that must be greater than 0 and and a multiple of 5
    optionValidators.put("example_not_for_production", new OptionValidator<Integer>("example_not_for_production") {
          @Override
          public Integer validate(Integer value) throws Exception {
            if (value > 0 && value % 5 == 0){
              return value;
            }
            throw new Exception("Invalid value for option '" + getOptionName()
                + "', value must be a n integer greater than 0 and a multiple of 5");
          }
    });
  }

  public DrillOptions(){
    optionValues = new HashMap<>();
  }


  @Override
  public Iterator<DrillOptionValue> iterator() {
    return optionValues.values().iterator();
  }

  public DrillOptions clone(){
    DrillOptions dOpts = new DrillOptions();
    dOpts.optionValues.clear();
    for (DrillOptionValue drillOptionValue : this.optionValues.values()){
      dOpts.optionValues.put(drillOptionValue.optionName, drillOptionValue.clone());
    }
    return dOpts;
  }

  private static class CaseInsensitiveMap<V> extends HashMap<String, V> {

    public V put(String s, V val){
      super.put(s.toLowerCase(), val);
      return val;
    }

    public V get(String s){
      return super.get(s.toLowerCase());
    }
  }

  public static OptionValidator allowAllValuesValidator(String s) {
    return new OptionValidator<Object>(s){
      @Override public Object validate(Object value) throws Exception {
        return value;
      }
    };
  }

  public void setOptionWithString(String optionName, String value) throws Exception {
    OptionValidator validator = optionValidators.get(optionName);
    DrillOptionValue opt = optionValues.get(optionName);
    if (validator == null){
      throw new Exception("invalid option optionName '" + optionName + "'");
    }
    optionValues.put(optionName, DrillOptionValue.wrapBareObject(optionName, validator.validate(opt.parse(value)), this));
  }

  public Object getOptionValue(String name){
    return optionValues.get(name);
  }

  /**
   * Returns the DrillOptionValue object, which can be serialized and deserialized for use
   * in the distributed cache.
   *
   * This method should only be used for getting options that will be placed into
   * distributed cache, for usage of actual option values use the getOptionValue method.
   *
   * @param name - name of a drill option
   * @return - serDe capable representation of a DrillObject
   */
  public DrillOptionValue getSerDeOptionValue(String name){
    DrillOptionValue opt = optionValues.get(name);
    // prevents value inside of map from being tampered with accidentally by users of this method
    opt.clone();
    return opt;
  }

  /**
   * Represents an available option in Drill, with a method for parsing a value from a string and a pluggable
   * nested validation object that can be added at construction time to validate options with different value
   * restrictions.
   * @param <E> - type of object for the option to store
   */
  public static abstract class DrillOptionValue<E> implements Cloneable, DrillSerializable{
    String optionName;

    E value;
    DrillOptions parentOptionList;

    public DrillOptionValue(String optionName, E value, DrillOptions parentOptionList) {
      this.optionName = optionName;
      this.parentOptionList = parentOptionList;
    }

    public String getOptionName() {
      return optionName;
    }

    public E getValue() {
      return value;
    }

    public void setValue(E value) {
      this.value = value;
    }

    public abstract E parse(String s) throws Exception;

    public String unparse(E value){
      return value.toString();
    }

    public DrillOptionValue clone(){
      DrillOptionValue o = newInstance();
      // as the validator and optionName should never be changed after creation, there
      // really isn't a need to make new copies of either in the cloned instance,
      // instead references are given to the new instance
      o.optionName = this.optionName;
      try {
      } catch (Exception e) {
        // should never throw an error as it is pulled from an instance of the same static class
        throw new RuntimeException(e);
      }
      return o;
    }

    public abstract DrillOptionValue newInstance();

    // This method is a bit of a hack to get around handling options as POJOs in
    // the validators, but handling them as wrapped OptionValue objects in the actual value
    // storage to allow them to be plugged into the distributed cache
    private static DrillOptionValue wrapBareObject(String optionName, Object o, DrillOptions parentOptionList){
      if (o instanceof Integer) {
        return new IntegerOptionValue(optionName, (Integer) o, parentOptionList);
      }
      else if (o instanceof Double) {
        return new DoubleOptionValue(optionName, (Double) o, parentOptionList);
      }
      else if (o instanceof Float) {
        return new FloatOptionValue(optionName, (Float) o, parentOptionList);
      }
      else if (o instanceof Long) {
        return new LongOptionValue(optionName, (Long) o, parentOptionList);
      }
      else if (o instanceof String) {
        return new StringOptionValue(optionName, (String) o, parentOptionList);
      }
      else if (o instanceof Boolean) {
        return  new BooleanOptionValue(optionName, (Boolean) o, parentOptionList);
      }
      else {
        throw new RuntimeException("Unsupported type stored in Drill option file for option '" + optionName + "'");
      }
    }

    /********************************************************************************
     * The methods below are used to serialize and deserialize the option values for
     * syncing them across the distributed cache.
     *
     * The cache stores a map, which implicitly stores the option names.
     *
     * The validation rules will not change while a cluster is running so they are
     * not serialized either (some might change occasionally with major version upgrades).
     *
     * This only does a serDe on the values themselves.
     ********************************************************************************/

    @Override
    public void read(DataInput input) throws IOException {
      try {
        parentOptionList.setOptionWithString(optionName, input.readUTF());
      } catch (Exception e) {
        throw new IOException("Error reading value for attribute '" + optionName + "'");
      }
    }

    @Override
    public void readFromStream(InputStream input) throws IOException {
      int len = input.read();
      byte[] strBytes = new byte[len];
      input.read(strBytes);
      try {
        parentOptionList.setOptionWithString(optionName, new String(strBytes, "UTF-8"));
      } catch (Exception e) {
        throw new IOException("Error reading value for attribute '" + optionName + "'");
      }
    }

    @Override
    public void write(DataOutput output) throws IOException {
      output.writeUTF(parentOptionList.getOptionValue(optionName).toString());
    }

    @Override
    public void writeToStream(OutputStream output) throws IOException {
      String val = parentOptionList.getOptionValue(optionName).toString();
      output.write(val.length());
      output.write(val.getBytes("UTF-8"));
    }

    /********************************************************************************
     * End of serDe methods for the distributed cache
     ********************************************************************************/
  }

  /**
   * Validates the values provided to Drill options.
   *
   * @param <E>
   */
  public abstract static class OptionValidator<E> {
    // Stored here as well as in the option static class to allow insertion of option optionName into
    // the error messages produced by the validator
    private String optionName;

    public OptionValidator(String optionName){
      this.optionName = optionName;
    }

    /**
     * This method determines if a given value is a valid setting for an option. For options that support some
     * ambiguity in their settings, such as case-insensitivity for string options, this method returns a modified
     * version of the passed value that is considered the standard format of the option that should be used for
     * system-internal representation.
     *
     * @param value - the value to validate
     * @return - the value requested, in its standard format to be used for representing the value within Drill
     *            Example: all lower case values for strings, to avoid ambiguities in how values are stored
     *            while allowing some flexibility for users
     * @throws Exception - message to describe error with value, including range or list of expected values
     */
    public abstract E validate(E value) throws Exception;

    public String getOptionName() {
      return optionName;
    }
  }


  /**
   * Validates against a given set of enumerated values provided at construction.
   * @param <TYPE> - type of objects provided in enumerated list and for validating later.
   */
  public static class EnumeratedValueValidator<TYPE extends Comparable> extends OptionValidator<TYPE> {
    private TYPE[] validValues;

    public EnumeratedValueValidator(String optionName, TYPE... validValues) {
      super(optionName);
      this.validValues = validValues;
    }

    @Override
    public TYPE validate(TYPE value) throws Exception {
      for ( TYPE val : validValues ) {
        if (val.compareTo(value) == 0){
          return value;
        }
      }
      String validVals = StringUtils.join(validValues, ',');
      throw new Exception("Value provided for option '" + getOptionName()
          + "' is not valid, select from [" + validVals + "]");
    }
  }

  public static class MinMaxValidator<NUM extends Comparable> extends OptionValidator<NUM>{

    boolean minSet;
    boolean maxSet;
    NUM min;
    NUM max;

    public MinMaxValidator(String name, NUM min, NUM max) {
      super(name);
      this.min = min;
      minSet = true;
      this.max = max;
      maxSet = true;
    }

    public NUM validate(NUM value) throws Exception {
      if (minSet) {
        if (min.compareTo(value) > 0) {
          throw new Exception("Value of option '" + getOptionName() + "' must be greater than " + min);
        }
      }
      if (maxSet) {
        if (max.compareTo(value) < 0 ) {
          throw new Exception("Value of option '" + getOptionName() + "' must be less than " + min);
        }
      }
      return value;
    }
  }

  public static class EnumeratedStringValidator extends EnumeratedValueValidator<String> {

    public EnumeratedStringValidator(String name, String value, String... validValues) {
      super(name, validValues);
    }

    public String validate(String s) throws Exception {
      for ( String val : super.validValues ) {
        if (val.equalsIgnoreCase(s)){
          return s.toLowerCase();
        }
      }

      String validVals = StringUtils.join(super.validValues, ',');
      throw new Exception("Value provided for option '" + getOptionName()
          + "' is not valid, select from [" + validVals + "]");
    }
  }

  public static class StringOptionValue extends DrillOptionValue<String> {

    public StringOptionValue(String name, String value, DrillOptions parentOptionList) {
      super(name, value, parentOptionList);
    }

    @Override
    public String parse(String s){
      return s;
    }

    public StringOptionValue newInstance(){
      return new StringOptionValue(optionName, value, parentOptionList);
    }
  }

  public static class IntegerOptionValue extends DrillOptionValue<Integer> {

    public IntegerOptionValue(String name, Integer value, DrillOptions parentOptionList) {
      super(name, value, parentOptionList);
    }

    @Override
    public Integer parse(String s) throws Exception {
      try {
        return Integer.parseInt(s);
      } catch (RuntimeException ex){
        throw new Exception("Error parsing integer value for attribute " + optionName);
      }
    }

    @Override
    public DrillOptionValue newInstance() {
      return new IntegerOptionValue(optionName, value, parentOptionList);
    }
  }

  public static class DoubleOptionValue extends DrillOptionValue<Double> {

    public DoubleOptionValue(String name, Double value, DrillOptions parentOptionList) {
      super(name, value, parentOptionList);
    }

    @Override
    public Double parse(String s) throws Exception {
      try {
        return Double.parseDouble(s);
      } catch (RuntimeException ex){
        throw new Exception("Error parsing double value for attribute " + optionName);
      }
    }

    @Override
    public DrillOptionValue newInstance() {
      return new DoubleOptionValue(optionName, value, parentOptionList);
    }
  }

  public static class FloatOptionValue extends DrillOptionValue<Float> {

    public FloatOptionValue(String name, Float value, DrillOptions parentOptionList) {
      super(name, value, parentOptionList);
    }

    @Override
    public Float parse(String s) throws Exception {
      try {
        return Float.parseFloat(s);
      } catch (RuntimeException ex){
        throw new Exception("Error parsing float value for attribute " + optionName);
      }
    }

    @Override
    public DrillOptionValue newInstance() {
      return new FloatOptionValue(optionName, value, parentOptionList);
    }
  }

  public static class LongOptionValue extends DrillOptionValue<Long> {

    public LongOptionValue(String name, Long value, DrillOptions parentOptionList) {
      super(name, value, parentOptionList);
    }

    @Override
    public Long parse(String s) throws Exception {
      try {
        return Long.parseLong(s);
      } catch (RuntimeException ex){
        throw new Exception("Error parsing float value for attribute '" + optionName + "'");
      }
    }

    @Override
    public DrillOptionValue newInstance() {
      return new LongOptionValue(optionName, value, parentOptionList);
    }
  }

  public static class BooleanOptionValue extends DrillOptionValue<Boolean> {

    public BooleanOptionValue(String optionName, Boolean value, DrillOptions parentOptionList) {
      super(optionName, value, parentOptionList);
    }

    @Override
    public Boolean parse(String s) throws Exception {
      if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on"))
        return true;
      else if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off"))
        return false;
      else
        throw new Exception("Invalid value for option '" + optionName + "' must be on or off.");
    }

    @Override
    public DrillOptionValue newInstance() {
      return new BooleanOptionValue(optionName, value, parentOptionList);
    }
  }

}
