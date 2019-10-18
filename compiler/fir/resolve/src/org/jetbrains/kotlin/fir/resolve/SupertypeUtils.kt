/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.impl.*
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*

fun lookupSuperTypes(
    klass: FirClass<*>,
    lookupInterfaces: Boolean,
    deep: Boolean,
    useSiteSession: FirSession
): List<ConeClassLikeType> {
    return mutableListOf<ConeClassLikeType>().also {
        klass.symbol.collectSuperTypes(it, mutableSetOf(), deep, lookupInterfaces, useSiteSession)
    }
}

class ScopeSession {
    private val scopes = hashMapOf<FirClassifierSymbol<*>, HashMap<ScopeSessionKey<*>, FirScope>>()
    fun <T : FirScope> getOrBuild(symbol: FirClassifierSymbol<*>, key: ScopeSessionKey<T>, build: () -> T): T {
        return scopes.getOrPut(symbol) {
            hashMapOf()
        }.getOrPut(key) {
            build()
        } as T
    }
}

abstract class ScopeSessionKey<T : FirScope>()

inline fun <reified T : FirScope> scopeSessionKey(): ScopeSessionKey<T> {
    return object : ScopeSessionKey<T>() {}
}

val USE_SITE = scopeSessionKey<FirScope>()

data class SubstitutionScopeKey(val type: ConeClassLikeType) : ScopeSessionKey<FirClassSubstitutionScope>() {}

fun FirClassSymbol<*>.buildUseSiteMemberScope(useSiteSession: FirSession, builder: ScopeSession): FirScope? {
    when (this) {
        is FirAnonymousObjectSymbol -> return fir.buildDefaultUseSiteMemberScope(useSiteSession, builder)
        is FirRegularClassSymbol -> return fir.buildUseSiteMemberScope(useSiteSession, builder)
    }
}

fun FirClass<*>.buildUseSiteMemberScope(useSiteSession: FirSession, builder: ScopeSession): FirScope? {
    if (classId.isLocal) {
        // It's not possible to find local class by symbol
        return buildDefaultUseSiteMemberScope(useSiteSession, builder)
    }
    val symbolProvider = useSiteSession.firSymbolProvider
    return symbolProvider.getClassUseSiteMemberScope(classId, useSiteSession, builder)
}

fun FirTypeAlias.buildUseSiteMemberScope(useSiteSession: FirSession, builder: ScopeSession): FirScope? {
    val type = expandedTypeRef.coneTypeUnsafe<ConeClassLikeType>()
    return type.scope(useSiteSession, builder)?.let {
        type.wrapSubstitutionScopeIfNeed(useSiteSession, it, this, builder)
    }
}

fun FirClass<*>.buildDefaultUseSiteMemberScope(useSiteSession: FirSession, builder: ScopeSession): FirScope {
    return builder.getOrBuild(symbol, USE_SITE) {

        val declaredScope = declaredMemberScope(this)
        val scopes = lookupSuperTypes(this, lookupInterfaces = true, deep = false, useSiteSession = useSiteSession)
            .mapNotNull { useSiteSuperType ->
                if (useSiteSuperType is ConeClassErrorType) return@mapNotNull null
                val symbol = useSiteSuperType.lookupTag.toSymbol(useSiteSession)
                if (symbol is FirRegularClassSymbol) {
                    val useSiteMemberScope = symbol.fir.buildUseSiteMemberScope(useSiteSession, builder)!!
                    useSiteSuperType.wrapSubstitutionScopeIfNeed(useSiteSession, useSiteMemberScope, symbol.fir, builder)
                } else {
                    null
                }
            }
        FirClassUseSiteMemberScope(
            useSiteSession,
            FirSuperTypeScope(useSiteSession, FirStandardOverrideChecker(useSiteSession), scopes),
            declaredScope
        )
    }
}

fun ConeClassLikeType.wrapSubstitutionScopeIfNeed(
    session: FirSession,
    useSiteMemberScope: FirScope,
    declaration: FirClassLikeDeclaration<*>,
    builder: ScopeSession
): FirScope {
    if (this.typeArguments.isEmpty()) return useSiteMemberScope
    return builder.getOrBuild(declaration.symbol, SubstitutionScopeKey(this)) {
        val typeParameters = (declaration as? FirTypeParametersOwner)?.typeParameters.orEmpty()
        @Suppress("UNCHECKED_CAST")
        val substitution = typeParameters.zip(this.typeArguments) { typeParameter, typeArgument ->
            typeParameter.symbol to (typeArgument as? ConeTypedProjection)?.type
        }.filter { (_, type) -> type != null }.toMap() as Map<FirTypeParameterSymbol, ConeKotlinType>

        FirClassSubstitutionScope(session, useSiteMemberScope, builder, substitution)
    }
}

private tailrec fun ConeClassLikeType.computePartialExpansion(useSiteSession: FirSession): ConeClassLikeType? {
    return when (this) {
        is ConeAbbreviatedType -> directExpansionType(useSiteSession)?.computePartialExpansion(useSiteSession)
        else -> this
    }
}

fun FirTypeRef.toExpandedSuperConeTypeIfAny(useSiteSession: FirSession): ConeClassLikeType? =
    coneTypeSafe<ConeClassLikeType>()?.computePartialExpansion(useSiteSession)

private fun FirClassifierSymbol<*>.collectSuperTypes(
    list: MutableList<ConeClassLikeType>,
    visitedSymbols: MutableSet<FirClassifierSymbol<*>>,
    deep: Boolean,
    lookupInterfaces: Boolean,
    useSiteSession: FirSession
) {
    if (!visitedSymbols.add(this)) return
    when (this) {
        is FirClassSymbol -> {
            val superClassTypes =
                (fir as FirClass<*>).superTypeRefs.mapNotNull { superTypeRef ->
                    superTypeRef.toExpandedSuperConeTypeIfAny(useSiteSession)?.takeIf { coneType ->
                        lookupInterfaces || coneType.isClassBasedType(useSiteSession)
                    }
                }
            list += superClassTypes
            if (deep)
                superClassTypes.forEach {
                    if (it !is ConeClassErrorType) {
                        it.lookupTag.toSymbol(useSiteSession)?.collectSuperTypes(
                            list,
                            visitedSymbols,
                            deep,
                            lookupInterfaces,
                            useSiteSession
                        )
                    }
                }
        }
        is FirTypeAliasSymbol -> {
            val expansion = fir.expandedConeType?.computePartialExpansion(useSiteSession) ?: return
            expansion.lookupTag.toSymbol(useSiteSession)?.collectSuperTypes(list, visitedSymbols, deep, lookupInterfaces, useSiteSession)
        }
        else -> error("?!id:1")
    }
}

private fun ConeClassLikeType?.isClassBasedType(
    useSiteSession: FirSession
): Boolean {
    if (this is ConeClassErrorType) return false
    val symbol = this?.lookupTag?.toSymbol(useSiteSession) as? FirClassSymbol ?: return false
    return when (symbol) {
        is FirAnonymousObjectSymbol -> true
        is FirRegularClassSymbol -> symbol.fir.classKind == ClassKind.CLASS
    }
}
