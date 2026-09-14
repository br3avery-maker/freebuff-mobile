package com.freebuff.app.di

import com.freebuff.app.AppNavigatorImpl
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * app 模块装配:
 * - [AppNavigator] 单例绑定(route/sheet/snackbar 全局状态)
 * - 三个可配置密钥只读自 BuildConfig,feature/core 层经 @Named 注入,
 *   不直接引用 app 的 BuildConfig。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAppNavigator(impl: AppNavigatorImpl): AppNavigator

    companion object {
        @Provides
        @Named("gatewayBaseUrl")
        fun provideGatewayBaseUrl(): String = com.freebuff.mobile.BuildConfig.DEFAULT_GATEWAY_BASE_URL

        @Provides
        @Named("githubClientId")
        fun provideGithubClientId(): String = com.freebuff.mobile.BuildConfig.GITHUB_OAUTH_CLIENT_ID

        @Provides
        @Named("updateUrl")
        fun provideUpdateUrl(): String = com.freebuff.mobile.BuildConfig.UPDATE_URL
    }
}
