package com.lanraragi.reader.ui

import com.lanraragi.reader.GetText
import com.lanraragi.reader.R
import com.lanraragi.reader.client.exception.AppException
import org.apache.http.conn.ConnectTimeoutException
import java.net.MalformedURLException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A user-facing message for a load failure (network errors get a translated line). */
fun readableErrorText(e: Throwable): String {
    e.printStackTrace()
    val localized = e.localizedMessage
    return when {
        e is MalformedURLException -> GetText.getString(R.string.error_invalid_url)
        e is ConnectTimeoutException || e is SocketTimeoutException -> GetText.getString(R.string.error_timeout)
        e is UnknownHostException -> GetText.getString(R.string.error_unknown_host)
        e is ProtocolException && e.message?.startsWith("Too many follow-up requests:") == true ->
            GetText.getString(R.string.error_redirection)
        e is ProtocolException || e is SocketException || e is SSLException -> GetText.getString(R.string.error_socket)
        e is AppException -> "" + e.message
        localized == null -> "" + e.message
        localized == e.message -> localized
        else -> localized + "\n" + e.message
    }
}
