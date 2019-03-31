/*
 * Copyright 2019 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.javascript.jscomp;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.TokenStream;

class PeepholeUnfuck extends AbstractPeepholeOptimization {

  private static class Tuple<X, Y> {
    public final X x;
    public final Y y;

    public Tuple(X x, Y y) {
      this.x = x;
      this.y = y;
    }
  }

  private static final DecimalFormat doubleIntFormat = new DecimalFormat("#.##############");

  private static final Set<String> arrayFunctions = new HashSet<String>(
      Arrays.asList("concat", "copyWithin", "entries", "every", "fill", "filter", "find",
          "findIndex", "flat", "flatMap", "forEach", "includes", "indexOf", "join", "keys",
          "lastIndexOf", "map", "pop", "push", "reduce", "reduceRight", "reverse", "shift", "slice",
          "some", "sort", "splice", "toLocaleString", "toSource", "toString", "unshift", "values"));

  private static final Set<String> evalArrayFunctions =
      new HashSet<String>(Arrays.asList("fill", "filter", "sort"));

  private static final Set<String> constructors = new HashSet<String>(
      Arrays.asList("Array", "Number", "String", "Boolean", "Function", "RegExp"));


  // Only applies to empty strings, as in `"".blink()`.
  private static final Map<String, Tuple<String, String>> stringToHtml =
      new HashMap<String, Tuple<String, String>>() {
        {
          put("anchor", new Tuple<String, String>("<a name=\"", "\"></a>"));
          put("big", new Tuple<String, String>("<big>", "</big>"));
          put("blink", new Tuple<String, String>("<blink>", "</blink>"));
          put("bold", new Tuple<String, String>("<b>", "</b>"));
          put("fixed", new Tuple<String, String>("<tt>", "</tt>"));
          put("fontcolor", new Tuple<String, String>("<font color=\"", "\"></font>"));
          put("fontsize", new Tuple<String, String>("<font size=\"", "\"></font>"));
          put("italics", new Tuple<String, String>("<i>", "</i>"));
          put("link", new Tuple<String, String>("<a href=\"", "\"></a>"));
          put("small", new Tuple<String, String>("<small>", "</small>"));
          put("strike", new Tuple<String, String>("<strike>", "</strike>"));
          put("sub", new Tuple<String, String>("<sub>", "</sub>"));
          put("sup", new Tuple<String, String>("<sup>", "</sup>"));
        }
      };

  PeepholeUnfuck() {}

  /**
   * Tries to apply our various peephole unfuckifications on the passed in node.
   */
  @Override
  public Node optimizeSubtree(Node n) {
    Node node = tryUndefined(n);
    if (node != n) {
      return node;
    }

    node = tryStringIndexedString(n);
    if (node != n) {
      return node;
    }

    node = tryArrayLiteralFunctionStringCoercion(n);
    if (node != n) {
      return node;
    }

    node = tryArrayFunctionConstructorInvocation(n);
    if (node != n) {
      return node;
    }

    node = tryFunctionConstructorInvocation(n);
    if (node != n) {
      return node;
    }

    node = tryUnfuckArrayEntries(n);
    if (node != n) {
      return node;
    }

    node = tryConstructorCoercion(n);
    if (node != n) {
      return node;
    }

    node = tryConstructorNameCoercion(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateIntToStringBase(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateEscape(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateStringToHtmlElement(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateStringSlice(n);
    if (node != n) {
      return node;
    }

    return n;
  }

  private Optional<Integer> tryGetInteger(Node arg) {
    double d;
    if (arg.isNumber()) {
      d = arg.getDouble();
    } else if (arg.isString()) {
      String s = arg.getString();

      try {
        d = Double.parseDouble(s);
      } catch (NullPointerException e) {
        return Optional.empty();
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    } else {
      return Optional.empty();
    }

    int i = (int) d;
    if (i != d) {
      return Optional.empty();
    }

    return Optional.of(i);
  }

  private Node tryEvaluateStringSlice(Node n) {
    if (!n.isGetProp() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall()
        || !(parent.hasTwoChildren() || parent.getChildCount() == 3)) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString() || right.getString() != "slice") {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString()) {
      return n;
    }
    String subject = left.getString();

    Node arg = parent.getSecondChild();
    Optional<Integer> b = tryGetInteger(arg);
    if (!b.isPresent()) {
      return n;
    }

    int beginIndex = b.get();
    int endIndex;

    if (parent.hasTwoChildren()) {
      endIndex = subject.length();
    } else {
      Node endArg = parent.getLastChild();
      Optional<Integer> e = tryGetInteger(endArg);
      if (!e.isPresent()) {
        return n;
      }
      endIndex = e.get();
    }

    // https://tc39.github.io/ecma262/#sec-string.prototype.slice
    int len = subject.length();
    if (beginIndex < 0) {
      beginIndex = Math.max(len + beginIndex, 0);
    } else {
      beginIndex = Math.min(beginIndex, len);
    }

    if (endIndex < 0) {
      endIndex = Math.max(len + endIndex, 0);
    } else {
      endIndex = Math.min(endIndex, len);
    }

    int span = Math.max(endIndex - beginIndex, 0);
    String result = subject.substring(beginIndex, beginIndex + span);

    Node replacement = IR.string(result);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private String getNativeDecl(String name) {
    return "function " + name + "() {\n    [native code]\n}";
  }

  private Node tryEvaluateStringToHtmlElement(Node n) {
    if (!n.isGetProp() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !(parent.hasOneChild() || parent.hasTwoChildren())) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString() || left.getString() != "") {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }

    Tuple<String, String> affixes = stringToHtml.get(right.getString());
    if (affixes == null) {
      return n;
    }

    String subject;
    if (parent.hasOneChild()) {
      subject = "";
    } else {
      Node arg = parent.getLastChild();
      if (!arg.isString()) {
        return n;
      }

      subject = arg.getString();
    }

    Node replacement = IR.string(affixes.x + subject + affixes.y);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateEscape(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName() || name.getString() != "escape") {
      return n;
    }

    String str;
    Node arg = n.getLastChild();
    if (arg.isString()) {
      str = arg.getString();
    } else if (arg.isGetProp() && arg.hasTwoChildren() && arg.getFirstChild().isArrayLit()) {
      Node second = arg.getLastChild();
      if (!second.isString()) {
        return n;
      }

      String funcName = second.getString();
      if (!PeepholeUnfuck.arrayFunctions.contains(funcName)) {
        return n;
      }
      str = getNativeDecl(funcName);
    } else {
      return n;
    }

    String escaped;
    try {
      // TODO Check if URLEncoder.encode is equivalent to
      // https://tc39.github.io/ecma262/#sec-escape-string
      escaped = URLEncoder.encode(str, "utf-8");
    } catch (UnsupportedEncodingException e) {
      return n;
    }

    Node replacement = IR.string(escaped);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateIntToStringBase(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node getElem = n.getFirstChild();
    if (!getElem.isGetElem() || !getElem.hasTwoChildren()) {
      return n;
    }
    Node num = getElem.getFirstChild();
    if (!num.isNumber()) {
      return n;
    }
    double d = num.getDouble();
    if (d < 0) {
      return n;
    }
    int i = (int) d;
    if (i != d) {
      return n;
    }
    Node op = getElem.getLastChild();
    if (!op.isString() || op.getString() != "toString") {
      return n;
    }

    Node numBase = n.getLastChild();
    if (!numBase.isNumber()) {
      return n;
    }
    d = numBase.getDouble();
    if (d < 0) {
      return n;
    }
    int b = (int) d;
    if (b != d) {
      return n;
    }
    if (b < 2 || b > 36) {
      return n;
    }

    String result = Integer.toString(i, b);
    Node replacement = IR.string(result);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryConstructorNameCoercion(Node n) {
    if (!n.isGetProp()) {
      return n;
    }
    Node left = n.getFirstChild();
    if (!left.isName()) {
      return n;
    }
    String name = left.getString();
    if (!constructors.contains(name)) {
      return n;
    }
    Node right = n.getLastChild();
    if (!right.isString() || right.getString() != "name") {
      return n;
    }

    Node replacement = IR.string(name);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryConstructorCoercion(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    Node right = n.getLastChild();
    if (!left.isName() && !right.isName()) {
      return n;
    }

    Node replacement = null;

    if (left.isName() && right.isName()) {
      String leftName = left.getString();
      String rightName = right.getString();
      if (constructors.contains(leftName) && constructors.contains(rightName)) {
        replacement = IR.string(getNativeDecl(leftName) + getNativeDecl(rightName));
      } else if (constructors.contains(leftName)) {
        right.detach();
        replacement = IR.add(IR.string(getNativeDecl(leftName)), right);
      } else if (constructors.contains(rightName)) {
        left.detach();
        replacement = IR.add(left, IR.string(getNativeDecl(rightName)));
      }
    } else if (left.isName()) {
      String leftName = left.getString();
      if (constructors.contains(leftName)) {
        right.detach();
        replacement = IR.add(IR.string(getNativeDecl(leftName)), right);
      }
    } else if (right.isName()) {
      String rightName = right.getString();
      if (constructors.contains(rightName)) {
        left.detach();
        replacement = IR.add(left, IR.string(getNativeDecl(rightName)));
      }
    }

    if (replacement == null) {
      return n;
    }

    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryUnfuckArrayEntries(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    Node call = n.getFirstChild();
    if (!call.isCall() || !call.hasOneChild()) {
      return n;
    }

    Node string = n.getLastChild();
    if (!string.isString()) {
      return n;
    }

    Node getProp = call.getFirstChild();
    if (!getProp.isGetProp() || !getProp.hasTwoChildren()) {
      return n;
    }
    if (!getProp.getFirstChild().isArrayLit()) {
      return n;
    }
    Node entries = getProp.getLastChild();
    if (!entries.isString() || entries.getString() != "entries") {
      return n;
    }

    String suffix = string.getString();
    Node replacement = IR.string("[object Array Iterator]" + suffix);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryFunctionConstructorInvocation(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !parent.hasOneChild()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName() || name.getString() != "Function") {
      return n;
    }
    Node argNode = n.getLastChild();
    if (!argNode.isString()) {
      return n;
    }
    String arg = argNode.getString();
    if (!arg.startsWith("return")) {
      return n;
    }
    String code = arg.substring("return".length()).trim();

    Node replacement;
    if (code.startsWith("/") && code.endsWith("/") && code.length() >= 2) {
      // Possibly regex.
      String regex = code.substring(1, code.length() - 1);
      Node regexArg = IR.string(regex);
      regexArg.useSourceInfoFrom(argNode);

      replacement = IR.regexp(regexArg);
      replacement.useSourceInfoFrom(parent);
    } else if (TokenStream.isJSIdentifier(code)) {
      replacement = IR.name(code);
    } else {
      Node eval = IR.name("eval");
      eval.putBooleanProp(Node.DIRECT_EVAL, true);
      eval.useSourceInfoFrom(parent);

      Node evalArg = IR.string(code);
      evalArg.useSourceInfoFrom(argNode);

      replacement = IR.call(eval, evalArg);
      replacement.putBooleanProp(Node.FREE_CALL, true);
    }

    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryArrayFunctionConstructorInvocation(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !parent.hasOneChild()) {
      return n;
    }

    Node getElem = n.getFirstChild();
    if (!getElem.isGetElem() || !getElem.hasTwoChildren()) {
      return n;
    }
    Node getProp = getElem.getFirstChild();
    if (!getProp.isGetProp() || !getProp.hasTwoChildren()) {
      return n;
    }
    if (!getProp.getFirstChild().isArrayLit()) {
      return n;
    }
    Node filter = getProp.getLastChild();
    if (!filter.isString() || !evalArrayFunctions.contains(filter.getString())) {
      return n;
    }

    Node ctor = getElem.getLastChild();
    if (!ctor.isString() && ctor.getString() != "constructor") {
      return n;
    }

    Node subject = n.getLastChild();
    if (!subject.isString()) {
      return n;
    }

    // We have `[].filter["constructor"]("XX")()`.
    Node eval = IR.name("eval");
    eval.putBooleanProp(Node.DIRECT_EVAL, true);
    eval.useSourceInfoFrom(parent);

    String code = subject.getString();
    Node evalArg = IR.string(code);
    evalArg.useSourceInfoFrom(subject);

    Node replacement = IR.call(eval, evalArg);
    replacement.putBooleanProp(Node.FREE_CALL, true);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryArrayLiteralFunctionStringCoercion(Node n) {
    if (!n.isGetProp() || !n.hasTwoChildren()) {
      return n;
    }

    Node add = n.getParent();
    if (add == null || !add.isAdd()) {
      return n;
    }

    // First operand can be a boolean, string, number or an arraylit `[]`.
    String affix;

    Boolean isReversed = add.getFirstChild() == n;
    Node op = isReversed ? add.getSecondChild() : add.getFirstChild();
    switch (op.getToken()) {
      case TRUE:
        affix = "true";
        break;
      case FALSE:
        affix = "false";
        break;
      case STRING:
        affix = op.getString();
        break;
      case NUMBER:
        affix = PeepholeUnfuck.doubleIntFormat.format(op.getDouble());
        break;
      case ARRAYLIT:
        affix = "";
        break;
      default:
        return n;
    }

    Node left = n.getFirstChild();
    if (!left.isArrayLit()) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }

    String name = right.getString();
    if (!PeepholeUnfuck.arrayFunctions.contains(name)) {
      return n;
    }

    String decl = getNativeDecl(name);

    // We have `X + [].func`.
    String result = isReversed ? decl + affix : affix + decl;
    Node replacement = IR.string(result);
    add.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryStringIndexedString(Node n) {
    if (!n.isGetElem() || !n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString()) {
      return n;
    }
    String leftStr = left.getString();

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }
    int index;
    try {
      index = Integer.parseInt(right.getString());
    } catch (NumberFormatException e) {
      return n;
    }
    if (index < 0 || index >= leftStr.length()) {
      return n;
    }

    // We have `"string"["integer"]`.
    String result = Character.toString(leftStr.charAt(index));
    Node replacement = IR.string(result);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryUndefined(Node n) {
    if (!n.isGetElem()) {
      return n;
    }

    if (!n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isArrayLit()) {
      return n;
    }
    if (left.hasChildren()) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isArrayLit()) {
      return n;
    }
    if (right.hasChildren()) {
      return n;
    }

    // We have `[][[]]`.
    Node replacement = IR.name("undefined");
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }
}
