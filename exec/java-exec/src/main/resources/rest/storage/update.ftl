<#-- Licensed to the Apache Software Foundation (ASF) under one or more contributor
  license agreements. See the NOTICE file distributed with this work for additional
  information regarding copyright ownership. The ASF licenses this file to
  You under the Apache License, Version 2.0 (the "License"); you may not use
  this file except in compliance with the License. You may obtain a copy of
  the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required
  by applicable law or agreed to in writing, software distributed under the
  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
  OF ANY KIND, either express or implied. See the License for the specific
  language governing permissions and limitations under the License. -->

<#include "*/generic.ftl">
<#macro page_head>
</#macro>

<#macro page_body>
  <a href="/queries">back</a><br/>
  <div class="page-header">
  </div>
  <h3>Configuration</h3>
  <form role="form" action="/storage/config/update" method="POST">
   <input type="hidden" name="name" value="${model.name}" />
   <div class="form-group">
      <textarea class="form-control" id="config" rows="20" cols="50" name="config">${model.config}</textarea>
   </div>
   <button class="btn btn-default" type="submit">
     <#if model.exists >Update<#else>Create</#if>
   </button>
  </form>
  <br/>
  <#if model.exists >
    <form role="form" action="/storage/config/delete" method="POST">
      <input type="hidden" name="name" value="${model.name}" />
      <button type="submit" class="btn btn-default" onclick="return confirm('Are you sure?')">
      Delete
      </button>
    </form>
  </#if>
  <script>
      var elem = document.getElementById("statusFontColor");
      elem.style.color = "green";
  </script>
</#macro>

<@page_html/>