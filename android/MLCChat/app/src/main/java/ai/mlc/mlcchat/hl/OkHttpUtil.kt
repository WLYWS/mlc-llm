package ai.mlc.mlcchat.hl

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


object OkHttpUtil {
    private var TAG = "OkHttpUtil"
    private const val TIMEOUT_CONNECT = 10L

    // 接口直接返回弹幕，增加超时时间
    private const val TIMEOUT_READ = 30L
    private const val TIMEOUT_WRITE = 30L
    private var okHttpClient: OkHttpClient = createCommonOkHttpClient()

    private fun createCommonOkHttpClient(): OkHttpClient {
        val customDispatcher = Dispatcher(Executors.newFixedThreadPool(4) { r: Runnable? ->
            val thread = Thread(r, TAG)
            // thread.isDaemon = false // 设置为非守护线程
            thread
        })
        customDispatcher.maxRequests = 30000
        customDispatcher.maxRequestsPerHost = 30000
        return OkHttpClient.Builder()
            .dispatcher(customDispatcher)
            .connectTimeout(TIMEOUT_CONNECT, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_READ, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_WRITE, TimeUnit.SECONDS)
            // 启用http2，该协议可在单个 TCP 连接上并发处理多个请求
            // .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .build()
    }

    fun post(url: String, body: RequestBody, headers: Map<String, String>? = null, callback: Callback) {
        request(url, body, callback, headers)
    }

    fun get(url: String, body: RequestBody, headers: Map<String, String>? = null, callback: Callback) {
        request(url, body, callback, headers, method = "GET")
    }

    fun request(url: String, body: RequestBody, callback: Callback, headers: Map<String, String>? = null, method: String = "POST") {
        if (url.isEmpty()) {
            return
        }
        val requestBuilder: Request.Builder
        // val stringBuilder = StringBuilder()
        if (!headers.isNullOrEmpty()) {
            // 当需要添加cookie时，允许添加多个同key(Cookie)的值，不会覆盖
            // 比如：.addHeader("Cookie", cookie1).addHeader("Cookie", cookie2)
            // 如果是设置其他单一key的话，可以调用.header("key", val)，已存在的值会被覆盖
            val builder: Request.Builder = Request.Builder()
            for ((key, value) in headers.entries) {
                if (!key.isNullOrEmpty() && !value.isNullOrEmpty()) {
                    builder.addHeader(key, value)
                }
            }
            requestBuilder = builder.url(url)
        } else {
            requestBuilder = Request.Builder().url(url)
        }
        requestBuilder.method(method, if ("GET" == method) null else body)

        // SLFLogUtil.sdke(TAG, "$method($url) cookies：$stringBuilder")
        val call: Call = okHttpClient.newCall(requestBuilder.build())

        // 异步enqueue()， 同步execute()
        call.enqueue(callback)
        // var resp: Response? = null
        // try {
        //     resp = call.execute()
        //     callback.onResponse(call, resp)
        // } catch (e: Exception) {
        //     SLFLogUtil.sdkException(e)
        //     callback.onFailure(call, IOException(e))
        // } finally {
        //     resp?.close()
        // }
    }

}
