package mano.algo.caster.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import mano.algo.caster.R

/**
 * Read by the Cast library through the manifest. The built-in receiver plays a plain video URL, so no receiver app is
 * needed; a registered "Styled Media Receiver" (see `res/values/cast.xml`) is used instead when its ID is filled in,
 * so the TV can show Caster's logo.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val own = context.getString(R.string.cast_receiver_app_id).trim()
        // An ID that isn't well formed would leave casting with nothing to start, so only a plausible one is used.
        val id = if (Regex("[A-Za-z0-9]{8}").matches(own)) own else CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        return CastOptions.Builder().setReceiverApplicationId(id).build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
