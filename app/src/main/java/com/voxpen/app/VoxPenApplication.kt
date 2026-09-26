package com.voxpen.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.voxpen.app.billing.BillingManager
import com.voxpen.app.billing.LicenseManager
import com.voxpen.app.data.repository.HybridInputRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VoxPenApplication : Application() {
    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var licenseManager: LicenseManager
    @Inject lateinit var hybridInputRepository: HybridInputRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        billingManager.initialize()
        applicationScope.launch(Dispatchers.IO) {
            runCatching { hybridInputRepository.ensureBootstrapLexicon() }
                .onFailure { Timber.e(it, "Unable to install the offline MixType dictionary") }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch {
                        licenseManager.validateCachedLicense()
                    }
                }
            },
        )
    }
}
