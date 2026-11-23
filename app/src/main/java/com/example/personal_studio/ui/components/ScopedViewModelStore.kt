package com.example.personal_studio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * View models created in [content] (`hiltViewModel()`, `viewModel()`) live as long as [content]
 * stays composed, instead of as long as the whole page. Used around a multi-step capture flow
 * (camera → corners → filters): its steps keep their state while the user goes back and forth,
 * and their full-size bitmaps are released when the flow closes rather than piling up for every
 * photo taken on the page.
 *
 * Creation still goes through the page's factory and extras, so Hilt view models and their
 * assisted factories work unchanged.
 */
@Composable
fun ScopedViewModelStore(content: @Composable () -> Unit) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "ScopedViewModelStore needs a ViewModelStoreOwner" }
    val owner = remember(parent) { ScopedOwner(parent) }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

private class ScopedOwner(private val parent: ViewModelStoreOwner) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
            ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
            ?: CreationExtras.Empty
}
