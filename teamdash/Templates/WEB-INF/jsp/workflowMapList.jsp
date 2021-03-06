<%@ page language="java" contentType="text/html; charset=UTF-8"
         pageEncoding="ISO-8859-1"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<html>
<head>
<title><c:out value="${resources['List.Title']}"/> - <c:out value="${workflow.process}"/></title>
<link rel="stylesheet" type="text/css" href="workflowMapList.css">
</head>
<body>

<h1><c:out value="${resources['List.Title']}"/></h1>

<c:if test="${!empty param.noPermission}">
  <div class="errorMessage">
    <c:out value="${resources['Permission.No_Permission']}"/>
  </div>
</c:if>

<div class="mainWorkflow"><div class="workflow">
  <div class="projectName">&laquo;&nbsp;<c:out
          value="${workflow.project}"/>&nbsp;&raquo;</div>
  <c:out value="${workflow.process}"/>
</div></div>

<div id="main">

<div id="imported">
<div class="arrow"></div>
<div class="description">
  <c:out value="${resources['List.Import_Description_FMT'][workflow.process]}"/>
</div>

<c:choose>
<c:when test="${empty imported}">
  <div class="noneFound"><c:out value="${resources['List.None']}"/></div>
</c:when>

<c:otherwise>
<c:forEach var="oneImport" items="${imported}">
  <div class="importRow${oneImport.explicit ? ' explicit' : ''}">
    <div class="horizontalLine" title="${oneImport.explicit ?
         resources.html['List.Explicit_Tooltip'] : ''}"></div>
    <div class="importedWorkflow"><div class="workflow">
      <div class="projectName">&laquo;&nbsp;<c:out
              value="${oneImport.project}"/>&nbsp;&raquo;</div>
      <a title="${resources.html['List.Name_Link_Tooltip_FMT'][oneImport.process]}"
         href="workflowMap?source=${oneImport.id}&amp;target=${workflow.id}&amp;focus=target"><c:out
              value="${oneImport.process}"/></a>
    </div></div>
  </div>
</c:forEach>
</c:otherwise>
</c:choose>

<div class="importRow addNew">
  <div class="horizontalLine"></div>
  <div class="importAddNew"><a href="workflowMap?add=${workflow.id}&amp;focus=target"><c:out
          value="${resources['List.Import_New']}"/></a></div>
</div>

</div><!-- imported -->


<div id="exported">
<div class="description">
  <c:out value="${resources['List.Export_Description_FMT'][workflow.process]}"/>
</div>

<c:choose>
<c:when test="${empty exported}">
  <div class="noneFound"><c:out value="${resources['List.None']}"/></div>
</c:when>

<c:otherwise>
<c:forEach var="oneExport" items="${exported}">
  <div class="exportRow${oneExport.explicit ? ' explicit' : ''}">
    <div class="horizontalLine" title="${oneExport.explicit ?
         resources.html['List.Explicit_Tooltip'] : ''}"><div class="arrow"></div></div>
    <div class="exportedWorkflow"><div class="workflow">
      <div class="projectName">&laquo;&nbsp;<c:out
              value="${oneExport.project}"/>&nbsp;&raquo;</div>
      <a title="${resources.html['List.Name_Link_Tooltip_FMT'][oneExport.process]}"
         href="workflowMap?source=${workflow.id}&amp;target=${oneExport.id}&amp;focus=source"><c:out
              value="${oneExport.process}"/></a>
    </div></div>
  </div>
</c:forEach>
</c:otherwise>
</c:choose>

<div class="exportRow addNew">
  <div class="horizontalLine"><div class="arrow"></div></div>
  <div class="exportAddNew"><a href="workflowMap?add=${workflow.id}&amp;focus=source"><c:out
          value="${resources['List.Export_New']}"/></a></div>
</div>

</div><!-- exported -->

</div><!-- main -->

</body>
</html>
