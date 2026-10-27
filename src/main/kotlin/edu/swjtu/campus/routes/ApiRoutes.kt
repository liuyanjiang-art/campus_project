package edu.swjtu.campus.routes

import edu.swjtu.campus.auth.ApiException
import edu.swjtu.campus.auth.AuthService
import edu.swjtu.campus.auth.currentUserOrNull
import edu.swjtu.campus.auth.requireRole
import edu.swjtu.campus.auth.requireUser
import edu.swjtu.campus.data.IdGen
import edu.swjtu.campus.data.Store
import edu.swjtu.campus.data.TimeUtil
import edu.swjtu.campus.model.Activity
import edu.swjtu.campus.model.ActivityRequest
import edu.swjtu.campus.model.ActivityStatus
import edu.swjtu.campus.model.ActivityUpdateRequest
import edu.swjtu.campus.model.ActivityView
import edu.swjtu.campus.model.AvatarRequest
import edu.swjtu.campus.model.LoginRequest
import edu.swjtu.campus.model.LoginResponse
import edu.swjtu.campus.model.OkResponse
import edu.swjtu.campus.model.QrInfoResponse
import edu.swjtu.campus.model.RegisterRequest
import edu.swjtu.campus.model.Registration
import edu.swjtu.campus.model.RegistrationStatus
import edu.swjtu.campus.model.RegistrationView
import edu.swjtu.campus.model.Reminder
import edu.swjtu.campus.model.ReminderRequest
import edu.swjtu.campus.model.ReviewRequest
import edu.swjtu.campus.model.Role
import edu.swjtu.campus.model.SignupRequest
import edu.swjtu.campus.model.StatsView
import edu.swjtu.campus.model.UploadResponse
import edu.swjtu.campus.model.User
import edu.swjtu.campus.model.text
import edu.swjtu.campus.model.toView
import edu.swjtu.campus.service.QrService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.io.File
import java.time.LocalDateTime

/** 允许上传的图片后缀与体积上限（本地存储，不做云对象存储） */
private val ALLOWED_IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

/** 把上传的图片写入本地 uploads 目录，返回可直接访问的相对地址 */
private fun saveUploadedImage(uploadDir: File, part: PartData.FileItem): String {
    val original = part.originalFileName.orEmpty()
    val ext = original.substringAfterLast('.', "").lowercase().ifEmpty { "png" }
    if (ext !in ALLOWED_IMAGE_EXT) {
        throw ApiException(HttpStatusCode.BadRequest, "仅支持 png / jpg / gif / webp / bmp / svg 图片")
    }
    val bytes = part.streamProvider().readBytes()
    if (bytes.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "上传的文件内容为空")
    if (bytes.size > MAX_IMAGE_BYTES) throw ApiException(HttpStatusCode.BadRequest, "图片不能超过 3MB")
    val name = IdGen.next("IMG").lowercase() + "." + ext
    uploadDir.mkdirs()
    File(uploadDir, name).writeBytes(bytes)
    return "/uploads/$name"
}

/** 组装活动视图：附带报名人数、剩余名额、当前学生是否已报名 */
private fun viewOf(store: Store, activity: Activity, studentId: String?): ActivityView {
    val registeredCount = store.registrations.count {
        it.activityId == activity.id && it.status == RegistrationStatus.REGISTERED
    }
    val deadline = TimeUtil.parse(activity.registrationDeadline)
    val notExpired = deadline == null || !LocalDateTime.now().isAfter(deadline)
    val open = activity.status == ActivityStatus.PUBLISHED && notExpired && registeredCount < activity.capacity
    val mine = studentId?.let { sid ->
        store.registrations.find {
            it.activityId == activity.id && it.studentId == sid && it.status == RegistrationStatus.REGISTERED
        }
    }
    return ActivityView(
        activity = activity,
        registeredCount = registeredCount,
        remaining = (activity.capacity - registeredCount).coerceAtLeast(0),
        registrationOpen = open,
        myRegistrationId = mine?.id,
        myRegistrationStatus = mine?.status?.name
    )
}

private fun callId(call: ApplicationCall): String =
    call.parameters["id"] ?: throw ApiException(HttpStatusCode.BadRequest, "缺少路径参数 id")

private fun requireActivity(store: Store, id: String): Activity =
    store.activities.byId(id) ?: throw ApiException(HttpStatusCode.NotFound, "活动不存在")

private fun baseUrlOf(call: ApplicationCall): String {
    call.request.queryParameters["base"]?.takeIf { it.startsWith("http") }?.let { return it }
    val origin = call.request.origin
    return "${origin.scheme}://${origin.serverHost}:${origin.serverPort}"
}

private fun validateActivity(
    title: String,
    location: String,
    capacity: Int,
    deadline: String,
    start: String,
    end: String
) {
    if (title.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "活动名称不能为空")
    if (location.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "活动地点不能为空")
    if (capacity < 1) throw ApiException(HttpStatusCode.BadRequest, "人数上限必须大于 0")
    val d = TimeUtil.parse(deadline) ?: throw ApiException(HttpStatusCode.BadRequest, "报名截止时间格式不正确")
    val s = TimeUtil.parse(start) ?: throw ApiException(HttpStatusCode.BadRequest, "活动开始时间格式不正确")
    val e = TimeUtil.parse(end) ?: throw ApiException(HttpStatusCode.BadRequest, "活动结束时间格式不正确")
    if (!d.isBefore(s)) throw ApiException(HttpStatusCode.BadRequest, "报名截止时间必须早于活动开始时间")
    if (e.isBefore(s)) throw ApiException(HttpStatusCode.BadRequest, "活动结束时间不能早于活动开始时间")
}

/** 挂载 /api 下的全部接口 */
fun Route.apiRoutes(store: Store, auth: AuthService, uploadDir: File) {

    route("/auth") {
        /** 登录（模拟学校统一身份认证） */
        post("/login") {
            val req = call.receive<LoginRequest>()
            val (token, user) = auth.login(req.username, req.password)
            call.respond(LoginResponse(token, user.toView()))
        }

        /** 注册新账号（学生 / 社团组织者） */
        post("/register") {
            val req = call.receive<RegisterRequest>()
            if (req.username.isBlank() || req.password.length < 6) {
                throw ApiException(HttpStatusCode.BadRequest, "账号不能为空，密码至少 6 位")
            }
            if (req.name.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "姓名不能为空")
            if (req.role == Role.TEACHER) {
                throw ApiException(HttpStatusCode.BadRequest, "教师账号由学校统一分配，不能自助注册")
            }
            if (req.role == Role.ORGANIZER && req.clubName.isBlank()) {
                throw ApiException(HttpStatusCode.BadRequest, "社团组织者必须填写所属社团")
            }
            if (store.users.byUsername(req.username) != null) {
                throw ApiException(HttpStatusCode.BadRequest, "该账号已被注册")
            }
            val user = User(
                id = IdGen.next("U"),
                username = req.username.trim(),
                password = req.password,
                name = req.name.trim(),
                role = req.role,
                clubName = req.clubName.trim(),
                college = req.college.trim(),
                phone = req.phone.trim()
            )
            store.users.insert(user)
            val (token, _) = auth.login(user.username, user.password)
            call.respond(HttpStatusCode.Created, LoginResponse(token, user.toView()))
        }

        post("/logout") {
            call.request.headers["Authorization"]?.removePrefix("Bearer")?.trim()?.let(auth::logout)
            call.respond(OkResponse())
        }

        get("/me") {
            val user = call.requireUser(auth)
            call.respond(user.toView())
        }

        /** 更新当前登录用户的头像（所有角色均可用） */
        post("/avatar") {
            val user = call.requireUser(auth)
            val req = call.receive<AvatarRequest>()
            val updated = user.copy(avatarUrl = req.avatarUrl.trim())
            store.users.upsert(updated)
            call.respond(updated.toView())
        }
    }

    /** 图片上传：头像 / 活动封面通用，登录后即可上传，成功返回本地访问地址 */
    post("/uploads") {
        call.requireUser(auth)
        val multipart = call.receiveMultipart()
        var url: String? = null
        multipart.forEachPart { part ->
            if (part is PartData.FileItem && url == null) {
                url = saveUploadedImage(uploadDir, part)
            }
            part.dispose()
        }
        val saved = url ?: throw ApiException(HttpStatusCode.BadRequest, "未收到上传文件")
        call.respond(HttpStatusCode.Created, UploadResponse(saved))
    }

    route("/activities") {

        /** 活动列表：支持关键词、社团、状态筛选 */
        get {
            val user = call.requireUser(auth)
            val keyword = call.request.queryParameters["keyword"]?.trim().orEmpty()
            val club = call.request.queryParameters["club"]?.trim().orEmpty()
            val statusParam = call.request.queryParameters["status"]?.trim().orEmpty()
            val mineOnly = call.request.queryParameters["mine"] == "true"

            var list = store.activities.all()

            list = when (user.role) {
                // 学生只能看到审核通过并发布的活动
                Role.STUDENT -> list.filter { it.status == ActivityStatus.PUBLISHED }
                // 组织者默认看本校社团公开活动，勾选「我的活动」时只看自己发布的
                Role.ORGANIZER -> if (mineOnly) {
                    list.filter { it.organizerId == user.id }
                } else {
                    list.filter { it.status == ActivityStatus.PUBLISHED || it.organizerId == user.id }
                }
                Role.TEACHER -> list
            }

            if (statusParam.isNotBlank()) {
                val status = runCatching { ActivityStatus.valueOf(statusParam) }.getOrNull()
                if (status != null) list = list.filter { it.status == status }
            }
            if (club.isNotBlank()) {
                list = list.filter { it.clubName.contains(club, ignoreCase = true) }
            }
            if (keyword.isNotBlank()) {
                list = list.filter {
                    it.title.contains(keyword, ignoreCase = true) ||
                        it.clubName.contains(keyword, ignoreCase = true) ||
                        it.content.contains(keyword, ignoreCase = true) ||
                        it.location.contains(keyword, ignoreCase = true)
                }
            }

            val views = list
                .sortedWith(compareBy({ it.status != ActivityStatus.PUBLISHED }, { it.startTime }))
                .map { viewOf(store, it, user.id) }
            call.respond(views)
        }

        /** 活动详情 */
        get("/{id}") {
            val user = call.requireUser(auth)
            val activity = requireActivity(store, callId(call))
            if (activity.status != ActivityStatus.PUBLISHED && activity.organizerId != user.id && user.role != Role.TEACHER) {
                throw ApiException(HttpStatusCode.Forbidden, "该活动尚未发布")
            }
            call.respond(viewOf(store, activity, user.id))
        }

        /** 组织者发布活动（提交后进入待审核） */
        post {
            val user = call.requireRole(auth, Role.ORGANIZER, Role.TEACHER)
            val req = call.receive<ActivityRequest>()
            validateActivity(req.title, req.location, req.capacity, req.registrationDeadline, req.startTime, req.endTime)
            val clubName = if (user.role == Role.ORGANIZER) user.clubName else req.title
            val activity = Activity(
                id = IdGen.next("A"),
                title = req.title.trim(),
                clubName = clubName,
                organizerId = user.id,
                organizerName = user.name,
                content = req.content.trim(),
                location = req.location.trim(),
                requirements = req.requirements.trim(),
                capacity = req.capacity,
                registrationDeadline = TimeUtil.normalize(req.registrationDeadline),
                startTime = TimeUtil.normalize(req.startTime),
                endTime = TimeUtil.normalize(req.endTime),
                status = ActivityStatus.PENDING_REVIEW,
                qrToken = IdGen.qrToken(),
                createdAt = TimeUtil.now(),
                imageUrl = req.imageUrl.trim()
            )
            store.activities.insert(activity)
            call.respond(HttpStatusCode.Created, viewOf(store, activity, null))
        }

        /** 组织者修改活动（延长报名截止 / 增加名额 / 修改地点等） */
        put("/{id}") {
            val user = call.requireRole(auth, Role.ORGANIZER, Role.TEACHER)
            val activity = requireActivity(store, callId(call))
            if (user.role == Role.ORGANIZER && activity.organizerId != user.id) {
                throw ApiException(HttpStatusCode.Forbidden, "只能修改自己社团发布的活动")
            }
            if (activity.status == ActivityStatus.CANCELLED) {
                throw ApiException(HttpStatusCode.BadRequest, "已取消的活动不能修改")
            }
            val req = call.receive<ActivityUpdateRequest>()
            val updated = activity.copy(
                title = req.title?.trim() ?: activity.title,
                content = req.content?.trim() ?: activity.content,
                location = req.location?.trim() ?: activity.location,
                requirements = req.requirements?.trim() ?: activity.requirements,
                capacity = req.capacity ?: activity.capacity,
                registrationDeadline = req.registrationDeadline?.let(TimeUtil::normalize)
                    ?: activity.registrationDeadline,
                startTime = req.startTime?.let(TimeUtil::normalize) ?: activity.startTime,
                endTime = req.endTime?.let(TimeUtil::normalize) ?: activity.endTime,
                imageUrl = req.imageUrl?.trim() ?: activity.imageUrl
            )
            validateActivity(
                updated.title, updated.location, updated.capacity,
                updated.registrationDeadline, updated.startTime, updated.endTime
            )
            val registeredCount = store.registrations.count {
                it.activityId == updated.id && it.status == RegistrationStatus.REGISTERED
            }
            if (updated.capacity < registeredCount) {
                throw ApiException(
                    HttpStatusCode.BadRequest,
                    "人数上限不能小于已报名人数（当前已报名 $registeredCount 人）"
                )
            }
            store.activities.upsert(updated)
            call.respond(viewOf(store, updated, user.id))
        }

        /** 组织者取消活动 */
        post("/{id}/cancel") {
            val user = call.requireRole(auth, Role.ORGANIZER, Role.TEACHER)
            val activity = requireActivity(store, callId(call))
            if (user.role == Role.ORGANIZER && activity.organizerId != user.id) {
                throw ApiException(HttpStatusCode.Forbidden, "只能取消自己社团发布的活动")
            }
            if (activity.status == ActivityStatus.CANCELLED) {
                throw ApiException(HttpStatusCode.BadRequest, "该活动已经是取消状态")
            }
            val updated = activity.copy(status = ActivityStatus.CANCELLED)
            store.activities.upsert(updated)
            call.respond(viewOf(store, updated, user.id))
        }

        /** 社团管理老师审核活动 */
        post("/{id}/review") {
            val teacher = call.requireRole(auth, Role.TEACHER)
            val activity = requireActivity(store, callId(call))
            if (activity.status != ActivityStatus.PENDING_REVIEW) {
                throw ApiException(
                    HttpStatusCode.BadRequest,
                    "该活动当前状态为「${activity.status.text}」，无法审核"
                )
            }
            val req = call.receive<ReviewRequest>()
            val updated = activity.copy(
                status = if (req.approve) ActivityStatus.PUBLISHED else ActivityStatus.REJECTED,
                reviewComment = req.comment.trim(),
                reviewerName = teacher.name,
                reviewedAt = TimeUtil.now()
            )
            store.activities.upsert(updated)
            call.respond(viewOf(store, updated, null))
        }

        /** 组织者 / 老师查看报名名单 */
        get("/{id}/registrations") {
            val user = call.requireRole(auth, Role.ORGANIZER, Role.TEACHER)
            val activity = requireActivity(store, callId(call))
            if (user.role == Role.ORGANIZER && activity.organizerId != user.id) {
                throw ApiException(HttpStatusCode.Forbidden, "只能查看自己社团活动的报名情况")
            }
            val list = store.registrations
                .filter { it.activityId == activity.id }
                .sortedBy { it.registeredAt }
            call.respond(list)
        }

        /** 报名二维码图片（供 <img> 直接引用，故不加鉴权） */
        get("/{id}/qrcode.png") {
            val activity = requireActivity(store, callId(call))
            val url = QrService.registrationUrl(baseUrlOf(call), activity.qrToken)
            call.respondBytes(QrService.png(url, 320), ContentType.Image.PNG)
        }

        /** 二维码文本信息：地址 + 令牌，便于复制分享 */
        get("/{id}/qrcode") {
            val user = call.requireRole(auth, Role.ORGANIZER, Role.TEACHER)
            val activity = requireActivity(store, callId(call))
            if (user.role == Role.ORGANIZER && activity.organizerId != user.id) {
                throw ApiException(HttpStatusCode.Forbidden, "只能查看自己社团活动的二维码")
            }
            call.respond(
                QrInfoResponse(
                    qrToken = activity.qrToken,
                    url = QrService.registrationUrl(baseUrlOf(call), activity.qrToken),
                    imageUrl = "/api/activities/${activity.id}/qrcode.png"
                )
            )
        }
    }

    route("/registrations") {

        /** 学生报名：支持 activityId 或扫码得到的 qrToken */
        post {
            val student = call.requireRole(auth, Role.STUDENT)
            val req = call.receive<SignupRequest>()
            val activity = when {
                req.qrToken.isNotBlank() -> store.activities.byQrToken(req.qrToken)
                req.activityId.isNotBlank() -> store.activities.byId(req.activityId)
                else -> null
            } ?: throw ApiException(HttpStatusCode.NotFound, "未找到对应活动，请确认二维码是否有效")

            if (activity.status != ActivityStatus.PUBLISHED) {
                throw ApiException(HttpStatusCode.BadRequest, "活动当前不可报名（状态：${activity.status.text}）")
            }
            val deadline = TimeUtil.parse(activity.registrationDeadline)
            if (deadline != null && LocalDateTime.now().isAfter(deadline)) {
                throw ApiException(HttpStatusCode.BadRequest, "报名已于 ${activity.registrationDeadline} 截止")
            }
            val existing = store.registrations.find {
                it.activityId == activity.id && it.studentId == student.id && it.status == RegistrationStatus.REGISTERED
            }
            if (existing != null) {
                throw ApiException(HttpStatusCode.BadRequest, "你已经报名过该活动")
            }
            val registeredCount = store.registrations.count {
                it.activityId == activity.id && it.status == RegistrationStatus.REGISTERED
            }
            if (registeredCount >= activity.capacity) {
                throw ApiException(HttpStatusCode.BadRequest, "报名人数已满（上限 ${activity.capacity} 人）")
            }
            val registration = Registration(
                id = IdGen.next("R"),
                activityId = activity.id,
                studentId = student.id,
                studentName = student.name,
                studentNo = student.username,
                registeredAt = TimeUtil.now(),
                status = RegistrationStatus.REGISTERED
            )
            store.registrations.insert(registration)
            call.respond(HttpStatusCode.Created, viewOf(store, activity, student.id))
        }

        /** 我的报名列表 */
        get("/mine") {
            val student = call.requireRole(auth, Role.STUDENT)
            val list = store.registrations
                .filter { it.studentId == student.id }
                .sortedByDescending { it.registeredAt }
                .map { RegistrationView(it, store.activities.byId(it.activityId)) }
            call.respond(list)
        }

        /** 取消报名 */
        delete("/{id}") {
            val student = call.requireRole(auth, Role.STUDENT)
            val id = callId(call)
            val registration = store.registrations.byId(id)
                ?: throw ApiException(HttpStatusCode.NotFound, "报名记录不存在")
            if (registration.studentId != student.id) {
                throw ApiException(HttpStatusCode.Forbidden, "不能取消他人的报名")
            }
            if (registration.status == RegistrationStatus.CANCELLED) {
                throw ApiException(HttpStatusCode.BadRequest, "该报名已经是取消状态")
            }
            store.registrations.upsert(registration.copy(status = RegistrationStatus.CANCELLED))
            call.respond(OkResponse())
        }
    }

    route("/reminders") {

        get("/mine") {
            val student = call.requireRole(auth, Role.STUDENT)
            call.respond(store.reminders.filter { it.studentId == student.id }.sortedBy { it.remindAt })
        }

        /** 为已报名的活动设置提醒（同一活动重复设置则覆盖） */
        post {
            val student = call.requireRole(auth, Role.STUDENT)
            val req = call.receive<ReminderRequest>()
            val activity = requireActivity(store, req.activityId)
            val registered = store.registrations.find {
                it.activityId == activity.id && it.studentId == student.id && it.status == RegistrationStatus.REGISTERED
            } ?: throw ApiException(HttpStatusCode.BadRequest, "请先报名该活动，再设置提醒")
            TimeUtil.parse(req.remindAt) ?: throw ApiException(HttpStatusCode.BadRequest, "提醒时间格式不正确")

            val existing = store.reminders.find { it.studentId == student.id && it.activityId == activity.id }
            val reminder = (existing ?: Reminder(
                id = IdGen.next("M"),
                studentId = student.id,
                activityId = activity.id,
                activityTitle = activity.title,
                remindAt = "",
                note = "",
                enabled = true
            )).copy(
                activityTitle = activity.title,
                remindAt = TimeUtil.normalize(req.remindAt),
                note = req.note.trim(),
                enabled = req.enabled
            )
            store.reminders.upsert(reminder)
            call.respond(HttpStatusCode.Created, reminder)
        }

        delete("/{id}") {
            val student = call.requireRole(auth, Role.STUDENT)
            val reminder = store.reminders.byId(callId(call))
                ?: throw ApiException(HttpStatusCode.NotFound, "提醒不存在")
            if (reminder.studentId != student.id) {
                throw ApiException(HttpStatusCode.Forbidden, "不能删除他人的提醒")
            }
            store.reminders.deleteById(reminder.id)
            call.respond(OkResponse())
        }
    }

    /** 首页统计信息 */
    get("/stats") {
        val user = call.requireUser(auth)
        val all = store.activities.all()
        call.respond(
            StatsView(
                totalActivities = all.size,
                published = all.count { it.status == ActivityStatus.PUBLISHED },
                pending = all.count { it.status == ActivityStatus.PENDING_REVIEW },
                myActivities = all.count { it.organizerId == user.id },
                myRegistrations = store.registrations.count {
                    it.studentId == user.id && it.status == RegistrationStatus.REGISTERED
                },
                myReminders = store.reminders.count { it.studentId == user.id }
            )
        )
    }

    /** 供扫码链接直接使用：根据令牌取活动 */
    get("/scan/{token}") {
        val activity = store.activities.byQrToken(call.parameters["token"] ?: "")
            ?: throw ApiException(HttpStatusCode.NotFound, "二维码无效或活动已删除")
        val user = call.currentUserOrNull(auth)
        call.respond(viewOf(store, activity, user?.id))
    }
}
