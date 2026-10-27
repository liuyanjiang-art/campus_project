package edu.swjtu.campus.model

import kotlinx.serialization.Serializable

/** 用户角色：学生 / 社团组织者 / 社团管理老师 */
@Serializable
enum class Role {
    STUDENT, ORGANIZER, TEACHER
}

/** 活动状态：待审核 / 已发布 / 审核未通过 / 已取消 */
@Serializable
enum class ActivityStatus {
    PENDING_REVIEW, PUBLISHED, REJECTED, CANCELLED
}

/** 报名状态：已报名 / 已取消 */
@Serializable
enum class RegistrationStatus {
    REGISTERED, CANCELLED
}

/** 用户。密码为本地明文存储（课程 mini-project 允许，不接入真实统一身份认证） */
@Serializable
data class User(
    val id: String,
    val username: String,
    val password: String,
    val name: String,
    val role: Role,
    val clubName: String = "",
    val college: String = "",
    val phone: String = "",
    /** 登录后上传的个性头像地址（本地 /uploads 目录） */
    val avatarUrl: String = ""
)

/** 活动。qrToken 用于生成报名二维码 */
@Serializable
data class Activity(
    val id: String,
    val title: String,
    val clubName: String,
    val organizerId: String,
    val organizerName: String,
    val content: String,
    val location: String,
    val requirements: String,
    val capacity: Int,
    val registrationDeadline: String,
    val startTime: String,
    val endTime: String,
    val status: ActivityStatus,
    val qrToken: String,
    val createdAt: String,
    val reviewComment: String = "",
    val reviewerName: String = "",
    val reviewedAt: String = "",
    val imageUrl: String = ""
)

/** 报名记录 */
@Serializable
data class Registration(
    val id: String,
    val activityId: String,
    val studentId: String,
    val studentName: String,
    val studentNo: String,
    val registeredAt: String,
    val status: RegistrationStatus
)

/** 活动提醒 */
@Serializable
data class Reminder(
    val id: String,
    val studentId: String,
    val activityId: String,
    val activityTitle: String,
    val remindAt: String,
    val note: String,
    val enabled: Boolean
)

/** 返回给前端的用户信息（不含密码） */
fun User.toView(): User = copy(password = "")

/** 活动状态的中文名称 */
val ActivityStatus.text: String
    get() = when (this) {
        ActivityStatus.PENDING_REVIEW -> "待审核"
        ActivityStatus.PUBLISHED -> "已发布"
        ActivityStatus.REJECTED -> "审核未通过"
        ActivityStatus.CANCELLED -> "已取消"
    }

/** 角色的中文名称 */
val Role.text: String
    get() = when (this) {
        Role.STUDENT -> "学生"
        Role.ORGANIZER -> "社团组织者"
        Role.TEACHER -> "社团管理老师"
    }
