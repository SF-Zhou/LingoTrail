package io.github.sfzhou.lingotrail.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.sfzhou.lingotrail.R
import io.github.sfzhou.lingotrail.net.ApiErrorKind

/**
 * User-facing explanation for a failed pipeline step.
 * String resources keep the English and Chinese copy in sync.
 */
@Composable
fun ApiErrorKind.userMessage(): String = stringResource(
    when (this) {
        ApiErrorKind.NETWORK -> R.string.error_network
        ApiErrorKind.TIMEOUT -> R.string.error_timeout
        ApiErrorKind.AUTH -> R.string.error_auth
        ApiErrorKind.BALANCE -> R.string.error_balance
        ApiErrorKind.RATE_LIMIT -> R.string.error_rate_limit
        ApiErrorKind.SERVER -> R.string.error_server
        ApiErrorKind.BAD_IMAGE -> R.string.error_bad_image
        ApiErrorKind.BAD_REQUEST, ApiErrorKind.PARSE, ApiErrorKind.UNKNOWN -> R.string.error_unknown
    },
)
