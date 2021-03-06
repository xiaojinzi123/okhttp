/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import okhttp3.internal.Util.closeQuietly
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.cache.CacheRequest
import okhttp3.internal.cache.CacheStrategy
import okhttp3.internal.cache.DiskLruCache
import okhttp3.internal.cache.InternalCache
import okhttp3.internal.http.HttpHeaders
import okhttp3.internal.http.HttpMethod
import okhttp3.internal.http.StatusLine
import okhttp3.internal.io.FileSystem
import okhttp3.internal.platform.Platform
import okio.buffer
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import java.io.Closeable
import java.io.File
import java.io.Flushable
import java.io.IOException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.util.ArrayList
import java.util.NoSuchElementException

/**
 * Caches HTTP and HTTPS responses to the filesystem so they may be reused, saving time and
 * bandwidth.
 *
 * ## Cache Optimization
 *
 * To measure cache effectiveness, this class tracks three statistics:
 *
 *  * **[Request Count:][requestCount]** the number of HTTP requests issued since this cache was
 *    created.
 *  * **[Network Count:][networkCount]** the number of those requests that required network use.
 *  * **[Hit Count:][hitCount]** the number of those requests whose responses were served by the
 *    cache.
 *
 * Sometimes a request will result in a conditional cache hit. If the cache contains a stale copy of
 * the response, the client will issue a conditional `GET`. The server will then send either
 * the updated response if it has changed, or a short 'not modified' response if the client's copy
 * is still valid. Such responses increment both the network count and hit count.
 *
 * The best way to improve the cache hit rate is by configuring the web server to return cacheable
 * responses. Although this client honors all [HTTP/1.1 (RFC 7234)][rfc_7234] cache headers, it
 * doesn't cache partial responses.
 *
 * ## Force a Network Response
 *
 * In some situations, such as after a user clicks a 'refresh' button, it may be necessary to skip
 * the cache, and fetch data directly from the server. To force a full refresh, add the `no-cache`
 * directive:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder().noCache().build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * If it is only necessary to force a cached response to be validated by the server, use the more
 * efficient `max-age=0` directive instead:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxAge(0, TimeUnit.SECONDS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * ## Force a Cache Response
 *
 * Sometimes you'll want to show resources if they are available immediately, but not otherwise.
 * This can be used so your application can show *something* while waiting for the latest data to be
 * downloaded. To restrict a request to locally-cached resources, add the `only-if-cached`
 * directive:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .onlyIfCached()
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * Response forceCacheResponse = client.newCall(request).execute();
 * if (forceCacheResponse.code() != 504) {
 *   // The resource was cached! Show it.
 * } else {
 *   // The resource was not cached.
 * }
 * ```
 *
 * This technique works even better in situations where a stale response is better than no response.
 * To permit stale cached responses, use the `max-stale` directive with the maximum staleness in
 * seconds:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxStale(365, TimeUnit.DAYS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * The [CacheControl] class can configure request caching directives and parse response caching
 * directives. It even offers convenient constants [CacheControl.FORCE_NETWORK] and
 * [CacheControl.FORCE_CACHE] that address the use cases above.
 *
 * [rfc_7234]: http://tools.ietf.org/html/rfc7234
 */
class Cache internal constructor(
  directory: File,
  maxSize: Long,
  fileSystem: FileSystem
) : Closeable, Flushable {
  internal val internalCache: InternalCache = object : InternalCache {
    override fun get(request: Request): Response? {
      return this@Cache.get(request)
    }

    override fun put(response: Response): CacheRequest? {
      return this@Cache.put(response)
    }

    override fun remove(request: Request) {
      this@Cache.remove(request)
    }

    override fun update(cached: Response, network: Response) {
      this@Cache.update(cached, network)
    }

    override fun trackConditionalCacheHit() {
      this@Cache.trackConditionalCacheHit()
    }

    override fun trackResponse(cacheStrategy: CacheStrategy) {
      this@Cache.trackResponse(cacheStrategy)
    }
  }

  internal val cache: DiskLruCache

  // read and write statistics, all guarded by 'this'.
  internal var writeSuccessCount: Int = 0
  internal var writeAbortCount: Int = 0
  private var networkCount: Int = 0
  private var hitCount: Int = 0
  private var requestCount: Int = 0

  val isClosed: Boolean get() = cache.isClosed

  /** Create a cache of at most `maxSize` bytes in `directory`. */
  constructor(directory: File, maxSize: Long) : this(directory, maxSize, FileSystem.SYSTEM)

  init {
    this.cache = DiskLruCache.create(fileSystem, directory, VERSION, ENTRY_COUNT, maxSize)
  }

  internal fun get(request: Request): Response? {
    val key = key(request.url())
    val snapshot: DiskLruCache.Snapshot = try {
      cache.get(key) ?: return null
    } catch (e: IOException) {
      return null // Give up because the cache cannot be read.
    }

    val entry: Entry = try {
      Entry(snapshot.getSource(ENTRY_METADATA))
    } catch (e: IOException) {
      closeQuietly(snapshot)
      return null
    }

    val response = entry.response(snapshot)
    if (!entry.matches(request, response)) {
      closeQuietly(response.body())
      return null
    }

    return response
  }

  internal fun put(response: Response): CacheRequest? {
    val requestMethod = response.request().method()

    if (HttpMethod.invalidatesCache(response.request().method())) {
      try {
        remove(response.request())
      } catch (ignored: IOException) {
        // The cache cannot be written.
      }
      return null
    }

    if (requestMethod != "GET") {
      // Don't cache non-GET responses. We're technically allowed to cache HEAD requests and some
      // POST requests, but the complexity of doing so is high and the benefit is low.
      return null
    }

    if (HttpHeaders.hasVaryAll(response)) {
      return null
    }

    val entry = Entry(response)
    var editor: DiskLruCache.Editor? = null
    try {
      editor = cache.edit(key(response.request().url())) ?: return null
      entry.writeTo(editor)
      return RealCacheRequest(editor)
    } catch (e: IOException) {
      abortQuietly(editor)
      return null
    }
  }

  @Throws(IOException::class)
  internal fun remove(request: Request) {
    cache.remove(key(request.url()))
  }

  internal fun update(cached: Response, network: Response) {
    val entry = Entry(network)
    val snapshot = (cached.body() as CacheResponseBody).snapshot
    var editor: DiskLruCache.Editor? = null
    try {
      editor = snapshot.edit() // Returns null if snapshot is not current.
      if (editor != null) {
        entry.writeTo(editor)
        editor.commit()
      }
    } catch (e: IOException) {
      abortQuietly(editor)
    }
  }

  private fun abortQuietly(editor: DiskLruCache.Editor?) {
    // Give up because the cache cannot be written.
    try {
      editor?.abort()
    } catch (ignored: IOException) {
    }
  }

  /**
   * Initialize the cache. This will include reading the journal files from the storage and building
   * up the necessary in-memory cache information.
   *
   * The initialization time may vary depending on the journal file size and the current actual
   * cache size. The application needs to be aware of calling this function during the
   * initialization phase and preferably in a background worker thread.
   *
   * Note that if the application chooses to not call this method to initialize the cache. By
   * default, OkHttp will perform lazy initialization upon the first usage of the cache.
   */
  @Throws(IOException::class)
  fun initialize() {
    cache.initialize()
  }

  /**
   * Closes the cache and deletes all of its stored values. This will delete all files in the cache
   * directory including files that weren't created by the cache.
   */
  @Throws(IOException::class)
  fun delete() {
    cache.delete()
  }

  /**
   * Deletes all values stored in the cache. In-flight writes to the cache will complete normally,
   * but the corresponding responses will not be stored.
   */
  @Throws(IOException::class)
  fun evictAll() {
    cache.evictAll()
  }

  /**
   * Returns an iterator over the URLs in this cache. This iterator doesn't throw
   * `ConcurrentModificationException`, but if new responses are added while iterating, their URLs
   * will not be returned. If existing responses are evicted during iteration, they will be absent
   * (unless they were already returned).
   *
   * The iterator supports [MutableIterator.remove]. Removing a URL from the iterator evicts the
   * corresponding response from the cache. Use this to evict selected responses.
   */
  @Throws(IOException::class)
  fun urls(): MutableIterator<String> {
    return object : MutableIterator<String> {
      val delegate: MutableIterator<DiskLruCache.Snapshot> = cache.snapshots()
      var nextUrl: String? = null
      var canRemove: Boolean = false

      override fun hasNext(): Boolean {
        if (nextUrl != null) return true

        canRemove = false // Prevent delegate.remove() on the wrong item!
        while (delegate.hasNext()) {
          try {
            delegate.next().use { snapshot ->
              val metadata = snapshot.getSource(ENTRY_METADATA).buffer()
              nextUrl = metadata.readUtf8LineStrict()
              return true
            }
          } catch (ignored: IOException) {
            // We couldn't read the metadata for this snapshot; possibly because the host filesystem
            // has disappeared! Skip it.
          }
        }

        return false
      }

      override fun next(): String {
        if (!hasNext()) throw NoSuchElementException()
        val result = nextUrl!!
        nextUrl = null
        canRemove = true
        return result
      }

      override fun remove() {
        if (!canRemove) throw IllegalStateException("remove() before next()")
        delegate.remove()
      }
    }
  }

  @Synchronized fun writeAbortCount(): Int = writeAbortCount

  @Synchronized fun writeSuccessCount(): Int = writeSuccessCount

  @Throws(IOException::class)
  fun size(): Long = cache.size()

  /** Max size of the cache (in bytes).  */
  fun maxSize(): Long = cache.maxSize

  @Throws(IOException::class)
  override fun flush() {
    cache.flush()
  }

  @Throws(IOException::class)
  override fun close() {
    cache.close()
  }

  fun directory(): File = cache.directory

  @Synchronized internal fun trackResponse(cacheStrategy: CacheStrategy) {
    requestCount++

    if (cacheStrategy.networkRequest != null) {
      // If this is a conditional request, we'll increment hitCount if/when it hits.
      networkCount++
    } else if (cacheStrategy.cacheResponse != null) {
      // This response uses the cache and not the network. That's a cache hit.
      hitCount++
    }
  }

  @Synchronized internal fun trackConditionalCacheHit() {
    hitCount++
  }

  @Synchronized fun networkCount(): Int = networkCount

  @Synchronized fun hitCount(): Int = hitCount

  @Synchronized fun requestCount(): Int = requestCount

  private inner class RealCacheRequest internal constructor(
    private val editor: DiskLruCache.Editor
  ) : CacheRequest {
    private val cacheOut: Sink = editor.newSink(ENTRY_BODY)
    private val body: Sink
    internal var done: Boolean = false

    init {
      this.body = object : ForwardingSink(cacheOut) {
        @Throws(IOException::class)
        override fun close() {
          synchronized(this@Cache) {
            if (done) return
            done = true
            writeSuccessCount++
          }
          super.close()
          editor.commit()
        }
      }
    }

    override fun abort() {
      synchronized(this@Cache) {
        if (done) return
        done = true
        writeAbortCount++
      }
      closeQuietly(cacheOut)
      try {
        editor.abort()
      } catch (ignored: IOException) {
      }
    }

    override fun body(): Sink = body
  }

  private class Entry {
    private val url: String
    private val varyHeaders: Headers
    private val requestMethod: String
    private val protocol: Protocol
    private val code: Int
    private val message: String
    private val responseHeaders: Headers
    private val handshake: Handshake?
    private val sentRequestMillis: Long
    private val receivedResponseMillis: Long

    private val isHttps: Boolean get() = url.startsWith("https://")

    /**
     * Reads an entry from an input stream. A typical entry looks like this:
     *
     * ```
     * http://google.com/foo
     * GET
     * 2
     * Accept-Language: fr-CA
     * Accept-Charset: UTF-8
     * HTTP/1.1 200 OK
     * 3
     * Content-Type: image/png
     * Content-Length: 100
     * Cache-Control: max-age=600
     * ```
     *
     * A typical HTTPS file looks like this:
     *
     * ```
     * https://google.com/foo
     * GET
     * 2
     * Accept-Language: fr-CA
     * Accept-Charset: UTF-8
     * HTTP/1.1 200 OK
     * 3
     * Content-Type: image/png
     * Content-Length: 100
     * Cache-Control: max-age=600
     *
     * AES_256_WITH_MD5
     * 2
     * base64-encoded peerCertificate[0]
     * base64-encoded peerCertificate[1]
     * -1
     * TLSv1.2
     * ```
     *
     * The file is newline separated. The first two lines are the URL and the request method. Next
     * is the number of HTTP Vary request header lines, followed by those lines.
     *
     * Next is the response status line, followed by the number of HTTP response header lines,
     * followed by those lines.
     *
     * HTTPS responses also contain SSL session information. This begins with a blank line, and then
     * a line containing the cipher suite. Next is the length of the peer certificate chain. These
     * certificates are base64-encoded and appear each on their own line. The next line contains the
     * length of the local certificate chain. These certificates are also base64-encoded and appear
     * each on their own line. A length of -1 is used to encode a null array. The last line is
     * optional. If present, it contains the TLS version.
     */
    @Throws(IOException::class)
    internal constructor(rawSource: Source) {
      try {
        val source = rawSource.buffer()
        url = source.readUtf8LineStrict()
        requestMethod = source.readUtf8LineStrict()
        val varyHeadersBuilder = Headers.Builder()
        val varyRequestHeaderLineCount = readInt(source)
        for (i in 0 until varyRequestHeaderLineCount) {
          addHeaderLenient(varyHeadersBuilder, source.readUtf8LineStrict())
        }
        varyHeaders = varyHeadersBuilder.build()

        val statusLine = StatusLine.parse(source.readUtf8LineStrict())
        protocol = statusLine.protocol
        code = statusLine.code
        message = statusLine.message
        val responseHeadersBuilder = Headers.Builder()
        val responseHeaderLineCount = readInt(source)
        for (i in 0 until responseHeaderLineCount) {
          addHeaderLenient(responseHeadersBuilder, source.readUtf8LineStrict())
        }
        val sendRequestMillisString = responseHeadersBuilder[SENT_MILLIS]
        val receivedResponseMillisString = responseHeadersBuilder[RECEIVED_MILLIS]
        responseHeadersBuilder.removeAll(SENT_MILLIS)
        responseHeadersBuilder.removeAll(RECEIVED_MILLIS)
        sentRequestMillis = sendRequestMillisString?.toLong() ?: 0L
        receivedResponseMillis = receivedResponseMillisString?.toLong() ?: 0L
        responseHeaders = responseHeadersBuilder.build()

        if (isHttps) {
          val blank = source.readUtf8LineStrict()
          if (blank.isNotEmpty()) {
            throw IOException("expected \"\" but was \"$blank\"")
          }
          val cipherSuiteString = source.readUtf8LineStrict()
          val cipherSuite = CipherSuite.forJavaName(cipherSuiteString)
          val peerCertificates = readCertificateList(source)
          val localCertificates = readCertificateList(source)
          val tlsVersion = if (!source.exhausted()) {
            TlsVersion.forJavaName(source.readUtf8LineStrict())
          } else {
            TlsVersion.SSL_3_0
          }
          handshake = Handshake.get(tlsVersion, cipherSuite, peerCertificates, localCertificates)
        } else {
          handshake = null
        }
      } finally {
        rawSource.close()
      }
    }

    internal constructor(response: Response) {
      this.url = response.request().url().toString()
      this.varyHeaders = HttpHeaders.varyHeaders(response)
      this.requestMethod = response.request().method()
      this.protocol = response.protocol()
      this.code = response.code()
      this.message = response.message()
      this.responseHeaders = response.headers()
      this.handshake = response.handshake()
      this.sentRequestMillis = response.sentRequestAtMillis()
      this.receivedResponseMillis = response.receivedResponseAtMillis()
    }

    @Throws(IOException::class)
    fun writeTo(editor: DiskLruCache.Editor) {
      val sink = editor.newSink(ENTRY_METADATA).buffer()
      sink.writeUtf8(url).writeByte('\n'.toInt())
      sink.writeUtf8(requestMethod).writeByte('\n'.toInt())
      sink.writeDecimalLong(varyHeaders.size().toLong()).writeByte('\n'.toInt())
      for (i in 0 until varyHeaders.size()) {
        sink.writeUtf8(varyHeaders.name(i))
            .writeUtf8(": ")
            .writeUtf8(varyHeaders.value(i))
            .writeByte('\n'.toInt())
      }

      sink.writeUtf8(StatusLine(protocol, code, message).toString()).writeByte('\n'.toInt())
      sink.writeDecimalLong((responseHeaders.size() + 2).toLong()).writeByte('\n'.toInt())
      for (i in 0 until responseHeaders.size()) {
        sink.writeUtf8(responseHeaders.name(i))
            .writeUtf8(": ")
            .writeUtf8(responseHeaders.value(i))
            .writeByte('\n'.toInt())
      }
      sink.writeUtf8(SENT_MILLIS)
          .writeUtf8(": ")
          .writeDecimalLong(sentRequestMillis)
          .writeByte('\n'.toInt())
      sink.writeUtf8(RECEIVED_MILLIS)
          .writeUtf8(": ")
          .writeDecimalLong(receivedResponseMillis)
          .writeByte('\n'.toInt())

      if (isHttps) {
        sink.writeByte('\n'.toInt())
        sink.writeUtf8(handshake!!.cipherSuite().javaName()).writeByte('\n'.toInt())
        writeCertList(sink, handshake.peerCertificates())
        writeCertList(sink, handshake.localCertificates())
        sink.writeUtf8(handshake.tlsVersion().javaName()).writeByte('\n'.toInt())
      }
      sink.close()
    }

    @Throws(IOException::class)
    private fun readCertificateList(source: BufferedSource): List<Certificate> {
      val length = readInt(source)
      if (length == -1) return emptyList() // OkHttp v1.2 used -1 to indicate null.

      try {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val result = ArrayList<Certificate>(length)
        for (i in 0 until length) {
          val line = source.readUtf8LineStrict()
          val bytes = Buffer()
          bytes.write(line.decodeBase64()!!)
          result.add(certificateFactory.generateCertificate(bytes.inputStream()))
        }
        return result
      } catch (e: CertificateException) {
        throw IOException(e.message)
      }
    }

    @Throws(IOException::class)
    private fun writeCertList(sink: BufferedSink, certificates: List<Certificate>) {
      try {
        sink.writeDecimalLong(certificates.size.toLong()).writeByte('\n'.toInt())
        for (i in 0 until certificates.size) {
          val bytes = certificates[i].encoded
          val line = ByteString.of(*bytes).base64()
          sink.writeUtf8(line).writeByte('\n'.toInt())
        }
      } catch (e: CertificateEncodingException) {
        throw IOException(e.message)
      }
    }

    fun matches(request: Request, response: Response): Boolean {
      return url == request.url().toString() &&
          requestMethod == request.method() &&
          HttpHeaders.varyMatches(response, varyHeaders, request)
    }

    fun response(snapshot: DiskLruCache.Snapshot): Response {
      val contentType = responseHeaders["Content-Type"]
      val contentLength = responseHeaders["Content-Length"]
      val cacheRequest = Request.Builder()
          .url(url)
          .method(requestMethod, null)
          .headers(varyHeaders)
          .build()
      return Response.Builder()
          .request(cacheRequest)
          .protocol(protocol)
          .code(code)
          .message(message)
          .headers(responseHeaders)
          .body(CacheResponseBody(snapshot, contentType, contentLength))
          .handshake(handshake)
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(receivedResponseMillis)
          .build()
    }

    companion object {
      /** Synthetic response header: the local time when the request was sent.  */
      private val SENT_MILLIS = Platform.get().getPrefix() + "-Sent-Millis"

      /** Synthetic response header: the local time when the response was received.  */
      private val RECEIVED_MILLIS = Platform.get().getPrefix() + "-Received-Millis"
    }
  }

  private class CacheResponseBody internal constructor(
    internal val snapshot: DiskLruCache.Snapshot,
    private val contentType: String?,
    private val contentLength: String?
  ) : ResponseBody() {
    private val bodySource: BufferedSource

    init {
      val source = snapshot.getSource(ENTRY_BODY)
      bodySource = object : ForwardingSource(source) {
        @Throws(IOException::class)
        override fun close() {
          snapshot.close()
          super.close()
        }
      }.buffer()
    }

    override fun contentType(): MediaType? {
      return if (contentType != null) MediaType.parse(contentType) else null
    }

    override fun contentLength(): Long {
      return try {
        contentLength?.toLong() ?: -1
      } catch (e: NumberFormatException) {
        -1L
      }
    }

    override fun source(): BufferedSource = bodySource
  }

  companion object {
    private const val VERSION = 201105
    private const val ENTRY_METADATA = 0
    private const val ENTRY_BODY = 1
    private const val ENTRY_COUNT = 2

    @JvmStatic
    fun key(url: HttpUrl): String = url.toString().encodeUtf8().md5().hex()

    @Throws(IOException::class)
    @JvmStatic
    internal fun readInt(source: BufferedSource): Int {
      try {
        val result = source.readDecimalLong()
        val line = source.readUtf8LineStrict()
        if (result < 0 || result > Integer.MAX_VALUE || !line.isEmpty()) {
          throw IOException("expected an int but was \"$result$line\"")
        }
        return result.toInt()
      } catch (e: NumberFormatException) {
        throw IOException(e.message)
      }
    }
  }
}
