package com.frigate.android.feature.history

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.frigate.android.core.server.ServerProfile
import java.net.URI

/**
 * Wraps the HTTP data source of the archive player: the URL is checked against the origin of the
 * profile before the request is made and the session cookie is attached by the transport.
 */
internal class HistoryMediaDataSource(
    private val delegate: DataSource,
    private val transport: HistoryMediaTransport,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        transport.checkUrl(dataSpec.uri.toString())
        val spec = dataSpec.buildUpon()
            .setHttpRequestHeaders(transport.headers(dataSpec.httpRequestHeaders))
            .build()
        return delegate.open(spec)
    }

    override fun getUri(): Uri? = delegate.getUri()

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.getResponseHeaders()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun close() {
        delegate.close()
    }
}

/** Builds the guarded data sources of the archive player for one server profile. */
internal object HistoryHlsDataSourceFactory {
    fun transport(profile: ServerProfile, cookie: String): HistoryMediaTransport =
        HistoryMediaTransport(URI(profile.baseUrl), cookie)

    fun create(profile: ServerProfile, cookie: String): DataSource.Factory {
        val transport = transport(profile, cookie)
        val delegate = OkHttpDataSource.Factory(transport.client)
        return DataSource.Factory { HistoryMediaDataSource(delegate.createDataSource(), transport) }
    }
}
