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

import com.google.javascript.rhino.Token;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Node.SideEffectFlags;

class PeepholeUnfuck extends AbstractPeepholeOptimization {

  private static final Set<String> arrayFunctions = new HashSet<String>(
      Arrays.asList("concat", "copyWithin", "entries", "every", "fill", "filter", "find",
          "findIndex", "flat", "flatMap", "forEach", "includes", "indexOf", "join", "keys",
          "lastIndexOf", "map", "pop", "push", "reduce", "reduceRight", "reverse", "shift", "slice",
          "some", "sort", "splice", "toLocaleString", "toSource", "toString", "unshift", "values"));

  private static final Set<String> evalArrayFunctions =
      new HashSet<String>(Arrays.asList("fill", "filter", "sort"));

  private static final DecimalFormat doubleIntFormat = new DecimalFormat("#.##############");

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

    return n;
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
    if (!parent.isCall() || !parent.hasOneChild()) {
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
    if (!parent.isCall() || !parent.hasOneChild()) {
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
    if (!add.isAdd()) {
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

    String decl = "function " + name + "() {\n    [native code]\n}";

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
