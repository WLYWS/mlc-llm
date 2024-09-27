package ai.mlc.mlcchat.hl

import ai.mlc.mlcchat.AppViewModel
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PromptUtil {
    private const val TAG = "PromptInfo"

    private const val PROMPT_EN = "" +
        "角色:" +
        "你是ATOM公司的一名热情、耐心、有责任心的客服代表，公司主要产品是家庭安防摄像机，你负责解答和处理用户在使用ATOM产品过程中提出的问题和建议，并安抚用户情绪。" +
        "\n问题:" +
        "{question}" +
        "\n知识点:" +
        "{knowledge}" +
        "\n任务:" +
        "1.仔细阅读<问题>识别用户的意图，仔细阅读<知识点>，总结出最佳答案；如果<知识点>中找不到问题原因，可以建议在APP中提交反馈由人工处理。" +
        "\n要求: " +
        "1.回复要简洁明了、积极、有礼貌，不要带给用户负面情绪，尽可能安抚用户" +
        "2.始终避免分享任何有害、不道德、种族主义、性别歧视、恶毒、危险或非法的内容。" +
        "3.翻译成英文输出结果。"

    private fun createPromptString(question: String, suggestions: String, isJa: Boolean = false): String {
        return (if (isJa) PROMPT_EN.apply { replace("英文", "日文") } else PROMPT_EN).replace("{question}", question).replace("{knowledge}", suggestions).trim()
            .apply {
                Log.i(TAG, this)
            }
    }

    interface OnAIResponseListener {
        fun onAIResponse(resp: String)
    }

    interface OnRequestAnswerListener {
        fun onRequestAnswer(answer: List<String>)

        fun onFailed(code: String, error: String)
    }

    private fun requestAnswers(question: String, callback: OnRequestAnswerListener) {
        val obj = JSONObject()
        obj.put("question", question)

        val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), obj.toString())
        OkHttpUtil.post("https://c7caiq0dlj.execute-api.us-west-2.amazonaws.com/test/qa", body, null, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "requestAnswers onFailure: ${e.message}")
                callback.onFailed("95", e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                val resp = response.body?.string()
                Log.i(TAG, "requestAnswers onResponse: $resp")
                if (resp.isNullOrEmpty()) {
                    callback.onFailed("96", "Empty response")
                    return
                }

                try {
                    val gson = Gson()
                    val data = gson.fromJson(resp, AnswerResp::class.java)
                    if (data.isOk()) {
                        callback.onRequestAnswer(data.body.knowledge)
                    } else {
                        callback.onFailed("${data.statusCode}", data.message ?: "Unknown error")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "requestAnswers onResponse: ${e.message}")
                    callback.onFailed("97", e.message ?: "Unknown error")
                }
            }
        })
    }

    private var noteFile: File? = null
    private var contextRef: WeakReference<Context>? = null
    private var chatState: AppViewModel.ChatState? = null
    private var index = -1

    fun doNext() {
        index++

        if (0 > index || index > qArr.length()) {
            done()
            return
        }

        if (index == qArr.length()) {
            done()
            return
        }

        val q = qArr.optString(index)

        requestAnswers(q, object : OnRequestAnswerListener {
            override fun onRequestAnswer(answer: List<String>) {
                askAI(q, answer)
            }

            override fun onFailed(code: String, error: String) {
                writeNote(q, "获取top3失败：code: $code error: $error", "")
                doNext()
            }
        })
    }

    private fun askAI(question: String, answer: List<String>) {
        val sb = StringBuilder()
        var i = 1
        answer.forEach {
            sb.append(i).append(".").append(it)
            i++
        }
        chatState?.requestGenerate(createPromptString(question, sb.toString(), index >= 49), object : OnAIResponseListener {
            override fun onAIResponse(resp: String) {
                writeNote(question, sb.toString(), resp)
                doNext()
            }
        })
    }

    private fun writeNote(question: String, suggestion: String, answer: String) {
        // 将问题、建议、回答追加到记录到本地文件中
        val no = index + 1
        val note = "No.$no\n问题：$question\n建议：$suggestion\n回答：$answer\n\n"
        Log.i(TAG, note)

        noteFile?.appendText(note)
    }

    private fun done() {
        Log.i(TAG, "done")

        noteFile?.appendText("结束")
    }

    fun init(context: Context, chatState: AppViewModel.ChatState) {
        this.contextRef = WeakReference(context)
        this.chatState = chatState

        val dir = File(context.getExternalFilesDir(null)?.absolutePath ?: "")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        if (noteFile != null) {
            return
        }
        val noteFilePath = "${dir.absolutePath}/note_${formatTime(System.currentTimeMillis(), "yyyy_MM_dd_HH_mm_ss")}.txt"
        noteFile = File(noteFilePath)
        noteFile?.appendText("开始")
    }


    private fun formatTime(timestamp: Long, formatStr: String = "yyyy.MM.dd HH:mm:ss.sss", locale: Locale = Locale.getDefault()): String {
        val str = "$timestamp"
        if (str.length < 10) {
            return str
        }

        var stamp = timestamp
        if (str.length == 10) {
            stamp = timestamp * 1000
        }
        val format = SimpleDateFormat(formatStr, locale)
        return format.format(Date(stamp))
    }

    private val qArr: JSONArray = JSONArray(
        "[\"What video encoding format does this home camera use? Is high efficiency video coding (H.265) supported?\"," +
            "\"Does the camera have built-in storage to continue recording video when disconnected?\"," +
            "\"Is there an option to support third-party storage services, such as FTP or NAS?\"," +
            "\"Does the camera have the ability to back up videos and photos to external storage?\"," +
            "\"As long as a rotation of the PTZ, the picture will appear Mosaic how to do?\"," +
            "\"Microphone is hollow and voices are choppy\"," +
            "\"Device Connection Failure\"," +
            "\"Detection Zone hard to edit\"," +
            "\"Firmware cannot be upgraded, a little at a time with the upgrade button What should I do if the update fails\"," +
            "\"App crashed while deleting some videos\"," +
            "\"1.Started firmare update.2.Got to 95% installing and just stayed there installing the rest of the day.3.Cant Access the Camera anymoreWhat should I do\"," +
            "\"I wish landscape mode was available also. It would be easier to see the view.\"," +
            "\"If there is an option to support third-party storage services such as FTP or NAS, it would be even better.\"," +
            "\"I would suggest changing the color of the screws packaging. White package in a white box in a small compartment may not be the best location for a white screw package. Also, it looks like a silica gel packs.\"," +
            "\"What do you do?\"," +
            "\"You are pretty impressive.\"," +
            "\"what fucking kind of the response is that?\"," +
            "\"Tilt the camera to the max height.The camera is too easy to knock over when it's at it's max height.\"," +
            "\"It would be nice if you could set the spotlight to come on automatically when the camera sees motion\"," +
            "\"web access,It'd be amazing if we could access from a browser on our PC\"," +
            "\"The product is too expensive, the cost performance is not high\"," +
            "\"The event video cannot be played after downloading, and it always indicates that the file is damaged\"," +
            "\"When rotating the gimbal, the live video experiences lag or stuttering.\"," +
            "\"I can't download the app\"," +
            "\"I changed Detection Settings to Continuous Recording. Nothing appears to have changed. Events and Playback are still 12 second recordings.What am I missing?\"," +
            "\"Can you tell me the weather for the coming week\"," +
            "\"What are the main functions of this camera?\"," +
            "\"How do I set up and connect this camera to my home network?\"," +
            "\"What type of storage device does it support to save videos?\"," +
            "\"Does this camera have night vision?\"," +
            "\"Is the camera waterproof and suitable for outdoor installation?\"," +
            "\"If I have multiple cameras, can I manage and view them through an app?\"," +
            "\"Sometimes it did not recognize the face recognition.\"," +
            "\"Same problem as always, connection drops off, this camera is defective.\"," +
            "\"The app is notifying me of motion, person, and pet alerts even with nothing moving in the frame.\"," +
            "\"Let it get a few events. You can download from events (and then scroll down to get further back events), but cannot download if you select an item from the playback index. These two features should have the same capabilities for download.Additionally, playback should allow for multiple select, multiple action (for delete or download) from the thumbnail pane.\"," +
            "\"the \\\"scroll down\\\" to look at more events is not very discoverable..\"," +
            "\"There is a slight delay for audio communications from the camera. Maybe a second or two.I tried different distances between phone and camera and the delay seemed to be the same.\"," +
            "\"Two way voice not working\"," +
            "\"Since the latest firmware update, I am getting tons of untriggered detection notifications.\"," +
            "\"Speaker has a latency\"," +
            "\"no events are being recorded. getting very frustrated now\"," +
            "\"Nothing works. Service is getting worse and worse. I think I keep trying as a form of self abuse.\"," +
            "\"I have tried multiple times to setup cam but the initial required firmware update fails. I've restarted the device and I've rebooted router but to no avail. About ready to send the damn thing back\"," +
            "\"Never connects! Fix the damn camera\"," +
            "\"error code, software is JUNK!\"," +
            "\"The camera has gone off every minute. That’s it, I’m done. I want a refund.\"," +
            "\"I AM ABOUT READY TO THROW THESE CAMERAS IN THE GARBAGE!!! ever since upgraded I have constant connection issues. I have submitted multiple logs. I want to downgrade my firmware!!!\"," +
            "\"Yet another notification of a person when there isn't a goddamn person. Your system is infuriating.\"," +
            "\"Get back to me and let’s talk about what can you do to get it better. Otherwise , I will return this piece of crap tomorrow\"," +
            "\"このホームカメラはどのビデオエンコード形式を使用していますか?高効率ビデオコーディング(H.26 5)はサポートされていますか?\"," +
            "\"カメラには、切断されたときにビデオを録画し続けるための内蔵ストレージがありますか?\"," +
            "\"FTPやNASなどのサードパーティのストレージサービスをサポートするオプションはありますか?\"," +
            "\"カメラには、ビデオや写真を外部ストレージにバックアップする機能がありますか?\"," +
            "\"限りPTZの回転として、画像が表示されますモザイクどのように行うには?\"," +
            "\"マイクが空洞で声が途切れています\"," +
            "\"デバイス接続の失敗\"," +
            "\"編集が難しい検出ゾーン\"," +
            "\"アップグレードボタンで少しずつファームウェアをアップグレードできません。アップデートに失敗した場合はどうすればいいですか?\"," +
            "\"一部の動画を削除中にアプリがクラッシュしました\"," +
            "\"1.95%のインストールupdate.2.Gotを開始し、残りの日をインストールし続けました。3.もうカメラにアクセスできないどうすればいいですか?\"," +
            "\"横画面モードもあればいいのに。そうすれば景色が見やすくなるのに。\"," +
            "\"FTPやNASなどのサードパーティのストレージサービスをサポートするオプションがある場合は、さらに良いでしょう。\"," +
            "\"スクリューパッケージの色を変更することをお勧めします。小さなコンパートメントにある白い箱の中の白いパッケージは、白いスクリューパッケージに最適な場所ではないかもしれません。また、シリカゲルパックのように見えます。\"," +
            "\"何をなさいますか。\"," +
            "\"あなたはかなり印象的です。\"," +
            "\"どういう返答だ?\"," +
            "\"カメラを最大の高さに傾けてください。カメラが最大の高さにあると、転倒しやすくなります。\"," +
            "\"カメラが動きを見ると自動的にスポットライトが点灯するように設定できるといいですね\"," +
            "\"ウェブアクセス、PCのブラウザからアクセスできたら素晴らしいですね\"," +
            "\"製品が高すぎて、コストパフォーマンスが高くない\"," +
            "\"イベントビデオはダウンロード後に再生できず、常にファイルが破損していることを示します\"," +
            "\"ジンバルを回転させると、ライブビデオに遅延やスタッタリングが生じます。\"," +
            "\"アプリをダウンロードできません。\"," +
            "\"検出設定を連続録画に変更しました。何も変わっていないようです。イベントと再生はまだ12秒の録画です。何が足りないのでしょうか?\"," +
            "\"来週の天気を教えてください。\"," +
            "\"このカメラの主な機能は何ですか?\"," +
            "\"このカメラをホームネットワークに設定して接続するにはどうすればよいですか?\"," +
            "\"ビデオを保存するためにサポートされているストレージデバイスの種類は何ですか?\"," +
            "\"このカメラは暗視機能がありますか?\"," +
            "\"カメラは防水で、屋外設置に適していますか?\"," +
            "\"複数のカメラを持っている場合、アプリで管理して表示できますか?\"," +
            "\"時々、顔認識を認識できなかった。\"," +
            "\"いつもと同じ問題、接続が切れる、このカメラは欠陥品です。\"," +
            "\"アプリは、フレーム内で何も動いていなくても、動き、人、ペットのアラートを私に通知しています。\"," +
            "\"いくつかのイベントを取得させてください。イベントからダウンロードすることはできますが、再生インデックスからアイテムを選択するとダウンロードできません。これら2つの機能は、ダウンロードに同じ機能を持つ必要があります。さらに、再生により、サムネイルペインから複数の選択、複数のアクション(削除またはダウンロード)が可能になる必要があります。\"," +
            "\"もっとイベントを見るための「スクロールダウン」はあまり見つけにくいです。\"," +
            "\"カメラからの音声通信にはわずかな遅延があります。おそらく1秒か2秒です。私は電話とカメラの間の異なる距離を試しましたが、遅延は同じように見えました。\"," +
            "\"双方向の音声が機能していません。\"," +
            "\"最新のファームウェアのアップデート以来、私はたくさんの未トリガーの検出通知を受け取っています。\"," +
            "\"スピーカーには遅延があります\"," +
            "\"イベントは録画されていません。今とてもイライラしています。\"," +
            "\"何も機能しません。サービスはどんどん悪化しています。私は自己の一形態として努力し続けていると思います。\"," +
            "\"私は複数回カムをセットアップしようとしましたが、最初に必要なファームウェアのアップデートが失敗しました。私はデバイスを再起動し、ルータを再起動しましたが、無駄でした。くそったれのものを送り返す準備ができています\"," +
            "\"接続しないでください!カメラを修理してください。\"," +
            "\"エラーコード, software is JUNK!\"," +
            "\"カメラは1分ごとに消えています。それだけです、私は終わりました。返金してほしいです。\"," +
            "\"私はこれらのカメラをゴミに投げる準備ができています!！！ アップグレードして以来、常に接続の問題があります。複数のログを提出しました。ファームウェアをダウングレードしたいです!！！\"," +
            "\"人がいないときに人に通知するもう一つの通知。あなたのシステムは腹立たしいです。\"," +
            "\"私に戻って、それをより良くするために何ができるかについて話しましょう。そうでなければ、私は明日このがらくたを返します\"]"
    )
}