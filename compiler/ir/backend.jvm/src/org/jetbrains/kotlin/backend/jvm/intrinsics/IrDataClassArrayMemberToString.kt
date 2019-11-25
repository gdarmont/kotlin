/*
 * Copyright 2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.intrinsics

import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.codegen.BlockInfo
import org.jetbrains.kotlin.backend.jvm.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.resolve.jvm.AsmTypes.JAVA_STRING_TYPE
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

object IrDataClassArrayMemberToString : IntrinsicMethod() {
    override fun toCallable(
        expression: IrFunctionAccessExpression,
        signature: JvmMethodSignature,
        context: JvmBackendContext
    ): IrIntrinsicFunction =
        object : IrIntrinsicFunction(expression, signature, context, listOf(signature.valueParameters[0].asmType)) {
            override fun invoke(v: InstructionAdapter, codegen: ExpressionCodegen, data: BlockInfo): StackValue {
                val arrayType = expression.getValueArgument(0)!!.type
                val asmArrayType = context.typeMapper.mapType(arrayType)
                codegen.gen(expression.getValueArgument(0)!!, asmArrayType, arrayType, data)
                val toStringArgumentDescriptor = if (arrayType.isPrimitiveArray()) asmArrayType.descriptor else "[Ljava/lang/Object;"
                v.invokestatic("java/util/Arrays", "toString", "($toStringArgumentDescriptor)Ljava/lang/String;", false)
                return StackValue.onStack(JAVA_STRING_TYPE)
            }
        }
}
