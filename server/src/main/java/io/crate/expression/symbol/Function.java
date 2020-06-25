/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.expression.symbol;

import io.crate.exceptions.ConversionException;
import io.crate.execution.engine.aggregation.impl.CountAggregation;
import io.crate.expression.operator.Operator;
import io.crate.expression.operator.any.AnyOperator;
import io.crate.expression.predicate.IsNullPredicate;
import io.crate.expression.predicate.MatchPredicate;
import io.crate.expression.predicate.NotPredicate;
import io.crate.expression.scalar.ExtractFunctions;
import io.crate.expression.scalar.SubscriptFunction;
import io.crate.expression.scalar.SubscriptObjectFunction;
import io.crate.expression.scalar.SubscriptRecordFunction;
import io.crate.expression.scalar.arithmetic.ArithmeticFunctions;
import io.crate.expression.scalar.arithmetic.ArrayFunction;
import io.crate.expression.scalar.cast.CastMode;
import io.crate.expression.scalar.cast.ExplicitCastFunction;
import io.crate.expression.scalar.cast.ImplicitCastFunction;
import io.crate.expression.scalar.cast.TryCastFunction;
import io.crate.expression.scalar.systeminformation.CurrentSchemaFunction;
import io.crate.expression.scalar.systeminformation.CurrentSchemasFunction;
import io.crate.expression.scalar.timestamp.CurrentTimestampFunction;
import io.crate.expression.symbol.format.MatchPrinter;
import io.crate.expression.symbol.format.Style;
import io.crate.metadata.FunctionInfo;
import io.crate.metadata.FunctionName;
import io.crate.metadata.FunctionType;
import io.crate.metadata.Reference;
import io.crate.metadata.Scalar;
import io.crate.metadata.functions.Signature;
import io.crate.types.ArrayType;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class Function extends Symbol implements Cloneable {

    private static final Map<String, String> ARITHMETIC_OPERATOR_MAPPING = Map.ofEntries(
        Map.entry(ArithmeticFunctions.Names.ADD, "+"),
        Map.entry(ArithmeticFunctions.Names.SUBTRACT, "-"),
        Map.entry(ArithmeticFunctions.Names.MULTIPLY, "*"),
        Map.entry(ArithmeticFunctions.Names.DIVIDE, "/"),
        Map.entry(ArithmeticFunctions.Names.MOD, "%"),
        Map.entry(ArithmeticFunctions.Names.MODULUS, "%")
    );

    private final List<Symbol> arguments;
    private final DataType<?> returnType;
    private final FunctionInfo info;
    @Nullable
    private final Signature signature;
    @Nullable
    private final Symbol filter;

    public Function(StreamInput in) throws IOException {
        info = new FunctionInfo(in);
        if (in.getVersion().onOrAfter(Version.V_4_1_0)) {
            filter = Symbols.nullableFromStream(in);
        } else {
            filter = null;
        }
        arguments = List.copyOf(Symbols.listFromStream(in));
        if (in.getVersion().onOrAfter(Version.V_4_2_0) && in.readBoolean()) {
            signature = new Signature(in);
            returnType = DataTypes.fromStream(in);
        } else {
            signature = null;
            returnType = info.returnType();
        }
    }

    public Function(Signature signature, List<Symbol> arguments, DataType<?> returnType) {
        this(signature, arguments, returnType, null);
    }

    public Function(Signature signature, List<Symbol> arguments, DataType<?> returnType, Symbol filter) {
        this.info = FunctionInfo.of(signature, Symbols.typeView(arguments), returnType);
        this.signature = signature;
        this.arguments = List.copyOf(arguments);
        this.returnType = returnType;
        this.filter = filter;
    }

    public List<Symbol> arguments() {
        return arguments;
    }

    /**
     * @deprecated Use {{@link #signature()}} instead. Will be removed with next major version.
     */
    public FunctionInfo info() {
        return info;
    }

    /**
     * Wrapper method for BWC when symbols are retrieved from older nodes without a signature.
     */
    public String name() {
        if (signature != null) {
            return signature.getName().name();
        }
        return info.ident().name();
    }

    /**
     * Wrapper method for BWC when symbols are retrieved from older nodes without a signature.
     */
    public boolean hasFeature(Scalar.Feature feature) {
        if (signature != null) {
            return signature.hasFeature(feature);
        }
        return info.hasFeature(feature);
    }

    /**
     * Wrapper method for BWC when symbols are retrieved from older nodes without a signature.
     */
    public boolean isDeterministic() {
        if (signature != null) {
            return signature.isDeterministic();
        }
        return info.isDeterministic();
    }

    /**
     * Wrapper method for BWC when symbols are retrieved from older nodes without a signature.
     */
    public FunctionName fqnName() {
        if (signature != null) {
            return signature.getName();
        }
        return info.ident().fqnName();
    }

    /**
     * Wrapper method for BWC when symbols are retrieved from older nodes without a signature.
     */
    public FunctionType type() {
        if (signature != null) {
            return signature.getKind();
        }
        return info.type();
    }

    @Nullable
    public Signature signature() {
        return signature;
    }

    @Nullable
    public Symbol filter() {
        return filter;
    }

    @Override
    public DataType<?> valueType() {
        return returnType;
    }

    @Override
    public Symbol cast(DataType<?> targetType, CastMode... modes) {
        String name;
        if (signature != null) {
            name = signature.getName().name();
        } else {
            name = info.ident().name();
        }
        if (targetType instanceof ArrayType && name.equals(ArrayFunction.NAME)) {
            /* We treat _array(...) in a special way since it's a value constructor and no regular function
             * This allows us to do proper type inference for inserts/updates where there are assignments like
             *
             *      some_array = [?, ?]
             * or
             *      some_array = array_cat([?, ?], [1, 2])
             */
            return castArrayElements(targetType, modes);
        } else {
            return super.cast(targetType, modes);
        }
    }

    private Symbol castArrayElements(DataType<?> newDataType, CastMode... modes) {
        DataType<?> innerType = ((ArrayType<?>) newDataType).innerType();
        ArrayList<Symbol> newArgs = new ArrayList<>(arguments.size());
        for (Symbol arg : arguments) {
            try {
                newArgs.add(arg.cast(innerType, modes));
            } catch (ConversionException e) {
                throw new ConversionException(returnType, newDataType);
            }
        }
        return new Function(
            signature,
            newArgs,
            newDataType,
            null
        );
    }

    @Override
    public SymbolType symbolType() {
        return SymbolType.FUNCTION;
    }

    @Override
    public <C, R> R accept(SymbolVisitor<C, R> visitor, C context) {
        return visitor.visitFunction(this, context);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        info.writeTo(out);
        if (out.getVersion().onOrAfter(Version.V_4_1_0)) {
            Symbols.nullableToStream(filter, out);
        }
        Symbols.toStream(arguments, out);
        if (out.getVersion().onOrAfter(Version.V_4_2_0)) {
            out.writeBoolean(signature != null);
            if (signature != null) {
                signature.writeTo(out);
                DataTypes.toStream(returnType, out);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Function function = (Function) o;
        return Objects.equals(arguments, function.arguments) &&
               Objects.equals(info, function.info) &&
               Objects.equals(filter, function.filter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(arguments, info, filter);
    }

    @Override
    public String toString(Style style) {
        StringBuilder builder = new StringBuilder();
        String name = info.ident().name();
        switch (name) {
            case MatchPredicate.NAME:
                MatchPrinter.printMatchPredicate(this, style, builder);
                break;

            case SubscriptFunction.NAME:
            case SubscriptObjectFunction.NAME:
                printSubscriptFunction(builder, style);
                break;

            case SubscriptRecordFunction.NAME:
                printSubscriptRecord(builder, style);
                break;

            case "current_user":
                builder.append("CURRENT_USER");
                break;

            case "session_user":
                builder.append("SESSION_USER");
                break;

            case CurrentSchemasFunction.NAME:
                builder.append(CurrentSchemasFunction.NAME);
                break;

            case CurrentSchemaFunction.NAME:
                builder.append(CurrentSchemaFunction.NAME);
                break;

            case IsNullPredicate.NAME:
                builder.append("(");
                builder.append(arguments.get(0).toString(style));
                builder.append(" IS NULL)");
                break;

            case NotPredicate.NAME:
                builder.append("(NOT ");
                builder.append(arguments.get(0).toString(style));
                builder.append(")");
                break;

            case CountAggregation.NAME:
                if (arguments.isEmpty()) {
                    builder.append("count(*)");
                    printFilter(builder, style);
                } else {
                    printFunctionWithParenthesis(builder, style);
                }
                break;

            case CurrentTimestampFunction.NAME:
                if (arguments.isEmpty()) {
                    builder.append("CURRENT_TIMESTAMP");
                } else {
                    printFunctionWithParenthesis(builder, style);
                }
                break;
            default:
                if (name.startsWith(AnyOperator.OPERATOR_PREFIX)) {
                    printAnyOperator(builder, style);
                } else if (name.equalsIgnoreCase(ImplicitCastFunction.NAME) ||
                           name.equalsIgnoreCase(ExplicitCastFunction.NAME) ||
                           name.equalsIgnoreCase(TryCastFunction.NAME)) {
                    printCastFunction(builder, style);
                } else if (name.startsWith(Operator.PREFIX)) {
                    printOperator(builder, style, null);
                } else if (name.startsWith(ExtractFunctions.NAME_PREFIX)) {
                    printExtract(builder, style);
                } else {
                    String arithmeticOperator = ARITHMETIC_OPERATOR_MAPPING.get(name);
                    if (arithmeticOperator != null) {
                        printOperator(builder, style, arithmeticOperator);
                    } else {
                        printFunctionWithParenthesis(builder, style);
                    }
                }
        }
        return builder.toString();
    }

    private void printSubscriptRecord(StringBuilder builder, Style style) {
        builder.append("(");
        builder.append(arguments.get(0).toString(style));
        builder.append(").");
        builder.append(arguments.get(1).toString(style));
    }

    private void printAnyOperator(StringBuilder builder, Style style) {
        String name = info.ident().name();
        assert name.startsWith(AnyOperator.OPERATOR_PREFIX) : "function for printAnyOperator must start with any prefix";
        assert arguments.size() == 2 : "function's number of arguments must be 2";
        String operatorName = name.substring(4).replace('_', ' ').toUpperCase(Locale.ENGLISH);
        builder
            .append("(") // wrap operator in parens to ensure precedence
            .append(arguments.get(0).toString(style))
            .append(" ")
            .append(operatorName)
            .append(" ")
            .append("ANY(")
            .append(arguments.get(1).toString(style))
            .append("))");
    }

    private void printCastFunction(StringBuilder builder, Style style) {
        var name = info.ident().name();
        var targetType = info.returnType();
        builder
            .append(name)
            .append("(")
            .append(arguments().get(0).toString(style));
        if (name.equalsIgnoreCase(ImplicitCastFunction.NAME)) {
            builder
                .append(", ")
                .append(arguments.get(1).toString(style));
        } else {
            builder.append(" AS ")
                .append(targetType.getTypeSignature().toString());
        }
        builder.append(")");
    }

    private void printExtract(StringBuilder builder, Style style) {
        String name = info.ident().name();
        assert name.startsWith(ExtractFunctions.NAME_PREFIX) : "name of function passed to printExtract must start with extract_";
        String fieldName = name.substring(ExtractFunctions.NAME_PREFIX.length());
        builder.append("extract(")
            .append(fieldName)
            .append(" FROM ");
        builder.append(arguments.get(0).toString(style));
        builder.append(")");
    }

    private void printOperator(StringBuilder builder, Style style, String operator) {
        if (operator == null) {
            String name = info.ident().name();
            assert name.startsWith(Operator.PREFIX);
            operator = name.substring(Operator.PREFIX.length()).toUpperCase(Locale.ENGLISH);
        }
        builder
            .append("(")
            .append(arguments.get(0).toString(style))
            .append(" ")
            .append(operator)
            .append(" ")
            .append(arguments.get(1).toString(style))
            .append(")");
    }

    private void printSubscriptFunction(StringBuilder builder, Style style) {
        Symbol base = arguments.get(0);
        if (base instanceof Reference && base.valueType() instanceof ArrayType && ((Reference) base).column().path().size() > 0) {
            Reference firstArgument = (Reference) base;
            builder.append(firstArgument.column().name());
            builder.append("[");
            builder.append(arguments.get(1).toString(style));
            builder.append("]");
            builder.append("['");
            builder.append(firstArgument.column().path().get(0));
            builder.append("']");
        } else {
            builder.append(base.toString(style));
            builder.append("[");
            builder.append(arguments.get(1).toString(style));
            builder.append("]");
        }
    }

    private void printFunctionWithParenthesis(StringBuilder builder, Style style) {
        FunctionName functionName = info.ident().fqnName();
        builder.append(functionName.toString(style));
        builder.append("(");
        for (int i = 0; i < arguments.size(); i++) {
            Symbol argument = arguments.get(i);
            builder.append(argument.toString(style));
            if (i + 1 < arguments.size()) {
                builder.append(", ");
            }
        }
        builder.append(")");
        printFilter(builder, style);
    }

    private void printFilter(StringBuilder builder, Style style) {
        if (filter != null) {
            builder.append(" FILTER (WHERE ");
            builder.append(filter.toString(style));
            builder.append(")");
        }
    }
}
