// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.goto

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.base.psi.isEffectivelyActual
import org.jetbrains.kotlin.psi.psiUtil.isExpectDeclaration
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.base.util.module
import org.jetbrains.kotlin.idea.search.ExpectActualUtils.actualsForExpect
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypeAndBranch

class KotlinExpectOrActualGotoRelatedProvider : GotoRelatedProvider() {
    private class ActualOrExpectGotoRelatedItem(element: PsiElement) : GotoRelatedItem(element) {
        override fun getCustomContainerName(): String? =
            element?.module?.let { KotlinBundle.message("goto.related.provider.in.module.0", it.name) }
    }

    @OptIn(KaExperimentalApi::class)
    override fun getItems(psiElement: PsiElement): List<GotoRelatedItem> {
        val declaration = psiElement.getParentOfTypeAndBranch<KtNamedDeclaration> { nameIdentifier } ?: return emptyList()
        val targets = when {
            declaration.isExpectDeclaration() -> declaration.actualsForExpect()
            declaration.isEffectivelyActual() -> analyze(declaration) {
                declaration.symbol.getExpectsForActual().mapNotNull { it.psi }
            }

            else -> emptyList()
        }
        return targets.map(::ActualOrExpectGotoRelatedItem)
    }
}