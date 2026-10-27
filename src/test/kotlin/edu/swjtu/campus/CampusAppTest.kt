package edu.swjtu.campus

import edu.swjtu.campus.data.TimeUtil
import edu.swjtu.campus.model.ActivityRequest
import edu.swjtu.campus.model.ActivityStatus
import edu.swjtu.campus.model.ActivityUpdateRequest
import edu.swjtu.campus.model.ActivityView
import edu.swjtu.campus.model.AvatarRequest
import edu.swjtu.campus.model.ErrorResponse
import edu.swjtu.campus.model.LoginRequest
import edu.swjtu.campus.model.LoginResponse
import edu.swjtu.campus.model.RegisterRequest
import edu.swjtu.campus.model.Registration
import edu.swjtu.campus.model.RegistrationStatus
import edu.swjtu.campus.model.RegistrationView
import edu.swjtu.campus.model.Reminder
import edu.swjtu.campus.model.ReminderRequest
import edu.swjtu.campus.model.ReviewRequest
import edu.swjtu.campus.model.Role
import edu.swjtu.campus.model.SignupRequest
import edu.swjtu.campus.model.UploadResponse
import edu.swjtu.campus.model.User
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 后端接口自动化测试：覆盖「发布 -> 审核 -> 报名 -> 管理 -> 提醒」的核心流程。
 * 每个用例使用独立的临时数据目录，互不影响。
 */
class CampusAppTest {

    private fun freshDataDir() {
        val dir = Files.createTempDirectory("campus-test-").toFile()
        System.setProperty("app.dataDir", dir.absolutePath)
    }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(AppJson) }
    }

    private suspend fun HttpClient.login(username: String, password: String = "123456"): String {
        val response = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }
        assertEquals(HttpStatusCode.OK, response.status, "登录应成功：$username")
        return response.body<LoginResponse>().token
    }

    private suspend fun HttpClient.registerOrganizer(club: String): String {
        val username = "org" + UUID.randomUUID().toString().take(6)
        val response = post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(username, "123456", "测试社长", Role.ORGANIZER, club, "计算机学院", "13800000000"))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body<LoginResponse>().token
    }

    private suspend fun HttpClient.registerStudent(): String {
        val username = "stu" + UUID.randomUUID().toString().take(6)
        val response = post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(username, "123456", "测试同学", Role.STUDENT, "", "外国语学院", "13900000000"))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body<LoginResponse>().token
    }

    private fun sampleActivity(title: String = "测试活动", capacity: Int = 30) = ActivityRequest(
        title = title,
        content = "这是自动化测试创建的活动",
        location = "犀浦校区 测试场地",
        requirements = "在校学生",
        capacity = capacity,
        registrationDeadline = TimeUtil.plusDays(2),
        startTime = TimeUtil.plusDays(3),
        endTime = TimeUtil.plusDays(3, 2)
    )

    /** 构造一个 multipart 请求体：以指定文件名上传一段字节 */
    private fun imagePart(fileName: String, bytes: ByteArray, contentType: String): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                append(
                    "file", bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    }
                )
            }
        )

    // ---------------------------------------------------------------

    @Test
    fun `登录失败应返回 401`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val bad = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("teacher01", "wrong-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("密码错误", bad.body<ErrorResponse>().error)

        val missing = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("nobody", "123456"))
        }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
    }

    @Test
    fun `未登录访问接口应返回 401`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/activities").status)
    }

    @Test
    fun `角色权限：学生不能发布活动，组织者不能审核`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val studentToken = client.login("20210001")
        val publishAsStudent = client.post("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(sampleActivity())
        }
        assertEquals(HttpStatusCode.Forbidden, publishAsStudent.status)

        val organizerToken = client.registerOrganizer("权限测试社")
        val created = client.post("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
            contentType(ContentType.Application.Json)
            setBody(sampleActivity())
        }.body<ActivityView>()

        val reviewAsOrganizer = client.post("/api/activities/${created.activity.id}/review") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
            contentType(ContentType.Application.Json)
            setBody(ReviewRequest(true, ""))
        }
        assertEquals(HttpStatusCode.Forbidden, reviewAsOrganizer.status)
    }

    @Test
    fun `完整流程：发布 - 待审核 - 学生不可见 - 审核通过 - 报名 - 满员拦截`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val organizerToken = client.registerOrganizer("流程测试社")
        val studentToken = client.registerStudent()
        val teacherToken = client.login("teacher01")

        // 1. 组织者发布活动，状态应为待审核
        val created = client.post("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
            contentType(ContentType.Application.Json)
            setBody(sampleActivity("流程测试活动", capacity = 1))
        }.body<ActivityView>()
        assertEquals(ActivityStatus.PENDING_REVIEW, created.activity.status)
        assertTrue(created.activity.qrToken.isNotBlank(), "发布后应自动生成二维码令牌")

        // 2. 待审核活动对其它学生不可见
        val studentList = client.get("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }.body<List<ActivityView>>()
        assertTrue(studentList.none { it.activity.id == created.activity.id }, "待审核活动不应出现在活动广场")

        // 3. 未发布不能报名
        val earlySignup = client.post("/api/registrations") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(SignupRequest(activityId = created.activity.id))
        }
        assertEquals(HttpStatusCode.BadRequest, earlySignup.status)

        // 4. 老师审核通过
        val approved = client.post("/api/activities/${created.activity.id}/review") {
            header(HttpHeaders.Authorization, "Bearer $teacherToken")
            contentType(ContentType.Application.Json)
            setBody(ReviewRequest(true, "内容完整，同意发布"))
        }.body<ActivityView>()
        assertEquals(ActivityStatus.PUBLISHED, approved.activity.status)

        // 5. 现在学生可见、可报名
        val visible = client.get("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }.body<List<ActivityView>>()
        assertTrue(visible.any { it.activity.id == created.activity.id })

        val signup = client.post("/api/registrations") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(SignupRequest(activityId = created.activity.id))
        }
        assertEquals(HttpStatusCode.Created, signup.status)
        assertEquals(1, signup.body<ActivityView>().registeredCount)

        // 6. 重复报名被拦截
        val duplicate = client.post("/api/registrations") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(SignupRequest(activityId = created.activity.id))
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)

        // 7. 名额已满（上限 1 人）
        val secondStudent = client.registerStudent()
        val full = client.post("/api/registrations") {
            header(HttpHeaders.Authorization, "Bearer $secondStudent")
            contentType(ContentType.Application.Json)
            setBody(SignupRequest(activityId = created.activity.id))
        }
        assertEquals(HttpStatusCode.BadRequest, full.status)
        assertTrue(full.body<ErrorResponse>().error.contains("已满"))

        // 8. 组织者可查看报名名单
        val registrations = client.get("/api/activities/${created.activity.id}/registrations") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
        }.body<List<Registration>>()
        assertEquals(1, registrations.size)
        assertEquals("测试同学", registrations.first().studentName)
    }

    @Test
    fun `扫码报名：用二维码令牌直接报名`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val studentToken = client.registerStudent()
        // 种子数据中吉他社迎新音乐会的二维码令牌
        val response = client.post("/api/registrations") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(SignupRequest(qrToken = "GTAR2026"))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(3, response.body<ActivityView>().registeredCount, "种子数据已有 2 人报名")

        // 无效二维码
        val invalid = client.post("/api/registrations") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(SignupRequest(qrToken = "NOT-EXIST"))
        }
        assertEquals(HttpStatusCode.NotFound, invalid.status)
    }

    @Test
    fun `二维码接口返回合法 PNG 图片`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val response = client.get("/api/activities/A-1001/qrcode.png")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType())
        val bytes = response.body<ByteArray>()
        assertTrue(bytes.size > 200, "二维码图片应有内容")
        // PNG 文件头
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
    }

    @Test
    fun `组织者可以延长截止时间 增加名额 修改地点 以及取消活动`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val organizerToken = client.registerOrganizer("管理测试社")
        val created = client.post("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
            contentType(ContentType.Application.Json)
            setBody(sampleActivity("管理测试活动", capacity = 5))
        }.body<ActivityView>()

        val teacherToken = client.login("teacher01")
        client.post("/api/activities/${created.activity.id}/review") {
            header(HttpHeaders.Authorization, "Bearer $teacherToken")
            contentType(ContentType.Application.Json)
            setBody(ReviewRequest(true, ""))
        }

        val newDeadline = TimeUtil.plusDays(9)
        val updated = client.put("/api/activities/${created.activity.id}") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
            contentType(ContentType.Application.Json)
            setBody(
                ActivityUpdateRequest(
                    capacity = 80,
                    location = "犀浦校区 新场地 301",
                    registrationDeadline = newDeadline,
                    startTime = TimeUtil.plusDays(20),
                    endTime = TimeUtil.plusDays(20, 3)
                )
            )
        }.body<ActivityView>()

        assertEquals(80, updated.activity.capacity)
        assertEquals("犀浦校区 新场地 301", updated.activity.location)
        assertEquals(newDeadline, updated.activity.registrationDeadline)

        // 取消活动
        val cancelled = client.post("/api/activities/${created.activity.id}/cancel") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
        }.body<ActivityView>()
        assertEquals(ActivityStatus.CANCELLED, cancelled.activity.status)

        // 取消后学生看不到
        val studentToken = client.registerStudent()
        val list = client.get("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }.body<List<ActivityView>>()
        assertTrue(list.none { it.activity.id == created.activity.id })
    }

    @Test
    fun `报名截止时间晚于活动开始时间应被拒绝`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val organizerToken = client.registerOrganizer("校验测试社")
        val bad = client.post("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
            contentType(ContentType.Application.Json)
            setBody(
                ActivityRequest(
                    title = "时间不合法活动",
                    content = "x",
                    location = "x",
                    capacity = 10,
                    registrationDeadline = TimeUtil.plusDays(5),
                    startTime = TimeUtil.plusDays(1),
                    endTime = TimeUtil.plusDays(2)
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.body<ErrorResponse>().error.contains("报名截止"))
    }

    @Test
    fun `活动提醒：未报名不能设置 报名后可设置并可删除`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val studentToken = client.registerStudent()

        // 未报名 -> 拒绝
        val beforeSignup = client.post("/api/reminders") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(ReminderRequest("A-1003", TimeUtil.plusDays(1)))
        }
        assertEquals(HttpStatusCode.BadRequest, beforeSignup.status)

        // 报名 ACM 新生程序设计竞赛
        client.post("/api/registrations") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(SignupRequest(activityId = "A-1003"))
        }

        val reminder = client.post("/api/reminders") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(ReminderRequest("A-1003", TimeUtil.plusDays(1), "记得带上学生证"))
        }
        assertEquals(HttpStatusCode.Created, reminder.status)
        val created = reminder.body<Reminder>()
        assertEquals("记得带上学生证", created.note)

        val mine = client.get("/api/reminders/mine") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }.body<List<Reminder>>()
        assertEquals(1, mine.size)

        // 重复设置应覆盖而不是新增
        client.post("/api/reminders") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(ReminderRequest("A-1003", TimeUtil.plusDays(2), "改到第二天"))
        }
        val afterUpdate = client.get("/api/reminders/mine") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }.body<List<Reminder>>()
        assertEquals(1, afterUpdate.size)
        assertEquals("改到第二天", afterUpdate.first().note)

        // 删除
        val deleted = client.delete("/api/reminders/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertTrue(
            client.get("/api/reminders/mine") {
                header(HttpHeaders.Authorization, "Bearer $studentToken")
            }.body<List<Reminder>>().isEmpty()
        )
    }

    @Test
    fun `我的报名：可查看并取消报名`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val studentToken = client.registerStudent()
        val signup = client.post("/api/registrations") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            contentType(ContentType.Application.Json)
            setBody(SignupRequest(activityId = "A-1004"))
        }.body<ActivityView>()

        val mine = client.get("/api/registrations/mine") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }.body<List<RegistrationView>>()
        assertEquals(1, mine.size)
        assertNotNull(mine.first().activity)

        val registrationId = mine.first().registration.id
        assertEquals(HttpStatusCode.OK, client.delete("/api/registrations/$registrationId") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }.status)

        val afterCancel = client.get("/api/registrations/mine") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }.body<List<RegistrationView>>()
        assertEquals(RegistrationStatus.CANCELLED, afterCancel.first().registration.status)
        assertEquals(signup.activity.id, afterCancel.first().registration.activityId)
    }

    @Test
    fun `数据持久化：写入的内容可以从 csv 文件重新读取`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val organizerToken = client.registerOrganizer("持久化测试社")
        client.post("/api/activities") {
            header(HttpHeaders.Authorization, "Bearer $organizerToken")
            contentType(ContentType.Application.Json)
            setBody(sampleActivity("会被写入 CSV 的活动"))
        }

        val dir = java.io.File(System.getProperty("app.dataDir"))
        val activitiesCsv = java.io.File(dir, "activities.csv")
        assertTrue(activitiesCsv.exists(), "activities.csv 应被创建")
        val text = activitiesCsv.readText(Charsets.UTF_8)
        assertTrue(text.contains("会被写入 CSV 的活动"))
        assertTrue(text.contains("qrToken"), "CSV 表头应包含 qrToken 字段")
        assertTrue(java.io.File(dir, "users.csv").exists())
    }

    @Test
    fun `登录后可上传图片 更新头像并从 uploads 访问`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val token = client.registerStudent()

        // 用后端生成的二维码图片作为一张合法 PNG
        val png = client.get("/api/activities/A-1001/qrcode.png").body<ByteArray>()
        assertTrue(png.isNotEmpty())

        val upload = client.post("/api/uploads") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(imagePart("avatar.png", png, "image/png"))
        }
        assertEquals(HttpStatusCode.Created, upload.status)
        val url = upload.body<UploadResponse>().url
        assertTrue(url.startsWith("/uploads/"), "上传后应返回本地地址：$url")

        // 上传的图片可通过 /uploads 直接访问（供 <img> 引用）
        val served = client.get(url)
        assertEquals(HttpStatusCode.OK, served.status)
        assertTrue(served.body<ByteArray>().contentEquals(png))

        // 更新当前用户头像并持久化到 users.csv
        val updated = client.post("/api/auth/avatar") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AvatarRequest(url))
        }.body<User>()
        assertEquals(url, updated.avatarUrl)

        val me = client.get("/api/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<User>()
        assertEquals(url, me.avatarUrl)
    }

    @Test
    fun `上传非图片格式会被拒绝`() = testApplication {
        freshDataDir()
        application { module() }
        val client = jsonClient()

        val token = client.registerStudent()
        val bad = client.post("/api/uploads") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(imagePart("evil.txt", "not an image".toByteArray(), "text/plain"))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.body<ErrorResponse>().error.contains("图片"))
    }
}
