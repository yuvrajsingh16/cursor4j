// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.j2k.ast

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.j2k.CodeBuilder
import org.jetbrains.kotlin.j2k.append
import org.jetbrains.kotlin.lexer.KtTokens
import java.util.*

class ArrayAccessExpression(val expression: Expression, val index: Expression, private val lvalue: Boolean) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, expression)
        if (!lvalue && expression.isNullable) builder.append("!!")
        builder append "[" append index append "]"
    }
}

open class AssignmentExpression(val left: Expression, val right: Expression, val op: Operator) : Expression() {

    fun isMultiAssignment() = right is AssignmentExpression

    private fun appendAssignment(builder: CodeBuilder, left: Expression, right: Expression) {
        builder.appendOperand(this, left).append(" ").append(op).append(" ").appendOperand(this, right)
    }

    override fun generateCode(builder: CodeBuilder) {
        if (right !is AssignmentExpression)
            appendAssignment(builder, left, right)
        else {
            right.generateCode(builder)
            builder.append("\n")
            appendAssignment(builder, left, right.left)
        }
    }
}

class BangBangExpression(val expr: Expression) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, expr).append("!!")
    }

    companion object {
        fun surroundIfNullable(expression: Expression): Expression {
            return if (expression.isNullable)
                BangBangExpression(expression).assignNoPrototype()
            else
                expression
        }
    }
}

class BinaryExpression(val left: Expression, val right: Expression, val op: Operator) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, left, false).append(" ").append(op).append(" ").appendOperand(this, right, true)
    }
}

class IsOperator(val expression: Expression, val type: Type) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, expression).append(" is ").append(type)
    }
}

class TypeCastExpression(val type: Type, val expression: Expression) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, expression).append(" as ").append(type)
    }

    override val isNullable: Boolean
        get() = type.isNullable
}

open class LiteralExpression(private val literalText: String) : Expression() {

    override fun generateCode(builder: CodeBuilder) {
        builder.append(literalText)
    }

    object NullLiteral : LiteralExpression("null"){
        override val isNullable: Boolean
            get() = true
    }
}

class ArrayLiteralExpression(val expressions: List<Expression>) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.append("[")
        builder.append(expressions, ", ")
        builder.append("]")
    }
}

class ParenthesizedExpression(val expression: Expression) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder append "(" append expression append ")"
    }
}

class PrefixExpression(val op: Operator, val expression: Expression) : Expression() {
    override fun generateCode(builder: CodeBuilder){
        builder.append(op).appendOperand(this, expression)
    }

    override val isNullable: Boolean
        get() = expression.isNullable
}

class PostfixExpression(val op: Operator, val expression: Expression) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, expression) append op
    }
}

class ThisExpression(val identifier: Identifier) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.append("this").appendWithPrefix(identifier, "@")
    }
}

class SuperExpression(val identifier: Identifier) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.append("super").appendWithPrefix(identifier, "@")
    }
}

class QualifiedExpression(val qualifier: Expression, val identifier: Expression, dotPrototype: PsiElement?) : Expression() {
    private val dot = Dot().assignPrototype(dotPrototype, CommentsAndSpacesInheritance.LINE_BREAKS)

    override val isNullable: Boolean
        get() = identifier.isNullable

    override fun generateCode(builder: CodeBuilder) {
        if (!qualifier.isEmpty) {
            builder.appendOperand(this, qualifier).append(if (qualifier.isNullable) "!!" else "")
            builder.append(dot)
        }

        builder.append(identifier)
    }

    private class Dot : Element() {
        override fun generateCode(builder: CodeBuilder) {
            builder.append(".")
        }
    }
}

open class Operator(private val operatorType: IElementType): Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.append(asString(operatorType))
    }

    fun asString() = asString(operatorType)

    val precedence: Int
        get() = when (this.operatorType) {
            JavaTokenType.ASTERISK, JavaTokenType.DIV, JavaTokenType.PERC -> 3
            JavaTokenType.PLUS, JavaTokenType.MINUS -> 4
            KtTokens.ELVIS -> 7
            JavaTokenType.GT, JavaTokenType.LT, JavaTokenType.GE, JavaTokenType.LE -> 9
            JavaTokenType.EQEQ, JavaTokenType.NE, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ -> 10
            JavaTokenType.ANDAND -> 11
            JavaTokenType.OROR -> 12
            JavaTokenType.GTGTGT, JavaTokenType.GTGT, JavaTokenType.LTLT -> 7
            else -> 6 /* simple name */
        }

    private fun asString(tokenType: IElementType): String {
        return when (tokenType) {
            JavaTokenType.EQ -> "="
            JavaTokenType.EQEQ -> "=="
            JavaTokenType.NE -> "!="
            JavaTokenType.ANDAND -> "&&"
            JavaTokenType.OROR -> "||"
            JavaTokenType.GT -> ">"
            JavaTokenType.LT -> "<"
            JavaTokenType.GE -> ">="
            JavaTokenType.LE -> "<="
            JavaTokenType.EXCL -> "!"
            JavaTokenType.PLUS -> "+"
            JavaTokenType.MINUS -> "-"
            JavaTokenType.ASTERISK -> "*"
            JavaTokenType.DIV -> "/"
            JavaTokenType.PERC -> "%"
            JavaTokenType.PLUSEQ -> "+="
            JavaTokenType.MINUSEQ -> "-="
            JavaTokenType.ASTERISKEQ -> "*="
            JavaTokenType.DIVEQ -> "/="
            JavaTokenType.PERCEQ -> "%="
            JavaTokenType.GTGT -> "shr"
            JavaTokenType.LTLT -> "shl"
            JavaTokenType.XOR -> "xor"
            JavaTokenType.AND -> "and"
            JavaTokenType.OR -> "or"
            JavaTokenType.GTGTGT -> "ushr"
            JavaTokenType.GTGTEQ -> "shr"
            JavaTokenType.LTLTEQ -> "shl"
            JavaTokenType.XOREQ -> "xor"
            JavaTokenType.ANDEQ -> "and"
            JavaTokenType.OREQ -> "or"
            JavaTokenType.GTGTGTEQ -> "ushr"
            JavaTokenType.PLUSPLUS -> "++"
            JavaTokenType.MINUSMINUS -> "--"
            KtTokens.EQEQEQ -> "==="
            KtTokens.EXCLEQEQEQ -> "!=="
            else -> "" //System.out.println("UNSUPPORTED TOKEN TYPE: " + tokenType?.toString())
        }
    }

    companion object {
        val EQEQ = Operator(JavaTokenType.EQEQ).assignNoPrototype()
        val EQ = Operator(JavaTokenType.EQ).assignNoPrototype()
    }
}

class LambdaExpression(val parameterList: ParameterList?, val block: Block) : Expression() {
    init {
        assignPrototypesFrom(block)
    }

    override fun generateCode(builder: CodeBuilder) {
        builder append block.lBrace append " "

        if (parameterList != null && parameterList.parameters.isNotEmpty()) {
            builder.append(parameterList)
                    .append("->")
                    .append(if (block.statements.size > 1) "\n" else " ")
                    .append(block.statements, "\n")
        }
        else {
            builder.append(block.statements, "\n")
        }

        builder append " " append block.rBrace
    }
}

class StarExpression(val operand: Expression) : Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.append("*").appendOperand(this, operand)
    }
}

class RangeExpression(val start: Expression, val end: Expression): Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, start).append("..").appendOperand(this, end)
    }
}

class UntilExpression(val start: Expression, val end: Expression): Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, start).append(" until ").appendOperand(this, end)
    }
}

class DownToExpression(val start: Expression, val end: Expression): Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.appendOperand(this, start).append(" downTo ").appendOperand(this, end)
    }
}

class ClassLiteralExpression(val type: Type): Expression() {
    override fun generateCode(builder: CodeBuilder) {
        builder.append(type).append("::class")
    }
}

fun createArrayInitializerExpression(arrayType: ArrayType, initializers: List<Expression>, needExplicitType: Boolean = true) : MethodCallExpression {
    val elementType = arrayType.elementType
    @Suppress("DEPRECATION")
    val createArrayFunction = when {
        elementType is PrimitiveType -> (elementType.toNotNullType().canonicalCode() + "ArrayOf").decapitalize(Locale.US)
        needExplicitType -> "arrayOf<" + arrayType.elementType.canonicalCode() + ">"
        else -> "arrayOf"
    }
    return MethodCallExpression.buildNonNull(null, createArrayFunction, ArgumentList.withNoPrototype(initializers))
}
