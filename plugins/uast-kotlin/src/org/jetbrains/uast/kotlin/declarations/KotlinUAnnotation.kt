package org.jetbrains.uast.kotlin

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.asJava.toLightAnnotation
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.ValueArgument
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ArgumentMatch
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.annotationClass
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.uast.*

class KotlinUAnnotation(
        val ktAnnotationEntry: KtAnnotationEntry,
        givenParent: UElement?
) : KotlinAbstractUElement(givenParent), UAnnotation {
    private val resolvedAnnotation: AnnotationDescriptor? by lz { ktAnnotationEntry.analyze()[BindingContext.ANNOTATION, ktAnnotationEntry] }

    private val resolvedCall: ResolvedCall<*>? by lz { ktAnnotationEntry.getResolvedCall(ktAnnotationEntry.analyze()) }

    override val psi: PsiElement?
        get() = ktAnnotationEntry.toLightAnnotation() ?: ktAnnotationEntry // there are still cases when there is no light annotation, but we need any kind of PSI

    override fun equals(other: Any?): Boolean {
        if (other !is KotlinUAnnotation) {
            return false
        }

        return this.ktAnnotationEntry == other.ktAnnotationEntry
    }

    override fun hashCode() = ktAnnotationEntry.hashCode()

    override val qualifiedName: String?
        get() = resolvedAnnotation?.fqName?.asString()

    override val attributeValues: List<UNamedExpression> by lz {
        resolvedCall?.valueArguments?.entries?.mapNotNull {
            val arguments = it.value.arguments
            val name = it.key.name.asString()
            when {
                arguments.size == 1 ->
                    KotlinUNamedExpression.create(name, arguments.first(), this)
                arguments.size > 1 ->
                    KotlinUNamedExpression.create(name, arguments, this)
                else -> null
            }
        } ?: emptyList()
    }

    override fun resolve(): PsiClass? {
        val descriptor = resolvedAnnotation?.annotationClass ?: return null
        return descriptor.toSource()?.getMaybeLightElement(this) as? PsiClass
    }

    override fun findAttributeValue(name: String?): UExpression? =
            findDeclaredAttributeValue(name) ?: findAttributeDefaultValue(name ?: "value")

    fun findAttributeValueExpression(arg: ValueArgument): UExpression? {
        val mapping = resolvedCall?.getArgumentMapping(arg)
        return (mapping as? ArgumentMatch)?.let { match ->
            val namedExpression = attributeValues.find { it.name == match.valueParameter.name.asString() }
            namedExpression?.expression as? KotlinUVarargExpression ?: namedExpression
        }
    }

    override fun findDeclaredAttributeValue(name: String?): UExpression? {
        return attributeValues.find {
            it.name == name ||
            (name == null && it.name == "value") ||
            (name == "value" && it.name == null)
        }?.expression
    }

    private fun findAttributeDefaultValue(name: String): UExpression? {
        val parameter = resolvedAnnotation
                                ?.annotationClass
                                ?.unsubstitutedPrimaryConstructor
                                ?.valueParameters
                                ?.find { it.name.asString() == name } ?: return null

        val defaultValue = (parameter.source.getPsi() as? KtParameter)?.defaultValue ?: return null
        return getLanguagePlugin().convertWithParent(defaultValue)
    }
}

