/*
 * STUB (LANraragi: E-Hentai DNS hosts removed)
 */
package com.hippo.ehviewer.client

import android.content.Context
import java.net.InetAddress
import okhttp3.Dns

/**
 * EhHosts — STUB.
 * E-Hentai DNS routing has been removed.
 * Falls through to system DNS.
 */
class EhHosts(context: Context) : Dns {
    override fun lookup(hostname: String): MutableList<InetAddress> {
        return Dns.SYSTEM.lookup(hostname).toMutableList()
    }
}
