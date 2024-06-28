<#-- @ftlvariable name="results" type="org.revapi.gradle.AnalysisResults" -->
<#list results.results() as result>
${result.code()}<#if result.description()??>: ${result.description()}</#if>

<#include "gradle-revapi-difference-template.ftl">
----------------------------------------------------------------------------------------------------
</#list>
