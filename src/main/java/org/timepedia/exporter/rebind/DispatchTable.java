package org.timepedia.exporter.rebind;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gwt.core.ext.typeinfo.JArrayType;
import com.google.gwt.core.ext.typeinfo.JType;

/**
 * Information to assist quick overloaded type resolution at runtime.
 */
public class DispatchTable {

  public DispatchTable(ExportableTypeOracle x) {
    xTypeOracle = x;
  }

  private boolean isOverloaded;
  private static ExportableTypeOracle xTypeOracle;

  /**
   * Add a signature to the dispatch table. Returns false if the same signature
   * occurs more than once.
   */
  public boolean addSignature(JExportableMethod method) {
    
    JExportableParameter[] exportableParameters = method.getExportableParameters();

    isOverloaded |= method.isVarArgs();
    
    Set<Signature> sigs = sigMap.get(exportableParameters.length);
    if (sigs == null) {
      sigs = new HashSet<Signature>();
      sigMap.put(exportableParameters.length, sigs);
    }
    isOverloaded |= sigMap.size() > 1;

    Signature sig = new Signature(method, exportableParameters);
    if (sigs.contains(sig)) {
      return false;
    } else {
      sigs.add(sig);
    }
    isOverloaded |= sigs.size() > 1;
    return true;
  }

  public int maxArity() {
    return Collections.max(sigMap.keySet()).intValue();
  }

  public boolean isOverloaded() {
    return isOverloaded;
  }

  public static String toJSON(HashMap<String, DispatchTable> dispatchMap) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    for (Map.Entry<String, DispatchTable> e : dispatchMap.entrySet()) {
      if (!e.getValue().isOverloaded()) {
        continue;
      }
      sb.append("  \"" + e.getKey() + "\":" + e.getValue().toJSON() + ",\n");
    }
    sb.append("}");
    return sb.toString();
  }

  public static class Signature {

    private JExportableMethod method;

    private JExportableParameter[] exportableParameters;

    public Signature(JExportableMethod method,
        JExportableParameter[] exportableParameters) {
      this.method = method;
      this.exportableParameters = exportableParameters;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Signature signature = (Signature) o;

      if (!Arrays
          .equals(exportableParameters, signature.exportableParameters)) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      return exportableParameters != null ? Arrays
          .hashCode(exportableParameters) : 0;
    }

    public String toJSON() {
      StringBuilder sb = new StringBuilder();
      sb.append("[");
      String functionRef = "@" + method.getJSNIReference();
      if (method.isStatic() || method.needsWrapper()) {
        sb.append(functionRef);
      } else {
        sb.append("function() { return this." + functionRef+".apply(this, arguments); }");
      }
      sb.append(",");
      
      String wrap = method.getExportableReturnType() == null ? null : method.getExportableReturnType().getWrapperFunc();
      sb.append(wrap + "," + generateWrapArgumentsFunction(method) + ",");
      for (JExportableParameter param : exportableParameters) {
        String jsType = param.getJsTypeOf();
        if (jsType.equals("number") || jsType.equals("object") ||
            jsType.equals("string") || jsType.equals("boolean") ||
            jsType.equals("array") ) {
          jsType = "\""+jsType+"\"";
        }
        sb.append(jsType + ",");
      }
      sb.append("]");
      return sb.toString();
    }
  }

  public String toJSON() {
    StringBuilder json = new StringBuilder();
    json.append("{\n");
    for (Integer arity : sigMap.keySet()) {
      json.append("    " + arity + ":" + toJSON(sigMap.get(arity)) + ",\n");
    }
    json.append("  }");
    return json.toString();
  }

  static boolean isAnyOverridden(HashMap<String, DispatchTable> dispatchMap) {
    for (Map.Entry<String, DispatchTable> e : dispatchMap.entrySet()) {
      if (e.getValue().isOverloaded()) {
        return true;
      }
    }
    return false;
  }
  private String toJSON(Set<Signature> signatures) {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (Signature s : signatures) {
      sb.append(s.toJSON() + ",");
    }
    sb.append("]");
    return sb.toString();
  }

  private Map<Integer, Set<Signature>> sigMap
      = new HashMap<Integer, Set<Signature>>();
  
  private static String generateWrapArgumentsFunction(JExportableMethod method) {
    String ret = "function(instance, args){return ";
    String args = "[";
    JExportableParameter params[] = method.getExportableParameters();
    boolean hasClosures = false;
    for (int i = 0; i < params.length; i++) {
      args += (i > 0 ? "," : "");
      String argName = "args[" + i + "]";
      JType t = params[i].getParam().getType();
      JArrayType a = t.isArray();
      if (a != null) {
        JExportableClassType requestedType = xTypeOracle.findExportableClassType(a.getComponentType().getQualifiedSourceName());
        if (xTypeOracle.isClosure(requestedType)) {
          hasClosures = true;
          args += argName
              + " == null ? null :function(a) {for (var i = 0; i < a.length ; i++) {a[i] = a[i].constructor == $wnd."
              + requestedType.getJSQualifiedExportName()
              + " ? a[i]." + ClassExporter.GWT_INSTANCE + " : @"
              + requestedType.getQualifiedExporterImplementationName()
              + "::makeClosure(Lcom/google/gwt/core/client/JavaScriptObject;)(a[i]);}return a;}("
              + argName + ")";
        } else {
          args += argName;
        }
      } else {
        JExportableClassType requestedType = xTypeOracle.findExportableClassType(t.getQualifiedSourceName());
        if (xTypeOracle.isClosure(requestedType)) {
          hasClosures = true;
          args += argName
              + " == null ? null : function(a) { a = a.constructor == $wnd."
              + requestedType.getJSQualifiedExportName()
              + " ? a." + ClassExporter.GWT_INSTANCE + " : @"
              + requestedType.getQualifiedExporterImplementationName()
              + "::makeClosure(Lcom/google/gwt/core/client/JavaScriptObject;)(a); return a;}("
              + argName + ")";
        } else {
          args += argName;
        }
      }
    }
    args = hasClosures ? (args + "]") : "args";
    
    if (!method.isStatic() && method.needsWrapper()) {
      args = "@org.timepedia.exporter.client.ExporterUtil::unshift(Ljava/lang/Object;Lcom/google/gwt/core/client/JavaScriptObject;)(instance, " + args + ")";
    } else if (!hasClosures) {
      return "null";
    }
    
    return ret + args + "}";
  }  
}
