package com.btl.tinder.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import io.getstream.video.android.compose.theme.VideoTheme
import io.getstream.video.android.compose.ui.components.call.activecall.CallContent
import io.getstream.video.android.core.call.state.*
import io.getstream.video.android.compose.permission.LaunchCallPermissions
import io.getstream.video.android.core.Call
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.StreamVideoBuilder
import io.getstream.video.android.model.User
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.models.Message
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Màn hình video call sử dụng Stream Video SDK
 */
class VideoCallScreen : ComponentActivity() {

    // ID của channel chat để gửi tin nhắn khi cuộc gọi kết thúc
    private var channelId: String? = null
    private var callStartTime: Long = 0
    private val isHandlingCallEnd = AtomicBoolean(false)
    private var messageSentFlag = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lấy thông tin từ Intent được truyền vào
        val callId = intent.getStringExtra(KEY_CALL_ID) // ID duy nhất của cuộc gọi
        val callType = intent.getStringExtra(KEY_CALL_TYPE) ?: "default" // Loại cuộc gọi (mặc định: "default")
        val userId = intent.getStringExtra(KEY_USER_ID) // ID người dùng hiện tại
        val userName = intent.getStringExtra(KEY_USER_NAME) // Tên người dùng
        val userImage = intent.getStringExtra(KEY_USER_IMAGE) // Ảnh đại diện người dùng
        channelId = intent.getStringExtra(KEY_CHANNEL_ID) // ID channel chat để gửi tin nhắn

        // Kiểm tra dữ liệu bắt buộc: callId và userId phải có
        if (callId == null || userId == null) {
            finish() // Đóng màn hình nếu thiếu dữ liệu
            return
        }

        setContent {
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
                }
            }
            Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                VideoTheme {
                    var currentCall by remember { mutableStateOf<Call?>(null) }
                    val coroutineScope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        // Tạo đối tượng User từ thông tin người dùng
                        // User object cần thiết để StreamVideo SDK xác thực
                        val user = User(
                            id = userId,
                            name = userName ?: "User",
                            image = userImage ?: ""
                        )

                        // Tạo devToken trực tiếp từ userId
                        val devToken = StreamVideo.devToken(userId)

                        if (devToken.isNotEmpty()) {
                            try {
                                // Khởi tạo hoặc tái sử dụng StreamVideo client
                                val videoClient = try {
                                    val existingClient = StreamVideo.instance()
                                    val existingUserId = existingClient.user?.id

                                    // Nếu đã có client và cùng user, tái sử dụng để tiết kiệm tài nguyên
                                    if (existingUserId != null && existingUserId == userId) {
                                        existingClient
                                    } else {
                                        // User khác, cần xóa client cũ và tạo mới
                                        StreamVideo.removeClient()
                                        StreamVideoBuilder(
                                            context = this@VideoCallScreen,
                                            apiKey = "ghhjw753ksej", // API key của Stream Video
                                            user = user,
                                            token = devToken
                                        ).build()
                                    }
                                } catch (e: Exception) {
                                    // Chưa có client, tạo mới
                                    StreamVideoBuilder(
                                        context = this@VideoCallScreen,
                                        apiKey = "ghhjw753ksej",
                                        user = user,
                                        token = devToken
                                    ).build()
                                }

                                // Tạo đối tượng Call với type và id
                                val newCall = videoClient.call(type = callType, id = callId)
                                currentCall = newCall

                                // Tham gia cuộc gọi trong coroutine riêng để không block UI
                                launch {
                                    try {
                                        newCall.join(create = true)
                                        // Lưu thời gian bắt đầu khi join thành công
                                        // Dùng để tính thời lượng cuộc gọi nếu SDK không cung cấp
                                        callStartTime = System.currentTimeMillis()
                                    } catch (e: Exception) {
                                        // Nếu join thất bại, đóng màn hình
                                        finish()
                                    }
                                }
                            } catch (e: Exception) {
                                // Nếu có lỗi trong quá trình setup, đóng màn hình
                                finish()
                            }
                        } else {
                            // Nếu không tạo được token, đóng màn hình
                            finish()
                        }
                    }

                    /**
                     * Hiển thị UI cuộc gọi khi đã tạo call thành công
                     */
                    currentCall?.let { call ->
                        // Yêu cầu quyền camera và microphone từ người dùng
                        // LaunchCallPermissions sẽ tự động xử lý việc request permissions
                        LaunchCallPermissions(call = call)

                        /**
                         * Lắng nghe sự kiện khi connection state thay đổi
                         */
                        LaunchedEffect(call) {
                            var wasConnected = false
                            var connectionStartTime = 0L

                            // Theo dõi connection state của call
                            // collect sẽ tự động trigger khi state thay đổi
                            call.state.connection.collect { connection ->
                                // Đánh dấu đã từng connected để phân biệt với disconnected ban đầu
                                if (connection is io.getstream.video.android.core.RealtimeConnection.Connected) {
                                    wasConnected = true
                                    connectionStartTime = System.currentTimeMillis()
                                    Log.d("VideoCallScreen", "✅ Connection state: CONNECTED")
                                }

                                // Nếu đang connected và chuyển sang disconnected, xử lý kết thúc cuộc gọi
                                if (connection is io.getstream.video.android.core.RealtimeConnection.Disconnected && wasConnected) {
                                    Log.d("VideoCallScreen", "⚠️ Connection state: DISCONNECTED")
                                    Log.d("VideoCallScreen", "⚠️ isHandlingCallEnd: ${isHandlingCallEnd.get()}, messageSentFlag: $messageSentFlag")
                                    if (!isHandlingCallEnd.get() && !messageSentFlag) {
                                        Log.d("VideoCallScreen", "✅ Disconnected detected - calling handleCallEnd()")
                                        handleCallEnd(call, coroutineScope)
                                    } else {
                                        Log.w("VideoCallScreen", "❌ Skipping handleCallEnd - already processing or message sent")
                                    }
                                }
                            }
                        }
                        LaunchedEffect(call) {
                            try {
                                // Subscribe để nhận tất cả events từ call
                                call.subscribe { event ->
                                    Log.d("VideoCallScreen", "📞 Call event received: ${event::class.simpleName}")

                                    // Xử lý các events liên quan đến kết thúc cuộc gọi
                                    when (event) {
                                        // Event khi cuộc gọi kết thúc qua SFU (Selective Forwarding Unit)
                                        is io.getstream.video.android.core.events.CallEndedSfuEvent -> {
                                            Log.d("VideoCallScreen", "✅ CallEndedSfuEvent detected")
                                            if (!isHandlingCallEnd.get() && !messageSentFlag) {
                                                Log.d("VideoCallScreen", "✅ Calling handleCallEnd() from CallEndedSfuEvent")
                                                handleCallEnd(call, coroutineScope)
                                            } else {
                                                Log.w("VideoCallScreen", "❌ Skipping handleCallEnd from CallEndedSfuEvent - already processing")
                                            }
                                        }
                                        // Event khi cuộc gọi kết thúc (generic)
                                        is io.getstream.android.video.generated.models.CallEndedEvent -> {
                                            Log.d("VideoCallScreen", "✅ CallEndedEvent detected")
                                            if (!isHandlingCallEnd.get() && !messageSentFlag) {
                                                Log.d("VideoCallScreen", "✅ Calling handleCallEnd() from CallEndedEvent")
                                                handleCallEnd(call, coroutineScope)
                                            } else {
                                                Log.w("VideoCallScreen", "❌ Skipping handleCallEnd from CallEndedEvent - already processing")
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("VideoCallScreen", "❌ Error subscribing to call events: ${e.message}", e)
                            }
                        }

                        CallContent(
                            modifier = Modifier.fillMaxSize().statusBarsPadding(),
                            call = call,
                            onBackPressed = {
                                Log.d("VideoCallScreen", "🔙 onBackPressed callback triggered")
                                if (!isHandlingCallEnd.get()) {
                                    Log.d("VideoCallScreen", "✅ Calling handleCallEnd() from onBackPressed")
                                    handleCallEnd(call, coroutineScope)
                                } else {
                                    Log.w("VideoCallScreen", "❌ Skipping handleCallEnd from onBackPressed - already processing")
                                }
                            },
                            onCallAction = { action ->
                                when (action) {
                                    is LeaveCall -> {
                                        handleCallEnd(call, coroutineScope)
                                    }
                                    is ToggleCamera -> {
                                        call.camera.setEnabled(action.isEnabled)
                                    }
                                    is ToggleMicrophone -> {
                                        call.microphone.setEnabled(action.isEnabled)
                                    }
                                    is FlipCamera -> {
                                        call.camera.flip()
                                    }
                                    else -> Unit
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * Xử lý khi cuộc gọi kết thúc
     *
     * @param call Đối tượng Call đại diện cho cuộc gọi
     * @param coroutineScope Scope để chạy các tác vụ bất đồng bộ
     */
    private fun handleCallEnd(call: Call, coroutineScope: kotlinx.coroutines.CoroutineScope) {
        Log.d("VideoCallScreen", "🔄 handleCallEnd() called")
        if (!isHandlingCallEnd.compareAndSet(false, true)) {
            // Đã có một thread khác đang xử lý, bỏ qua
            Log.w("VideoCallScreen", "❌ handleCallEnd() already in progress - ignoring duplicate call")
            return
        }

        Log.d("VideoCallScreen", "✅ handleCallEnd() started processing")

        // Chạy trên main thread để đảm bảo UI được cập nhật đúng cách
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                // Bước 1: Tính thời lượng cuộc gọi
                var durationInMs = call.state.durationInMs.value

                // Nếu SDK không cung cấp durationInMs, thử lấy từ duration (Duration object)
                if (durationInMs == null || durationInMs == 0L) {
                    val duration = call.state.duration.value
                    if (duration != null) {
                        // Chuyển đổi Duration sang milliseconds
                        durationInMs = duration.inWholeSeconds * 1000
                    } else {
                        // Nếu vẫn không có, tính từ thời gian bắt đầu (fallback)
                        if (callStartTime > 0) {
                            durationInMs = System.currentTimeMillis() - callStartTime
                        } else {
                            durationInMs = 0
                        }
                    }
                }
                 // Đảm bảo thời lượng không bị âm, có thể do lỗi đồng bộ thời gian
                if (durationInMs != null && durationInMs < 0) {
                    durationInMs = 0L
                }


                // Format thời lượng thành chuỗi dạng "MM:SS" hoặc "HH:MM:SS"
                val durationText = if (durationInMs != null && durationInMs > 0) {
                    formatCallDuration(durationInMs)
                } else {
                    "0:00"
                }

                // Bước 2: Gửi tin nhắn vào channel chat TRƯỚC khi leave
                if (!messageSentFlag) {
                    Log.d("VideoCallScreen", "📤 Sending call ended message: $durationText")
                    sendCallEndedMessage(durationText)
                    messageSentFlag = true
                } else {
                    Log.w("VideoCallScreen", "⚠️ Message already sent - skipping")
                }

                // Bước 3: Rời cuộc gọi
                try {
                    val connection = call.state.connection.value
                    if (connection !is io.getstream.video.android.core.RealtimeConnection.Disconnected) {
                        // Chỉ leave nếu chưa disconnected
                        Log.d("VideoCallScreen", "📞 Calling call.leave()")
                        call.leave()
                        Log.d("VideoCallScreen", "✅ call.leave() completed")
                    } else {
                        Log.d("VideoCallScreen", "⏭️ Already disconnected - skipping call.leave()")
                    }
                } catch (e: Exception) {
                    Log.w("VideoCallScreen", "⚠️ Error calling call.leave(): ${e.message}")
                }

            } catch (e: kotlinx.coroutines.CancellationException) {

                if (!messageSentFlag) {
                    try {
                        val durationInMs = call.state.durationInMs.value ?:
                        (if (callStartTime > 0) System.currentTimeMillis() - callStartTime else 0)
                        val durationText = if (durationInMs > 0) formatCallDuration(durationInMs) else "0:00"
                        sendCallEndedMessage(durationText)
                        messageSentFlag = true
                    } catch (ex: Exception) {
                    }
                }
            } catch (e: Exception) {
            } finally {
                Log.d("VideoCallScreen", "🏁 handleCallEnd() finishing")

                // Reset flag để cho phép xử lý lần sau
                isHandlingCallEnd.set(false)

                // Bước 4: Đóng màn hình và quay về màn hình chat
                if (!isFinishing && !isDestroyed) {
                    Log.d("VideoCallScreen", "🚪 Finishing activity")
                    finish()
                } else {
                    Log.w("VideoCallScreen", "⚠️ Activity already finishing or destroyed")
                }
            }
        }
    }

    /**
     * Format thời lượng cuộc gọi từ milliseconds sang chuỗi dạng "MM:SS" hoặc "HH:MM:SS"
     *
     * @param durationMs Thời lượng cuộc gọi tính bằng milliseconds
     * @return Chuỗi thời lượng đã được format
     */
    @SuppressLint("DefaultLocale")
    private fun formatCallDuration(durationMs: Long): String {
        // Tính số giây, phút, giờ từ milliseconds
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)

        // Format theo định dạng phù hợp
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds) // Có giờ: "HH:MM:SS"
            minutes > 0 -> String.format("%d:%02d", minutes, seconds) // Chỉ có phút: "MM:SS"
            else -> String.format("0:%02d", seconds) // Chỉ có giây: "0:SS"
        }
    }

    /**
     * Gửi tin nhắn thông báo "Cuộc gọi đã kết thúc • [thời gian]" vào channel chat
     *
     * @param durationText Thời lượng cuộc gọi đã được format (ví dụ: "1:05")
     */
    private fun sendCallEndedMessage(durationText: String) {
        val channelIdValue = channelId
        if (channelIdValue == null) {
            // Không có channelId, không thể gửi tin nhắn
            return
        }

        try {
            val chatClient = ChatClient.instance()
            val parts = channelIdValue.split(":")
            val channelType = if (parts.size > 1) parts[0] else "messaging" // Mặc định là "messaging"
            val actualChannelId = if (parts.size > 1) parts[1] else channelIdValue
            val message = Message(
                text = "Cuộc gọi đã kết thúc • $durationText"
            )

            // Lấy channel và gửi tin nhắn
            val channel = chatClient.channel(channelType, actualChannelId)
            channel.sendMessage(message).enqueue { result ->
                // Xử lý kết quả (thành công hoặc thất bại)
                // Không cần xử lý gì đặc biệt, chỉ cần gửi được là đủ
            }
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val KEY_CALL_ID = "callId" // ID duy nhất của cuộc gọi
        private const val KEY_CALL_TYPE = "callType" // Loại cuộc gọi (mặc định: "default")
        private const val KEY_USER_ID = "userId" // ID người dùng hiện tại
        private const val KEY_USER_NAME = "userName" // Tên người dùng
        private const val KEY_USER_IMAGE = "userImage" // Ảnh đại diện người dùng
        private const val KEY_CHANNEL_ID = "channelId" // ID channel chat để gửi tin nhắn

        fun getIntent(
            context: Context,
            callId: String,
            callType: String = "default",
            userId: String,
            userName: String? = null,
            userImage: String? = null,
            channelId: String? = null
        ): Intent {
            return Intent(context, VideoCallScreen::class.java).apply {
                putExtra(KEY_CALL_ID, callId)
                putExtra(KEY_CALL_TYPE, callType)
                putExtra(KEY_USER_ID, userId)
                putExtra(KEY_USER_NAME, userName)
                putExtra(KEY_USER_IMAGE, userImage)
                putExtra(KEY_CHANNEL_ID, channelId)
            }
        }
    }
}