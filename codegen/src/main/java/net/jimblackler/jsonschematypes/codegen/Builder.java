package net.jimblackler.jsonschematypes.codegen;

import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JClassContainer;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;
import java.util.Map;
import java.util.Set;
import net.jimblackler.jsonschemafriend.ObjectSchema;
import net.jimblackler.jsonschemafriend.Schema;
import org.json.JSONArray;
import org.json.JSONObject;

public class Builder {
  private final CodeGenerator codeGenerator;
  private final JDefinedClass jDefinedClass;
  private final String _name;
  private final JType dataType;

  public Builder(CodeGenerator codeGenerator, Schema schema1) {
    this.codeGenerator = codeGenerator;
    ObjectSchema schema = schema1.asObjectSchema();
    codeGenerator.register(schema.getUri(), this);

    JPackage jPackage = codeGenerator.getJPackage();
    JCodeModel jCodeModel = codeGenerator.getJCodeModel();

    Set<String> types = schema.getTypes();

    if (types.size() == 1) {
      switch (types.iterator().next()) {
        case "array":
          dataType = jCodeModel.ref(JSONArray.class);
          break;
        case "boolean":
          dataType = jCodeModel.BOOLEAN;
          break;
        case "integer":
          dataType = jCodeModel.INT;
          break;
        case "null":
          dataType = jCodeModel.NULL;
          break;
        case "number":
          dataType = jCodeModel.ref(Number.class);
          break;
        case "object":
          dataType = jCodeModel.ref(JSONObject.class);
          break;
        case "string":
          dataType = jCodeModel.ref(String.class);
          break;
        default:
          throw new IllegalStateException();
      }
    } else {
      dataType = jCodeModel.ref(Object.class);
    }

    boolean isComplexObject =
        dataType.equals(jCodeModel.ref(JSONObject.class)) && !schema.getProperties().isEmpty();
    if (!(isComplexObject || dataType.equals(jCodeModel.ref(Object.class))
            || dataType.equals(jCodeModel.ref(JSONArray.class)))) {
      jDefinedClass = null;
      _name = null;
      return;
    }

    Schema parentSchema = schema.getParent();

    JClassContainer classParent;
    if (parentSchema == null) {
      classParent = jPackage;
    } else {
      Builder parent = codeGenerator.getBuilder(parentSchema);
      classParent = parent.getDefinedClass();
    }
    String name = nameForSchema(schema);

    /* Ensure no direct ancestor has the same name */
    while (true) {
      boolean changed = false;
      for (JClassContainer container = classParent; container instanceof JDefinedClass;
           container = container.parentContainer()) {
        JDefinedClass classContainer = (JDefinedClass) container;
        if (classContainer.name().equals(name)) {
          name = varyName(name);
          changed = true;
          break;
        }
      }
      if (!changed) {
        break;
      }
    }

    JDefinedClass _class;
    while (true) {
      try {
        _class = classParent._class(
            parentSchema == null ? JMod.PUBLIC : JMod.STATIC | JMod.PUBLIC, name);
        break;
      } catch (JClassAlreadyExistsException e) {
        name = varyName(name);
      }
    }
    jDefinedClass = _class;
    _name = name;
    StringBuilder docs = new StringBuilder();
    docs.append("Created from ").append(schema.getUri()).append(System.lineSeparator());
    docs.append("Explicit types ").append(schema.getExplicitTypes()).append(System.lineSeparator());
    docs.append("Inferred types ").append(schema.getInferredTypes()).append(System.lineSeparator());
    docs.append("<pre>").append(schema.getSchemaJson().toString(2)).append("</pre>");

    jDefinedClass.javadoc().add(docs.toString());

    String name1 = dataType.name().replace("JSON", "Json");
    String dataObjectName = NameUtils.lowerCaseFirst(NameUtils.snakeToCamel(name1));
    JFieldVar dataField = jDefinedClass.field(JMod.PRIVATE | JMod.FINAL, dataType, dataObjectName);

    /* Constructor */
    JMethod constructor = jDefinedClass.constructor(JMod.PUBLIC);

    JVar objectParam = constructor.param(dataType, dataObjectName);
    constructor.body().assign(JExpr._this().ref(dataField), objectParam);

    /* Getter */
    JMethod getter = jDefinedClass.method(JMod.PUBLIC, dataType,
        (dataType.equals(jCodeModel.BOOLEAN) ? "is" : "get") + dataType.name());
    getter.body()._return(dataField);

    for (Map.Entry<String, Schema> entry : schema.getProperties().entrySet()) {
      Schema propertySchema = entry.getValue();
      if (!propertySchema.isObjectSchema()) {
        continue;
      }
      ObjectSchema propertyObjectSchema = propertySchema.asObjectSchema();
      Builder builder = codeGenerator.getBuilder(propertySchema);
      String propertyName = entry.getKey();
      builder.writePropertyGetters(schema.getRequiredProperties().contains(propertyName),
          expressionFromObject(propertyObjectSchema.getDefault()), jDefinedClass, dataField,
          propertyName);
    }

    for (Schema itemsSchema : schema.getItems()) {
      Builder builder = codeGenerator.getBuilder(itemsSchema);
      if (!itemsSchema.isObjectSchema()) {
        continue;
      }
      ObjectSchema itemObjectSchema = itemsSchema.asObjectSchema();
      builder.writeItemGetters(
          jDefinedClass, expressionFromObject(itemObjectSchema.getDefault()), dataField);
    }

    if (types.contains("array")) {
      JMethod sizeMethod = jDefinedClass.method(JMod.PUBLIC, jCodeModel.INT, "size");
      JExpression asJsonArray = castIfNeeded(jCodeModel.ref(JSONArray.class), dataField);
      sizeMethod.body()._return(JExpr.invoke(asJsonArray, "length"));
    }
  }

  private static JExpression expressionFromObject(Object object) {
    if (object instanceof Integer) {
      return JExpr.lit((Integer) object);
    }

    if (object instanceof Long) {
      return JExpr.lit((Long) object);
    }

    if (object instanceof Float) {
      return JExpr.lit((Float) object);
    }

    if (object instanceof Boolean) {
      return JExpr.lit((Boolean) object);
    }

    if (object instanceof Double) {
      return JExpr.lit((Double) object);
    }

    if (object instanceof Character) {
      return JExpr.lit((Character) object);
    }

    if (object instanceof String) {
      return JExpr.lit((String) object);
    }

    return null;
  }

  private static String nameForSchema(Schema schema) {
    String[] split = schema.getUri().toString().split("/");
    String lastPart = split[split.length - 1];
    String namePart = lastPart.split("\\.", 2)[0];
    return NameUtils.snakeToCamel(namePart);
  }

  private static JExpression castIfNeeded(JClass _class, JFieldVar field) {
    return field.type().equals(_class) ? field : JExpr.cast(_class, field);
  }

  private static String getOptOrGet(boolean get, JType dataType, JCodeModel jCodeModel) {
    String kind = get ? "get" : "opt";
    if (dataType.equals(jCodeModel.ref(JSONObject.class))) {
      return kind + "JSONObject";
    }
    if (dataType.equals(jCodeModel.ref(JSONArray.class))) {
      return kind + "JSONArray";
    }
    if (dataType.equals(jCodeModel.BOOLEAN)) {
      return kind + "Boolean";
    }
    if (dataType.equals(jCodeModel.ref(String.class))) {
      return kind + "String";
    }
    if (dataType.equals(jCodeModel.INT)) {
      return kind + "Int";
    }
    if (dataType.equals(jCodeModel.ref(Number.class))) {
      return kind + "Number";
    }

    return "get";
  }

  private static String varyName(String name) {
    for (int idx = 0; idx < name.length(); idx++) {
      try {
        int i = Integer.parseInt(name.substring(idx));
        return name.substring(0, idx) + (i + 1);
      } catch (NumberFormatException e) {
      }
    }
    return name + "2";
  }

  private JDefinedClass getDefinedClass() {
    return jDefinedClass;
  }

  private void writePropertyGetters(boolean requiredProperty, JExpression defaultValue,
      JDefinedClass holderClass, JFieldVar dataField, String propertyName) {
    JCodeModel jCodeModel = codeGenerator.getJCodeModel();
    JExpression asJsonObject = castIfNeeded(jCodeModel.ref(JSONObject.class), dataField);
    JType returnType;
    if (jDefinedClass == null) {
      returnType = dataType;
    } else {
      returnType = jDefinedClass;
    }
    String nameForGetters = NameUtils.snakeToCamel(propertyName);
    JMethod getter = holderClass.method(JMod.PUBLIC, returnType,
        (returnType.equals(jCodeModel.BOOLEAN) ? "is" : "get") + nameForGetters);
    boolean isGet = defaultValue == null;
    JInvocation getObject =
        JExpr.invoke(asJsonObject, getOptOrGet(isGet, dataType, jCodeModel)).arg(propertyName);
    if (defaultValue != null && !defaultValue.equals(JExpr.lit(false))) {
      getObject.arg(defaultValue);
    }
    if (jDefinedClass == null) {
      getter.body()._return(getObject);
    } else {
      getter.body()._return(JExpr._new(jDefinedClass).arg(getObject));
    }

    if (!requiredProperty && isGet) {
      JMethod has = holderClass.method(JMod.PUBLIC, jCodeModel.BOOLEAN, "has" + nameForGetters);
      has.body()._return(JExpr.invoke(asJsonObject, "has").arg(propertyName));
    }
  }

  private void writeItemGetters(
      JDefinedClass holderClass, JExpression defaultValue, JFieldVar dataField) {
    JCodeModel jCodeModel = codeGenerator.getJCodeModel();
    JExpression asJsonArray = castIfNeeded(jCodeModel.ref(JSONArray.class), dataField);
    JType returnType;
    if (jDefinedClass == null) {
      returnType = dataType;
    } else {
      returnType = jDefinedClass;
    }
    String nameForGetters = _name;
    JMethod getter = holderClass.method(JMod.PUBLIC, returnType,
        (returnType.equals(jCodeModel.BOOLEAN) ? "is" : "get") + nameForGetters);
    JVar indexParam = getter.param(jCodeModel.INT, "index");
    boolean isGet = defaultValue == null;
    JInvocation getObject =
        JExpr.invoke(asJsonArray, getOptOrGet(isGet, dataType, jCodeModel)).arg(indexParam);
    if (defaultValue != null && !defaultValue.equals(JExpr.lit(false))) {
      getObject.arg(defaultValue);
    }
    if (jDefinedClass == null) {
      getter.body()._return(getObject);
    } else {
      getter.body()._return(JExpr._new(jDefinedClass).arg(getObject));
    }
  }
}