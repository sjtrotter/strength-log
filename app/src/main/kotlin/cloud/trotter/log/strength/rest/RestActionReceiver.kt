package cloud.trotter.log.strength.rest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.trotter.log.strength.StrengthLogApp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

class RestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ADD && action != SKIP) return
        val pending = goAsync()
        val rest = EntryPointAccessors.fromApplication(context, StrengthLogApp.PhoneRestEntryPoint::class.java).phoneRestController()
        val scope = EntryPointAccessors.fromApplication(context, StrengthLogApp.AppScopeEntryPoint::class.java).appScope()
        scope.launch {
            try {
                when (action) {
                    ADD -> rest.adjust(System.currentTimeMillis(), 15)
                    SKIP -> rest.skip()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ADD = "cloud.trotter.log.strength.rest.ADD"
        const val SKIP = "cloud.trotter.log.strength.rest.SKIP"
    }
}
