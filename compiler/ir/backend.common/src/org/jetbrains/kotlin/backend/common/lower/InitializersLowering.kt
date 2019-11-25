/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

object SYNTHESIZED_INIT_BLOCK : IrStatementOriginImpl("SYNTHESIZED_INIT_BLOCK")

open class InitializersLowering(context: CommonBackendContext) : InitializersLoweringBase(context) {
    override fun lower(irClass: IrClass) {
        val instanceInitializerStatements = extractInitializers(irClass) {
            (it is IrField && !it.isStatic) || (it is IrAnonymousInitializer && !it.isStatic)
        }
        irClass.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
            // Only transform constructors of current class.
            override fun visitClassNew(declaration: IrClass) = declaration

            override fun visitSimpleFunction(declaration: IrSimpleFunction) = declaration

            override fun visitInstanceInitializerCall(expression: IrInstanceInitializerCall): IrExpression =
                IrBlockImpl(irClass.startOffset, irClass.endOffset, context.irBuiltIns.unitType, null, instanceInitializerStatements)
                    // TODO ensure there is only one InstanceInitializerCall (in a primary constructor)
                    .deepCopyWithSymbols(currentScope!!.irElement as IrDeclarationParent)
        })
    }
}

abstract class InitializersLoweringBase(open val context: CommonBackendContext) : ClassLoweringPass {
    protected fun extractInitializers(irClass: IrClass, filter: (IrDeclaration) -> Boolean): List<IrStatement> {
        val result = mutableListOf<IrStatement>()
        irClass.declarations.removeAll {
            when {
                !filter(it) -> return@removeAll false
                it is IrField -> handleField(irClass, it)
                it is IrAnonymousInitializer -> handleAnonymousInitializer(it)
                else -> null
            }?.let(result::add)
            it is IrAnonymousInitializer
        }
        return result
    }

    protected open fun shouldEraseFieldInitializer(irField: IrField): Boolean = true

    private fun handleField(irClass: IrClass, declaration: IrField): IrStatement? =
        declaration.initializer?.run {
            val receiver = if (!declaration.isStatic) // TODO isStaticField
                IrGetValueImpl(startOffset, endOffset, irClass.thisReceiver!!.type, irClass.thisReceiver!!.symbol)
            else
                null
            val value = if (shouldEraseFieldInitializer(declaration)) {
                declaration.initializer = null
                expression
            } else {
                expression.deepCopyWithSymbols()
            }
            IrSetFieldImpl(startOffset, endOffset, declaration.symbol, receiver, value, context.irBuiltIns.unitType)
        }

    private fun handleAnonymousInitializer(declaration: IrAnonymousInitializer): IrStatement =
        with(declaration) {
            IrBlockImpl(startOffset, endOffset, context.irBuiltIns.unitType, SYNTHESIZED_INIT_BLOCK, body.statements)
        }
}
