package com.freebuff.core.data.di

import android.content.Context
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.FreebuffDatabase
import com.freebuff.core.data.network.FreebuffApi
import com.freebuff.core.data.network.GithubApi
import com.freebuff.core.data.network.buildDefaultClient
import com.freebuff.core.data.repository.ChatRepository
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.DataMigrator
import com.freebuff.core.data.repository.DemoGitAuthRepository
import com.freebuff.core.data.repository.GitAuthRepository
import com.freebuff.core.data.repository.MemoryRepository
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.RealGitAuthRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.data.repository.UpdateRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = buildDefaultClient()

    @Provides
    @Singleton
    fun provideCryptoManager(): com.freebuff.core.data.security.CryptoManager =
        com.freebuff.core.data.security.CryptoManager()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FreebuffDatabase =
        FreebuffDatabase.get(context)

    @Provides
    @Singleton
    fun provideDao(db: FreebuffDatabase): FreebuffDao = db.dao()

    @Provides
    @Singleton
    fun provideSettingsRepository(dao: FreebuffDao): SettingsRepository = SettingsRepository(dao)

    @Provides
    @Singleton
    fun provideCustomModelRepository(
        dao: FreebuffDao,
        crypto: com.freebuff.core.data.security.CryptoManager,
    ): CustomModelRepository = CustomModelRepository(dao, crypto)

    @Provides
    @Singleton
    fun provideChatRepository(): ChatRepository = ChatRepository()

    @Provides
    @Singleton
    fun provideUpdateRepository(
        @Named("updateUrl") updateUrl: String,
        client: OkHttpClient,
    ): UpdateRepository = UpdateRepository(updateUrl, client)

    @Provides
    @Singleton
    fun provideSessionRepository(dao: FreebuffDao): SessionRepository = SessionRepository(dao)

    /** 官方网关客户端:目录拉取等只读接口。 */
    @Provides
    @Singleton
    fun provideFreebuffApi(
        @Named("gatewayBaseUrl") gatewayBaseUrl: String,
        client: OkHttpClient,
    ): FreebuffApi = FreebuffApi(gatewayBaseUrl, client)

    /** 官方模型目录:网关实时 + 内置回退。 */
    @Provides
    @Singleton
    fun provideModelCatalogRepository(api: FreebuffApi): ModelCatalogRepository =
        ModelCatalogRepository(api)

    /** GitHub REST 客户端(可用自建域名或代理时替换默认端点)。 */
    @Provides
    @Singleton
    fun provideGithubApi(client: OkHttpClient): GithubApi = GithubApi(client)

    /**
     * Git 账号接入:配置了 GITHUB_OAUTH_CLIENT_ID 走真实设备流,否则回退演示实现。
     * 两种实现共享同一阶段协议,UI 无需分支。
     */
    @Provides
    @Singleton
    fun provideGitAuthRepository(
        demo: DemoGitAuthRepository,
        real: RealGitAuthRepository,
        @Named("githubClientId") clientId: String,
    ): GitAuthRepository = if (clientId.isBlank()) demo else real

    @Provides
    @Singleton
    fun provideDataMigrator(dao: FreebuffDao, crypto: com.freebuff.core.data.security.CryptoManager): DataMigrator =
        DataMigrator(dao, crypto)

    /** 记忆仓库(Letta 式核心记忆块持久化)。 */
    @Provides
    @Singleton
    fun provideMemoryRepository(dao: FreebuffDao): MemoryRepository = MemoryRepository(dao)
}
