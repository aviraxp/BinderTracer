package com.btrace.viewer.di

import android.content.Context
import com.btrace.viewer.BuildConfig
import com.btrace.viewer.data.AppRepository
import com.btrace.viewer.data.EventRepository
import com.btrace.viewer.data.SocketClient
import com.btrace.viewer.data.SettingsRepository
import com.btrace.viewer.parser.AppClassLoaderRegistry
import com.btrace.viewer.parser.ApplicationLifecycleObserver
import com.btrace.viewer.parser.InterfaceIndex
import com.btrace.viewer.parser.MethodResolver
import com.btrace.viewer.parser.ParcelArgumentDecoder
import com.btrace.viewer.parser.ParcelParser
import com.btrace.viewer.parser.PersistentSignatureCache
import com.btrace.viewer.parser.StaticMethodTable
import com.btrace.viewer.parser.TransactionPairer
import com.btrace.viewer.root.BTraceManager
import com.btrace.viewer.root.RootManager
import com.btrace.viewer.service.MonitoringServiceConnector
import com.btrace.viewer.service.MonitoringSessionController
import com.btrace.viewer.service.RealSessionTransport
import com.btrace.viewer.service.SessionTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRootManager(): RootManager {
        return RootManager()
    }

    @Provides
    @Singleton
    fun provideBTraceManager(
        @ApplicationContext context: Context,
        rootManager: RootManager
    ): BTraceManager {
        return BTraceManager(context, rootManager)
    }

    @Provides
    @Singleton
    fun provideAppRepository(
        @ApplicationContext context: Context
    ): AppRepository {
        return AppRepository(context)
    }

    @Provides
    @Singleton
    fun provideSocketClient(): SocketClient {
        return SocketClient()
    }

    @Provides
    @Singleton
    fun provideTransactionPairer(): TransactionPairer {
        return TransactionPairer()
    }

    @Provides
    @Singleton
    fun provideParcelParser(
        transactionPairer: TransactionPairer,
        methodResolver: MethodResolver,
        handleResolver: com.btrace.viewer.parser.BinderHandleResolver
    ): ParcelParser {
        return ParcelParser(transactionPairer, methodResolver, handleResolver)
    }

    @Provides
    @Singleton
    fun provideAppClassLoaderRegistry(
        @ApplicationContext context: Context,
        rootManager: RootManager
    ): AppClassLoaderRegistry {
        return AppClassLoaderRegistry(context, rootManager)
    }

    @Provides
    @Singleton
    fun provideStaticMethodTable(
        @ApplicationContext context: Context
    ): StaticMethodTable {
        return StaticMethodTable(context)
    }

    @Provides
    @Singleton
    fun providePersistentSignatureCache(
        @ApplicationContext context: Context
    ): PersistentSignatureCache {
        // spec § 4.4:容量上限走 BuildConfig 暴露,预留 RemoteConfig 接入位。
        return PersistentSignatureCache(
            context = context,
            maxEntries = BuildConfig.SIG_CACHE_MAX_ENTRIES,
            maxPackages = BuildConfig.SIG_CACHE_MAX_PACKAGES,
            maxBytesPerPackage = BuildConfig.SIG_CACHE_MAX_BYTES_PER_PACKAGE,
            totalSoftLimitBytes = BuildConfig.SIG_CACHE_TOTAL_SOFT_LIMIT_BYTES,
        )
    }

    @Provides
    @Singleton
    fun provideMethodResolver(
        appClassLoaderRegistry: AppClassLoaderRegistry,
        staticMethodTable: StaticMethodTable,
        interfaceIndex: InterfaceIndex,
        persistentCache: PersistentSignatureCache,
    ): MethodResolver {
        // spec § 4.4:内存层 LRU 上限走 BuildConfig.SIG_CACHE_MAX_ENTRIES 暴露。
        val signatureCache = com.btrace.viewer.parser.SignatureCache(
            maxEntries = BuildConfig.SIG_CACHE_MAX_ENTRIES,
        )
        // 注入完毕后回填(避免循环依赖):
        //   - resolver 拿到持久缓存指针,Put 时携带版本元数据;
        //   - registry 拿到两份 cache 失效回调,APK 升级时三件套清缓存。
        val resolver = MethodResolver(
            appClassLoaderRegistry, staticMethodTable, interfaceIndex,
            signatureCache = signatureCache,
        )
        resolver.attachPersistentCache(persistentCache)
        appClassLoaderRegistry.attachCacheInvalidators(
            persistentCache = persistentCache,
            signatureInvalidator = { pkg -> resolver.invalidatePackage(pkg) },
        )
        return resolver
    }

    @Provides
    @Singleton
    fun provideParcelArgumentDecoder(): ParcelArgumentDecoder {
        return ParcelArgumentDecoder()
    }

    @Provides
    @Singleton
    fun provideEventRepository(
        parcelParser: ParcelParser,
        methodResolver: MethodResolver,
        argumentDecoder: ParcelArgumentDecoder,
        appRepository: AppRepository,
        interfaceIndex: InterfaceIndex,
        serviceManagerCatalog: com.btrace.viewer.parser.ServiceManagerCatalog,
        coldStore: com.btrace.viewer.data.ColdEventStore,
    ): EventRepository {
        return EventRepository(
            parcelParser, methodResolver, argumentDecoder, appRepository, interfaceIndex,
            serviceManagerCatalog, coldStore,
        )
    }

    @Provides
    @Singleton
    fun provideApplicationLifecycleObserver(
        persistentCache: PersistentSignatureCache,
    ): ApplicationLifecycleObserver {
        return ApplicationLifecycleObserver(persistentCache)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository {
        return SettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideSessionTransport(
        socketClient: SocketClient,
        btraceManager: BTraceManager,
        classLoaderRegistry: AppClassLoaderRegistry,
        methodResolver: MethodResolver,
        eventRepository: EventRepository,
        interfaceIndexBuilder: com.btrace.viewer.parser.InterfaceIndexBuilder,
        serviceManagerCatalog: com.btrace.viewer.parser.ServiceManagerCatalog,
    ): SessionTransport {
        return RealSessionTransport(
            socketClient,
            btraceManager,
            classLoaderRegistry,
            methodResolver,
            eventRepository,
            interfaceIndexBuilder,
            serviceManagerCatalog,
        )
    }

    @Provides
    @Singleton
    fun provideMonitoringSessionController(
        transport: SessionTransport
    ): MonitoringSessionController {
        return MonitoringSessionController(transport, kotlinx.coroutines.Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideMonitoringServiceConnector(
        @ApplicationContext context: Context
    ): MonitoringServiceConnector {
        return MonitoringServiceConnector(context)
    }
}
