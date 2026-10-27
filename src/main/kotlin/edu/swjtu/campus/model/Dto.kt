package edu.swjtu.campus.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class QrInfoResponse(val qrToken: String, val url: String, val imageUrl: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val name: String,
    val role: Role = Role.STUDENT,
    val clubName: String = "",
    val college: String = "",
    val phone: String = ""
)

@Serializable
data class LoginResponse(val token: String, val user: User)

/** 上传图片成功后返回本地可访问地址 */
@Serializable
data class UploadResponse(val url: String)

/** 用户更新自己的头像 */
@Serializable
data class AvatarRequest(val avatarUrl: String)

/** 扫码报名：qrToken 与 activityId 二选一 */
@Serializable
data class SignupRequest(val activityId: String = "", val qrToken: String = "")

@Serializable
data class ReviewRequest(val approve: Boolean, val comment: String = "")

@Serializable
data class ActivityRequest(
    val title: String,
    val content: String,
    val location: String,
    val requirements: String = "",
    val capacity: Int,
    val registrationDeadline: String,
    val startTime: String,
    val endTime: String,
    val imageUrl: String = ""
)

/** 组织者修改活动：只提交需要变更的字段 */
@Serializable
data class ActivityUpdateRequest(
    val title: String? = null,
    val content: String? = null,
    val location: String? = null,
    val requirements: String? = null,
    val capacity: Int? = null,
    val registrationDeadline: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val imageUrl: String? = null
)

@Serializable
data class ReminderRequest(
    val activityId: String,
    val remindAt: String,
    val note: String = "",
    val enabled: Boolean = true
)

/** 活动 + 报名统计，供前端列表和详情页使用 */
@Serializable
data class ActivityView(
    val activity: Activity,
    val registeredCount: Int,
    val remaining: Int,
    val registrationOpen: Boolean,
    val myRegistrationId: String? = null,
    val myRegistrationStatus: String? = null
)

/** 报名记录 + 对应活动 */
@Serializable
data class RegistrationView(
    val registration: Registration,
    val activity: Activity? = null
)

@Serializable
data class StatsView(
    val totalActivities: Int = 0,
    val published: Int = 0,
    val pending: Int = 0,
    val myActivities: Int = 0,
    val myRegistrations: Int = 0,
    val myReminders: Int = 0
)
